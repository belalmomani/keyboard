import { api, type Diag, type ProjectInfo, type TreeEntry } from "./api";
import { jsonPathLines, lineForPath } from "./jsonPath";
import { runTool } from "./actions";
import { chooseDialog, confirmDialog, mount, promptDialog, textDialog } from "./dialog";
import { byId, fmtTime, h } from "./dom";
import { createEditor, type CodeEditor } from "./editor";
import { PERM_LABELS, formatReport } from "./securityUi";
import { sdkDoc } from "./sdkDoc";
import { openGuided } from "./guided";
import { copyAiPrompt } from "./pasteInstall";
import { openToolForm } from "./toolForm";
import { state } from "./state";
import { setStatus } from "./terminals";
import { afterInstall, bytesToB64, MAX_PACKAGE } from "./toolsPanel";

const S = {
  open: false,
  projects: [] as ProjectInfo[],
  id: null as string | null,
  tree: [] as TreeEntry[],
  sel: null as string | null, // العنصر المحدد في الشجرة (ملف أو مجلد)
  openPath: null as string | null,
  dirty: false,
  editor: null as CodeEditor | null,
};

const el = {
  root: null as HTMLElement | null,
  select: null as HTMLSelectElement | null,
  tree: null as HTMLElement | null,
  path: null as HTMLElement | null,
  host: null as HTMLElement | null,
  log: null as HTMLElement | null,
  problems: null as HTMLElement | null,
  buttons: [] as HTMLButtonElement[], // تتعطل بلا مشروع
};

function log(msg: string): void {
  if (!el.log) return;
  const t = new Date().toLocaleTimeString("ar", { hour12: false });
  el.log.textContent += `[${t}] ${msg}\n`;
  el.log.scrollTop = el.log.scrollHeight;
}

function needProject(): string | null {
  if (!S.id) setStatus("اختر مشروعاً أو أنشئ واحداً أولاً");
  return S.id;
}

// ---------------- المشاريع والملفات ----------------

async function loadProjects(select?: string): Promise<void> {
  try {
    S.projects = await api.studioProjects();
  } catch (err) {
    log(`تعذّر تحميل المشاريع: ${String(err)}`);
    S.projects = [];
  }
  const want = select ?? S.id;
  S.id = S.projects.some((p) => p.id === want) ? want : (S.projects[0]?.id ?? null);
  const sel = el.select;
  if (sel) {
    sel.replaceChildren();
    if (S.projects.length === 0) sel.append(h("option", { text: "— لا توجد مشاريع —", value: "" }));
    for (const p of S.projects) {
      sel.append(h("option", { text: `${p.name} (${p.id})${p.hasManifest ? "" : " ⚠ بلا manifest"}`, value: p.id }));
    }
    if (S.id) sel.value = S.id;
  }
  for (const b of el.buttons) b.disabled = !S.id;
  S.openPath = null;
  S.dirty = false;
  S.sel = null;
  clearProblems();
  if (el.path) el.path.textContent = "";
  S.editor?.open("", "x.txt");
  await loadTree();
  if (S.id && S.tree.some((e) => e.path === "manifest.json")) await openFile("manifest.json");
}

async function loadTree(): Promise<void> {
  S.tree = [];
  if (S.id) {
    try {
      S.tree = await api.studioTree(S.id);
    } catch (err) {
      log(`تعذّر تحميل الملفات: ${String(err)}`);
    }
  }
  renderTree();
}

function renderTree(): void {
  const box = el.tree;
  if (!box) return;
  box.replaceChildren();
  if (!S.id) {
    box.append(h("div", { class: "muted-box", text: "أنشئ مشروعاً أو استورد واحداً." }));
    return;
  }
  for (const e of S.tree) {
    const depth = e.path.split("/").length - 1;
    const name = e.path.split("/").pop() ?? e.path;
    const row = h("div", {
      class: "st-node" + (e.dir ? " dir" : "") + (S.sel === e.path ? " sel" : "") + (S.openPath === e.path ? " open" : ""),
      text: (e.dir ? "▸ " : "") + name + (e.path === S.openPath && S.dirty ? " ●" : ""),
      title: e.path,
    });
    row.style.paddingInlineStart = `${8 + depth * 14}px`;
    row.addEventListener("click", () => {
      S.sel = e.path;
      if (!e.dir) void openFile(e.path);
      else renderTree();
    });
    box.append(row);
  }
}

async function saveCurrent(quiet = false): Promise<boolean> {
  if (!S.id || !S.openPath || !S.editor || !S.dirty) return true;
  try {
    await api.studioWrite(S.id, S.openPath, S.editor.getValue());
    S.dirty = false;
    renderTree();
    if (!quiet) log(`حُفظ ${S.openPath}`);
    if (S.openPath === "manifest.json") void liveCheck();
    return true;
  } catch (err) {
    log(`تعذّر الحفظ: ${String(err)}`);
    await textDialog("تعذّر الحفظ", String(err));
    return false;
  }
}

async function openFile(path: string): Promise<void> {
  if (!S.id || !S.editor) return;
  if (!(await saveCurrent(true))) return; // الحفظ التلقائي عند التبديل
  try {
    const text = await api.studioRead(S.id, path);
    S.openPath = path;
    S.sel = path;
    S.dirty = false;
    S.editor.open(text, path);
    if (el.path) el.path.textContent = path;
    renderTree();
    S.editor.focus();
  } catch (err) {
    log(`تعذّر فتح ${path}: ${String(err)}`);
  }
}

function baseDir(): string {
  const s = S.sel;
  if (!s) return "";
  const e = S.tree.find((x) => x.path === s);
  if (e?.dir) return s + "/";
  const i = s.lastIndexOf("/");
  return i >= 0 ? s.slice(0, i + 1) : "";
}

async function newFile(): Promise<void> {
  const id = needProject();
  if (!id) return;
  const p = await promptDialog("ملف جديد", "المسار داخل المشروع (مثال: src/run.ps1)", baseDir(), true);
  if (!p) return;
  try {
    await api.studioWrite(id, p, "");
    await loadTree();
    await openFile(p);
    log(`أُنشئ ${p}`);
  } catch (err) {
    await textDialog("تعذّر الإنشاء", String(err));
  }
}

async function newFolder(): Promise<void> {
  const id = needProject();
  if (!id) return;
  const p = await promptDialog("مجلد جديد", "المسار داخل المشروع", baseDir(), true);
  if (!p) return;
  try {
    await api.studioMkdir(id, p);
    await loadTree();
    log(`أُنشئ المجلد ${p}`);
  } catch (err) {
    await textDialog("تعذّر الإنشاء", String(err));
  }
}

async function renameSel(): Promise<void> {
  const id = needProject();
  if (!id || !S.sel) return;
  const to = await promptDialog("إعادة تسمية / نقل", `من: ${S.sel}`, S.sel, true);
  if (!to || to === S.sel) return;
  if (!(await saveCurrent(true))) return;
  try {
    await api.studioRename(id, S.sel, to);
    if (S.openPath === S.sel) S.openPath = to;
    S.sel = to;
    await loadTree();
    if (el.path && S.openPath) el.path.textContent = S.openPath;
    log(`أُعيدت تسمية ${to}`);
  } catch (err) {
    await textDialog("تعذّرت إعادة التسمية", String(err));
  }
}

async function deleteSel(): Promise<void> {
  const id = needProject();
  if (!id || !S.sel) return;
  if (!(await confirmDialog("حذف", `سيُحذف «${S.sel}» نهائياً من المشروع.`))) return;
  try {
    await api.studioDeletePath(id, S.sel);
    if (S.openPath && (S.openPath === S.sel || S.openPath.startsWith(S.sel + "/"))) {
      S.openPath = null;
      S.dirty = false;
      S.editor?.open("", "x.txt");
      if (el.path) el.path.textContent = "";
    }
    log(`حُذف ${S.sel}`);
    S.sel = null;
    await loadTree();
  } catch (err) {
    await textDialog("تعذّر الحذف", String(err));
  }
}

/** يفتح نموذج الأداة ثم يعيد تحميل manifest.json في المحرر ويفحصه. */
async function formEdit(): Promise<void> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return;
  if (await openToolForm(id)) {
    await loadTree();
    S.openPath = null;
    await openFile("manifest.json");
    await liveCheck();
    log("حُفظ manifest.json من النموذج");
  }
}

/** الإنشاء الموجّه: اسم + ماذا تفعل + شكل النتيجة. */
async function guidedCreate(): Promise<void> {
  if (!(await saveCurrent(true))) return;
  const r = await openGuided();
  if (!r) return;
  await loadProjects(r.projectId);
  log(`أُنشئ المشروع ${r.projectId} بالإنشاء الموجّه`);
  await liveCheck();
  if (r.openForm) await formEdit();
}

async function newProject(): Promise<void> {
  const name = await promptDialog("مشروع أداة جديد", "اسم الأداة (للعرض)");
  if (!name) return;
  const id = await promptDialog("معرّف الأداة", "أحرف إنجليزية صغيرة وأرقام وشرطة، يبدأ بحرف", "", true);
  if (!id) return;
  try {
    const p = await api.studioCreate(id, name);
    await loadProjects(p.id);
    log(`أُنشئ المشروع ${p.id}`);
    await formEdit(); // يبدأ بالنموذج مباشرة لتقليل الأخطاء
  } catch (err) {
    await textDialog("تعذّر إنشاء المشروع", String(err));
  }
}

async function importFile(file: File): Promise<void> {
  if (file.size > MAX_PACKAGE) {
    await textDialog("تعذّر الاستيراد", "الحجم أكبر من 20MB.");
    return;
  }
  const data = bytesToB64(await file.arrayBuffer());
  let id: string | null = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const p = await api.studioImport(data, id);
      await loadProjects(p.id);
      log(`استُورد المشروع ${p.id}${p.hasManifest ? "" : " — لا يحتوي manifest.json؛ أنشئه قبل التحقق"}`);
      return;
    } catch (err) {
      const msg = String(err);
      if (attempt === 0 && msg.includes("أدخل معرّفاً")) {
        id = await promptDialog("معرّف المشروع", "لا يوجد manifest.json. أدخل معرّفاً للمشروع", "", true);
        if (!id) return;
        continue;
      }
      await textDialog("تعذّر الاستيراد", msg);
      return;
    }
  }
}

async function deleteProject(): Promise<void> {
  const id = needProject();
  if (!id) return;
  if (!(await confirmDialog("حذف المشروع", `سيُحذف المشروع «${id}» وكل ملفاته نهائياً (الأداة المثبتة منه لا تتأثر).`))) return;
  try {
    await api.studioDelete(id);
    S.id = null;
    log(`حُذف المشروع ${id}`);
    await loadProjects();
  } catch (err) {
    await textDialog("تعذّر الحذف", String(err));
  }
}

/** «إصلاح تلقائي وترتيب»: يزيل الفواصل الزائدة ويعيد كتابة manifest.json منظماً. */
async function fixManifest(): Promise<void> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return;
  try {
    await api.studioFormat(id);
    log("أُصلح manifest.json ورُتّب تلقائياً");
    S.openPath = null;
    await loadTree();
    await openFile("manifest.json");
    await liveCheck();
  } catch (err) {
    log(`تعذّر الإصلاح التلقائي: ${String(err)}`);
    await textDialog("تعذّر الإصلاح التلقائي", String(err));
  }
}

/** يفتح مشروعاً بعينه في الاستوديو (مثلاً أداة مثبتة بزر «تعديل»). */
export async function openInStudio(id: string): Promise<void> {
  if (S.open) {
    if (!(await saveCurrent(true))) return;
    await loadProjects(id);
  } else {
    S.id = id; // تقرأه تهيئة الاستوديو عند الفتح فتختار هذا المشروع
    setStudio(true);
  }
  log(`فُتحت الأداة ${id} للتعديل — ارفع version قبل «تثبيت وتجربة» لتُحدَّث النسخة المثبتة`);
}

// ---------------- لوحة المشاكل ----------------

function clearProblems(): void {
  S.editor?.setMarkers([]);
  if (el.problems) {
    el.problems.replaceChildren();
    el.problems.classList.remove("show");
  }
}

/** يعرض المشاكل في اللوحة، ويميّز أسطرها في المحرر إن كان manifest.json مفتوحاً. */
async function showProblems(diags: Diag[]): Promise<void> {
  const box = el.problems;
  if (!box || !S.id) return;
  box.replaceChildren();
  if (diags.length === 0) {
    box.classList.remove("show");
    S.editor?.setMarkers([]);
    return;
  }
  let text = "";
  try {
    text = await api.studioRead(S.id, "manifest.json");
  } catch {
    /* manifest مفقود: تبقى المشاكل بلا أسطر */
  }
  const lines = jsonPathLines(text);
  const items = diags.map((d) => ({ d, line: d.line ?? (text ? lineForPath(lines, d.path) : 1) }));
  const errs = items.filter((x) => x.d.severity === "error").length;
  box.append(h("div", { class: "pb-head", text: `المشاكل: ${errs} خطأ، ${items.length - errs} تنبيه — اضغط على مشكلة للانتقال إلى مكانها` }));
  if (diags.some((d) => d.path === "" && d.line !== null)) {
    box.append(h("button", { class: "btn small primary", text: "إصلاح تلقائي وترتيب (يزيل الفواصل الزائدة)", onclick: () => void fixManifest() }));
  }
  for (const { d, line } of items) {
    const row = h(
      "div",
      { class: "pb-row " + d.severity },
      h("div", { class: "pb-msg", text: `${d.severity === "error" ? "✘" : "⚠"} manifest.json — السطر ${line}: ${d.message}` }),
      d.hint ? h("div", { class: "pb-hint", text: `الحل: ${d.hint}` }) : null,
    );
    row.addEventListener("click", () => {
      void (async () => {
        if (S.openPath !== "manifest.json") await openFile("manifest.json");
        S.editor?.reveal(line);
      })();
    });
    box.append(row);
  }
  box.classList.add("show");
  if (S.openPath === "manifest.json") {
    S.editor?.setMarkers(items.map((x) => ({ line: x.line, message: x.d.message + (x.d.hint ? `\nالحل: ${x.d.hint}` : ""), severity: x.d.severity })));
  }
}

/** تحقق صامت (بلا سجل): يُستدعى بعد حفظ manifest.json لإظهار الأخطاء فوراً. */
async function liveCheck(): Promise<void> {
  if (!S.id) return;
  try {
    const r = await api.studioValidate(S.id);
    await showProblems(r.diagnostics);
  } catch {
    /* التحقق الحي لا يمنع العمل */
  }
}

// ---------------- التحقق والبناء والاختبار ----------------

async function doValidate(): Promise<boolean> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return false;
  try {
    const r = await api.studioValidate(id);
    log(r.ok ? "التحقق: ✔ المشروع صالح" : `التحقق: ✘ ${r.errors.length} خطأ — التفاصيل وأماكنها في لوحة «المشاكل» أسفل المحرر`);
    await showProblems(r.diagnostics);
    if (r.report) log(`  الفحص الساكن: خطورة ${r.report.risk}، ${r.report.findings.length} نتيجة (تقريبي)`);
    return r.ok;
  } catch (err) {
    log(`التحقق: ${String(err)}`);
    return false;
  }
}

async function doTest(): Promise<void> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return;
  log("اختبار المشروع (لا تُنفَّذ أوامر الأداة)...");
  try {
    const r = await api.studioTest(id);
    for (const s of r.steps) log(`  ${s.ok ? "✔" : "✘"} ${s.name}: ${s.detail}`);
    log(r.ok ? "نتيجة الاختبار: نجح" : "نتيجة الاختبار: فشل");
  } catch (err) {
    log(`الاختبار: ${String(err)}`);
  }
}

function download(name: string, b64: string): void {
  const bin = atob(b64);
  const u8 = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  const url = URL.createObjectURL(new Blob([u8], { type: "application/zip" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

async function doBuild(): Promise<void> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return;
  try {
    const b = await api.studioBuild(id);
    log(`بُنيت الحزمة ${b.fileName} — ${b.fileCount} ملف، ${b.size} بايت`);
    log(`  SHA-256: ${b.sha256}`);
    log(`  الملف على الجهاز: ${b.fullPath}`);
    download(b.fileName, b.data);
    await loadTree();
  } catch (err) {
    log(`البناء: ${String(err)}`);
    await textDialog("تعذّر البناء", String(err));
  }
}

async function doInstallAndTry(): Promise<void> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return;
  try {
    const out = await api.studioInstall(id);
    log(`ثُبّتت الأداة ${out.tool.id} v${out.tool.version}`);
    await afterInstall(out);
    const tool = state.tools.find((t) => t.id === out.tool.id);
    if (!tool) return;
    setStudio(false);
    await runTool(tool);
  } catch (err) {
    const msg = String(err);
    log(`التثبيت: ${msg}`);
    if (msg.includes("مثبّت بالفعل")) {
      // نفس الإصدار مثبت: اعرض تشغيل المثبّت أو رفع الإصدار
      const m = await api.studioValidate(id).then(() => api.studioRead(id, "manifest.json")).then((t) => JSON.parse(t) as { id?: string }).catch(() => null);
      const tool = state.tools.find((t) => t.id === m?.id);
      if (tool && (await confirmDialog("هذا الإصدار مثبّت بالفعل", "هل تشغّل النسخة المثبتة الآن؟\n\nلتحديثها بتعديلاتك الجديدة ارفع رقم version في manifest.json (مثلاً 1.0.1) ثم أعد «تثبيت وتجربة»."))) {
        setStudio(false);
        await runTool(tool);
      }
      return;
    }
    await textDialog("تعذّر التثبيت", msg);
  }
}

async function showToolLog(): Promise<void> {
  const id = needProject();
  if (!id) return;
  try {
    const m = JSON.parse(await api.studioRead(id, "manifest.json")) as { id?: string };
    if (!m.id) throw new Error("manifest بلا id");
    const rows = await api.toolLogRecent(m.id, 30);
    log(`— سجل تنفيذ الأداة ${m.id} —`);
    if (rows.length === 0) log("  لا سجلات (الأداة غير مثبتة أو لم تُشغَّل).");
    for (const r of rows) log(`  ${fmtTime(r.ts)} [${r.level}] ${r.message}`);
  } catch (err) {
    log(`تعذّر قراءة السجل: ${String(err)}`);
  }
}

// ---------------- manifest: اعتماديات وصلاحيات ----------------

async function editManifest(mutate: (m: Record<string, unknown>) => void): Promise<boolean> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return false;
  try {
    const m = JSON.parse(await api.studioRead(id, "manifest.json")) as Record<string, unknown>;
    mutate(m);
    await api.studioWrite(id, "manifest.json", JSON.stringify(m, null, 2) + "\n");
    if (S.openPath === "manifest.json") {
      S.openPath = null;
      await openFile("manifest.json");
    }
    return true;
  } catch (err) {
    await textDialog("تعذّر تعديل manifest.json", String(err));
    return false;
  }
}

async function depsDialog(): Promise<void> {
  const id = needProject();
  if (!id) return;
  for (;;) {
    let deps: string[] = [];
    try {
      const m = JSON.parse(await api.studioRead(id, "manifest.json")) as { dependencies?: string[] };
      deps = m.dependencies ?? [];
    } catch (err) {
      await textDialog("الاعتماديات", `تعذّرت قراءة manifest.json: ${String(err)}`);
      return;
    }
    const pick = await chooseDialog("الاعتماديات (أدوات يجب تثبيتها قبل أداتك)", [
      { id: "+add", label: "＋ إضافة اعتمادية", desc: "معرّف أداة أخرى" },
      ...deps.map((d) => ({
        id: d,
        label: `إزالة: ${d}`,
        desc: state.tools.some((t) => t.id === d) ? "✔ مثبتة حالياً" : "✘ غير مثبتة حالياً",
      })),
    ]);
    if (!pick) return;
    if (pick === "+add") {
      const d = await promptDialog("إضافة اعتمادية", "معرّف الأداة المطلوبة", "", true);
      if (!d) continue;
      await editManifest((m) => {
        const cur = (m.dependencies as string[] | undefined) ?? [];
        if (!cur.includes(d)) cur.push(d);
        m.dependencies = cur;
      });
    } else {
      await editManifest((m) => {
        m.dependencies = ((m.dependencies as string[] | undefined) ?? []).filter((x) => x !== pick);
      });
    }
  }
}

async function permsDialog(): Promise<void> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return;
  let declared: string[] = [];
  let required: string[] = [];
  try {
    declared = ((JSON.parse(await api.studioRead(id, "manifest.json")) as { permissions?: string[] }).permissions ?? []);
    required = (await api.studioValidate(id)).report?.required ?? [];
  } catch (err) {
    await textDialog("الصلاحيات", String(err));
    return;
  }
  await new Promise<void>((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = () => {
      handle?.close();
      resolve();
    };
    const checks = new Map<string, HTMLInputElement>();
    const rows = Object.entries(PERM_LABELS).map(([p, label]) => {
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = declared.includes(p);
      checks.set(p, cb);
      const need = required.includes(p);
      return h("label", { class: "perm-row" + (need && !cb.checked ? " risky" : "") }, cb, h("span", { text: `${label} (${p})${need ? " — يطلبها الفحص" : ""}` }));
    });
    const save = async () => {
      const list = [...checks.entries()].filter(([, c]) => c.checked).map(([p]) => p);
      if (await editManifest((m) => (m.permissions = list))) {
        log(`حُدّثت الصلاحيات المعلنة: ${list.join("، ") || "—"}`);
        done();
      }
    };
    const box = h(
      "div",
      { class: "dialog wide" },
      h("div", { class: "dialog-title", text: "الصلاحيات المعلنة للأداة" }),
      h("div", { class: "dialog-msg", text: "أعلن ما تحتاجه أوامرك فقط. المستخدم هو من يمنح كل صلاحية عند التثبيت." }),
      h("div", { class: "perm-list" }, ...rows),
      h("div", { class: "dialog-actions" }, h("button", { class: "btn primary", text: "حفظ", onclick: () => void save() }), h("button", { class: "btn", text: "إلغاء", onclick: done })),
    );
    handle = mount(box, done);
  });
}

async function runtimesDialog(): Promise<void> {
  try {
    const r = await api.studioRuntimes();
    const lines = ["محركات التشغيل (Runtime Adapters):", ""];
    for (const a of r.adapters) lines.push(`• ${a.name} — ${a.status === "available" ? "منفَّذ" : "مخطَّط (غير منفَّذ)"}\n    ${a.detail}`);
    lines.push("", "لغات/أدوات مكتشفة على جهازك (معلومة فقط، ليست محركات):");
    for (const t of r.hostTools) lines.push(`  ${t.name}: ${t.version ?? "غير موجود"}`);
    await textDialog("المحركات واللغات", lines.join("\n"));
  } catch (err) {
    await textDialog("المحركات واللغات", String(err));
  }
}

async function reportDialog(): Promise<void> {
  const id = needProject();
  if (!id || !(await saveCurrent(true))) return;
  try {
    const r = await api.studioValidate(id);
    await textDialog("تقرير الفحص", (r.report ? formatReport(r.report) : "لا يوجد تقرير") + (r.errors.length ? `\n\nأخطاء:\n${r.errors.join("\n")}` : ""));
  } catch (err) {
    await textDialog("تقرير الفحص", String(err));
  }
}

// ---------------- البناء الأولي للواجهة ----------------

export function setStudio(open: boolean): void {
  S.open = open;
  el.root?.classList.toggle("open", open);
  byId("studio-toggle").classList.toggle("active", open);
  if (open) {
    void (async () => {
      if (!S.editor && el.host) {
        const { editor, note } = await createEditor(el.host);
        S.editor = editor;
        editor.onChange(() => {
          if (!S.dirty && S.openPath) {
            S.dirty = true;
            S.editor?.setMarkers([]);
            renderTree();
          }
        });
        if (note) log(note);
      }
      await loadProjects();
      requestAnimationFrame(() => S.editor?.layout());
    })();
  }
}

export function initStudio(): void {
  const btn = (text: string, fn: () => void, cls = "", needs = false, title = ""): HTMLButtonElement => {
    const b = h("button", { class: "btn small " + cls, text, title, onclick: fn });
    if (needs) el.buttons.push(b);
    return b;
  };
  const input = document.createElement("input");
  input.type = "file";
  input.accept = ".zip,.tarpkg";
  input.style.display = "none";
  input.addEventListener("change", () => {
    const f = input.files?.[0];
    input.value = "";
    if (f) void importFile(f);
  });

  const select = document.createElement("select");
  select.className = "st-select";
  select.addEventListener("change", () => {
    void (async () => {
      if (!(await saveCurrent(true))) return;
      await loadProjects(select.value);
    })();
  });
  el.select = select;

  const review = btn("إرسال للمراجعة", () => {}, "", false, "غير متاح: يحتاج Marketplace (المرحلة 8)");
  review.disabled = true;

  const head = h(
    "div",
    { class: "st-head" },
    h("div", { class: "st-title", text: "Terminal AR Studio" }),
    select,
    btn("إنشاء موجّه", () => void guidedCreate(), "primary", false, "اسم + ماذا تريد أن تفعل + شكل النتيجة، والاستوديو يبني الباقي"),
    btn("مشروع جديد (يدوي)", () => void newProject()),
    btn("استيراد zip/.tarpkg", () => input.click(), "", false, "استيراد مشروع من خارج Terminal AR"),
    btn("حذف المشروع", () => void deleteProject(), "danger", true),
    h("span", { class: "st-sep" }),
    btn("نموذج الأداة", () => void formEdit(), "primary", true, "أدخل بيانات الأداة بنموذج بدل كتابة JSON"),
    btn("ترتيب الملف", () => void fixManifest(), "", true, "يصلح الفواصل الزائدة ويرتّب manifest.json في أسطر"),
    btn("حفظ", () => void saveCurrent(), "", true),
    btn("تحقق", () => void doValidate(), "", true),
    btn("اختبار", () => void doTest(), "", true),
    btn("بناء .tarpkg", () => void doBuild(), "", true),
    btn("تثبيت وتجربة", () => void doInstallAndTry(), "primary", true, "يثبّت الأداة ثم يشغّلها في الطرفية الرئيسية"),
    h("span", { class: "st-sep" }),
    btn("اعتماديات", () => void depsDialog(), "", true),
    btn("صلاحيات", () => void permsDialog(), "", true),
    btn("تقرير الفحص", () => void reportDialog(), "", true),
    btn("سجل التنفيذ", () => void showToolLog(), "", true),
    btn("المحركات", () => void runtimesDialog()),
    btn("SDK", () => void textDialog("Terminal AR SDK", sdkDoc())),
    btn("طلب للذكاء الاصطناعي", () => void copyAiPrompt(), "admin-only", false, "ينسخ طلباً جاهزاً لأي ذكاء اصطناعي ليكتب لك manifest.json بالصيغة الصحيحة"),
    review,
    h("span", { class: "spacer" }),
    btn("إغلاق", () => {
      void saveCurrent(true).then(() => setStudio(false));
    }),
  );

  el.tree = h("div", { class: "st-tree-list" });
  const treeBar = h(
    "div",
    { class: "st-treebar" },
    btn("＋ ملف", () => void newFile(), "", true),
    btn("＋ مجلد", () => void newFolder(), "", true),
    btn("تسمية", () => void renameSel(), "", true),
    btn("حذف", () => void deleteSel(), "danger", true),
  );
  el.path = h("div", { class: "st-path" });
  el.host = h("div", { class: "st-editor" });
  el.log = h("pre", { class: "st-log" });
  el.problems = h("div", { class: "st-problems" });

  const root = h(
    "div",
    { class: "studio" },
    input,
    head,
    h(
      "div",
      { class: "st-body" },
      h("div", { class: "st-side" }, treeBar, el.tree),
      h("div", { class: "st-main" }, el.path, el.host, el.problems),
    ),
    h("div", { class: "st-logbar" }, h("span", { text: "سجل الاستوديو" }), btn("مسح", () => (el.log!.textContent = ""))),
    el.log,
  );
  el.root = root;
  document.body.append(root);

  for (const b of el.buttons) b.disabled = true;
  byId("studio-toggle").addEventListener("click", () => setStudio(!S.open));
  root.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
      e.preventDefault();
      void saveCurrent();
    }
  });
  new ResizeObserver(() => S.editor?.layout()).observe(el.host);
}
