// صفحة المشرف المخفية. لا يوجد أي عنصر لها في DOM قبل نجاح الفتح، والحالة في ذاكرة الجلسة فقط.
// تنبيه: إخفاء وتحقق بالتجزئة، وليست حماية قوية (انظر admin.rs).
import { setAdmin } from "./adminState";
import { api } from "./api";
import { mount, promptDialog } from "./dialog";
import { byId, fmtTime, h } from "./dom";
import { trustedDialog } from "./securityUi";
import { setStatus } from "./terminals";

let unlocked = false;

/** خروج المشرف: Run ext ثم الكلمة السرية. يعيد true إن كان مشرفاً (عولج الطلب). */
export async function tryExit(): Promise<boolean> {
  if (!unlocked) return false;
  const secret = await promptDialog("خروج المشرف", "الرقم السري", "", true, true);
  if (secret === null) return true;
  let ok = false;
  try {
    ok = await api.adminProbe(secret);
  } catch {
    ok = false;
  }
  if (!ok) {
    setStatus("الرقم السري غير صحيح");
    return true;
  }
  await api.adminLock();
  unlocked = false;
  removeButton();
  setAdmin(false);
  setStatus("تم الخروج من حساب المشرف");
  return true;
}

/** يُستدعى من شريط Run عند كتابة كلمة غير معرّف أداة. يعيد true إن تم التعامل معها كمشرف. */
export async function tryAdmin(secret: string): Promise<boolean> {
  let ok = false;
  try {
    ok = await api.adminProbe(secret);
  } catch {
    return false;
  }
  if (!ok) return false;
  const name = await promptDialog("التحقق", "الاسم");
  if (name === null) return true;
  const year = await promptDialog("التحقق", "التاريخ");
  if (year === null) return true;
  try {
    await api.adminUnlock(secret, name, year);
  } catch {
    setStatus("بيانات غير صحيحة");
    return true;
  }
  unlocked = true;
  setAdmin(true);
  addButton();
  await openAdmin();
  return true;
}

function addButton(): void {
  if (document.getElementById("admin-toggle")) return;
  const anchor = byId("studio-toggle");
  const b = h("button", { class: "btn", text: "المشرف", title: "صفحة المشرف" });
  b.id = "admin-toggle";
  b.addEventListener("click", () => void openAdmin());
  anchor.after(b);
}

function removeButton(): void {
  document.getElementById("admin-toggle")?.remove();
}

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

const TABLE_LABELS: Record<string, string> = {
  tools: "الأدوات",
  tool_log: "سجل تشغيل الأدوات",
  history: "السجل",
  sessions: "الجلسات",
  favorites: "المفضلة",
  trusted_keys: "الناشرون الموثوقون",
  audit_log: "سجل التدقيق",
};

async function openAdmin(): Promise<void> {
  if (!unlocked) return;
  let info;
  try {
    info = await api.adminInfo();
  } catch {
    unlocked = false;
    removeButton();
    setAdmin(false);
    return;
  }
  let handle: { close: () => void } | null = null;
  const close = () => handle?.close();
  const out = h("pre", { class: "adm-out" });
  const run = (fn: () => Promise<string>) => async () => {
    try {
      out.textContent = await fn();
    } catch (err) {
      out.textContent = `تعذّر: ${String(err)}`;
    }
  };
  const rows = [
    ["الإصدار", info.version],
    ["مجلد البيانات", info.dataDir],
    ["مجلد الأدوات", info.toolsDir],
    ["مجلد الاستوديو", info.studioDir],
    ["حجم قاعدة البيانات", fmtBytes(info.dbBytes)],
    ...info.counts.map(([t, n]) => [TABLE_LABELS[t] ?? t, String(n)]),
  ];
  const table = h(
    "div",
    { class: "adm-table" },
    ...rows.map(([k, v]) =>
      h("div", { class: "adm-row" }, h("span", { class: "adm-k", text: k }), h("span", { class: "adm-v", text: v })),
    ),
  );
  const box = h(
    "div",
    { class: "dialog adm-dialog" },
    h("div", { class: "dialog-title", text: "صفحة المشرف" }),
    h("div", {
      class: "dialog-msg",
      text: "مخفية خلف بوابة بسيطة (إخفاء لا أمان قوي). كل فتح وكل محاولة فاشلة تُسجَّل في سجل التدقيق.",
    }),
    table,
    h(
      "div",
      { class: "dialog-actions adm-actions" },
      h("button", {
        class: "btn",
        text: "سجل التدقيق الكامل",
        onclick: run(async () => {
          const r = await api.adminAudit(1000);
          return r.length ? r.map((x) => `${fmtTime(x.ts)}  [${x.kind}]  ${x.subject}  ${x.detail}`).join("\n") : "لا توجد سجلات.";
        }),
      }),
      h("button", {
        class: "btn",
        text: "الناشرون الموثوقون",
        onclick: () => {
          close();
          void trustedDialog();
        },
      }),
      h("button", {
        class: "btn",
        text: "إعادة فحص كل الأدوات",
        onclick: run(async () => {
          const r = await api.adminRescanAll();
          return r.map(([id, risk]) => `${id}: ${risk}`).join("\n") || "لا أدوات.";
        }),
      }),
      h("button", {
        class: "btn",
        text: "فتح مجلد البيانات",
        onclick: run(async () => {
          await api.adminOpenDataDir();
          return "فُتح المجلد.";
        }),
      }),
    ),
    out,
    h(
      "div",
      { class: "dialog-actions" },
      h("button", {
        class: "btn",
        text: "قفل المشرف",
        onclick: () => {
          void api.adminLock();
          unlocked = false;
          removeButton();
          setAdmin(false);
          close();
          setStatus("أُقفل المشرف");
        },
      }),
      h("button", { class: "btn primary", text: "إغلاق", onclick: close }),
    ),
  );
  handle = mount(box, close);
}

