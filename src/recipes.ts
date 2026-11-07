/**
 * وصفات الإنشاء الموجّه: أمثلة جاهزة لما يريده المستخدم، تُنتج أوامر PowerShell حقيقية.
 * لا يوجد ذكاء اصطناعي هنا ولا فهم لنص حر: الوصفة قائمة ثابتة، ويُسأل المستخدم فقط عن مُدخلاتها الناقصة.
 */

export type OutputMode = "raw" | "table" | "list" | "file";

export interface Param {
  key: string;
  label: string;
  placeholder: string;
  default?: string;
  /** يعيد رسالة خطأ أو null */
  check: (v: string) => string | null;
}

export interface Recipe {
  id: string;
  title: string;
  description: string;
  params: Param[];
  allowed: OutputMode[];
  /** يبني الخطوات. الخطوة الأخيرة هي التي يُضاف لها تنسيق الخرج. */
  build: (p: Record<string, string>) => string[];
}

export const OUTPUT_LABELS: Record<OutputMode, string> = {
  raw: "كما يخرج من الأمر (في الطرفية)",
  table: "جدول مرتب في الطرفية",
  list: "قائمة مفصلة (سطر لكل حقل) في الطرفية",
  file: "في الطرفية + حفظ نسخة في ملف result.txt داخل مجلد الأداة",
};

const NO_QUOTE = (v: string): string | null =>
  /['\r\n\0]/.test(v) ? "لا يُسمح بعلامة ' ولا بأسطر جديدة" : null;

const NAME = (v: string): string | null =>
  /^[A-Za-z0-9._-]{1,64}$/.test(v) ? null : "حروف إنجليزية وأرقام ونقطة وشرطة فقط (مثال: java)";

const HOST = (v: string): string | null =>
  /^[A-Za-z0-9.-]{1,253}$/.test(v) ? null : "اسم موقع أو عنوان فقط (مثال: google.com أو 8.8.8.8)";

const COUNT = (v: string): string | null => (/^\d{1,3}$/.test(v) && Number(v) >= 1 ? null : "رقم من 1 إلى 999");

export const RECIPES: Recipe[] = [
  {
    id: "check-program",
    title: "هل برنامج معيّن مثبت؟",
    description: "يتحقق من وجود برنامج (مثل java أو node أو git) ويعرض مساره.",
    params: [{ key: "program", label: "اسم أمر البرنامج", placeholder: "java", check: NAME }],
    allowed: ["raw"],
    build: (p) => [
      `if (Get-Command ${p.program} -ErrorAction SilentlyContinue) { Write-Output '${p.program} مثبت:'; Get-Command ${p.program} | Select-Object -ExpandProperty Source } else { Write-Output '${p.program} غير مثبت على هذا الجهاز' }`,
    ],
  },
  {
    id: "open-ports",
    title: "المنافذ المفتوحة والبرامج المرتبطة بها",
    description: "يعرض المنافذ التي تستمع للاتصالات واسم البرنامج صاحب كل منفذ.",
    params: [],
    allowed: ["raw", "table", "list", "file"],
    build: () => [
      "Get-NetTCPConnection -State Listen | Select-Object LocalAddress,LocalPort,@{n='Process';e={(Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName}}",
    ],
  },
  {
    id: "system-info",
    title: "معلومات النظام",
    description: "اسم نظام التشغيل وإصداره ووقت آخر تشغيل.",
    params: [],
    allowed: ["raw", "table", "list", "file"],
    build: () => ["Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,OSArchitecture,LastBootUpTime"],
  },
  {
    id: "disk-space",
    title: "مساحة الأقراص",
    description: "المستخدم والمتاح (بالجيجابايت) لكل قرص.",
    params: [],
    allowed: ["raw", "table", "list", "file"],
    build: () => [
      "Get-PSDrive -PSProvider FileSystem | Select-Object Name,@{n='UsedGB';e={[math]::Round($_.Used/1GB,1)}},@{n='FreeGB';e={[math]::Round($_.Free/1GB,1)}}",
    ],
  },
  {
    id: "top-processes",
    title: "أكثر البرامج استهلاكاً للمعالج",
    description: "قائمة البرامج الأعلى استهلاكاً مع الذاكرة المستخدمة.",
    params: [{ key: "count", label: "عدد البرامج المعروضة", placeholder: "10", default: "10", check: COUNT }],
    allowed: ["raw", "table", "list", "file"],
    build: (p) => [
      `Get-Process | Sort-Object CPU -Descending | Select-Object -First ${Number(p.count)} Name,Id,CPU,@{n='MB';e={[math]::Round($_.WorkingSet64/1MB)}}`,
    ],
  },
  {
    id: "network-info",
    title: "معلومات الشبكة",
    description: "عناوين الشبكة والبطاقات (ipconfig /all).",
    params: [],
    allowed: ["raw", "file"],
    build: () => ["ipconfig /all"],
  },
  {
    id: "ping-host",
    title: "اختبار الاتصال بموقع",
    description: "يرسل 4 طلبات ping ويعرض زمن الاستجابة.",
    params: [{ key: "host", label: "الموقع أو العنوان", placeholder: "google.com", check: HOST }],
    allowed: ["raw", "file"],
    build: (p) => [`ping -n 4 ${p.host}`],
  },
  {
    id: "list-folder",
    title: "عرض ملفات مجلد",
    description: "أسماء الملفات وأحجامها وتاريخ آخر تعديل.",
    params: [{ key: "path", label: "مسار المجلد", placeholder: "C:\\Users", check: (v) => (v.trim() ? NO_QUOTE(v) : "المسار مطلوب") }],
    allowed: ["raw", "table", "list", "file"],
    build: (p) => [`Get-ChildItem -Path '${p.path}' | Select-Object Name,Length,LastWriteTime`],
  },
  {
    id: "custom-command",
    title: "أمر أكتبه بنفسي",
    description: "أمر واحد من عندك تشغّله بزر.",
    params: [{ key: "command", label: "الأمر", placeholder: "whoami", check: (v) => (v.trim() && !/[\r\n\0]/.test(v) ? null : "اكتب أمراً واحداً في سطر واحد") }],
    allowed: ["raw", "file"],
    build: (p) => [p.command.trim()],
  },
];

/** يضيف تنسيق الخرج إلى الخطوة الأخيرة. */
export function applyOutput(steps: string[], mode: OutputMode): string[] {
  if (mode === "raw" || steps.length === 0) return steps;
  const out = steps.slice();
  const i = out.length - 1;
  if (mode === "table") out[i] += " | Format-Table -AutoSize";
  else if (mode === "list") out[i] += " | Format-List";
  else out[i] += " | Out-String -Width 200 | Tee-Object -FilePath result.txt";
  return out;
}

export function paramErrors(r: Recipe, values: Record<string, string>): Record<string, string> {
  const e: Record<string, string> = {};
  for (const p of r.params) {
    const msg = p.check((values[p.key] ?? "").trim());
    if (msg) e[p.key] = msg;
  }
  return e;
}

export function generate(r: Recipe, values: Record<string, string>, mode: OutputMode): string[] {
  const clean: Record<string, string> = {};
  for (const p of r.params) clean[p.key] = (values[p.key] ?? "").trim();
  return applyOutput(r.build(clean), mode);
}
