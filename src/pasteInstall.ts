import { isAdmin } from "./adminState";
import { api, type Diag } from "./api";
import { mount, promptDialog, textDialog } from "./dialog";
import { h } from "./dom";
import { jsonPathLines, lineForPath } from "./jsonPath";
import { aiPrompt } from "./sdkDoc";
import { afterInstall } from "./toolsPanel";

function diagText(diags: Diag[], text: string): string {
  if (diags.length === 0) return "✔ لا توجد مشاكل.";
  const lines = jsonPathLines(text);
  return diags
    .map((d) => {
      const line = d.line ?? lineForPath(lines, d.path);
      return `${d.severity === "error" ? "✘" : "⚠"} السطر ${line}: ${d.message}${d.hint ? `\n    الحل: ${d.hint}` : ""}`;
    })
    .join("\n");
}

async function copyText(t: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(t);
    return true;
  } catch {
    const ta = document.createElement("textarea");
    ta.value = t;
    document.body.append(ta);
    ta.select();
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch {
      ok = false;
    }
    ta.remove();
    return ok;
  }
}

/** ينسخ طلباً جاهزاً لأي ذكاء اصطناعي (يسأل أولاً ماذا تريد من الأداة). */
export async function copyAiPrompt(): Promise<void> {
  if (!isAdmin()) return;
  const goal = await promptDialog("طلب للذكاء الاصطناعي", "ماذا تريد أن تفعل الأداة؟ (اتركه فارغاً لتكتبه بنفسك لاحقاً)");
  if (goal === null) return;
  const text = aiPrompt(goal);
  if (await copyText(text)) {
    await textDialog("تم النسخ", "نُسخ الطلب. الصقه عند أي ذكاء اصطناعي، ثم انسخ ناتجه (JSON فقط) والصقه هنا واضغط «فحص».");
  } else {
    await textDialog("انسخ الطلب بنفسك", text);
  }
}

/** تثبيت أداة بلصق كود manifest.json مباشرة (بلا ملف ولا مشروع). */
export function pasteInstallDialog(): Promise<void> {
  return new Promise((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = () => {
      handle?.close();
      resolve();
    };
    const ta = document.createElement("textarea");
    ta.className = "text-input tf-cmds";
    ta.dir = "ltr";
    ta.spellcheck = false;
    ta.rows = 14;
    ta.placeholder = "الصق هنا كود manifest.json كاملاً (من { إلى })";
    const out = h("pre", { class: "tf-preview", text: "" });
    const installBtn = h("button", { class: "btn primary", text: "تثبيت" });

    const check = async (): Promise<boolean> => {
      if (!ta.value.trim()) {
        out.textContent = "الصق الكود أولاً.";
        return false;
      }
      try {
        const r = await api.studioCheckText(ta.value);
        if (r.fixed || r.report.ok) ta.value = r.text; // الإصلاح التلقائي: فواصل زائدة وترتيب الأسطر
        const head = r.fixed ? "تم إصلاح الكود تلقائياً (فواصل زائدة/ترتيب) — راجعه.\n\n" : "";
        out.textContent = head + diagText(r.report.diagnostics, r.text);
        return r.report.ok;
      } catch (err) {
        out.textContent = String(err);
        return false;
      }
    };

    const install = async () => {
      if (!(await check())) return;
      installBtn.disabled = true;
      try {
        const o = await api.toolInstallText(ta.value);
        done();
        await afterInstall(o);
      } catch (err) {
        installBtn.disabled = false;
        await textDialog("تعذّر التثبيت", String(err));
      }
    };
    installBtn.addEventListener("click", () => void install());

    const box = h(
      "div",
      { class: "dialog wide tf-dialog" },
      h("div", { class: "dialog-title", text: "تثبيت أداة من كود" }),
      h("div", {
        class: "dialog-msg",
        text: "الصق كود الأداة. «فحص» يصلح الفواصل الزائدة ويرتّب الأسطر ويدلّك على كل خطأ بسطره، ثم «تثبيت». الأداة تمر بكل فحوص التثبيت العادية وتحتاج موافقتك على صلاحياتها.",
      }),
      h("div", { class: "tf-body" }, ta, out),
      h(
        "div",
        { class: "dialog-actions" },
        h("button", { class: "btn admin-only", text: "نسخ طلب للذكاء الاصطناعي", title: "طلب جاهز تعطيه لأي ذكاء اصطناعي ليكتب لك الكود بالصيغة الصحيحة", onclick: () => void copyAiPrompt() }),
        h("button", { class: "btn", text: "فحص", onclick: () => void check() }),
        installBtn,
        h("button", { class: "btn", text: "إغلاق", onclick: done }),
      ),
    );
    handle = mount(box, done);
    setTimeout(() => ta.focus(), 0);
  });
}
