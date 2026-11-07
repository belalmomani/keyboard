import { h } from "./dom";

export function mount(box: HTMLElement, onEscape: () => void): { close: () => void } {
  const overlay = h("div", { class: "overlay" });
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "Escape") onEscape();
  };
  overlay.append(box);
  document.body.append(overlay);
  document.addEventListener("keydown", onKey);
  return {
    close: () => {
      overlay.remove();
      document.removeEventListener("keydown", onKey);
    },
  };
}

/** نافذة تأكيد مخصصة (بدل confirm() الأصلية). تعيد true عند التأكيد. */
export function confirmDialog(title: string, message: string, code?: string): Promise<boolean> {
  return new Promise((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = (v: boolean) => {
      handle?.close();
      resolve(v);
    };
    const box = h(
      "div",
      { class: "dialog" },
      h("div", { class: "dialog-title", text: title }),
      h("div", { class: "dialog-msg", text: message }),
      code ? h("code", { class: "dialog-code", text: code }) : null,
      h(
        "div",
        { class: "dialog-actions" },
        h("button", { class: "btn danger", text: "تأكيد", onclick: () => done(true) }),
        h("button", { class: "btn", text: "إلغاء", onclick: () => done(false) }),
      ),
    );
    handle = mount(box, () => done(false));
  });
}

export interface Choice {
  id: string;
  label: string;
  desc?: string;
}

/** نافذة اختيار من قائمة. تعيد معرّف الخيار أو null عند الإلغاء. */
export function chooseDialog(title: string, choices: Choice[]): Promise<string | null> {
  return new Promise((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = (v: string | null) => {
      handle?.close();
      resolve(v);
    };
    const list = h(
      "div",
      { class: "choice-list" },
      ...choices.map((c) =>
        h(
          "button",
          { class: "choice", onclick: () => done(c.id) },
          h("span", { class: "choice-label", text: c.label }),
          c.desc ? h("span", { class: "choice-desc", text: c.desc }) : null,
        ),
      ),
    );
    const box = h(
      "div",
      { class: "dialog" },
      h("div", { class: "dialog-title", text: title }),
      list,
      h("div", { class: "dialog-actions" }, h("button", { class: "btn", text: "إلغاء", onclick: () => done(null) })),
    );
    handle = mount(box, () => done(null));
  });
}

/** نافذة نص للقراءة فقط (تفاصيل، رسائل خطأ). */
export function textDialog(title: string, body: string): Promise<void> {
  return new Promise((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = () => {
      handle?.close();
      resolve();
    };
    const box = h(
      "div",
      { class: "dialog wide" },
      h("div", { class: "dialog-title", text: title }),
      h("pre", { class: "dialog-pre", text: body }),
      h("div", { class: "dialog-actions" }, h("button", { class: "btn", text: "إغلاق", onclick: done })),
    );
    handle = mount(box, done);
  });
}

/** نافذة إدخال نص. تعيد النص أو null عند الإلغاء. */
export function promptDialog(title: string, label: string, initial = "", ltr = false, secret = false): Promise<string | null> {
  return new Promise((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = (v: string | null) => {
      handle?.close();
      resolve(v);
    };
    const input = document.createElement("input");
    input.className = "text-input";
    input.value = initial;
    if (ltr) input.dir = "ltr";
    if (secret) input.type = "password";
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") done(input.value.trim());
    });
    const box = h(
      "div",
      { class: "dialog" },
      h("div", { class: "dialog-title", text: title }),
      h("div", { class: "dialog-msg", text: label }),
      input,
      h(
        "div",
        { class: "dialog-actions" },
        h("button", { class: "btn primary", text: "موافق", onclick: () => done(input.value.trim()) }),
        h("button", { class: "btn", text: "إلغاء", onclick: () => done(null) }),
      ),
    );
    handle = mount(box, () => done(null));
    setTimeout(() => input.focus(), 0);
  });
}
