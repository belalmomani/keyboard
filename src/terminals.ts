import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { byId } from "./dom";
import { emit, state, type ShellKind } from "./state";

export type Kind = ShellKind;

export interface Sess {
  id: string;
  kind: Kind;
  title: string;
  term: Terminal;
  fit: FitAddon;
  host: HTMLDivElement;
  tab: HTMLDivElement;
  dot: HTMLSpanElement;
  exited: boolean;
}

const sessions = new Map<string, Sess>();
// خرج وصل قبل تسجيل الجلسة في الواجهة (السباق بين فتح الجلسة وأول خرج)
const pendingOutput = new Map<string, Uint8Array[]>();
const pendingExit = new Map<string, number | null>();
const counters: Record<Kind, number> = { powershell: 0, cmd: 0 };
let activeId: string | null = null;

const stage = () => byId<HTMLElement>("stage");
const tabsEl = () => byId<HTMLDivElement>("tabs");
const emptyEl = () => byId<HTMLDivElement>("empty");
const statusEl = () => byId<HTMLSpanElement>("status");
const killBtn = () => byId<HTMLButtonElement>("kill");

export function setStatus(text: string): void {
  statusEl().textContent = text;
}

export function activeSession(): Sess | undefined {
  return activeId ? sessions.get(activeId) : undefined;
}

export function focusActive(): void {
  activeSession()?.term.focus();
}

export function writeTo(id: string, data: string): Promise<void> {
  return invoke<void>("terminal_write", { id, data });
}

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function syncActiveKind(): void {
  const s = activeSession();
  state.activeKind = s && !s.exited ? s.kind : null;
}

function showExit(s: Sess, code: number | null): void {
  s.exited = true;
  s.dot.classList.add(code === 0 ? "exited" : "failed");
  s.term.writeln(`\r\n\x1b[90m[process exited with code ${code ?? "?"}]\x1b[0m`);
  if (activeId === s.id) {
    setStatus(`انتهت العملية (رمز الخروج: ${code ?? "؟"})`);
    killBtn().disabled = true;
    syncActiveKind();
    emit();
  }
}

function activate(id: string | null): void {
  activeId = id;
  for (const s of sessions.values()) {
    const on = s.id === id;
    s.host.classList.toggle("active", on);
    s.tab.classList.toggle("active", on);
  }
  emptyEl().classList.toggle("hidden", sessions.size > 0);
  const s = id ? sessions.get(id) : undefined;
  killBtn().disabled = !s || s.exited;
  if (s) {
    requestAnimationFrame(() => {
      s.fit.fit();
      s.term.focus();
    });
    setStatus(s.exited ? "الجلسة منتهية" : `الجلسة: ${s.title}`);
  } else {
    setStatus("جاهز");
  }
  syncActiveKind();
  emit();
}

export interface OpenOpts {
  cwd?: string | null;
  title?: string;
}

const toolSessions = new Map<string, string>();

export async function openSession(kind: Kind, opts: OpenOpts = {}): Promise<Sess | null> {
  const label = kind === "powershell" ? "PowerShell" : "CMD";
  const term = new Terminal({
    fontFamily: '"Cascadia Mono", Consolas, "Courier New", monospace',
    fontSize: 14,
    cursorBlink: true,
    scrollback: 10000,
    theme: {
      background: "#0b0f14",
      foreground: "#dbe5f0",
      cursor: "#2dd4bf",
      selectionBackground: "#1f4d52",
    },
  });
  const fit = new FitAddon();
  term.loadAddon(fit);

  // نسخ بـ Ctrl+C عند وجود تحديد (وإلا يُرسل ^C للعملية)
  term.attachCustomKeyEventHandler((ev) => {
    if (ev.type === "keydown" && ev.ctrlKey && ev.key.toLowerCase() === "c" && term.hasSelection()) {
      void navigator.clipboard?.writeText(term.getSelection());
      return false;
    }
    return true;
  });

  const host = document.createElement("div");
  host.className = "term-host";
  stage().appendChild(host);
  for (const s of sessions.values()) s.host.classList.remove("active");
  host.classList.add("active");
  emptyEl().classList.add("hidden");

  term.open(host);
  fit.fit();

  let id: string;
  try {
    id = await invoke<string>("terminal_open", {
      kind,
      cols: term.cols,
      rows: term.rows,
      cwd: opts.cwd ?? null,
    });
  } catch (err) {
    term.dispose();
    host.remove();
    setStatus(`تعذّر فتح الجلسة: ${String(err)}`);
    activate(activeId);
    return null;
  }

  counters[kind] += 1;
  const title = opts.title ?? `${label} ${counters[kind]}`;

  const tab = document.createElement("div");
  tab.className = "tab";
  const dot = document.createElement("span");
  dot.className = "dot";
  const titleEl = document.createElement("span");
  titleEl.className = "title";
  titleEl.textContent = title;
  const closeBtn = document.createElement("button");
  closeBtn.className = "close";
  closeBtn.textContent = "×";
  closeBtn.title = "إغلاق الجلسة";
  tab.append(dot, titleEl, closeBtn);
  tabsEl().appendChild(tab);

  const sess: Sess = { id, kind, title, term, fit, host, tab, dot, exited: false };
  sessions.set(id, sess);

  term.onData((data) => {
    void writeTo(id, data).catch(() => {});
  });
  term.onResize(({ cols, rows }) => {
    void invoke("terminal_resize", { id, cols, rows }).catch(() => {});
  });

  tab.addEventListener("click", () => activate(id));
  closeBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    void closeSession(id);
  });

  // أفرغ أي خرج سبق التسجيل
  const early = pendingOutput.get(id);
  if (early) {
    for (const chunk of early) term.write(chunk);
    pendingOutput.delete(id);
  }
  if (pendingExit.has(id)) {
    showExit(sess, pendingExit.get(id) ?? null);
    pendingExit.delete(id);
  }

  activate(id);
  return sess;
}

/** يعيد الجلسة الحالية إن كانت حيّة ومناسبة للصدفة المطلوبة، وإلا يفتح جلسة جديدة. */
export async function ensureSession(want: Kind | "any"): Promise<Sess | null> {
  const cur = activeSession();
  if (cur && !cur.exited && (want === "any" || cur.kind === want)) return cur;
  return openSession(want === "any" ? "powershell" : want);
}

/** جلسة مخصصة لأداة محلية (cwd = مجلد الأداة). تُعاد إن كانت حيّة، وإلا تُفتح جديدة. */
export async function ensureToolSession(
  toolId: string,
  name: string,
  want: Kind | "any",
  cwd: string | null,
): Promise<Sess | null> {
  const existing = toolSessions.get(toolId);
  const s = existing ? sessions.get(existing) : undefined;
  if (s && !s.exited && (want === "any" || s.kind === want)) {
    activate(s.id);
    return s;
  }
  const opened = await openSession(want === "any" ? "powershell" : want, { cwd, title: `أداة: ${name}` });
  if (opened) toolSessions.set(toolId, opened.id);
  return opened;
}

async function closeSession(id: string): Promise<void> {
  const s = sessions.get(id);
  if (!s) return;
  try {
    await invoke("terminal_close", { id });
  } catch {
    /* الجلسة قد تكون أُغلقت */
  }
  s.term.dispose();
  s.host.remove();
  s.tab.remove();
  sessions.delete(id);
  if (activeId === id) {
    activate([...sessions.keys()].pop() ?? null);
  } else {
    activate(activeId);
  }
}

async function killActive(): Promise<void> {
  if (!activeId) return;
  try {
    await invoke("terminal_kill", { id: activeId });
    setStatus("تم إرسال طلب إيقاف العملية");
  } catch (err) {
    setStatus(`تعذّر الإيقاف: ${String(err)}`);
  }
}

export async function initTerminals(): Promise<void> {
  await listen<{ id: string; data: string }>("terminal-output", (e) => {
    const bytes = b64ToBytes(e.payload.data);
    const s = sessions.get(e.payload.id);
    if (s) {
      s.term.write(bytes);
    } else {
      const list = pendingOutput.get(e.payload.id) ?? [];
      list.push(bytes);
      pendingOutput.set(e.payload.id, list);
    }
  });

  await listen<{ id: string; code: number | null }>("terminal-exit", (e) => {
    const s = sessions.get(e.payload.id);
    if (s) showExit(s, e.payload.code);
    else pendingExit.set(e.payload.id, e.payload.code);
  });

  byId("new-ps").addEventListener("click", () => void openSession("powershell"));
  byId("new-cmd").addEventListener("click", () => void openSession("cmd"));
  killBtn().addEventListener("click", () => void killActive());

  new ResizeObserver(() => {
    activeSession()?.fit.fit();
  }).observe(stage());

  try {
    const info = await invoke<{ name: string; version: string; build: string }>("app_info");
    byId("info").textContent = `v${info.version} — Build ${info.build}`;
  } catch {
    byId("info").textContent = "";
  }
}
