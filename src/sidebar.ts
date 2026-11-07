import { api, type FrequentEntry, type HistoryEntry, type SessionRow } from "./api";
import { runCommand, runItem } from "./actions";
import { ALL, ITEM_BY_ID, TREE, searchItems, type CmdItem, type FlatItem } from "./commandTree";
import { confirmDialog } from "./dialog";
import { byId, clear, fmtTime, h } from "./dom";
import { emit, state, subscribe, togglePin, type ShellKind, type Tab } from "./state";
import { renderToolsTab } from "./toolsPanel";

const TABS: { id: Tab; label: string }[] = [
  { id: "commands", label: "الأوامر" },
  { id: "tools", label: "الأدوات" },
  { id: "smart", label: "ذكي" },
  { id: "favorites", label: "المفضلة" },
  { id: "history", label: "السجل" },
];

const openSet = new Set<string>(["sys"]);
let content: HTMLDivElement;
let tabsRow: HTMLDivElement;
let renderToken = 0;

async function toggleFavorite(id: string): Promise<void> {
  try {
    const now = await api.favoriteToggle(id);
    if (now) state.favorites.add(id);
    else state.favorites.delete(id);
  } catch {
    return;
  }
  emit();
}

function itemRow(f: FlatItem, showPath: boolean): HTMLElement {
  const it: CmdItem = f.item;
  const fav = state.favorites.has(it.id);
  const pinned = state.pinned.includes(it.id);
  return h(
    "div",
    { class: "row" },
    h(
      "div",
      { class: "row-main" },
      h("div", { class: "row-title", text: it.title }),
      showPath ? h("div", { class: "row-path", text: f.path }) : null,
      h("div", { class: "desc", text: it.desc }),
      h("code", { class: "cmd", text: it.command }),
    ),
    h(
      "div",
      { class: "row-actions" },
      h("button", {
        class: "icon-btn run",
        text: it.insertOnly ? "⤓" : "▶",
        title: it.insertOnly ? "إدراج في الطرفية دون تنفيذ" : "تنفيذ",
        onclick: () => void runItem(it),
      }),
      h("button", {
        class: "icon-btn" + (fav ? " on" : ""),
        text: fav ? "★" : "☆",
        title: fav ? "إزالة من المفضلة" : "إضافة إلى المفضلة",
        onclick: () => void toggleFavorite(it.id),
      }),
      h("button", {
        class: "icon-btn" + (pinned ? " on" : ""),
        text: "📌",
        title: pinned ? "إزالة من شريط الأدوات" : "تثبيت في شريط الأدوات",
        onclick: () => togglePin(it.id),
      }),
    ),
  );
}

function historyRow(e: { item_id: string | null; command: string; shell: string }, extra?: string): HTMLElement {
  const known = e.item_id ? ITEM_BY_ID.get(e.item_id) : undefined;
  const shell = (e.shell === "cmd" ? "cmd" : "powershell") as ShellKind;
  return h(
    "div",
    { class: "row" },
    h(
      "div",
      { class: "row-main" },
      known ? h("div", { class: "row-title", text: known.item.title }) : null,
      h("code", { class: "cmd show", text: e.command }),
      extra ? h("div", { class: "row-path", text: extra }) : null,
    ),
    h(
      "div",
      { class: "row-actions" },
      h("button", {
        class: "icon-btn run",
        text: "▶",
        title: "إعادة التنفيذ",
        onclick: () => void runCommand({ itemId: e.item_id, command: e.command, shell }),
      }),
    ),
  );
}

function section(title: string, ...rows: HTMLElement[]): HTMLElement {
  return h("div", { class: "section" }, h("div", { class: "section-title", text: title }), ...rows);
}

function empty(msg: string): HTMLElement {
  return h("div", { class: "muted-box", text: msg });
}

// ---------- التبويبات ----------

function renderTree(): HTMLElement {
  const wrap = h("div", { class: "tree" });
  for (const cat of TREE) {
    const d = h("details", { class: "cat" });
    d.open = openSet.has(cat.id);
    d.addEventListener("toggle", () => {
      if (d.open) openSet.add(cat.id);
      else openSet.delete(cat.id);
    });
    d.append(h("summary", { text: cat.title }));
    for (const g of cat.groups) {
      const gd = h("details", { class: "grp" });
      gd.open = openSet.has(g.id);
      gd.addEventListener("toggle", () => {
        if (gd.open) openSet.add(g.id);
        else openSet.delete(g.id);
      });
      gd.append(h("summary", { text: g.title }));
      for (const item of g.items) {
        gd.append(itemRow({ item, path: `${cat.title} › ${g.title}` }, false));
      }
      d.append(gd);
    }
    wrap.append(d);
  }
  return wrap;
}

function renderSearch(q: string): HTMLElement {
  const res = searchItems(q);
  if (res.length === 0) return empty("لا توجد نتائج مطابقة.");
  return h("div", { class: "results" }, ...res.map((f) => itemRow(f, true)));
}

function renderFavorites(): HTMLElement {
  const rows = ALL.filter((f) => state.favorites.has(f.item.id)).map((f) => itemRow(f, true));
  if (rows.length === 0) return empty("لا توجد مفضلة بعد. اضغط ☆ بجانب أي أمر لإضافته.");
  return h("div", { class: "results" }, ...rows);
}

async function renderSmart(token: number): Promise<HTMLElement | null> {
  let frequent: FrequentEntry[] = [];
  let recent: HistoryEntry[] = [];
  try {
    [frequent, recent] = await Promise.all([api.historyFrequent(5), api.historyRecent(5)]);
  } catch {
    /* بلا سجل */
  }
  if (token !== renderToken) return null;

  const wrap = h("div", { class: "smart" });

  const favs = ALL.filter((f) => state.favorites.has(f.item.id)).slice(0, 6);
  if (favs.length) wrap.append(section("مفضلتك", ...favs.map((f) => itemRow(f, false))));

  if (frequent.length) wrap.append(section("الأكثر استخداماً", ...frequent.map((e) => historyRow(e, `${e.count} مرّة`))));
  if (recent.length) wrap.append(section("الأحدث", ...recent.map((e) => historyRow(e, fmtTime(e.ts)))));

  const kind = state.activeKind;
  const suggestions = ALL.filter(
    (f) => !state.favorites.has(f.item.id) && !f.item.insertOnly && (kind === null || f.item.shell === "any" || f.item.shell === kind),
  ).slice(0, 6);
  const label = kind === null ? "اقتراحات" : `مقترحة لجلسة ${kind === "cmd" ? "CMD" : "PowerShell"} الحالية`;
  wrap.append(section(label, ...suggestions.map((f) => itemRow(f, true))));

  return wrap;
}

async function renderHistory(token: number): Promise<HTMLElement | null> {
  let recent: HistoryEntry[] = [];
  let sess: SessionRow[] = [];
  try {
    [recent, sess] = await Promise.all([api.historyRecent(100), api.sessionsRecent(10)]);
  } catch {
    /* بلا سجل */
  }
  if (token !== renderToken) return null;

  const wrap = h("div", { class: "history" });
  if (recent.length === 0) {
    wrap.append(empty("لا توجد أوامر منفذة بعد. الأوامر التي تُشغَّل من Terminal AR تُسجَّل هنا."));
  } else {
    wrap.append(
      h(
        "div",
        { class: "section-head" },
        h("div", { class: "section-title", text: "الأوامر المنفذة" }),
        h("button", {
          class: "btn small danger",
          text: "مسح السجل",
          onclick: async () => {
            const ok = await confirmDialog("مسح السجل", "سيتم حذف سجل الأوامر المنفذة نهائياً.");
            if (!ok) return;
            try {
              await api.historyClear();
            } catch {
              return;
            }
            emit();
          },
        }),
      ),
      ...recent.map((e) => historyRow(e, fmtTime(e.ts))),
    );
  }
  if (sess.length) {
    wrap.append(
      section(
        "الجلسات السابقة",
        ...sess.map((s) =>
          h(
            "div",
            { class: "row" },
            h(
              "div",
              { class: "row-main" },
              h("div", { class: "row-title", text: s.shell === "cmd" ? "CMD" : "PowerShell" }),
              h("div", {
                class: "row-path",
                text: `${fmtTime(s.started_at)} — ${s.ended_at ? `رمز الخروج: ${s.exit_code ?? "؟"}` : "لم تُسجَّل نهايتها"}`,
              }),
            ),
          ),
        ),
      ),
    );
  }
  return wrap;
}

// ---------- العرض ----------

function render(): void {
  const sidebar = byId<HTMLElement>("sidebar");
  sidebar.classList.toggle("collapsed", !state.sidebarOpen);
  byId("sidebar-toggle").classList.toggle("active", state.sidebarOpen);

  for (const b of tabsRow.querySelectorAll<HTMLButtonElement>("button")) {
    b.classList.toggle("active", b.dataset.tab === state.tab && state.query.trim() === "");
  }

  const token = ++renderToken;
  const q = state.query.trim();

  const show = (el: HTMLElement | null) => {
    if (el && token === renderToken) {
      clear(content);
      content.append(el);
    }
  };

  if (q) return show(renderSearch(q));
  switch (state.tab) {
    case "commands":
      return show(renderTree());
    case "tools":
      return show(renderToolsTab());
    case "favorites":
      return show(renderFavorites());
    case "smart":
      void renderSmart(token).then(show);
      return;
    case "history":
      void renderHistory(token).then(show);
      return;
  }
}

export function initSidebar(): void {
  const sidebar = byId<HTMLElement>("sidebar");
  const search = h("input", {
    class: "search",
    type: "search",
    placeholder: "ابحث عن وظيفة بالعربية…",
    oninput: (e) => {
      state.query = (e.target as HTMLInputElement).value;
      render();
    },
  });
  tabsRow = h(
    "div",
    { class: "side-tabs" },
    ...TABS.map((t) => {
      const b = h("button", {
        class: "side-tab",
        text: t.label,
        onclick: () => {
          state.tab = t.id;
          state.query = "";
          search.value = "";
          emit();
        },
      });
      b.dataset.tab = t.id;
      return b;
    }),
  );
  content = h("div", { class: "side-content" });
  sidebar.append(h("div", { class: "side-head" }, search, tabsRow), content);

  byId("sidebar-toggle").addEventListener("click", () => {
    state.sidebarOpen = !state.sidebarOpen;
    emit();
  });

  subscribe(render);
  render();
}
