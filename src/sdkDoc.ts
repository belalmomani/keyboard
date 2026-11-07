import { PERM_LABELS } from "./securityUi";

/** توثيق SDK الإصدار 1 (يعكس ما تتحقق منه النواة فعلاً؛ المرجع الكامل docs/TARPKG.md). */
export function sdkDoc(): string {
  const perms = Object.entries(PERM_LABELS)
    .map(([k, v]) => `  ${k.padEnd(18)} ${v}`)
    .join("\n");
  return `Terminal AR SDK — الإصدار 1 (أدوات من نوع command / محرك shell)

هيكل الحزمة (.tarpkg = أرشيف zip):
  manifest.json            (إلزامي، في الجذر)
  أي ملفات إضافية          (سكربتات، توثيق...)
  signature/signature.json (اختياري؛ يُضاف بأداة التوقيع، ويُهمل عند الاستيراد)

حقول manifest.json:
  id            معرّف: أحرف إنجليزية صغيرة وأرقام وشرطة، يبدأ بحرف (يُستدعى: Run <id>)
  name          اسم العرض
  developer     اسم المطوّر
  version       semver مثل 1.0.0 (التحديث يقبل إصداراً أعلى فقط)
  description   وصف
  platforms     ["windows"]  (الأنظمة الأخرى في مراحل لاحقة)
  kind          "command"
  engine        "shell"
  sdkVersion    "1"
  minAppVersion اختياري
  dependencies  معرّفات أدوات أخرى يجب أن تكون مثبتة
  permissions   الصلاحيات المعلنة (يجب أن تتضمن process.run)
  actions       من 1 إلى 20 إجراءً

الإجراء:
  id, title, description, shell ("powershell" | "cmd" | "any"), steps: [أوامر]
  mode: "run" (الافتراضي: ينفَّذ مباشرة) | "insert" (يُدرج في الطرفية دون Enter ليعدّله المستخدم؛ أمر واحد فقط)
  confirm: true ليسأل المستخدم قبل كل تشغيل
  تُرسل الخطوات سطراً سطراً إلى جلسة طرفية حقيقية، مجلد عملها = مجلد الأداة.

الصلاحيات المعروفة:
${perms}

قواعد الأمان:
  • الفحص الساكن يستخرج الصلاحيات التي تحتاجها أوامرك؛ أي صلاحية مطلوبة وغير معلنة تُرفض عند التثبيت.
  • كل صلاحية معلنة يمنحها المستخدم بنفسه لكل أداة. لا شيء يُمنح تلقائياً.
  • الفحص تقريبي وليس ضماناً، ولا يوجد عزل ديناميكي بعد.

دورة العمل في الاستوديو: تحرير ← تحقق ← اختبار ← بناء ← تثبيت وتجربة في الطرفية.
التوقيع: python tools/sign_tarpkg.py sign in.tarpkg out.tarpkg --key my.key
`;
}

/** نص طلب جاهز لأي ذكاء اصطناعي ليكتب manifest.json مطابقاً لصيغة Terminal AR. */
export function aiPrompt(goal: string): string {
  const perms = Object.keys(PERM_LABELS).join(", ");
  const want = goal.trim() || "[اكتب هنا ما تريد أن تفعله الأداة]";
  return `اكتب لي ملف manifest.json لأداة Terminal AR على Windows بهذه القواعد:
- JSON فقط بلا أي شرح قبله أو بعده، وبلا فواصل زائدة، وكل أمر في عنصر منفصل داخل steps.
- الحقول: id (إنجليزي صغير وأرقام وشرطة - فقط، يبدأ بحرف، بلا _ ولا مسافات)، name (عربي)، developer، version مثل 1.0.0، description، platforms: ["windows"], kind: "command", engine: "shell", sdkVersion: "1", permissions (يجب أن تتضمن "process.run")، actions.
- كل action فيه: id (بنفس قاعدة id)، title (عنوان الزر بالعربية)، description، shell: "powershell"، steps: [أمر PowerShell في كل عنصر]. اختياري: "confirm": true لسؤال المستخدم قبل التشغيل.
- الصلاحيات المعروفة: ${perms}. أعلن في permissions كل ما تحتاجه أوامرك.
- لا تستخدم أوامر تحتاج صلاحيات المسؤول، ولا تحذف أو تغيّر شيئاً في النظام، ولا تنزّل ملفات.
المطلوب: أداة تقوم بـ: ${want}`;
}
