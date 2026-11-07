import { api, type LabReport, type SecurityInfo, type SigStatus, type ToolInfo } from "./api";
import { mount, textDialog } from "./dialog";
import { fmtTime, h } from "./dom";

export const PERM_LABELS: Record<string, string> = {
  "system.read": "قراءة معلومات النظام",
  "files.read": "قراءة الملفات",
  "files.write": "كتابة/تعديل الملفات",
  "files.delete": "حذف الملفات",
  network: "الوصول إلى الشبكة",
  "process.run": "تشغيل برامج/عمليات",
  "services.manage": "إدارة الخدمات",
  "system.sensitive": "عمليات حساسة على النظام",
  elevated: "صلاحيات المسؤول (رفع الصلاحيات)",
};

export const SIG_LABELS: Record<SigStatus, string> = {
  builtin: "مدمجة",
  trusted: "موقّعة • ناشر موثوق",
  untrusted: "موقّعة • ناشر غير موثوق",
  unsigned: "غير موقّعة",
};

export function permLabel(p: string): string {
  return PERM_LABELS[p] ? `${PERM_LABELS[p]}  (${p})` : p;
}

const RISK_LABELS: Record<string, string> = { low: "منخفضة", medium: "متوسطة", high: "عالية" };

/** نص تقرير الفحص الساكن. هذا فحص تقريبي وليس ضماناً. */
export function formatReport(r: LabReport): string {
  const lines = [
    `الخطورة التقديرية: ${RISK_LABELS[r.risk] ?? r.risk}`,
    `وقت الفحص: ${fmtTime(r.generatedAt)}`,
    `الصلاحيات التي يحتاجها الفحص: ${r.required.join("، ") || "—"}`,
    `الصلاحيات المعلنة: ${r.declared.join("، ") || "—"}`,
  ];
  if (r.undeclared.length) lines.push(`⚠ صلاحيات غير معلنة: ${r.undeclared.join("، ")}`);
  lines.push("", "النتائج:");
  if (r.findings.length === 0) lines.push("  لا شيء لافت.");
  for (const f of r.findings) {
    lines.push(`• [${f.severity}] ${f.permission} — ${f.rule}  (الإجراء ${f.action})`);
    lines.push(`    ${f.step}`);
  }
  for (const n of r.notes) lines.push("", n);
  lines.push("", "تنبيه: هذا فحص ساكن تقريبي؛ لا يضمن أن الأداة آمنة ولا يعوّض الثقة بالمصدر.");
  return lines.join("\n");
}

/** نافذة مراجعة الصلاحيات: يمنح المستخدم كل صلاحية أو يرفضها على حدة. تعيد true إذا حُفظت. */
export function permissionsDialog(tool: ToolInfo, info: SecurityInfo): Promise<boolean> {
  return new Promise((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = (v: boolean) => {
      handle?.close();
      resolve(v);
    };
    const checks = new Map<string, HTMLInputElement>();
    const rows = info.declared.map((p) => {
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = info.granted.includes(p);
      checks.set(p, cb);
      const risky = p === "elevated" || p === "files.delete" || p === "system.sensitive";
      return h(
        "label",
        { class: "perm-row" + (risky ? " risky" : "") },
        cb,
        h("span", { text: permLabel(p) }),
      );
    });
    const save = async () => {
      const granted = [...checks.entries()].filter(([, c]) => c.checked).map(([p]) => p);
      try {
        await api.toolSetGrants(tool.id, granted);
        done(true);
      } catch (err) {
        await textDialog("تعذّر حفظ الصلاحيات", String(err));
      }
    };
    const box = h(
      "div",
      { class: "dialog wide" },
      h("div", { class: "dialog-title", text: `صلاحيات «${tool.name}»` }),
      h("div", {
        class: "dialog-msg",
        text: `الحالة: ${SIG_LABELS[info.signature.status]}. الصلاحيات تُمنح لكل أداة على حدة، ولا يمنحها وجود الأداة في أي متجر.`,
      }),
      info.declared.length === 0
        ? h("div", { class: "muted-box", text: "الأداة لا تطلب أي صلاحية." })
        : h("div", { class: "perm-list" }, ...rows),
      info.report
        ? h("div", {
            class: "muted-box",
            text: `الفحص الساكن: خطورة ${RISK_LABELS[info.report.risk] ?? info.report.risk}، ${info.report.findings.length} نتيجة.`,
          })
        : null,
      h(
        "div",
        { class: "dialog-actions" },
        h("button", { class: "btn primary", text: "حفظ", onclick: () => void save() }),
        h("button", { class: "btn", text: "إلغاء", onclick: () => done(false) }),
      ),
    );
    handle = mount(box, () => done(false));
  });
}

/** إدارة الناشرين الموثوقين (مفاتيح Ed25519 العامة). */
export async function trustedDialog(): Promise<void> {
  return new Promise((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = () => {
      handle?.close();
      resolve();
    };
    const listEl = h("div", { class: "perm-list" });
    const nameIn = document.createElement("input");
    nameIn.className = "text-input";
    nameIn.placeholder = "اسم الناشر";
    const keyIn = document.createElement("input");
    keyIn.className = "text-input";
    keyIn.placeholder = "المفتاح العام (base64)";
    keyIn.dir = "ltr";
    const msg = h("div", { class: "dialog-msg", text: "" });

    const reload = async () => {
      listEl.replaceChildren();
      try {
        const keys = await api.trustedList();
        if (keys.length === 0) listEl.append(h("div", { class: "muted-box", text: "لا يوجد ناشرون موثوقون." }));
        for (const k of keys) {
          listEl.append(
            h(
              "div",
              { class: "perm-row" },
              h("span", { text: `${k.name} — ${k.keyId} — ${fmtTime(k.addedAt)}` }),
              h("button", {
                class: "btn small danger",
                text: "إزالة",
                onclick: async () => {
                  try {
                    await api.trustedRemove(k.keyId);
                  } catch (err) {
                    msg.textContent = String(err);
                  }
                  await reload();
                },
              }),
            ),
          );
        }
      } catch (err) {
        msg.textContent = String(err);
      }
    };
    const add = async () => {
      try {
        const k = await api.trustedAdd(nameIn.value.trim(), keyIn.value.trim());
        msg.textContent = `أُضيف الناشر ${k.name} (${k.keyId})`;
        nameIn.value = "";
        keyIn.value = "";
      } catch (err) {
        msg.textContent = String(err);
      }
      await reload();
    };
    const box = h(
      "div",
      { class: "dialog wide" },
      h("div", { class: "dialog-title", text: "الناشرون الموثوقون" }),
      h("div", {
        class: "dialog-msg",
        text: "الثقة بناشر تعني فقط أن توقيعه صحيح؛ لا تمنح أي صلاحية تلقائياً. أضف مفتاحاً حصلت عليه من الناشر عبر قناة تثق بها.",
      }),
      listEl,
      nameIn,
      keyIn,
      msg,
      h(
        "div",
        { class: "dialog-actions" },
        h("button", { class: "btn primary", text: "إضافة", onclick: () => void add() }),
        h("button", { class: "btn", text: "إغلاق", onclick: done }),
      ),
    );
    handle = mount(box, done);
    void reload();
  });
}

export async function auditDialog(): Promise<void> {
  let body: string;
  try {
    const rows = await api.auditRecent(100);
    body = rows.length
      ? rows.map((r) => `${fmtTime(r.ts)}  [${r.kind}]  ${r.subject}  ${r.detail}`).join("\n")
      : "لا توجد سجلات.";
  } catch (err) {
    body = `تعذّر التحميل: ${String(err)}`;
  }
  await textDialog("سجل التدقيق (الأحدث أولاً)", body);
}
