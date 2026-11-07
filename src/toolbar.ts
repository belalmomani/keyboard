import { runItem, runTool } from "./actions";
import { ITEM_BY_ID } from "./commandTree";
import { byId, clear, h } from "./dom";
import { emit, savePinned, state, subscribe } from "./state";

interface Entry {
  idx: number; // موضع العنصر في state.pinned
  title: string;
  tip: string;
  dim: boolean;
  run: () => void;
}

function move(i: number, delta: number): void {
  const j = i + delta;
  if (j < 0 || j >= state.pinned.length) return;
  [state.pinned[i], state.pinned[j]] = [state.pinned[j], state.pinned[i]];
  void savePinned();
  emit();
}

function unpin(i: number): void {
  state.pinned.splice(i, 1);
  void savePinned();
  emit();
}

function entries(): Entry[] {
  const out: Entry[] = [];
  state.pinned.forEach((id, idx) => {
    if (id.startsWith("tool:")) {
      const t = state.tools.find((x) => x.id === id.slice(5));
      if (t) out.push({ idx, title: t.name, tip: `Run ${t.id}`, dim: !t.enabled, run: () => void runTool(t) });
    } else {
      const f = ITEM_BY_ID.get(id);
      if (f) out.push({ idx, title: f.item.title, tip: f.item.command, dim: false, run: () => void runItem(f.item) });
    }
  });
  return out;
}

function render(): void {
  const el = byId<HTMLElement>("toolbar");
  const toggle = byId("toolbar-toggle");
  el.classList.toggle("open", state.toolbarOpen);
  el.setAttribute("aria-hidden", state.toolbarOpen ? "false" : "true");
  toggle.classList.toggle("active", state.toolbarOpen);
  clear(el);

  el.append(
    h(
      "div",
      { class: "tb-head" },
      h("div", { class: "tb-title", text: "الأدوات المثبتة" }),
      h("button", {
        class: "btn small" + (state.customize ? " primary" : ""),
        text: state.customize ? "تم" : "تخصيص",
        onclick: () => {
          state.customize = !state.customize;
          emit();
        },
      }),
    ),
  );

  const list = h("div", { class: "tb-list" });
  const items = entries();
  if (items.length === 0) {
    list.append(h("div", { class: "muted-box", text: "لا توجد أدوات مثبتة. اضغط 📌 بجانب أي أمر أو أداة لتثبيتها هنا." }));
  }
  for (const e of items) {
    list.append(
      h(
        "div",
        { class: "tb-item" },
        h("button", { class: "tb-run" + (e.dim ? " dim" : ""), text: e.title, title: e.tip, onclick: e.run }),
        state.customize
          ? h(
              "div",
              { class: "tb-edit" },
              h("button", { class: "icon-btn", text: "↑", title: "تحريك للأعلى", onclick: () => move(e.idx, -1) }),
              h("button", { class: "icon-btn", text: "↓", title: "تحريك للأسفل", onclick: () => move(e.idx, 1) }),
              h("button", { class: "icon-btn", text: "✕", title: "إخفاء من الشريط", onclick: () => unpin(e.idx) }),
            )
          : null,
      ),
    );
  }
  el.append(list);
}

export function initToolbar(): void {
  byId("toolbar-toggle").addEventListener("click", () => {
    state.toolbarOpen = !state.toolbarOpen;
    emit();
  });
  subscribe(render);
  render();
}
