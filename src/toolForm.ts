import { api } from "./api";
import { mount, textDialog } from "./dialog";
import { h } from "./dom";
import { PERM_LABELS } from "./securityUi";

/** نموذج إنشاء/تعديل أداة بدل كتابة JSON يدوياً: تحقق فوري، صلاحيات تلقائية، ومعاينة لطريقة التشغيل. */

interface FAction {
  id: string;
  title: string;
  description: string;
  shell: "powershell" | "cmd" | "any";
  mode: "run" | "insert";
  confirm: boolean;
  commands: string; // أمر في كل سطر
}

interface FForm {
  id: string;
  name: string;
  developer: string;
  version: string;
  description: string;
  actions: FAction[];
  permissions: string[];
  autoPerms: boolean;
  extra: Record<string, unknown>; // حقول أخرى في manifest تُحفظ كما هي (اعتماديات...)
}

const SLUG = /^[a-z][a-z0-9-]*$/;
const SEMVER = /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$/;
const KNOWN = ["id", "name", "developer", "version", "description", "platforms", "kind", "engine", "sdkVersion", "permissions", "actions"];

const isSlug = (s: string, max = 32): boolean => SLUG.test(s) && !s.endsWith("-") && s.length <= max;

export function slugFrom(text: string): string {
  if (!/^[\x20-\x7e]+$/.test(text)) return "";
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .replace(/^[0-9-]+/, "")
    .slice(0, 32)
    .replace(/-+$/g, "");
}

function stepsOf(a: FAction): string[] {
  return a.commands
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
}

function emptyAction(): FAction {
  return { id: "", title: "", description: "", shell: "powershell", mode: "run", confirm: false, commands: "" };
}

function fromManifest(m: Record<string, unknown>): FForm {
  const str = (v: unknown): string => (typeof v === "string" ? v : "");
  const acts = Array.isArray(m.actions) ? (m.actions as Record<string, unknown>[]) : [];
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(m)) if (!KNOWN.includes(k)) extra[k] = v;
  return {
    id: str(m.id),
    name: str(m.name),
    developer: str(m.developer),
    version: str(m.version) || "1.0.0",
    description: str(m.description),
    permissions: Array.isArray(m.permissions) ? (m.permissions as string[]).filter((p) => typeof p === "string") : ["process.run"],
    autoPerms: true,
    extra,
    actions: acts.map((a) => ({
      id: str(a.id),
      title: str(a.title),
      description: str(a.description),
      shell: (["powershell", "cmd", "any"].includes(str(a.shell)) ? str(a.shell) : "powershell") as FAction["shell"],
      mode: a.mode === "insert" ? "insert" : "run",
      confirm: a.confirm === true,
      commands: Array.isArray(a.steps) ? (a.steps as unknown[]).map(String).join("\n") : "",
    })),
  };
}

function toManifest(f: FForm): Record<string, unknown> {
  const out: Record<string, unknown> = {
    id: f.id,
    name: f.name,
    developer: f.developer,
    version: f.version,
    description: f.description,
    platforms: ["windows"],
    kind: "command",
    engine: "shell",
    sdkVersion: "1",
    permissions: f.permissions,
    actions: f.actions.map((a) => {
      const o: Record<string, unknown> = { id: a.id, title: a.title, description: a.description, shell: a.shell };
      if (a.mode === "insert") o.mode = "insert";
      if (a.confirm) o.confirm = true;
      o.steps = stepsOf(a);
      return o;
    }),
  };
  for (const [k, v] of Object.entries(f.extra)) out[k] = v;
  return out;
}

interface Problem {
  key: string;
  msg: string;
}

function check(f: FForm): Problem[] {
  const p: Problem[] = [];
  if (!isSlug(f.id) || f.id.length < 2)
    p.push({ key: "id", msg: "المعرّف: 2-32 حرفاً إنجليزياً صغيراً أو أرقاماً أو شرطة -، ويبدأ بحرف (مثال: port-watcher). الشرطة السفلية _ غير مقبولة." });
  if (!f.name.trim() || f.name.length > 60) p.push({ key: "name", msg: "اسم الأداة مطلوب (حتى 60 حرفاً)" });
  if (!f.developer.trim() || f.developer.length > 60) p.push({ key: "developer", msg: "اسم المطوّر مطلوب (حتى 60 حرفاً)" });
  if (!SEMVER.test(f.version)) p.push({ key: "version", msg: "الإصدار ثلاثة أرقام تفصل بينها نقطة، مثل 1.0.0" });
  if (f.description.length > 500) p.push({ key: "description", msg: "الوصف أطول من 500 حرف" });
  if (f.actions.length === 0 || f.actions.length > 20) p.push({ key: "actions", msg: "أضف إجراءً واحداً على الأقل (حتى 20)" });
  const seen = new Set<string>();
  f.actions.forEach((a, i) => {
    if (!isSlug(a.id)) p.push({ key: `a${i}.id`, msg: "معرّف الإجراء: أحرف إنجليزية صغيرة وأرقام وشرطة - فقط (مثال: full-scan)" });
    else if (seen.has(a.id)) p.push({ key: `a${i}.id`, msg: "هذا المعرّف مكرر في إجراء آخر" });
    seen.add(a.id);
    if (!a.title.trim() || a.title.length > 80) p.push({ key: `a${i}.title`, msg: "عنوان الزر مطلوب (حتى 80 حرفاً)" });
    const st = stepsOf(a);
    if (st.length === 0 || st.length > 30) p.push({ key: `a${i}.commands`, msg: "اكتب أمراً واحداً على الأقل (حتى 30 سطراً)" });
    else if (a.mode === "insert" && st.length !== 1) p.push({ key: `a${i}.commands`, msg: "وضع «الإدراج» يقبل أمراً واحداً فقط — أبقِ سطراً واحداً أو اختر «ينفَّذ مباشرة»" });
    else if (st.some((s) => s.length > 2000)) p.push({ key: `a${i}.commands`, msg: "أحد الأوامر أطول من 2000 حرف" });
  });
  return p;
}

function modeText(a: FAction): string {
  const parts = [a.mode === "insert" ? "يُدرج الأمر في الطرفية لتعدّله ثم تضغط Enter" : "ينفَّذ مباشرة عند الضغط"];
  if (a.confirm) parts.push("ويسألك قبل التنفيذ");
  return parts.join(" ");
}

/** يفتح النموذج لمشروع. يعيد true إذا حُفظ manifest.json. */
export async function openToolForm(projectId: string): Promise<boolean> {
  let form: FForm;
  try {
    const text = await api.studioRead(projectId, "manifest.json");
    form = fromManifest(JSON.parse(text) as Record<string, unknown>);
  } catch (err) {
    await textDialog(
      "تعذّر فتح النموذج",
      `manifest.json فيه خطأ أو غير موجود، أصلحه من المحرر (لوحة المشاكل تدلك على مكانه) ثم افتح النموذج.\n\n${String(err)}`,
    );
    return false;
  }
  if (form.actions.length === 0) form.actions.push(emptyAction());
  if (!form.permissions.includes("process.run")) form.permissions.push("process.run");

  return new Promise<boolean>((resolve) => {
    let handle: { close: () => void } | null = null;
    const done = (v: boolean) => {
      handle?.close();
      resolve(v);
    };
    const errEls = new Map<string, HTMLElement>();
    const summary = h("div", { class: "tf-summary" });
    const preview = h("pre", { class: "tf-preview" });
    const saveBtn = h("button", { class: "btn primary", text: "حفظ الأداة" });
    const body = h("div", { class: "tf-body" });
    const status = h("div", { class: "dialog-msg", text: "" });

    const errBox = (key: string): HTMLElement => {
      const e = h("div", { class: "tf-err" });
      errEls.set(key, e);
      return e;
    };
    const field = (label: string, input: HTMLElement, key: string, hint?: string): HTMLElement =>
      h("label", { class: "tf-field" }, h("span", { class: "tf-label", text: label }), input, hint ? h("span", { class: "tf-hint", text: hint }) : null, errBox(key));

    const text = (value: string, on: (v: string) => void, ltr = false, placeholder = ""): HTMLInputElement => {
      const i = document.createElement("input");
      i.className = "text-input";
      i.value = value;
      if (ltr) i.dir = "ltr";
      if (placeholder) i.placeholder = placeholder;
      i.addEventListener("input", () => {
        on(i.value);
        refresh();
      });
      return i;
    };

    const refresh = () => {
      const problems = check(form);
      for (const [k, el] of errEls) el.textContent = problems.find((p) => p.key === k)?.msg ?? "";
      summary.textContent = problems.length === 0 ? "✔ لا توجد أخطاء — يمكنك الحفظ" : `✘ ${problems.length} خطأ — صححها (مكتوبة بالأحمر تحت كل حقل) ليُفعَّل الحفظ`;
      summary.className = "tf-summary " + (problems.length === 0 ? "ok" : "bad");
      saveBtn.disabled = problems.length > 0;
      const lines = [`تشغيل الأداة:  Run ${form.id || "<المعرّف>"}`, "أو زر «تشغيل» على بطاقتها في تبويب «الأدوات».", "", "الإجراءات:"];
      for (const a of form.actions) lines.push(`• ${a.title || "(بلا عنوان)"} — ${modeText(a)}`);
      preview.textContent = lines.join("\n");
    };

    const build = () => {
      errEls.clear();
      body.replaceChildren();
      const idInput = text(form.id, (v) => (form.id = v), true, "my-tool");
      body.append(
        h("div", { class: "tf-sec", text: "1) معلومات الأداة" }),
        field("اسم الأداة (يظهر في القائمة)", text(form.name, (v) => (form.name = v)), "name"),
        field("المعرّف (للاستدعاء Run <المعرّف>)", idInput, "id", "إنجليزي صغير وأرقام وشرطة - فقط"),
        field("المطوّر", text(form.developer, (v) => (form.developer = v)), "developer"),
        field("الإصدار", text(form.version, (v) => (form.version = v), true), "version", "ارفعه عند كل تعديل تريد تثبيته فوق نسخة مثبتة"),
        field("الوصف", text(form.description, (v) => (form.description = v)), "description"),
        h("div", { class: "tf-sec", text: "2) الإجراءات (كل إجراء = زر يشغّل أوامر)" }),
        errBox("actions"),
      );
      form.actions.forEach((a, i) => {
        const titleIn = text(a.title, (v) => {
          a.title = v;
          if (!a.id) {
            const sug = slugFrom(v);
            if (sug) {
              a.id = sug;
              idEl.value = sug;
            }
          }
        });
        const idEl = text(a.id, (v) => (a.id = v), true, "full-scan");
        const shell = document.createElement("select");
        shell.className = "text-input";
        for (const [v, l] of [["powershell", "PowerShell"], ["cmd", "CMD"], ["any", "أي صدفة (الحالية)"]] as const) {
          const o = document.createElement("option");
          o.value = v;
          o.textContent = l;
          shell.append(o);
        }
        shell.value = a.shell;
        shell.addEventListener("change", () => {
          a.shell = shell.value as FAction["shell"];
          refresh();
        });
        const mode = document.createElement("select");
        mode.className = "text-input";
        for (const [v, l] of [["run", "ينفَّذ مباشرة"], ["insert", "يُدرج في الطرفية لأعدّله ثم أضغط Enter (أمر واحد)"]] as const) {
          const o = document.createElement("option");
          o.value = v;
          o.textContent = l;
          mode.append(o);
        }
        mode.value = a.mode;
        mode.addEventListener("change", () => {
          a.mode = mode.value as FAction["mode"];
          refresh();
        });
        const confirm = document.createElement("input");
        confirm.type = "checkbox";
        confirm.checked = a.confirm;
        confirm.addEventListener("change", () => {
          a.confirm = confirm.checked;
          refresh();
        });
        const cmds = document.createElement("textarea");
        cmds.className = "text-input tf-cmds";
        cmds.dir = "ltr";
        cmds.spellcheck = false;
        cmds.rows = 5;
        cmds.value = a.commands;
        cmds.placeholder = "أمر واحد في كل سطر، مثال:\nwhoami\nGet-Date";
        cmds.addEventListener("input", () => {
          a.commands = cmds.value;
          refresh();
        });
        body.append(
          h(
            "div",
            { class: "tf-action" },
            h(
              "div",
              { class: "tf-action-head" },
              h("span", { text: `إجراء ${i + 1}` }),
              h("span", { class: "spacer" }),
              h("button", { class: "btn small", text: "↑", disabled: i === 0, onclick: () => move(i, -1) }),
              h("button", { class: "btn small", text: "↓", disabled: i === form.actions.length - 1, onclick: () => move(i, 1) }),
              h("button", { class: "btn small danger", text: "حذف", disabled: form.actions.length === 1, onclick: () => remove(i) }),
            ),
            field("عنوان الزر", titleIn, `a${i}.title`),
            field("معرّف الإجراء", idEl, `a${i}.id`, "يُقترح تلقائياً من العنوان إن كان إنجليزياً"),
            field("الصدفة", shell, `a${i}.shell`),
            field("طريقة التشغيل", mode, `a${i}.mode`),
            h("label", { class: "perm-row" }, confirm, h("span", { text: "اسأل قبل التنفيذ (تأكيد في كل مرة)" })),
            field("الأوامر (أمر في كل سطر)", cmds, `a${i}.commands`, "لا حاجة لأقواس ولا JSON ولا علامات اقتباس إضافية: اكتب الأمر كما تكتبه في الطرفية"),
          ),
        );
      });
      body.append(
        h("button", { class: "btn small primary", text: "＋ إجراء جديد", onclick: () => { form.actions.push(emptyAction()); build(); } }),
        h("div", { class: "tf-sec", text: "3) الصلاحيات" }),
      );
      const autoCb = document.createElement("input");
      autoCb.type = "checkbox";
      autoCb.checked = form.autoPerms;
      autoCb.addEventListener("change", () => (form.autoPerms = autoCb.checked));
      body.append(h("label", { class: "perm-row" }, autoCb, h("span", { text: "أضف تلقائياً الصلاحيات التي تحتاجها أوامري (موصى به)" })));
      const list = h("div", { class: "perm-list" });
      for (const [p, label] of Object.entries(PERM_LABELS)) {
        const cb = document.createElement("input");
        cb.type = "checkbox";
        cb.checked = form.permissions.includes(p);
        cb.disabled = p === "process.run";
        cb.addEventListener("change", () => {
          form.permissions = form.permissions.filter((x) => x !== p);
          if (cb.checked) form.permissions.push(p);
        });
        list.append(h("label", { class: "perm-row" }, cb, h("span", { text: `${label} (${p})` })));
      }
      body.append(list, h("div", { class: "tf-sec", text: "4) كيف ستعمل" }), preview);
      refresh();
    };

    const move = (i: number, d: number) => {
      const j = i + d;
      if (j < 0 || j >= form.actions.length) return;
      [form.actions[i], form.actions[j]] = [form.actions[j], form.actions[i]];
      build();
    };
    const remove = (i: number) => {
      form.actions.splice(i, 1);
      build();
    };

    const save = async () => {
      saveBtn.disabled = true;
      status.textContent = "جارٍ الحفظ...";
      try {
        const write = () => api.studioWrite(projectId, "manifest.json", JSON.stringify(toManifest(form), null, 2) + "\n");
        await write();
        let r = await api.studioValidate(projectId);
        const added = form.autoPerms && r.report ? r.report.undeclared : [];
        if (added.length > 0) {
          for (const p of added) if (!form.permissions.includes(p)) form.permissions.push(p);
          await write();
          r = await api.studioValidate(projectId);
        }
        const msg = [r.ok ? "✔ حُفظت الأداة وهي صالحة." : "حُفظت الأداة لكن بقيت مشاكل (ستظهر في لوحة المشاكل)."];
        if (added.length) msg.push(`أُضيفت الصلاحيات تلقائياً: ${added.join("، ")}`);
        for (const w of r.warnings) msg.push(`تنبيه: ${w}`);
        if (!r.ok) for (const e of r.errors) msg.push(`خطأ: ${e}`);
        msg.push("", r.ok ? "الخطوة التالية: «تثبيت وتجربة» في أعلى الاستوديو." : "");
        await textDialog("نتيجة الحفظ", msg.join("\n"));
        done(true);
      } catch (err) {
        status.textContent = `تعذّر الحفظ: ${String(err)}`;
        refresh();
      }
    };
    saveBtn.addEventListener("click", () => void save());

    const box = h(
      "div",
      { class: "dialog wide tf-dialog" },
      h("div", { class: "dialog-title", text: "نموذج الأداة" }),
      summary,
      body,
      status,
      h("div", { class: "dialog-actions" }, saveBtn, h("button", { class: "btn", text: "إلغاء", onclick: () => done(false) })),
    );
    handle = mount(box, () => done(false));
    build();
  });
}
