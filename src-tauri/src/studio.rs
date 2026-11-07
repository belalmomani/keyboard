//! Terminal AR Studio (المرحلة 5): مشاريع الأدوات، الملفات، التحقق، البناء، الاختبار، الاستيراد.
//!
//! كل مشروع مجلد داخل بيانات التطبيق (`studio/<id>`). كل مسار قادم من الواجهة يمر عبر `safe_rel`.
//! لا تُنفَّذ هنا أي أوامر أداة: الاختبار يتحقق ويفحص ويثبّت في قاعدة معزولة مؤقتة فقط.

use crate::plugin::{self, Diag, InstallOutcome, Manifest};
use crate::security::{self, LabReport};
use crate::storage::Db;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{Cursor, Read, Write};
use std::path::{Component, Path, PathBuf};

const MAX_FILE_BYTES: u64 = 2 * 1024 * 1024;
const MAX_PACKAGE_BYTES: usize = 20 * 1024 * 1024;
const MAX_IMPORT_UNPACKED: u64 = 50 * 1024 * 1024;
const MAX_FILES: usize = 500;
const MAX_TREE: usize = 2000;
const MAX_DEPTH: usize = 12;

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProjectInfo {
    pub id: String,
    pub name: String,
    pub version: String,
    pub has_manifest: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct TreeEntry {
    pub path: String,
    pub dir: bool,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ValidateReport {
    pub ok: bool,
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
    /// المشاكل مع أماكنها (للوحة المشاكل وتمييز الأسطر في المحرر).
    pub diagnostics: Vec<Diag>,
    pub report: Option<LabReport>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BuildOutcome {
    pub file_name: String,
    pub path: String,
    /// المسار الكامل للملف على الجهاز.
    pub full_path: String,
    pub size: usize,
    pub sha256: String,
    pub file_count: usize,
    /// الحزمة بترميز base64 (للتنزيل من الواجهة).
    pub data: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct TestStep {
    pub name: String,
    pub ok: bool,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct TestReport {
    pub ok: bool,
    pub steps: Vec<TestStep>,
}

// ---------------------------------------------------------------------------
// المسارات

/// يحوّل مساراً نسبياً من الواجهة إلى مسار آمن داخل المشروع.
/// `write` = true يمنع الكتابة في المجلدات المحجوزة (`dist`, `signature`).
pub fn safe_rel(rel: &str, write: bool) -> Result<PathBuf, String> {
    let norm = rel.replace('\\', "/");
    if norm.is_empty() || norm.starts_with('/') || norm.contains(':') || norm.contains('\0') {
        return Err("مسار غير صالح".into());
    }
    let mut out = PathBuf::new();
    for c in Path::new(&norm).components() {
        match c {
            Component::Normal(p) => out.push(p),
            _ => return Err("مسار غير آمن".into()),
        }
    }
    if out.as_os_str().is_empty() {
        return Err("مسار غير صالح".into());
    }
    if write {
        let first = out
            .components()
            .next()
            .and_then(|c| c.as_os_str().to_str())
            .unwrap_or("");
        if first == "dist" || first == "signature" {
            return Err(format!("المجلد «{first}» محجوز ولا يمكن تعديله يدوياً"));
        }
    }
    Ok(out)
}

fn project_dir(root: &Path, id: &str) -> Result<PathBuf, String> {
    if !plugin::is_slug(id, 64) {
        return Err("معرّف المشروع غير صالح (أحرف إنجليزية صغيرة وأرقام وشرطة)".into());
    }
    let d = root.join(id);
    if !d.is_dir() {
        return Err("المشروع غير موجود".into());
    }
    Ok(d)
}

fn read_manifest(dir: &Path) -> Result<Manifest, String> {
    let text = fs::read_to_string(dir.join("manifest.json"))
        .map_err(|_| "لا يوجد ملف manifest.json في جذر المشروع".to_string())?;
    serde_json::from_str::<Manifest>(&text).map_err(|e| format!("manifest.json غير صالح: {e}"))
}

// ---------------------------------------------------------------------------
// المشاريع

pub fn list(root: &Path) -> Result<Vec<ProjectInfo>, String> {
    let mut out = Vec::new();
    let rd = match fs::read_dir(root) {
        Ok(r) => r,
        Err(_) => return Ok(out),
    };
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if name.starts_with('.') || !e.path().is_dir() || !plugin::is_slug(&name, 64) {
            continue;
        }
        let (title, version, has) = match read_manifest(&e.path()) {
            Ok(m) => (m.name, m.version, true),
            Err(_) => (name.clone(), String::new(), false),
        };
        out.push(ProjectInfo {
            id: name,
            name: title,
            version,
            has_manifest: has,
        });
    }
    out.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(out)
}

pub fn create(root: &Path, id: &str, name: &str) -> Result<ProjectInfo, String> {
    if !plugin::is_slug(id, 64) {
        return Err("معرّف المشروع غير صالح (أحرف إنجليزية صغيرة وأرقام وشرطة، يبدأ بحرف)".into());
    }
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 80 || name.chars().any(|c| c.is_control()) {
        return Err("اسم المشروع غير صالح".into());
    }
    let dir = root.join(id);
    if dir.exists() {
        return Err("يوجد مشروع بهذا المعرّف".into());
    }
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let manifest = serde_json::json!({
        "id": id,
        "name": name,
        "developer": "أنا",
        "version": "0.1.0",
        "description": "",
        "platforms": ["windows"],
        "kind": "command",
        "engine": "shell",
        "sdkVersion": "1",
        "permissions": ["process.run", "system.read"],
        "actions": [{
            "id": "hello",
            "title": "تحية",
            "description": "يطبع رسالة ثم اسم المستخدم.",
            "shell": "powershell",
            "steps": ["Write-Output 'Hello from Terminal AR Studio'", "whoami"]
        }]
    });
    let text = serde_json::to_string_pretty(&manifest).map_err(|e| e.to_string())?;
    fs::write(dir.join("manifest.json"), text).map_err(|e| e.to_string())?;
    fs::write(
        dir.join("README.md"),
        format!("# {name}\n\nأداة Terminal AR. عدّل manifest.json ثم اضغط «تحقق» و«بناء».\n"),
    )
    .map_err(|e| e.to_string())?;
    Ok(ProjectInfo {
        id: id.into(),
        name: name.into(),
        version: "0.1.0".into(),
        has_manifest: true,
    })
}

pub fn delete(root: &Path, id: &str) -> Result<(), String> {
    let dir = project_dir(root, id)?;
    fs::remove_dir_all(dir).map_err(|e| e.to_string())
}

/// يستورد مشروعاً من zip (مشروع خارجي أو حزمة .tarpkg). التوقيع القديم يُهمل لأن التعديل يبطله.
pub fn import_zip(root: &Path, bytes: &[u8], id: Option<&str>) -> Result<ProjectInfo, String> {
    if bytes.is_empty() || bytes.len() > MAX_PACKAGE_BYTES {
        return Err("حجم الملف غير مقبول (الحد الأقصى 20MB)".into());
    }
    let mut ar = zip::ZipArchive::new(Cursor::new(bytes))
        .map_err(|_| "الملف ليس أرشيف zip صالحاً".to_string())?;
    if ar.len() == 0 || ar.len() > MAX_FILES {
        return Err(format!("عدد الملفات غير مقبول (الحد الأقصى {MAX_FILES})"));
    }
    // المسارات الآمنة لكل مدخل
    let mut entries: Vec<(usize, PathBuf, bool)> = Vec::new();
    for i in 0..ar.len() {
        let f = ar.by_index(i).map_err(|e| e.to_string())?;
        let p = f
            .enclosed_name()
            .ok_or_else(|| format!("مسار غير آمن في الأرشيف: {}", f.name()))?;
        if f.name().contains(':') {
            return Err(format!("مسار غير آمن في الأرشيف: {}", f.name()));
        }
        if let Some(mode) = f.unix_mode() {
            if mode & 0o170000 == 0o120000 {
                continue; // روابط رمزية تُتجاهل
            }
        }
        entries.push((i, p, f.is_dir()));
    }
    // إزالة مجلد جذر واحد مشترك (zip من GitHub مثلاً)
    let files: Vec<&(usize, PathBuf, bool)> = entries.iter().filter(|e| !e.2).collect();
    let strip = !files.is_empty()
        && files.iter().all(|e| e.1.components().count() >= 2)
        && files
            .iter()
            .map(|e| e.1.components().next().map(|c| c.as_os_str().to_owned()))
            .collect::<std::collections::HashSet<_>>()
            .len()
            == 1;
    let rel_of = |p: &Path| -> PathBuf {
        if strip {
            p.components().skip(1).collect()
        } else {
            p.to_path_buf()
        }
    };
    let mut keep: Vec<(usize, PathBuf)> = Vec::new();
    for (i, p, is_dir) in &entries {
        if *is_dir {
            continue;
        }
        let rel = rel_of(p);
        let first = rel
            .components()
            .next()
            .and_then(|c| c.as_os_str().to_str())
            .unwrap_or("")
            .to_string();
        if first == "signature" || first == "dist" || first == ".git" {
            continue;
        }
        keep.push((*i, rel));
    }
    if keep.is_empty() {
        return Err("الأرشيف لا يحتوي ملفات قابلة للاستيراد".into());
    }
    // تحديد المعرّف: من manifest.json إن وُجد، وإلا من المستخدم
    let mut final_id: Option<String> = None;
    if let Some((i, _)) = keep.iter().find(|(_, r)| r == Path::new("manifest.json")) {
        let mut f = ar.by_index(*i).map_err(|e| e.to_string())?;
        let mut s = String::new();
        (&mut f)
            .take(256 * 1024)
            .read_to_string(&mut s)
            .map_err(|_| "manifest.json ليس نصاً صالحاً".to_string())?;
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&s) {
            if let Some(mid) = v.get("id").and_then(|x| x.as_str()) {
                if plugin::is_slug(mid, 64) {
                    final_id = Some(mid.to_string());
                }
            }
        }
    }
    let final_id = final_id
        .or_else(|| id.map(|s| s.to_string()))
        .ok_or_else(|| "لم أجد manifest.json بمعرّف صالح؛ أدخل معرّفاً للمشروع".to_string())?;
    if !plugin::is_slug(&final_id, 64) {
        return Err("معرّف المشروع غير صالح".into());
    }
    let dest = root.join(&final_id);
    if dest.exists() {
        return Err(format!(
            "يوجد مشروع بالمعرّف «{final_id}» — احذفه أو غيّر المعرّف"
        ));
    }
    fs::create_dir_all(root).map_err(|e| e.to_string())?;
    let tmp = root.join(format!(".import-{}", crate::storage::now()));
    let _ = fs::remove_dir_all(&tmp);
    let result = (|| -> Result<(), String> {
        let mut written: u64 = 0;
        for (i, rel) in &keep {
            let out = tmp.join(rel);
            if let Some(p) = out.parent() {
                fs::create_dir_all(p).map_err(|e| e.to_string())?;
            }
            let mut f = ar.by_index(*i).map_err(|e| e.to_string())?;
            let mut o = fs::File::create(&out).map_err(|e| e.to_string())?;
            let n = std::io::copy(&mut (&mut f).take(MAX_IMPORT_UNPACKED + 1), &mut o)
                .map_err(|e| e.to_string())?;
            written += n;
            if written > MAX_IMPORT_UNPACKED {
                return Err("حجم المحتوى بعد فك الضغط كبير جداً".into());
            }
        }
        fs::rename(&tmp, &dest).map_err(|e| e.to_string())
    })();
    if let Err(e) = result {
        let _ = fs::remove_dir_all(&tmp);
        return Err(e);
    }
    let (name, version, has) = match read_manifest(&dest) {
        Ok(m) => (m.name, m.version, true),
        Err(_) => (final_id.clone(), String::new(), false),
    };
    Ok(ProjectInfo {
        id: final_id,
        name,
        version,
        has_manifest: has,
    })
}

// ---------------------------------------------------------------------------
// الملفات

fn walk(base: &Path, dir: &Path, depth: usize, out: &mut Vec<TreeEntry>) -> Result<(), String> {
    if depth > MAX_DEPTH {
        return Ok(());
    }
    let rd = fs::read_dir(dir).map_err(|e| e.to_string())?;
    for e in rd.flatten() {
        if out.len() >= MAX_TREE {
            return Err(format!("عدد عناصر المشروع كبير جداً (الحد {MAX_TREE})"));
        }
        let md = match fs::symlink_metadata(e.path()) {
            Ok(m) => m,
            Err(_) => continue,
        };
        if md.file_type().is_symlink() {
            continue;
        }
        let rel = e
            .path()
            .strip_prefix(base)
            .map_err(|_| "مسار غير متوقع".to_string())?
            .to_string_lossy()
            .replace('\\', "/");
        if md.is_dir() {
            out.push(TreeEntry {
                path: rel,
                dir: true,
                size: 0,
            });
            walk(base, &e.path(), depth + 1, out)?;
        } else {
            out.push(TreeEntry {
                path: rel,
                dir: false,
                size: md.len(),
            });
        }
    }
    Ok(())
}

pub fn tree(root: &Path, id: &str) -> Result<Vec<TreeEntry>, String> {
    let dir = project_dir(root, id)?;
    let mut out = Vec::new();
    walk(&dir, &dir, 0, &mut out)?;
    out.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(out)
}

pub fn read_file(root: &Path, id: &str, rel: &str) -> Result<String, String> {
    let dir = project_dir(root, id)?;
    let p = dir.join(safe_rel(rel, false)?);
    let md = fs::symlink_metadata(&p).map_err(|_| "الملف غير موجود".to_string())?;
    if !md.is_file() {
        return Err("ليس ملفاً".into());
    }
    if md.len() > MAX_FILE_BYTES {
        return Err("الملف أكبر من 2MB — لا يُفتح في المحرر".into());
    }
    let bytes = fs::read(&p).map_err(|e| e.to_string())?;
    String::from_utf8(bytes).map_err(|_| "الملف ليس نصاً بترميز UTF-8".to_string())
}

pub fn write_file(root: &Path, id: &str, rel: &str, content: &str) -> Result<(), String> {
    if content.len() as u64 > MAX_FILE_BYTES {
        return Err("المحتوى أكبر من 2MB".into());
    }
    let dir = project_dir(root, id)?;
    let p = dir.join(safe_rel(rel, true)?);
    if let Ok(md) = fs::symlink_metadata(&p) {
        if !md.is_file() {
            return Err("الوجهة ليست ملفاً عادياً".into());
        }
    }
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    fs::write(&p, content).map_err(|e| e.to_string())
}

pub fn make_dir(root: &Path, id: &str, rel: &str) -> Result<(), String> {
    let dir = project_dir(root, id)?;
    fs::create_dir_all(dir.join(safe_rel(rel, true)?)).map_err(|e| e.to_string())
}

pub fn delete_path(root: &Path, id: &str, rel: &str) -> Result<(), String> {
    let dir = project_dir(root, id)?;
    let p = dir.join(safe_rel(rel, true)?);
    let md = fs::symlink_metadata(&p).map_err(|_| "العنصر غير موجود".to_string())?;
    if md.is_dir() {
        fs::remove_dir_all(&p).map_err(|e| e.to_string())
    } else {
        fs::remove_file(&p).map_err(|e| e.to_string())
    }
}

pub fn rename_path(root: &Path, id: &str, from: &str, to: &str) -> Result<(), String> {
    let dir = project_dir(root, id)?;
    let a = dir.join(safe_rel(from, true)?);
    let b = dir.join(safe_rel(to, true)?);
    if !a.exists() {
        return Err("العنصر غير موجود".into());
    }
    if b.exists() {
        return Err("الوجهة موجودة مسبقاً".into());
    }
    if let Some(p) = b.parent() {
        fs::create_dir_all(p).map_err(|e| e.to_string())?;
    }
    fs::rename(a, b).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// التحقق والبناء والاختبار

pub fn validate(root: &Path, id: &str, app_version: &str) -> Result<ValidateReport, String> {
    let dir = project_dir(root, id)?;
    match fs::read_to_string(dir.join("manifest.json")) {
        Ok(text) => Ok(validate_text(&text, Some(id), app_version)),
        Err(_) => {
            let d = Diag::error(
                "",
                "لا يوجد ملف manifest.json في جذر المشروع",
                Some("أنشئ ملفاً باسم manifest.json في جذر المشروع (الأداة تُعرَّف به)"),
            );
            Ok(report_from(vec![d], None))
        }
    }
}

/// يفحص نص manifest (من ملف مشروع أو من لصق مباشر). `project_id` للمقارنة مع id الأداة إن وُجد.
pub fn validate_text(text: &str, project_id: Option<&str>, app_version: &str) -> ValidateReport {
    let m: Manifest = match serde_json::from_str(text) {
        Ok(m) => m,
        Err(e) => {
            let mut d = Diag::error(
                "",
                format!("manifest.json غير صالح: {e}"),
                Some("افحص الفواصل بين العناصر، وإغلاق الأقواس { } [ ]، وعلامات الاقتباس \" \"، وأن كل الحقول الإلزامية موجودة"),
            );
            d.line = Some(e.line());
            d.column = Some(e.column());
            return report_from(vec![d], None);
        }
    };
    let mut diags = plugin::diagnose(&m, app_version, true);
    if project_id.is_some_and(|pid| m.id != pid) {
        let id = project_id.unwrap_or_default();
        diags.push(Diag::warning(
            "id",
            format!(
                "معرّف الأداة في manifest ({}) يختلف عن معرّف المشروع ({id})",
                m.id
            ),
            None,
        ));
    }
    if !m.dependencies.is_empty() {
        diags.push(Diag::warning(
            "dependencies",
            format!(
                "الأداة تعتمد على: {} — يجب أن تكون مثبتة عند تثبيت الحزمة",
                m.dependencies.join("، ")
            ),
            None,
        ));
    }
    let report = security::analyze(&m);
    if !report.undeclared.is_empty() {
        // حدّد الخطوات المسؤولة عن كل صلاحية غير معلنة
        let mut located = false;
        for f in &report.findings {
            if !report.undeclared.contains(&f.permission) {
                continue;
            }
            for (ai, a) in m.actions.iter().enumerate() {
                if a.id != f.action {
                    continue;
                }
                for (si, s) in a.steps.iter().enumerate() {
                    if *s == f.step {
                        located = true;
                        diags.push(Diag::error(
                            &format!("actions[{ai}].steps[{si}]"),
                            format!(
                                "هذا الأمر يحتاج الصلاحية {} ولم تُعلن (سيُرفض التثبيت): {}",
                                f.permission, f.rule
                            ),
                            Some(
                                format!("أضف \"{}\" إلى permissions، أو غيّر الأمر", f.permission)
                                    .as_str(),
                            ),
                        ));
                    }
                }
            }
        }
        if !located {
            diags.push(Diag::error(
                "permissions",
                format!(
                    "الأوامر تحتاج صلاحيات غير معلنة (سيُرفض التثبيت): {}",
                    report.undeclared.join("، ")
                ),
                Some("أضفها إلى permissions"),
            ));
        }
    }
    report_from(diags, Some(report))
}

fn report_from(diags: Vec<Diag>, report: Option<LabReport>) -> ValidateReport {
    let errors: Vec<String> = diags
        .iter()
        .filter(|d| d.severity == "error")
        .map(|d| d.message.clone())
        .collect();
    let warnings: Vec<String> = diags
        .iter()
        .filter(|d| d.severity == "warning")
        .map(|d| d.message.clone())
        .collect();
    ValidateReport {
        ok: errors.is_empty(),
        errors,
        warnings,
        diagnostics: diags,
        report,
    }
}

fn collect_files(base: &Path, dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), String> {
    for e in fs::read_dir(dir).map_err(|e| e.to_string())?.flatten() {
        let md = match fs::symlink_metadata(e.path()) {
            Ok(m) => m,
            Err(_) => continue,
        };
        if md.file_type().is_symlink() {
            continue;
        }
        let rel = e
            .path()
            .strip_prefix(base)
            .map_err(|e| e.to_string())?
            .to_path_buf();
        let first = rel
            .components()
            .next()
            .and_then(|c| c.as_os_str().to_str())
            .unwrap_or("")
            .to_string();
        if first == "dist" || first == "signature" || first == ".git" {
            continue;
        }
        if md.is_dir() {
            collect_files(base, &e.path(), out)?;
        } else {
            out.push(rel);
        }
    }
    Ok(())
}

fn build_bytes(
    root: &Path,
    id: &str,
    app_version: &str,
) -> Result<(Vec<u8>, Manifest, usize), String> {
    let v = validate(root, id, app_version)?;
    if !v.ok {
        return Err(format!("المشروع لا يجتاز التحقق:\n{}", v.errors.join("\n")));
    }
    let dir = project_dir(root, id)?;
    let m = read_manifest(&dir)?;
    let mut files = Vec::new();
    collect_files(&dir, &dir, &mut files)?;
    files.sort();
    if files.len() > MAX_FILES {
        return Err(format!("عدد الملفات أكبر من {MAX_FILES}"));
    }
    let mut buf = Cursor::new(Vec::new());
    {
        let mut zw = zip::ZipWriter::new(&mut buf);
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        for rel in &files {
            let name = rel.to_string_lossy().replace('\\', "/");
            let data = fs::read(dir.join(rel)).map_err(|e| e.to_string())?;
            zw.start_file(name, opts).map_err(|e| e.to_string())?;
            zw.write_all(&data).map_err(|e| e.to_string())?;
        }
        zw.finish().map_err(|e| e.to_string())?;
    }
    let bytes = buf.into_inner();
    if bytes.len() > MAX_PACKAGE_BYTES {
        return Err("الحزمة الناتجة أكبر من 20MB".into());
    }
    Ok((bytes, m, files.len()))
}

pub fn build(root: &Path, id: &str, app_version: &str) -> Result<BuildOutcome, String> {
    use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
    let (bytes, m, count) = build_bytes(root, id, app_version)?;
    let dir = project_dir(root, id)?;
    let dist = dir.join("dist");
    fs::create_dir_all(&dist).map_err(|e| e.to_string())?;
    let file_name = format!("{}-{}.tarpkg", m.id, m.version);
    let path = dist.join(&file_name);
    fs::write(&path, &bytes).map_err(|e| e.to_string())?;
    let sha = Sha256::digest(&bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect::<String>();
    Ok(BuildOutcome {
        file_name: file_name.clone(),
        path: format!("dist/{file_name}"),
        full_path: path.to_string_lossy().to_string(),
        size: bytes.len(),
        sha256: sha,
        file_count: count,
        data: B64.encode(&bytes),
    })
}

/// يثبّت الحزمة المبنية من المشروع في الأدوات الحقيقية (يمر بكل فحوص التثبيت).
pub fn install(
    root: &Path,
    db: &Db,
    tools_dir: &Path,
    id: &str,
    app_version: &str,
) -> Result<InstallOutcome, String> {
    let (bytes, _, _) = build_bytes(root, id, app_version)?;
    plugin::install_package(db, tools_dir, &bytes, app_version)
}

fn step(name: &str, r: Result<String, String>) -> TestStep {
    match r {
        Ok(d) => TestStep {
            name: name.into(),
            ok: true,
            detail: d,
        },
        Err(d) => TestStep {
            name: name.into(),
            ok: false,
            detail: d,
        },
    }
}

/// اختبار المشروع: تحقق ← فحص ساكن ← بناء ← تثبيت في قاعدة ومجلد مؤقتين.
/// لا يُنفَّذ أي أمر من أوامر الأداة، ولا يمس الأدوات المثبتة فعلاً.
pub fn test(root: &Path, id: &str, app_version: &str) -> Result<TestReport, String> {
    let mut steps = Vec::new();
    let v = validate(root, id, app_version)?;
    steps.push(step(
        "التحقق من manifest",
        if v.ok {
            Ok("صالح".into())
        } else {
            Err(v.errors.join("\n"))
        },
    ));
    if let Some(r) = &v.report {
        steps.push(step(
            "الفحص الساكن للصلاحيات",
            if r.undeclared.is_empty() {
                Ok(format!(
                    "خطورة تقديرية: {} — {} نتيجة (فحص تقريبي وليس ضماناً)",
                    r.risk,
                    r.findings.len()
                ))
            } else {
                Err(format!("صلاحيات غير معلنة: {}", r.undeclared.join("، ")))
            },
        ));
    }
    if !v.ok {
        return Ok(TestReport { ok: false, steps });
    }
    let built = build_bytes(root, id, app_version);
    steps.push(step(
        "بناء الحزمة",
        built
            .as_ref()
            .map(|(b, _, n)| format!("{n} ملف، {} بايت", b.len()))
            .map_err(|e| e.clone()),
    ));
    if let Ok((bytes, m, _)) = built {
        let tmp = root.join(format!(".tmp-test-{id}"));
        let _ = fs::remove_dir_all(&tmp);
        let r = (|| -> Result<String, String> {
            fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;
            let db = Db::open_in_memory()?;
            let out = plugin::install_package(&db, &tmp, &bytes, app_version)?;
            if !tmp.join(&m.id).join("manifest.json").is_file() {
                return Err("manifest.json غير موجود في مجلد الأداة بعد التثبيت".into());
            }
            Ok(format!(
                "ثُبّتت {} v{} في بيئة مؤقتة",
                out.tool.id, out.tool.version
            ))
        })();
        let _ = fs::remove_dir_all(&tmp);
        steps.push(step("تثبيت تجريبي معزول", r));
    }
    let ok = steps.iter().all(|s| s.ok);
    Ok(TestReport { ok, steps })
}

// ---------------------------------------------------------------------------
// إصلاح JSON، تثبيت من نص، فتح أداة مثبتة في الاستوديو

/// يزيل الفواصل الزائدة قبل `]` أو `}` (خارج النصوص فقط).
pub fn strip_trailing_commas(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut in_str = false;
    let mut esc = false;
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if in_str {
            out.push(c);
            if esc {
                esc = false;
            } else if c == '\\' {
                esc = true;
            } else if c == '"' {
                in_str = false;
            }
            i += 1;
            continue;
        }
        if c == '"' {
            in_str = true;
            out.push(c);
            i += 1;
            continue;
        }
        if c == ',' {
            let mut j = i + 1;
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            if j < chars.len() && (chars[j] == ']' || chars[j] == '}') {
                i += 1; // تجاهل الفاصلة الزائدة
                continue;
            }
        }
        out.push(c);
        i += 1;
    }
    out
}

/// يصلح الفواصل الزائدة ويعيد JSON مرتّباً (بترتيب المفاتيح الأصلي). يفشل إن بقي النص غير صالح.
pub fn fix_json_text(text: &str) -> Result<String, String> {
    let cleaned = strip_trailing_commas(text.trim_start_matches('\u{feff}'));
    let v: serde_json::Value = serde_json::from_str(&cleaned).map_err(|e| {
        format!(
            "لا يمكن الإصلاح التلقائي: {e} (السطر {}، العمود {})",
            e.line(),
            e.column()
        )
    })?;
    let mut out = serde_json::to_string_pretty(&v).map_err(|e| e.to_string())?;
    out.push('\n');
    Ok(out)
}

/// «إصلاح تلقائي وترتيب» لـ manifest.json في مشروع.
pub fn format_manifest(root: &Path, id: &str) -> Result<(), String> {
    let dir = project_dir(root, id)?;
    let text = fs::read_to_string(dir.join("manifest.json"))
        .map_err(|_| "لا يوجد manifest.json".to_string())?;
    let fixed = fix_json_text(&text)?;
    fs::write(dir.join("manifest.json"), fixed).map_err(|e| e.to_string())
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TextCheck {
    /// النص بعد الإصلاح والترتيب (إن أمكن)، وإلا النص الأصلي.
    pub text: String,
    pub fixed: bool,
    pub report: ValidateReport,
}

/// فحص manifest ملصوق: يحاول الإصلاح التلقائي أولاً ثم يفحص.
pub fn check_text(text: &str, app_version: &str) -> TextCheck {
    match fix_json_text(text) {
        Ok(fixed) => {
            let report = validate_text(&fixed, None, app_version);
            TextCheck {
                fixed: fixed.trim() != text.trim(),
                text: fixed,
                report,
            }
        }
        Err(_) => TextCheck {
            text: text.to_string(),
            fixed: false,
            report: validate_text(text, None, app_version),
        },
    }
}

/// يثبّت أداة من نص manifest مباشرة (يُبنى أرشيف بالملف الواحد ويمر بكل فحوص التثبيت).
pub fn install_text(
    db: &Db,
    tools_dir: &Path,
    text: &str,
    app_version: &str,
) -> Result<InstallOutcome, String> {
    let fixed = fix_json_text(text)?;
    let report = validate_text(&fixed, None, app_version);
    if !report.ok {
        return Err(format!(
            "الكود لا يجتاز الفحص:\n{}",
            report.errors.join("\n")
        ));
    }
    let mut buf = Cursor::new(Vec::new());
    {
        let mut zw = zip::ZipWriter::new(&mut buf);
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        zw.start_file("manifest.json", opts)
            .map_err(|e| e.to_string())?;
        zw.write_all(fixed.as_bytes()).map_err(|e| e.to_string())?;
        zw.finish().map_err(|e| e.to_string())?;
    }
    plugin::install_package(db, tools_dir, &buf.into_inner(), app_version)
}

fn copy_tree(src: &Path, dst: &Path, depth: usize, count: &mut usize) -> Result<(), String> {
    if depth > MAX_DEPTH {
        return Ok(());
    }
    fs::create_dir_all(dst).map_err(|e| e.to_string())?;
    for e in fs::read_dir(src).map_err(|e| e.to_string())?.flatten() {
        let md = match fs::symlink_metadata(e.path()) {
            Ok(m) => m,
            Err(_) => continue,
        };
        if md.file_type().is_symlink() {
            continue;
        }
        let name = e.file_name();
        if depth == 0 && (name == "signature" || name == "dist") {
            continue;
        }
        if md.is_dir() {
            copy_tree(&e.path(), &dst.join(&name), depth + 1, count)?;
        } else {
            *count += 1;
            if *count > MAX_FILES || md.len() > MAX_IMPORT_UNPACKED {
                return Err("الأداة كبيرة جداً لفتحها في الاستوديو".into());
            }
            fs::copy(e.path(), dst.join(&name)).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// ينسخ أداة مثبتة إلى مشروع استوديو (إن لم يوجد مشروع بمعرّفها) ليعدّلها المستخدم.
pub fn open_tool(root: &Path, tools_dir: &Path, id: &str) -> Result<ProjectInfo, String> {
    if !plugin::is_slug(id, 64) {
        return Err("معرّف غير صالح".into());
    }
    let dest = root.join(id);
    if !dest.is_dir() {
        let src = tools_dir.join(id);
        if !src.is_dir() {
            return Err("لا يمكن فتح هذه الأداة في الاستوديو (الأدوات المدمجة لا تُعدَّل)".into());
        }
        let tmp = root.join(format!(".open-{id}"));
        let _ = fs::remove_dir_all(&tmp);
        let mut count = 0usize;
        if let Err(e) = copy_tree(&src, &tmp, 0, &mut count) {
            let _ = fs::remove_dir_all(&tmp);
            return Err(e);
        }
        fs::rename(&tmp, &dest).map_err(|e| e.to_string())?;
    }
    let (name, version, has) = match read_manifest(&dest) {
        Ok(m) => (m.name, m.version, true),
        Err(_) => (id.to_string(), String::new(), false),
    };
    Ok(ProjectInfo {
        id: id.into(),
        name,
        version,
        has_manifest: has,
    })
}

// ---------------------------------------------------------------------------
// Runtime Adapters ولغات الجهاز

#[derive(Debug, Clone, Serialize)]
pub struct RuntimeInfo {
    pub id: String,
    pub name: String,
    /// "available" | "planned"
    pub status: String,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct HostTool {
    pub name: String,
    pub version: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Runtimes {
    pub adapters: Vec<RuntimeInfo>,
    pub host_tools: Vec<HostTool>,
}

fn probe(cmd: &str, args: &[&str]) -> Option<String> {
    use std::process::{Command, Stdio};
    let (tx, rx) = std::sync::mpsc::channel();
    let cmd = cmd.to_string();
    let args: Vec<String> = args.iter().map(|s| s.to_string()).collect();
    std::thread::spawn(move || {
        let mut c = Command::new(&cmd);
        c.args(&args).stdin(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        let r = c.output().ok().and_then(|o| {
            if !o.status.success() {
                return None;
            }
            let mut s = String::from_utf8_lossy(&o.stdout).to_string();
            if s.trim().is_empty() {
                s = String::from_utf8_lossy(&o.stderr).to_string();
            }
            s.lines().next().map(|l| l.trim().to_string())
        });
        let _ = tx.send(r);
    });
    rx.recv_timeout(std::time::Duration::from_secs(4))
        .ok()
        .flatten()
}

pub fn runtimes() -> Runtimes {
    let adapters = vec![
        RuntimeInfo {
            id: "shell".into(),
            name: "Shell (PowerShell / CMD)".into(),
            status: "available".into(),
            detail: "المحرك الوحيد المنفَّذ: خطوات أوامر تُرسل إلى جلسة طرفية حقيقية.".into(),
        },
        RuntimeInfo {
            id: "wasm".into(),
            name: "WebAssembly (WASI)".into(),
            status: "planned".into(),
            detail: "غير منفَّذ بعد. مرتبط بدراسة العزل (docs/ISOLATION.md).".into(),
        },
        RuntimeInfo {
            id: "python".into(),
            name: "Python adapter".into(),
            status: "planned".into(),
            detail: "غير منفَّذ بعد؛ يمكن الآن استدعاء python من خطوات shell إن كان مثبتاً.".into(),
        },
        RuntimeInfo {
            id: "node".into(),
            name: "Node.js adapter".into(),
            status: "planned".into(),
            detail: "غير منفَّذ بعد؛ يمكن الآن استدعاء node من خطوات shell إن كان مثبتاً.".into(),
        },
    ];
    let probes: [(&str, &str, &[&str]); 5] = [
        ("Node.js", "node", &["--version"]),
        ("Python", "python", &["--version"]),
        ("Git", "git", &["--version"]),
        ("Rust (rustc)", "rustc", &["--version"]),
        ("PowerShell 7", "pwsh", &["--version"]),
    ];
    let host_tools = probes
        .iter()
        .map(|(name, cmd, args)| HostTool {
            name: (*name).into(),
            version: probe(cmd, args),
        })
        .collect();
    Runtimes {
        adapters,
        host_tools,
    }
}

// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!(
            "tar-studio-{tag}-{}-{}",
            std::process::id(),
            crate::storage::now()
        ));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    fn make_zip(files: &[(&str, &str)]) -> Vec<u8> {
        let mut buf = Cursor::new(Vec::new());
        {
            let mut zw = zip::ZipWriter::new(&mut buf);
            let o = zip::write::SimpleFileOptions::default();
            for (n, c) in files {
                zw.start_file(*n, o).unwrap();
                zw.write_all(c.as_bytes()).unwrap();
            }
            zw.finish().unwrap();
        }
        buf.into_inner()
    }

    #[test]
    fn safe_rel_rules() {
        assert!(safe_rel("src/a.ps1", true).is_ok());
        assert!(safe_rel("manifest.json", true).is_ok());
        for bad in [
            "",
            "/etc/passwd",
            "../x",
            "a/../../x",
            "C:/x",
            "a\\..\\b",
            "a/./b/..",
        ] {
            assert!(safe_rel(bad, false).is_err(), "should reject {bad}");
        }
        assert!(safe_rel("dist/x.tarpkg", true).is_err());
        assert!(safe_rel("signature/signature.json", true).is_err());
        assert!(safe_rel("dist/x.tarpkg", false).is_ok());
    }

    #[test]
    fn create_list_read_write_roundtrip() {
        let root = tmp("crud");
        let p = create(&root, "demo", "تجربة").unwrap();
        assert_eq!(p.id, "demo");
        assert!(create(&root, "demo", "x").is_err());
        assert!(create(&root, "Bad_ID", "x").is_err());
        assert_eq!(list(&root).unwrap().len(), 1);
        write_file(&root, "demo", "src/run.ps1", "echo hi").unwrap();
        assert_eq!(read_file(&root, "demo", "src/run.ps1").unwrap(), "echo hi");
        assert!(write_file(&root, "demo", "dist/a", "x").is_err());
        assert!(read_file(&root, "demo", "../demo/manifest.json").is_err());
        rename_path(&root, "demo", "src/run.ps1", "src/go.ps1").unwrap();
        assert!(read_file(&root, "demo", "src/run.ps1").is_err());
        let t = tree(&root, "demo").unwrap();
        assert!(t.iter().any(|e| e.path == "src/go.ps1" && !e.dir));
        delete_path(&root, "demo", "src").unwrap();
        delete(&root, "demo").unwrap();
        assert!(list(&root).unwrap().is_empty());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn import_rejects_traversal_and_strips_top_folder() {
        let root = tmp("imp");
        let evil = make_zip(&[("../evil.txt", "x"), ("manifest.json", "{}")]);
        assert!(import_zip(&root, &evil, Some("evil")).is_err());
        assert!(!root.parent().unwrap().join("evil.txt").exists());

        let m = r#"{"id":"ext-tool","name":"x","developer":"d","version":"1.0.0","kind":"command","actions":[]}"#;
        let z = make_zip(&[
            ("repo-main/manifest.json", m),
            ("repo-main/src/a.ps1", "echo a"),
            ("repo-main/signature/signature.json", "{}"),
        ]);
        let info = import_zip(&root, &z, None).unwrap();
        assert_eq!(info.id, "ext-tool");
        assert!(read_file(&root, "ext-tool", "src/a.ps1").is_ok());
        assert!(read_file(&root, "ext-tool", "signature/signature.json").is_err());
        // استيراد ثانٍ بنفس المعرّف يُرفض
        assert!(import_zip(&root, &z, None).is_err());
        // بلا manifest وبلا معرّف
        let nz = make_zip(&[("a.txt", "x")]);
        assert!(import_zip(&root, &nz, None).is_err());
        assert!(import_zip(&root, &nz, Some("plain")).is_ok());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn validate_flags_undeclared_permissions() {
        let root = tmp("val");
        create(&root, "vv", "v").unwrap();
        let m = r#"{"id":"vv","name":"v","developer":"d","version":"1.0.0","platforms":["windows"],"kind":"command","engine":"shell","permissions":["process.run"],"actions":[{"id":"a","title":"t","shell":"powershell","steps":["Remove-Item -Recurse -Force C:\\x"]}]}"#;
        write_file(&root, "vv", "manifest.json", m).unwrap();
        let r = validate(&root, "vv", "0.5.0").unwrap();
        assert!(!r.ok);
        assert!(
            r.errors.iter().any(|e| e.contains("files.delete")),
            "{:?}",
            r.errors
        );
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn diagnostics_have_paths_and_lines() {
        let root = tmp("diag");
        create(&root, "dg", "d").unwrap();
        let bad = r#"{"id":"port_watcher","name":"x","developer":"d","version":"1.0.0","platforms":["windows"],"kind":"command","permissions":["process.run"],"actions":[{"id":"full_scan","title":"t","shell":"powershell","steps":["Get-Process"]}]}"#;
        write_file(&root, "dg", "manifest.json", bad).unwrap();
        let r = validate(&root, "dg", "0.5.0").unwrap();
        let paths: Vec<&str> = r.diagnostics.iter().map(|d| d.path.as_str()).collect();
        assert!(paths.contains(&"id"), "{paths:?}");
        assert!(paths.contains(&"actions[0].id"), "{paths:?}");
        assert!(paths.contains(&"actions[0].steps[0]"), "{paths:?}");
        assert!(r
            .diagnostics
            .iter()
            .any(|d| d.path == "id" && d.hint.is_some()));

        write_file(
            &root,
            "dg",
            "manifest.json",
            "{\n \"id\": \"a\",\n \"name\": }\n",
        )
        .unwrap();
        let r2 = validate(&root, "dg", "0.5.0").unwrap();
        assert!(!r2.ok);
        assert_eq!(r2.diagnostics[0].line, Some(3));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn insert_mode_requires_single_step() {
        let root = tmp("mode");
        create(&root, "md-t", "m").unwrap();
        let m = r#"{"id":"md-t","name":"m","developer":"d","version":"1.0.0","platforms":["windows"],"kind":"command","permissions":["process.run"],"actions":[{"id":"a","title":"t","shell":"powershell","mode":"insert","steps":["echo 1","echo 2"]},{"id":"b","title":"t","shell":"powershell","mode":"weird","steps":["echo 1"]}]}"#;
        write_file(&root, "md-t", "manifest.json", m).unwrap();
        let r = validate(&root, "md-t", "0.5.1").unwrap();
        let paths: Vec<&str> = r.diagnostics.iter().map(|d| d.path.as_str()).collect();
        assert!(paths.contains(&"actions[0].steps"), "{paths:?}");
        assert!(paths.contains(&"actions[1].mode"), "{paths:?}");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn trailing_commas_are_fixed_outside_strings_only() {
        let t = "{\"a\": [1, 2, ], \"b\": \"x,]\", \"c\": {\"d\": 1,},}";
        let f = fix_json_text(t).unwrap();
        let v: serde_json::Value = serde_json::from_str(&f).unwrap();
        assert_eq!(v["b"], "x,]");
        assert_eq!(v["a"].as_array().unwrap().len(), 2);
        assert!(fix_json_text("{\"a\": }").is_err());
        // ترتيب المفاتيح محفوظ
        let k = fix_json_text("{\"z\":1,\"a\":2,}").unwrap();
        assert!(k.find("\"z\"").unwrap() < k.find("\"a\"").unwrap(), "{k}");
    }

    #[test]
    fn check_text_reports_fixed_and_errors() {
        let m = r#"{"id":"port_watcher","name":"x","developer":"d","version":"1.0.0","platforms":["windows"],"kind":"command","permissions":["process.run",],"actions":[{"id":"a","title":"t","shell":"powershell","steps":["echo 1",],},],}"#;
        let r = check_text(m, "0.5.4");
        assert!(r.fixed);
        assert!(r.report.diagnostics.iter().any(|d| d.path == "id"));
        let broken = check_text("{ \"id\": ", "0.5.4");
        assert!(!broken.report.ok && !broken.fixed);
    }

    #[test]
    fn open_tool_copies_installed_tool_once() {
        let root = tmp("open");
        let tools = tmp("open-tools");
        assert!(open_tool(&root, &tools, "nope").is_err());
        fs::create_dir_all(tools.join("tt").join("signature")).unwrap();
        fs::write(tools.join("tt").join("manifest.json"), "{}").unwrap();
        fs::write(
            tools.join("tt").join("signature").join("signature.json"),
            "{}",
        )
        .unwrap();
        let p = open_tool(&root, &tools, "tt").unwrap();
        assert_eq!(p.id, "tt");
        assert!(root.join("tt").join("manifest.json").is_file());
        assert!(!root.join("tt").join("signature").exists());
        fs::write(root.join("tt").join("manifest.json"), "{\"edited\":1}").unwrap();
        open_tool(&root, &tools, "tt").unwrap();
        assert!(fs::read_to_string(root.join("tt").join("manifest.json"))
            .unwrap()
            .contains("edited"));
        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_dir_all(&tools);
    }

    #[cfg(windows)]
    #[test]
    fn build_test_and_install_template() {
        let root = tmp("build");
        create(&root, "bt", "bt").unwrap();
        let v = validate(&root, "bt", "0.5.0").unwrap();
        assert!(v.ok, "{:?}", v.errors);
        let b = build(&root, "bt", "0.5.0").unwrap();
        assert_eq!(b.file_name, "bt-0.1.0.tarpkg");
        assert!(root.join("bt").join("dist").join(&b.file_name).is_file());
        // dist لا يدخل الحزمة التالية
        let b2 = build(&root, "bt", "0.5.0").unwrap();
        assert_eq!(b.file_count, b2.file_count);
        let t = test(&root, "bt", "0.5.0").unwrap();
        assert!(t.ok, "{:?}", t.steps);
        assert!(!root.join(".tmp-test-bt").exists());
        let _ = fs::remove_dir_all(&root);
    }
}
