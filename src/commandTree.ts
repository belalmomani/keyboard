// شجرة الأوامر العربية: العناوين والشروح بالعربية، والأوامر المنفذة على النظام تبقى بصيغتها الأصلية.

export type ShellReq = "powershell" | "cmd" | "any";

export interface CmdItem {
  id: string; // معرّف ثابت (يُخزَّن في المفضلة والتثبيت) — لا يُغيَّر بعد النشر
  title: string;
  desc: string;
  command: string;
  shell: ShellReq;
  /** إن وُجد: يُطلب تأكيد المستخدم قبل التنفيذ ويُعرض هذا النص */
  confirm?: string;
  /** يُدرج الأمر في الطرفية دون تنفيذ (لأنه يحتاج تعديل قيم قبل التشغيل) */
  insertOnly?: boolean;
}

export interface CmdGroup {
  id: string;
  title: string;
  items: CmdItem[];
}

export interface CmdCategory {
  id: string;
  title: string;
  groups: CmdGroup[];
}

export const TREE: CmdCategory[] = [
  {
    id: "sys",
    title: "النظام",
    groups: [
      {
        id: "sys-info",
        title: "معلومات النظام",
        items: [
          { id: "sys-info-full", title: "معلومات النظام الكاملة", desc: "تفاصيل الجهاز وويندوز والذاكرة والتحديثات.", command: "systeminfo", shell: "any" },
          { id: "sys-os", title: "إصدار ويندوز", desc: "اسم النظام ورقم الإصدار والمعمارية وآخر إقلاع.", command: "Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,OSArchitecture,LastBootUpTime | Format-List", shell: "powershell" },
          { id: "sys-host", title: "اسم الجهاز", desc: "يعرض اسم الجهاز على الشبكة.", command: "hostname", shell: "any" },
          { id: "sys-user", title: "المستخدم الحالي", desc: "اسم المستخدم المسجّل دخوله.", command: "whoami", shell: "any" },
        ],
      },
      {
        id: "sys-proc",
        title: "العمليات",
        items: [
          { id: "proc-top", title: "أكثر العمليات استهلاكاً للمعالج", desc: "أعلى 15 عملية حسب وقت المعالج.", command: "Get-Process | Sort-Object CPU -Descending | Select-Object -First 15 Name,Id,CPU,WS | Format-Table -AutoSize", shell: "powershell" },
          { id: "proc-list", title: "قائمة العمليات", desc: "كل العمليات العاملة الآن.", command: "tasklist", shell: "any" },
          { id: "proc-kill", title: "إيقاف عملية بمعرّفها", desc: "يُدرج الأمر؛ أضف رقم العملية (PID) ثم اضغط Enter.", command: "Stop-Process -Id ", shell: "powershell", insertOnly: true },
        ],
      },
      {
        id: "sys-svc",
        title: "الخدمات",
        items: [
          { id: "svc-running", title: "الخدمات العاملة", desc: "الخدمات التي حالتها Running.", command: "Get-Service | Where-Object Status -eq 'Running' | Format-Table -AutoSize", shell: "powershell" },
          { id: "svc-stopped", title: "الخدمات المتوقفة", desc: "الخدمات التي حالتها Stopped.", command: "Get-Service | Where-Object Status -eq 'Stopped' | Format-Table -AutoSize", shell: "powershell" },
        ],
      },
      {
        id: "sys-files",
        title: "إدارة الملفات",
        items: [
          { id: "files-list", title: "عرض محتوى المجلد", desc: "الملفات والمجلدات في المسار الحالي.", command: "dir", shell: "any" },
          { id: "files-pwd", title: "المسار الحالي", desc: "يعرض المجلد الذي تعمل فيه الجلسة.", command: "(Get-Location).Path", shell: "powershell" },
          { id: "files-disks", title: "مساحة الأقراص", desc: "المستخدم والمتاح لكل قرص.", command: "Get-PSDrive -PSProvider FileSystem | Format-Table Name,Used,Free,Root -AutoSize", shell: "powershell" },
          { id: "files-tree", title: "شجرة المجلد", desc: "عرض هرمي للمجلدات والملفات.", command: "tree /F", shell: "any" },
        ],
      },
    ],
  },
  {
    id: "net",
    title: "الشبكة",
    groups: [
      {
        id: "net-conn",
        title: "الاتصال",
        items: [
          { id: "net-ip", title: "عناوين الشبكة", desc: "إعدادات بطاقات الشبكة وعناوين IP وDNS.", command: "ipconfig /all", shell: "any" },
          { id: "net-adapters", title: "بطاقات الشبكة", desc: "حالة كل بطاقة وسرعة الربط.", command: "Get-NetAdapter | Format-Table Name,Status,LinkSpeed -AutoSize", shell: "powershell" },
          { id: "net-flush", title: "تفريغ ذاكرة DNS", desc: "يمسح ذاكرة DNS المؤقتة؛ مفيد عند مشاكل فتح المواقع.", command: "ipconfig /flushdns", shell: "any", confirm: "سيتم مسح ذاكرة DNS المؤقتة في هذا الجهاز." },
        ],
      },
      {
        id: "net-diag",
        title: "تشخيص الشبكة",
        items: [
          { id: "net-ping", title: "اختبار الاتصال بالإنترنت", desc: "4 حزم إلى 8.8.8.8 لقياس الاتصال والتأخير.", command: "ping -n 4 8.8.8.8", shell: "any" },
          { id: "net-dns", title: "فحص DNS", desc: "يتحقق من تحويل اسم موقع إلى عنوان.", command: "nslookup example.com", shell: "any" },
          { id: "net-trace", title: "تتبع المسار", desc: "المسار الذي تسلكه الحزم حتى 8.8.8.8.", command: "tracert -d 8.8.8.8", shell: "any" },
        ],
      },
      {
        id: "net-ports",
        title: "المنافذ",
        items: [
          { id: "ports-listen", title: "المنافذ المفتوحة (استماع)", desc: "المنافذ التي تنتظر اتصالات والعملية المالكة.", command: "Get-NetTCPConnection -State Listen | Sort-Object LocalPort | Format-Table LocalAddress,LocalPort,OwningProcess -AutoSize", shell: "powershell" },
          { id: "ports-all", title: "كل اتصالات الشبكة", desc: "الاتصالات الحالية مع معرّف العملية.", command: "netstat -ano", shell: "any" },
        ],
      },
    ],
  },
  {
    id: "db",
    title: "قواعد البيانات",
    groups: [
      {
        id: "db-check",
        title: "الأدوات المثبتة",
        items: [
          { id: "db-sqlite-v", title: "فحص SQLite", desc: "يتحقق من وجود sqlite3 ويعرض إصداره.", command: "sqlite3 --version", shell: "any" },
          { id: "db-mysql-v", title: "فحص MySQL", desc: "يتحقق من وجود عميل mysql.", command: "mysql --version", shell: "any" },
          { id: "db-psql-v", title: "فحص PostgreSQL", desc: "يتحقق من وجود عميل psql.", command: "psql --version", shell: "any" },
        ],
      },
      {
        id: "db-connect",
        title: "الاتصال",
        items: [
          { id: "db-mysql-conn", title: "اتصال بـ MySQL", desc: "يُدرج الأمر؛ عدّل المستخدم ثم اضغط Enter وأدخل كلمة المرور.", command: "mysql -u root -p", shell: "any", insertOnly: true },
          { id: "db-psql-conn", title: "اتصال بـ PostgreSQL", desc: "يُدرج الأمر؛ عدّل المستخدم ثم اضغط Enter.", command: "psql -U postgres", shell: "any", insertOnly: true },
        ],
      },
      {
        id: "db-query",
        title: "الاستعلامات",
        items: [
          { id: "db-sqlite-q", title: "استعلام على ملف SQLite", desc: "عدّل اسم الملف والجدول ثم نفّذ.", command: 'sqlite3 .\\data.db "SELECT * FROM table_name LIMIT 20;"', shell: "powershell", insertOnly: true },
        ],
      },
      {
        id: "db-backup",
        title: "النسخ الاحتياطي",
        items: [
          { id: "db-mysql-dump", title: "نسخ احتياطي لـ MySQL", desc: "عدّل اسم القاعدة ثم نفّذ؛ يُنشئ ملف backup.sql.", command: "mysqldump -u root -p dbname > backup.sql", shell: "any", insertOnly: true },
          { id: "db-pg-dump", title: "نسخ احتياطي لـ PostgreSQL", desc: "عدّل اسم القاعدة ثم نفّذ؛ يُنشئ ملف backup.sql.", command: "pg_dump -U postgres dbname > backup.sql", shell: "any", insertOnly: true },
        ],
      },
    ],
  },
  {
    id: "dev",
    title: "أدوات المطور",
    groups: [
      {
        id: "dev-git",
        title: "Git",
        items: [
          { id: "git-version", title: "إصدار Git", desc: "يتحقق من تثبيت Git.", command: "git --version", shell: "any" },
          { id: "git-status", title: "حالة المستودع", desc: "الملفات المعدّلة والمجهزة في المجلد الحالي.", command: "git status", shell: "any" },
          { id: "git-log", title: "آخر 10 إيداعات", desc: "سجل مختصر لآخر الإيداعات.", command: "git log --oneline -n 10", shell: "any" },
          { id: "git-branches", title: "الفروع", desc: "كل الفروع المحلية والبعيدة.", command: "git branch -a", shell: "any" },
        ],
      },
      {
        id: "dev-docker",
        title: "Docker",
        items: [
          { id: "docker-version", title: "إصدار Docker", desc: "يتحقق من تثبيت Docker.", command: "docker --version", shell: "any" },
          { id: "docker-ps", title: "الحاويات العاملة", desc: "الحاويات التي تعمل الآن.", command: "docker ps", shell: "any" },
          { id: "docker-ps-all", title: "كل الحاويات", desc: "العاملة والمتوقفة.", command: "docker ps -a", shell: "any" },
          { id: "docker-images", title: "الصور", desc: "الصور المحمّلة محلياً.", command: "docker images", shell: "any" },
        ],
      },
      {
        id: "dev-python",
        title: "Python",
        items: [
          { id: "py-version", title: "إصدار Python", desc: "يتحقق من تثبيت Python.", command: "python --version", shell: "any" },
          { id: "pip-list", title: "الحزم المثبتة", desc: "قائمة حزم pip.", command: "pip list", shell: "any" },
        ],
      },
      {
        id: "dev-node",
        title: "Node.js",
        items: [
          { id: "node-version", title: "إصدار Node.js", desc: "يتحقق من تثبيت Node.js.", command: "node --version", shell: "any" },
          { id: "npm-version", title: "إصدار npm", desc: "يتحقق من تثبيت npm.", command: "npm --version", shell: "any" },
        ],
      },
    ],
  },
];

export interface FlatItem {
  item: CmdItem;
  path: string; // مثال: النظام › العمليات
}

export const ALL: FlatItem[] = TREE.flatMap((cat) =>
  cat.groups.flatMap((g) => g.items.map((item) => ({ item, path: `${cat.title} › ${g.title}` }))),
);

export const ITEM_BY_ID = new Map<string, FlatItem>(ALL.map((f) => [f.item.id, f]));

/** تطبيع النص العربي للبحث: إزالة التشكيل والتطويل وتوحيد الألف والتاء المربوطة والياء. */
export function norm(s: string): string {
  return s
    .toLowerCase()
    .replace(/[ً-ٰٟـ]/g, "")
    .replace(/[أإآ]/g, "ا")
    .replace(/ة/g, "ه")
    .replace(/ى/g, "ي")
    .trim();
}

/** بحث بالعربية (أو باسم الأمر الأصلي). كل كلمة في الاستعلام يجب أن تتطابق. */
export function searchItems(query: string): FlatItem[] {
  const tokens = norm(query).split(/\s+/).filter(Boolean);
  if (tokens.length === 0) return [];
  const scored: { f: FlatItem; score: number }[] = [];
  for (const f of ALL) {
    const title = norm(f.item.title);
    const rest = norm(`${f.item.desc} ${f.item.command} ${f.path}`);
    let score = 0;
    let ok = true;
    for (const t of tokens) {
      if (title.includes(t)) score += 0;
      else if (rest.includes(t)) score += 1;
      else {
        ok = false;
        break;
      }
    }
    if (ok) scored.push({ f, score });
  }
  scored.sort((a, b) => a.score - b.score);
  return scored.map((s) => s.f);
}
