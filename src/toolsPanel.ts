import { api, type InstallOutcome, type ToolInfo } from "./api";
import { runTool } from "./actions";
import { confirmDialog, textDialog } from "./dialog";
import { pasteInstallDialog } from "./pasteInstall";
import { auditDialog, formatReport, permissionsDialog, SIG_LABELS, trustedDialog } from "./securityUi";
import { fmtTime, h } from "./dom";
import { refreshTools, savePinned, state, togglePin } from "./state";
import { setStatus } from "./terminals";

export const MAX_PACKAGE = 20 * 1024 * 1024;

export function bytesToB64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let bin = "";
  const CH = 0x4000;
  for (let i = 0; i < bytes.length; i += CH) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CH));
  }
  return btoa(bin);
}

/** ما بعد التثبيت: تنبيهات، ثم مراجعة الصلاحيات المعلنة. مشتركة بين تبويب الأدوات والاستوديو. */
export async function afterInstall(out: InstallOutcome): Promise<void> {
  await refreshTools();
  if (out.warnings.length) await textDialog("تنبيهات التثبيت", out.warnings.join("\n\n"));
  if (out.tool.manifest.permissions.length > 0) {
    try {
      const info = await api.toolSecurity(out.tool.id);
      if (await permissionsDialog(out.tool, info)) await refreshTools();
    } catch (err) {
      await textDialog("تعذّر تحميل الصلاحيات", String(err));
    }
  }
  setStatus(
    out.updatedFrom
      ? `تم تحديث ${out.tool.name} من ${out.updatedFrom} إلى ${out.tool.version}`
      : `تم تثبيت ${out.tool.name} ${out.tool.version}`,
  );
}

async function installFile(file: File): Promise<void> {
  if (file.size > MAX_PACKAGE) {
    await textDialog("تعذّر تثبيت الحزمة", "حجم الحزمة أكبر من 20MB.");
    return;
  }
  try {
    await afterInstall(await api.toolInstall(bytesToB64(await file.arrayBuffer())));
  } catch (err) {
    await textDialog("تعذّر تثبيت الحزمة", String(err));
  }
}

async function showDetails(t: ToolInfo): Promise<void> {
  const m = t.manifest;
  const lines: string[] = [
    `المعرّف: ${t.id}   (الاستدعاء: Run ${t.id})`,
    `المطوّر: ${t.developer}`,
    `الإصدار: ${t.version}`,
    `المصدر: ${t.source === "builtin" ? "أداة مدمجة" : "حزمة محلية — دون عزل ديناميكي"}`,
    `التوقيع: ${SIG_LABELS[t.signature]}`,
    `الصلاحيات الممنوحة: ${t.granted.join("، ") || "—"}`,
    `الأنظمة: ${m.platforms.join("، ")}`,
    `النوع / المحرك: ${m.kind} / ${m.engine || "shell"}`,
    `الصلاحيات المعلنة: ${m.permissions.join("، ") || "—"}`,
    `الاعتماديات: ${m.dependencies.join("، ") || "—"}`,
    `SDK: ${m.sdkVersion ?? "—"}   الحد الأدنى للتطبيق: ${m.minAppVersion ?? "—"}`,
    `SHA-256: ${t.packageSha256 ?? "—"}`,
    `تاريخ التثبيت: ${fmtTime(t.installedAt)}`,
    "",
    "الإجراءات:",
  ];
  for (const a of m.actions) {
    lines.push(
      `• ${a.id} — ${a.title}  [${a.shell}]${a.mode === "insert" ? " — يُدرج الأمر دون تنفيذ" : ""}${a.confirm ? " — يسأل قبل التنفيذ" : ""}`,
    );
    for (const s of a.steps) lines.push(`    ${s}`);
  }
  try {
    const info = await api.toolSecurity(t.id);
    if (info.signature.keyId) lines.push("", `مفتاح التوقيع: ${info.signature.keyId}   الموقّع: ${info.signature.signer ?? "—"}`);
    if (info.report) lines.push("", "— تقرير الفحص الساكن —", formatReport(info.report));
  } catch {
    lines.push("", "تعذّر تحميل معلومات الأمان.");
  }
  lines.push("", "السجل (الأحدث أولاً):");
  try {
    const log = await api.toolLogRecent(t.id, 20);
    if (log.length === 0) lines.push("  لا توجد سجلات.");
    for (const r of log) lines.push(`  ${fmtTime(r.ts)}  [${r.level}]  ${r.message}`);
  } catch {
    lines.push("  تعذّر تحميل السجل.");
  }
  await textDialog(`${t.name} — تفاصيل`, lines.join("\n"));
}

async function editInStudio(t: ToolInfo): Promise<void> {
  try {
    const p = await api.studioOpenTool(t.id);
    const { openInStudio } = await import("./studio");
    await openInStudio(p.id);
  } catch (err) {
    await textDialog("تعذّر فتح الأداة في الاستوديو", String(err));
  }
}

async function editPermissions(t: ToolInfo): Promise<void> {
  try {
    const info = await api.toolSecurity(t.id);
    if (await permissionsDialog(t, info)) await refreshTools();
  } catch (err) {
    await textDialog("تعذّر تحميل الصلاحيات", String(err));
  }
}

async function rescan(t: ToolInfo): Promise<void> {
  try {
    const r = await api.toolRescan(t.id);
    await textDialog(`${t.name} — فحص جديد`, formatReport(r));
  } catch (err) {
    await textDialog("تعذّر الفحص", String(err));
  }
}

async function toggleEnabled(t: ToolInfo): Promise<void> {
  try {
    await api.toolSetEnabled(t.id, !t.enabled);
  } catch (err) {
    setStatus(`تعذّر تغيير الحالة: ${String(err)}`);
    return;
  }
  await refreshTools();
}

async function uninstall(t: ToolInfo): Promise<void> {
  const ok = await confirmDialog("إزالة الأداة", `سيتم حذف «${t.name}» وملفاتها وسجلها نهائياً.`);
  if (!ok) return;
  try {
    await api.toolUninstall(t.id);
  } catch (err) {
    await textDialog("تعذّرت الإزالة", String(err));
    return;
  }
  const key = `tool:${t.id}`;
  const i = state.pinned.indexOf(key);
  if (i >= 0) {
    state.pinned.splice(i, 1);
    void savePinned();
  }
  await refreshTools();
  setStatus(`أُزيلت الأداة ${t.name}`);
}

function card(t: ToolInfo): HTMLElement {
  const pinned = state.pinned.includes(`tool:${t.id}`);
  const builtin = t.source === "builtin";
  return h(
    "div",
    { class: "tool" + (t.enabled ? "" : " off") },
    h(
      "div",
      { class: "tool-head" },
      h("div", { class: "tool-name", text: t.name }),
      h("span", { class: "badge " + t.signature, text: SIG_LABELS[t.signature] }),
      h("span", { class: "ver", text: `v${t.version}` }),
    ),
    t.description ? h("div", { class: "desc show", text: t.description }) : null,
    h("div", { class: "row-path", text: `${t.developer} — Run ${t.id}${t.enabled ? "" : " (متوقفة)"}` }),
    h(
      "div",
      { class: "tool-actions" },
      h("button", { class: "btn small primary", text: "تشغيل", disabled: !t.enabled, onclick: () => void runTool(t) }),
      h("button", { class: "btn small", text: "تفاصيل", onclick: () => void showDetails(t) }),
      h("button", { class: "btn small", text: t.enabled ? "إيقاف" : "تفعيل", onclick: () => void toggleEnabled(t) }),
      h("button", {
        class: "btn small" + (pinned ? " active" : ""),
        text: pinned ? "إلغاء التثبيت" : "تثبيت 📌",
        onclick: () => togglePin(`tool:${t.id}`),
      }),
      builtin ? null : h("button", { class: "btn small", text: "تعديل", title: "يفتح الأداة في الاستوديو", onclick: () => void editInStudio(t) }),
      builtin ? null : h("button", { class: "btn small", text: "الصلاحيات", onclick: () => void editPermissions(t) }),
      builtin ? null : h("button", { class: "btn small", text: "فحص", onclick: () => void rescan(t) }),
      builtin ? null : h("button", { class: "btn small danger", text: "إزالة", onclick: () => void uninstall(t) }),
    ),
  );
}

export function renderToolsTab(): HTMLElement {
  const input = document.createElement("input");
  input.type = "file";
  input.accept = ".tarpkg,.zip";
  input.style.display = "none";
  input.addEventListener("change", () => {
    const f = input.files?.[0];
    input.value = "";
    if (f) void installFile(f);
  });

  const wrap = h(
    "div",
    { class: "tools" },
    h(
      "div",
      { class: "section-head" },
      h("div", { class: "section-title", text: `الأدوات المثبّتة (${state.tools.length})` }),
      h(
        "div",
        { class: "tool-actions" },
        h("button", { class: "btn small", text: "الناشرون الموثوقون", onclick: () => void trustedDialog().then(refreshTools) }),
        h("button", { class: "btn small", text: "سجل التدقيق", onclick: () => void auditDialog() }),
        h("button", { class: "btn small", text: "تثبيت من كود", onclick: () => void pasteInstallDialog() }),
        h("button", { class: "btn small primary", text: "تثبيت حزمة .tarpkg", onclick: () => input.click() }),
      ),
    ),
    input,
    h("div", {
      class: "muted-box warn",
      text: "الأدوات المحلية تعمل بصلاحياتك الكاملة داخل الطرفية — لا يوجد عزل ديناميكي بعد. التوقيع والفحص الساكن والصلاحيات تقلل المخاطر ولا تضمن الأمان. ثبّت حزماً تثق بمصدرها فقط.",
    }),
  );
  if (state.tools.length === 0) wrap.append(h("div", { class: "muted-box", text: "لا توجد أدوات مثبتة." }));
  for (const t of state.tools) wrap.append(card(t));
  return wrap;
}
