# Terminal AR

منصة طرفية بواجهة عربية. المرحلة الحالية: **المرحلة 1 — Windows Core Prototype**.

## كيف تجرّب النسخة (من الهاتف)
1. ارفع محتويات هذا المجلد كما هي إلى مستودع GitHub (الفرع `main`).
2. افتح تبويب **Actions** ← **Windows Build** (يبدأ تلقائياً عند الرفع، أو اضغط **Run workflow**).
3. بعد انتهائه افتح التشغيلة: ستجد في الأعلى ملخص نتائج الاختبارات، وفي الأسفل **Artifacts** باسم
   `terminal-ar-<الإصدار>-build-<رقم البناء>-windows`.
4. داخل الحزمة: المثبّت `*-setup.exe`، ونسخة `terminal-ar-portable.exe`، و`test-results.txt`،
   وملفا القفل `Cargo.lock` و`package-lock.json` (أضفهما للمستودع بعد أول بناء).

## البنية
- `src-tauri/src/terminal.rs` — Terminal Engine (جلسات حقيقية عبر ConPTY) مع الاختبارات.
- `src-tauri/src/lib.rs` — أوامر Tauri التي تربط الواجهة بالنواة.
- `src/` — الواجهة (TypeScript + xterm.js).
- `.github/workflows/windows.yml` — البناء والاختبار.
- `docs/PHASE0.md` — تقرير المرحلة 0.

## الإصدار
كل بناء يحمل رقم `github.run_number` (يزيد تلقائياً) ويظهر في أسفل التطبيق.
معرّف التطبيق `com.terminalar.app` ثابت ولا يُغيَّر.

## ملاحظات معروفة
- النسخة غير موقعة، فقد يعرض Windows تحذير SmartScreen.
- xterm.js لا يدعم تشكيل الحروف العربية ولا الاتجاه من اليمين لليسار داخل الطرفية؛ يتناول التقرير ذلك.
