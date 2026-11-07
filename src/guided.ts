import { api } from "./api";
import { mount, textDialog } from "./dialog";
import { h } from "./dom";
import { OUTPUT_LABELS, RECIPES, generate, paramErrors, type OutputMode, type Recipe } from "./recipes";
import { slugFrom } from "./toolForm";

/**
 * الإنشاء الموجّه: اسم الأداة + ماذا تريد أن تفعل + كيف تظهر النتيجة.
 * الاستوديو يولّد الهيكل من «وصفة» جاهزة ويسأل فقط عن المُدخلات الناقصة.
 * ليس فهماً لنص حر: القائمة ثابتة، وكل الأوامر المولَّدة تُعرض قبل الإنشاء.
 */

const SLUG = /^[a-z][a-z0-9-]*$/;
const okId = (s: string): boolean => s.length >= 2 && s.length <= 32 && SLUG.test(s) && !s.endsWith("-");

export interface GuidedResult {
  projectId: string;
  openForm: boolean;
}

export async function openGuided(): Promise<GuidedResult | null> {
  let existing = new Set<string>();
  try {
    existing = new Set((await api.studioProjects()).map((p) => p.id));
  } catch {
    /* التحقق من التكرار سيتم عند الإنشاء */
  }

  return new Promise<GuidedResult | null>((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = (v: GuidedResult | null) => {
      handle?.close();
      resolve(v);
    };

    let recipe: Recipe = RECIPES[0];
    const values: Record<string, string> = {};
    let output: OutputMode = "raw";
    let name = "";
    let manualId = "";

    const input = (value: string, on: (v: string) => void, ltr = false, ph = ""): HTMLInputElement => {
      const i = document.createElement("input");
      i.className = "text-input";
      i.value = value;
      i.placeholder = ph;
      if (ltr) i.dir = "ltr";
      i.addEventListener("input", () => {
        on(i.value);
        refresh();
      });
      return i;
    };
    const errEl = (): HTMLElement => h("div", { class: "tf-err" });
    const field = (label: string, el: HTMLElement, err: HTMLElement, hint?: string): HTMLElement =>
      h("label", { class: "tf-field" }, h("span", { class: "tf-label", text: label }), el, hint ? h("span", { class: "tf-hint", text: hint }) : null, err);

    const nameErr = errEl();
    const idErr = errEl();
    const idField = field("المعرّف (إنجليزي — اسم الأداة بالعربية لا يصلح معرّفاً)", input("", (v) => (manualId = v), true, "my-tool"), idErr, "أحرف إنجليزية صغيرة وأرقام وشرطة -");
    const paramsBox = h("div", {});
    const paramErrEls = new Map<string, HTMLElement>();
    const recipeDesc = h("div", { class: "tf-hint" });
    const outErr = errEl();
    const preview = h("pre", { class: "tf-preview" });
    const note = h("div", { class: "dialog-msg" });
    const createBtn = h("button", { class: "btn primary", text: "إنشاء الأداة" });
    const openFormCb = document.createElement("input");
    openFormCb.type = "checkbox";

    const recipeSel = document.createElement("select");
    recipeSel.className = "text-input";
    for (const r of RECIPES) {
      const o = document.createElement("option");
      o.value = r.id;
      o.textContent = r.title;
      recipeSel.append(o);
    }
    const outSel = document.createElement("select");
    outSel.className = "text-input";

    const idOf = (): { id: string; needManual: boolean } => {
      const s = slugFrom(name.trim());
      if (s.length >= 2) return { id: s, needManual: false };
      return { id: manualId.trim(), needManual: true };
    };

    const buildOutputs = () => {
      outSel.replaceChildren();
      for (const m of recipe.allowed) {
        const o = document.createElement("option");
        o.value = m;
        o.textContent = OUTPUT_LABELS[m];
        outSel.append(o);
      }
      if (!recipe.allowed.includes(output)) output = recipe.allowed[0];
      outSel.value = output;
    };

    const buildParams = () => {
      paramsBox.replaceChildren();
      paramErrEls.clear();
      for (const p of recipe.params) {
        if (values[p.key] === undefined) values[p.key] = p.default ?? "";
        const e = errEl();
        paramErrEls.set(p.key, e);
        paramsBox.append(field(p.label, input(values[p.key], (v) => (values[p.key] = v), true, p.placeholder), e));
      }
    };

    const refresh = () => {
      const { id, needManual } = idOf();
      idField.style.display = needManual ? "" : "none";
      let bad = false;
      nameErr.textContent = !name.trim() ? "اكتب اسم الأداة" : name.length > 60 ? "الاسم أطول من 60 حرفاً" : "";
      if (nameErr.textContent) bad = true;
      idErr.textContent = "";
      if (needManual || name.trim()) {
        if (!okId(id)) idErr.textContent = needManual ? "اكتب معرّفاً صالحاً (إنجليزي صغير، 2-32)" : "";
        else if (existing.has(id)) idErr.textContent = `المعرّف «${id}» مستخدم في مشروع آخر — غيّر الاسم أو اكتب معرّفاً`;
        if (idErr.textContent) bad = true;
        if (!needManual && existing.has(id)) idField.style.display = "";
      }
      const pe = paramErrors(recipe, values);
      for (const [k, el] of paramErrEls) el.textContent = pe[k] ?? "";
      if (Object.keys(pe).length) bad = true;
      recipeDesc.textContent = recipe.description;
      if (Object.keys(pe).length === 0) {
        const steps = generate(recipe, values, output);
        preview.textContent = ["الأوامر التي ستُنشأ (راجعها قبل الإنشاء):", "", ...steps].join("\n");
      } else {
        preview.textContent = "أكمل الحقول الناقصة لتظهر الأوامر.";
      }
      createBtn.disabled = bad;
    };

    recipeSel.addEventListener("change", () => {
      recipe = RECIPES.find((r) => r.id === recipeSel.value) ?? RECIPES[0];
      buildParams();
      buildOutputs();
      refresh();
    });
    outSel.addEventListener("change", () => {
      output = outSel.value as OutputMode;
      refresh();
    });

    const create = async () => {
      const { id } = idOf();
      const steps = generate(recipe, values, output);
      createBtn.disabled = true;
      note.textContent = "جارٍ الإنشاء...";
      try {
        await api.studioCreate(id, name.trim());
        const permissions = ["process.run"];
        const manifest = () => ({
          id,
          name: name.trim(),
          developer: "أنا",
          version: "1.0.0",
          description: recipe.description,
          platforms: ["windows"],
          kind: "command",
          engine: "shell",
          sdkVersion: "1",
          permissions,
          actions: [{ id: recipe.id, title: recipe.title, description: recipe.description, shell: "powershell", steps }],
        });
        const write = () => api.studioWrite(id, "manifest.json", JSON.stringify(manifest(), null, 2) + "\n");
        await write();
        let r = await api.studioValidate(id);
        const added = r.report?.undeclared ?? [];
        if (added.length) {
          permissions.push(...added);
          await write();
          r = await api.studioValidate(id);
        }
        const msg = [
          r.ok ? "✔ أُنشئت الأداة وهي صالحة." : "أُنشئت الأداة لكن بقيت مشاكل (ستظهر في لوحة المشاكل).",
          "",
          `الاسم: ${name.trim()}   المعرّف: ${id}`,
          `ما ستفعله: ${recipe.title}`,
          `الصلاحيات المعلنة: ${permissions.join("، ")}`,
          "",
          "الأوامر:",
          ...steps.map((s) => `  ${s}`),
        ];
        for (const w of r.warnings) msg.push("", `تنبيه: ${w}`);
        for (const e of r.errors) msg.push("", `خطأ: ${e}`);
        if (r.ok) msg.push("", "الخطوة التالية: اضغط «تثبيت وتجربة» في أعلى الاستوديو.");
        await textDialog("تم الإنشاء", msg.join("\n"));
        done({ projectId: id, openForm: openFormCb.checked });
      } catch (err) {
        note.textContent = `تعذّر الإنشاء: ${String(err)}`;
        refresh();
      }
    };
    createBtn.addEventListener("click", () => void create());

    const box = h(
      "div",
      { class: "dialog wide tf-dialog" },
      h("div", { class: "dialog-title", text: "إنشاء أداة موجّه" }),
      h("div", { class: "dialog-msg", text: "ثلاث خطوات فقط. الاستوديو يبني الأداة ويسأل عما ينقص." }),
      h(
        "div",
        { class: "tf-body" },
        field("اسم الأداة", input("", (v) => (name = v), false, "مثال: فحص جافا"), nameErr),
        idField,
        field("ماذا تريد أن تفعل؟", recipeSel, errEl()),
        recipeDesc,
        paramsBox,
        field("ماذا تريد أن تظهر لك النتيجة؟", outSel, outErr),
        preview,
        h("label", { class: "perm-row" }, openFormCb, h("span", { text: "افتح النموذج الكامل بعد الإنشاء لتعديل التفاصيل" })),
      ),
      note,
      h("div", { class: "dialog-actions" }, createBtn, h("button", { class: "btn", text: "إلغاء", onclick: () => done(null) })),
    );
    handle = mount(box, () => done(null));
    buildParams();
    buildOutputs();
    refresh();
  });
}
