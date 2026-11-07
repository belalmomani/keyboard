import { api, type RunPlan, type ToolInfo } from "./api";
import type { CmdItem, ShellReq } from "./commandTree";
import { chooseDialog, confirmDialog, textDialog } from "./dialog";
import { permissionsDialog, SIG_LABELS } from "./securityUi";
import { emit, state } from "./state";
import { ensureSession, ensureToolSession, focusActive, setStatus, writeTo } from "./terminals";

const PERM_TAG = "[PERMISSIONS]";

export interface RunOptions {
  itemId: string | null;
  command: string;
  shell: ShellReq;
  insertOnly?: boolean;
  confirm?: string;
}

/** ينفذ الأمر الحقيقي في جلسة مناسبة: الحالية إن طابقت، وإلا تُفتح جلسة جديدة. */
export async function runCommand(o: RunOptions): Promise<void> {
  if (o.confirm) {
    const ok = await confirmDialog("تأكيد التنفيذ", o.confirm, o.command);
    if (!ok) return;
  }
  const sess = await ensureSession(o.shell);
  if (!sess) {
    setStatus("تعذّر فتح جلسة مناسبة لتنفيذ الأمر");
    return;
  }
  try {
    await writeTo(sess.id, o.command + (o.insertOnly ? "" : "\r"));
  } catch (err) {
    setStatus(`تعذّر الإرسال إلى الجلسة: ${String(err)}`);
    return;
  }
  focusActive();
  if (o.insertOnly) {
    setStatus("أُدرج الأمر في الطرفية — عدّله ثم اضغط Enter");
    return;
  }
  try {
    await api.historyAdd(o.itemId, o.command, sess.kind);
  } catch {
    /* السجل لا يمنع التنفيذ */
  }
  setStatus(`نُفّذ في ${sess.title}`);
  emit();
}

export function runItem(item: CmdItem): Promise<void> {
  return runCommand({
    itemId: item.id,
    command: item.command,
    shell: item.shell,
    insertOnly: item.insertOnly,
    confirm: item.confirm,
  });
}

// ---------------- الأدوات ----------------

async function logTool(id: string, level: "info" | "warn" | "error", msg: string): Promise<void> {
  try {
    await api.toolLogAdd(id, level, msg);
  } catch {
    /* السجل لا يمنع التنفيذ */
  }
}

/**
 * يشغّل إجراءً من أداة. الخطوات تُقرأ من manifest المخزّن في النواة.
 * النواة (plan_run) هي البوابة: تتحقق من الصلاحيات الممنوحة وتفحص الخطوات وتحدد هل يلزم تأكيد.
 * لا يوجد عزل ديناميكي بعد (انظر docs/ISOLATION.md).
 */
export async function runTool(tool: ToolInfo, actionId?: string): Promise<boolean> {
  if (!tool.enabled) {
    setStatus(`الأداة ${tool.id} متوقفة — فعّلها من تبويب الأدوات`);
    return false;
  }
  const actions = tool.manifest.actions;
  let action = actionId ? actions.find((a) => a.id === actionId) : actions.length === 1 ? actions[0] : undefined;
  if (actionId && !action) {
    setStatus(`لا يوجد إجراء «${actionId}» في الأداة ${tool.id}`);
    return false;
  }
  if (!action) {
    const pick = await chooseDialog(
      tool.name,
      actions.map((a) => ({ id: a.id, label: a.title, desc: a.description })),
    );
    if (!pick) return false;
    action = actions.find((a) => a.id === pick);
    if (!action) return false;
  }

  // القرار النهائي من النواة (صلاحيات + فحص ساكن + تأكيد)؛ الواجهة تنفذ ما يعاد فقط.
  let plan: RunPlan;
  try {
    plan = await api.toolPlanRun(tool.id, action.id);
  } catch (err) {
    const msg = String(err);
    if (msg.startsWith(PERM_TAG)) {
      const ok = await offerPermissions(tool, msg.slice(PERM_TAG.length).trim());
      if (!ok) {
        void logTool(tool.id, "warn", `رُفض تشغيل الإجراء ${action.id}: صلاحيات غير ممنوحة`);
        return false;
      }
      return runTool(tool, action.id);
    }
    await textDialog("تعذّر تشغيل الأداة", msg);
    void logTool(tool.id, "error", `رفضت النواة الإجراء ${action.id}: ${msg}`);
    return false;
  }

  if (plan.needsConfirm) {
    const ok = await confirmDialog(
      `تشغيل «${tool.name}» — ${plan.title}`,
      [`التوقيع: ${SIG_LABELS[plan.signature]}`, `الخطورة التقديرية: ${plan.risk}`, ...plan.warnings].join("\n"),
      plan.steps.join("\n"),
    );
    if (!ok) {
      void logTool(tool.id, "warn", `ألغى المستخدم تشغيل الإجراء ${action.id}`);
      return false;
    }
  }

  const sess =
    tool.source === "builtin"
      ? await ensureSession(plan.shell)
      : await ensureToolSession(tool.id, tool.name, plan.shell, plan.cwd);
  if (!sess) {
    setStatus("تعذّر فتح جلسة مناسبة لتشغيل الأداة");
    void logTool(tool.id, "error", `تعذّر فتح جلسة لتشغيل الإجراء ${action.id}`);
    return false;
  }
  const insert = plan.mode === "insert" && plan.steps.length === 1;
  try {
    // insert: يُدرج الأمر دون Enter ليعدّله المستخدم؛ run: ينفَّذ مباشرة
    await writeTo(sess.id, insert ? plan.steps[0] : plan.steps.join("\r") + "\r");
  } catch (err) {
    setStatus(`تعذّر الإرسال إلى الجلسة: ${String(err)}`);
    void logTool(tool.id, "error", `فشل إرسال الإجراء ${action.id}: ${String(err)}`);
    return false;
  }
  try {
    await api.toolRunCommit(tool.id, action.id);
  } catch {
    /* التدقيق لا يمنع التنفيذ بعد أن أُرسلت الأوامر */
  }
  focusActive();
  void logTool(tool.id, "info", `${insert ? "إدراج" : "تشغيل"} الإجراء ${action.id} في ${sess.title}`);
  setStatus(insert ? `أُدرج الأمر في الطرفية — عدّله ثم اضغط Enter (${action.title})` : `${tool.name} ← ${action.title}`);
  return true;
}

/** يعرض نافذة الصلاحيات بعد رفض النواة لنقص المنح. */
async function offerPermissions(tool: ToolInfo, detail: string): Promise<boolean> {
  let info;
  try {
    info = await api.toolSecurity(tool.id);
  } catch (err) {
    await textDialog("تعذّر تحميل الصلاحيات", String(err));
    return false;
  }
  setStatus(detail || "الأداة تحتاج صلاحيات لم تُمنح");
  return permissionsDialog(tool, info);
}

const RUN_RE = /^run\s+([a-z][a-z0-9-]*)(?:\s+([a-z][a-z0-9-]*))?$/i;

/**
 * شريط الاستدعاء الموحد: "Run <معرّف-الأداة> [إجراء]". كلمة Run ثابتة.
 * يعمل فقط في شريط Terminal AR، ولا يعترض ما يكتبه المستخدم داخل الطرفية.
 * يعيد true إذا كان النص صيغة Run صحيحة (حتى لو لم تُنفَّذ).
 */
export async function runBarCommand(text: string): Promise<boolean> {
  const m = RUN_RE.exec(text.trim());
  if (!m) {
    setStatus("الصيغة: Run <معرّف الأداة>  مثال: Run net");
    return false;
  }
  const id = m[1].toLowerCase();
  const tool = state.tools.find((t) => t.id === id);
  if (!tool) {
    setStatus(`لا توجد أداة بالمعرّف «${id}»`);
    return false;
  }
  await runTool(tool, m[2]?.toLowerCase());
  return true;
}
