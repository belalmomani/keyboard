//! Plugin Engine — الأدوات كحزم .tarpkg: التحقق من manifest، التثبيت، التحديث، الإزالة.
//!
//! ملاحظة أمنية مهمة (المرحلة 3): هذا المحرك يتحقق من صحة البنية وسلامة المسارات فقط.
//! لا يوجد بعد التحقق من التوقيع ولا عزل للتنفيذ ولا فرض للصلاحيات — كلها في المرحلة 4.
//! لذلك تُعامل الحزم المحلية على أنها غير موثوقة، وتُعرض أوامرها للمستخدم قبل كل تشغيل.

use crate::security;
use crate::storage::{now, Db, ToolRow};
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{Cursor, Read};
use std::path::Path;

const MAX_PACKAGE_BYTES: usize = 20 * 1024 * 1024;
const MAX_MANIFEST_BYTES: u64 = 256 * 1024;
const MAX_FILES: usize = 500;
const MAX_UNPACKED: u64 = 100 * 1024 * 1024;

pub const KNOWN_PERMISSIONS: [&str; 9] = [
    "system.read",
    "files.read",
    "files.write",
    "files.delete",
    "network",
    "process.run",
    "services.manage",
    "system.sensitive",
    "elevated",
];

fn default_mode() -> String {
    "run".into()
}

fn default_shell() -> String {
    "any".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Action {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
    /// "powershell" | "cmd" | "any"
    #[serde(default = "default_shell")]
    pub shell: String,
    pub steps: Vec<String>,
    /// "run" = ينفَّذ مباشرة (الافتراضي) | "insert" = يُدرج في الطرفية دون Enter ليعدّله المستخدم (خطوة واحدة فقط)
    #[serde(default = "default_mode")]
    pub mode: String,
    /// يطلب تأكيد المستخدم قبل كل تشغيل
    #[serde(default)]
    pub confirm: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub id: String,
    pub name: String,
    pub developer: String,
    pub version: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub platforms: Vec<String>,
    pub kind: String,
    #[serde(default)]
    pub engine: String,
    /// معرّفات أدوات أخرى مطلوبة
    #[serde(default)]
    pub dependencies: Vec<String>,
    #[serde(default)]
    pub sdk_version: Option<String>,
    #[serde(default)]
    pub min_app_version: Option<String>,
    #[serde(default)]
    pub permissions: Vec<String>,
    /// محجوز للمرحلة 4 (التوقيعات)
    #[serde(default)]
    pub signature: Option<serde_json::Value>,
    #[serde(default)]
    pub actions: Vec<Action>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolInfo {
    pub id: String,
    pub name: String,
    pub developer: String,
    pub version: String,
    pub description: String,
    pub source: String,
    pub enabled: bool,
    pub installed_at: i64,
    pub package_sha256: Option<String>,
    pub manifest: Manifest,
    /// "unsigned" | "untrusted" | "trusted" | "builtin" (تُملأ عبر security::hydrate)
    pub signature: String,
    /// الصلاحيات الممنوحة فعلاً (تُملأ عبر security::hydrate)
    pub granted: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallOutcome {
    pub tool: ToolInfo,
    pub updated_from: Option<String>,
    pub warnings: Vec<String>,
}

fn current_platform() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

pub(crate) fn is_slug(s: &str, max: usize) -> bool {
    let b = s.as_bytes();
    !b.is_empty()
        && b.len() <= max
        && b[0].is_ascii_lowercase()
        && b.iter()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
        && !s.ends_with('-')
}

fn has_control_chars(s: &str) -> bool {
    s.chars().any(|c| c.is_control() && c != '\t')
}

pub fn validate(m: &Manifest, app_version: &str) -> Result<(), String> {
    validate_inner(m, app_version, true)
}

/// مشكلة في manifest: المكان (path مثل `actions[0].steps[2]`) والرسالة وتلميح للحل.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Diag {
    /// "error" | "warning"
    pub severity: String,
    pub path: String,
    pub message: String,
    pub hint: Option<String>,
    pub line: Option<usize>,
    pub column: Option<usize>,
}

impl Diag {
    pub fn error(path: &str, message: impl Into<String>, hint: Option<&str>) -> Self {
        Diag {
            severity: "error".into(),
            path: path.into(),
            message: message.into(),
            hint: hint.map(|h| h.to_string()),
            line: None,
            column: None,
        }
    }
    pub fn warning(path: &str, message: impl Into<String>, hint: Option<&str>) -> Self {
        Diag {
            severity: "warning".into(),
            ..Self::error(path, message, hint)
        }
    }
}

const SLUG_HINT: &str =
    "استخدم أحرفاً إنجليزية صغيرة وأرقاماً وشرطة عادية - فقط، وابدأ بحرف (مثال: port-watcher). الشرطة السفلية _ والحروف الكبيرة والمسافات غير مقبولة.";

fn validate_inner(m: &Manifest, app_version: &str, check_platform: bool) -> Result<(), String> {
    let e = diagnose(m, app_version, check_platform);
    if e.is_empty() {
        Ok(())
    } else {
        Err(e
            .iter()
            .map(|d| d.message.as_str())
            .collect::<Vec<_>>()
            .join("؛ "))
    }
}

/// يفحص manifest ويعيد كل المشاكل مع أماكنها (للاستوديو والتثبيت).
pub fn diagnose(m: &Manifest, app_version: &str, check_platform: bool) -> Vec<Diag> {
    let mut e: Vec<Diag> = Vec::new();

    if m.id.len() < 2 || !is_slug(&m.id, 32) {
        e.push(Diag::error(
            "id",
            "المعرّف (id) يجب أن يكون 2-32 حرفاً: أحرف إنجليزية صغيرة وأرقام وشرطة ويبدأ بحرف",
            Some(SLUG_HINT),
        ));
    }
    if m.name.trim().is_empty() || m.name.chars().count() > 60 {
        e.push(Diag::error(
            "name",
            "اسم الأداة (name) مطلوب وبحد أقصى 60 حرفاً",
            None,
        ));
    }
    if m.developer.trim().is_empty() || m.developer.chars().count() > 60 {
        e.push(Diag::error(
            "developer",
            "اسم المطوّر (developer) مطلوب وبحد أقصى 60 حرفاً",
            None,
        ));
    }
    if m.description.chars().count() > 500 {
        e.push(Diag::error(
            "description",
            "الوصف (description) أطول من 500 حرف",
            None,
        ));
    }
    if Version::parse(&m.version).is_err() {
        e.push(Diag::error(
            "version",
            format!(
                "الإصدار '{}' ليس بصيغة Semantic Versioning (مثل 1.2.0)",
                m.version
            ),
            Some("اكتبه ثلاثة أرقام تفصل بينها نقطة، مثل 1.0.0"),
        ));
    }

    if m.platforms.is_empty() {
        e.push(Diag::error(
            "platforms",
            "حدّد الأنظمة المدعومة (platforms)",
            Some("مثال: \"platforms\": [\"windows\"]"),
        ));
    } else if check_platform && !m.platforms.iter().any(|p| p == current_platform()) {
        e.push(Diag::error(
            "platforms",
            format!("الأداة لا تدعم نظام {}", current_platform()),
            None,
        ));
    }

    if m.kind != "command" {
        e.push(Diag::error(
            "kind",
            format!(
                "نوع الأداة '{}' غير مدعوم بعد (المدعوم حالياً: command)",
                m.kind
            ),
            Some("اكتب \"kind\": \"command\""),
        ));
    }
    if !m.engine.is_empty() && m.engine != "shell" {
        e.push(Diag::error(
            "engine",
            format!(
                "محرك التشغيل '{}' غير مدعوم بعد (المدعوم حالياً: shell)",
                m.engine
            ),
            Some("اكتب \"engine\": \"shell\""),
        ));
    }

    if let Some(min) = &m.min_app_version {
        match (Version::parse(min), Version::parse(app_version)) {
            (Ok(min_v), Ok(app_v)) => {
                if app_v < min_v {
                    e.push(Diag::error(
                        "minAppVersion",
                        format!(
                            "الأداة تتطلب Terminal AR بإصدار {min} أو أحدث (الحالي {app_version})"
                        ),
                        None,
                    ));
                }
            }
            (Err(_), _) => e.push(Diag::error(
                "minAppVersion",
                format!("minAppVersion '{min}' غير صالح"),
                Some("اكتبه مثل 0.3.0"),
            )),
            _ => {}
        }
    }

    for (i, d) in m.dependencies.iter().enumerate() {
        let path = format!("dependencies[{i}]");
        if !is_slug(d, 32) {
            e.push(Diag::error(
                &path,
                format!("اعتمادية غير صالحة: '{d}'"),
                Some(SLUG_HINT),
            ));
        } else if *d == m.id {
            e.push(Diag::error(
                &path,
                "الأداة لا يمكن أن تعتمد على نفسها",
                Some("الاعتمادية = أداة أخرى يجب تثبيتها قبل أداتك. احذف هذا السطر إن لم تكن تحتاج أداة أخرى."),
            ));
        }
    }

    for (i, p) in m.permissions.iter().enumerate() {
        if !KNOWN_PERMISSIONS.contains(&p.as_str()) {
            e.push(Diag::error(
                &format!("permissions[{i}]"),
                format!("صلاحية غير معروفة: '{p}'"),
                Some(&format!(
                    "الصلاحيات المعروفة: {}",
                    KNOWN_PERMISSIONS.join("، ")
                )),
            ));
        }
    }
    if !m.permissions.iter().any(|p| p == "process.run") {
        e.push(Diag::error(
            "permissions",
            "أدوات النوع command تنفّذ أوامر، لذا يجب أن تعلن الصلاحية process.run",
            Some("أضف \"process.run\" إلى قائمة permissions"),
        ));
    }

    if m.actions.is_empty() || m.actions.len() > 20 {
        e.push(Diag::error(
            "actions",
            "يجب أن تحتوي الأداة من 1 إلى 20 إجراءً (actions)",
            None,
        ));
    }
    let mut seen: Vec<&str> = Vec::new();
    for (ai, a) in m.actions.iter().enumerate() {
        let base = format!("actions[{ai}]");
        if !is_slug(&a.id, 32) {
            e.push(Diag::error(
                &format!("{base}.id"),
                format!("معرّف الإجراء '{}' غير صالح", a.id),
                Some(SLUG_HINT),
            ));
        } else if seen.contains(&a.id.as_str()) {
            e.push(Diag::error(
                &format!("{base}.id"),
                format!("معرّف الإجراء '{}' مكرر", a.id),
                Some("لكل إجراء معرّف مختلف"),
            ));
        }
        seen.push(a.id.as_str());
        if a.title.trim().is_empty() || a.title.chars().count() > 80 {
            e.push(Diag::error(
                &format!("{base}.title"),
                format!("عنوان الإجراء '{}' مطلوب وبحد أقصى 80 حرفاً", a.id),
                None,
            ));
        }
        if !["powershell", "cmd", "any"].contains(&a.shell.as_str()) {
            e.push(Diag::error(
                &format!("{base}.shell"),
                format!(
                    "الإجراء '{}': shell يجب أن يكون powershell أو cmd أو any",
                    a.id
                ),
                None,
            ));
        }
        if a.mode != "run" && a.mode != "insert" {
            e.push(Diag::error(
                &format!("{base}.mode"),
                format!("الإجراء '{}': mode يجب أن يكون run أو insert", a.id),
                Some("run = ينفَّذ مباشرة، insert = يُدرج في الطرفية لتعدّله ثم تضغط Enter"),
            ));
        } else if a.mode == "insert" && a.steps.len() != 1 {
            e.push(Diag::error(
                &format!("{base}.steps"),
                format!("الإجراء '{}': وضع الإدراج (insert) يقبل أمراً واحداً فقط", a.id),
                Some("اترك أمراً واحداً، أو اجعل الإجراء ينفَّذ مباشرة (run)"),
            ));
        }
        if a.steps.is_empty() || a.steps.len() > 30 {
            e.push(Diag::error(
                &format!("{base}.steps"),
                format!("الإجراء '{}': من 1 إلى 30 خطوة", a.id),
                Some("قسّم الإجراء الكبير إلى إجراءات أصغر"),
            ));
        }
        for (si, s) in a.steps.iter().enumerate() {
            let sp = format!("{base}.steps[{si}]");
            if s.trim().is_empty() || s.chars().count() > 2000 {
                e.push(Diag::error(
                    &sp,
                    format!("الإجراء '{}': كل خطوة غير فارغة وبحد أقصى 2000 حرف", a.id),
                    None,
                ));
            } else if has_control_chars(s) {
                e.push(Diag::error(
                    &sp,
                    format!(
                        "الإجراء '{}': الخطوة يجب أن تكون سطراً واحداً بلا محارف تحكم",
                        a.id
                    ),
                    Some("كل أمر في عنصر منفصل داخل steps"),
                ));
            }
        }
    }
    e
}

pub fn to_info(row: ToolRow) -> Result<ToolInfo, String> {
    let manifest: Manifest =
        serde_json::from_str(&row.manifest).map_err(|e| format!("manifest مخزّن تالف: {e}"))?;
    Ok(ToolInfo {
        id: row.id,
        name: row.name,
        developer: row.developer,
        version: row.version,
        description: row.description,
        source: row.source,
        enabled: row.enabled,
        installed_at: row.installed_at,
        package_sha256: row.package_sha256,
        manifest,
        signature: "unsigned".into(),
        granted: Vec::new(),
    })
}

pub fn list(db: &Db) -> Result<Vec<ToolInfo>, String> {
    Ok(db
        .tools_list()?
        .into_iter()
        .filter_map(|r| to_info(r).ok())
        .map(|t| security::hydrate(db, t))
        .collect())
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    format!("{:x}", h.finalize())
}

fn extract_all(archive: &mut zip::ZipArchive<Cursor<&[u8]>>, dest: &Path) -> Result<(), String> {
    let mut written: u64 = 0;
    for i in 0..archive.len() {
        let mut f = archive.by_index(i).map_err(|e| e.to_string())?;
        let rel = f
            .enclosed_name()
            .ok_or_else(|| format!("مسار غير آمن في الحزمة: {}", f.name()))?;
        let out = dest.join(&rel);
        if f.is_dir() {
            fs::create_dir_all(&out).map_err(|e| e.to_string())?;
            continue;
        }
        if let Some(p) = out.parent() {
            fs::create_dir_all(p).map_err(|e| e.to_string())?;
        }
        let mut o = fs::File::create(&out).map_err(|e| e.to_string())?;
        let n = std::io::copy(&mut (&mut f).take(MAX_UNPACKED + 1), &mut o)
            .map_err(|e| e.to_string())?;
        written += n;
        if written > MAX_UNPACKED {
            return Err("حجم محتوى الحزمة بعد فك الضغط كبير جداً".into());
        }
    }
    Ok(())
}

/// يثبّت (أو يحدّث) أداة من بايتات حزمة .tarpkg.
pub fn install_package(
    db: &Db,
    tools_dir: &Path,
    bytes: &[u8],
    app_version: &str,
) -> Result<InstallOutcome, String> {
    if bytes.is_empty() || bytes.len() > MAX_PACKAGE_BYTES {
        return Err("حجم الحزمة غير مقبول (الحد الأقصى 20MB)".into());
    }
    let sha = sha256_hex(bytes);

    let mut archive = zip::ZipArchive::new(Cursor::new(bytes))
        .map_err(|_| "الملف ليس حزمة .tarpkg صالحة (ليس أرشيف zip)".to_string())?;

    let n = archive.len();
    if n == 0 || n > MAX_FILES {
        return Err(format!(
            "عدد الملفات في الحزمة غير مقبول (الحد الأقصى {MAX_FILES})"
        ));
    }
    let mut total: u64 = 0;
    let mut seen = std::collections::HashSet::new();
    for i in 0..n {
        let f = archive.by_index(i).map_err(|e| e.to_string())?;
        if f.enclosed_name().is_none() || f.name().contains(':') {
            return Err(format!("مسار غير آمن في الحزمة: {}", f.name()));
        }
        if !seen.insert(f.name().replace('\\', "/")) {
            return Err(format!("اسم ملف مكرر في الحزمة: {}", f.name()));
        }
        if let Some(mode) = f.unix_mode() {
            if mode & 0o170000 == 0o120000 {
                return Err("الحزمة تحتوي رابطاً رمزياً وهو غير مسموح".into());
            }
        }
        total = total.saturating_add(f.size());
    }
    if total > MAX_UNPACKED {
        return Err("حجم محتوى الحزمة بعد فك الضغط كبير جداً".into());
    }

    let raw = {
        let mut mf = archive
            .by_name("manifest.json")
            .map_err(|_| "الحزمة لا تحتوي manifest.json في جذرها".to_string())?;
        let mut buf = Vec::new();
        (&mut mf)
            .take(MAX_MANIFEST_BYTES + 1)
            .read_to_end(&mut buf)
            .map_err(|e| e.to_string())?;
        if buf.len() as u64 > MAX_MANIFEST_BYTES {
            return Err("manifest.json أكبر من 256KB".into());
        }
        buf
    };
    let manifest: Manifest =
        serde_json::from_slice(&raw).map_err(|e| format!("manifest.json غير صالح: {e}"))?;
    validate(&manifest, app_version)?;

    // الأمان: توقيع صالح (إن وُجد) + إعلان صادق للصلاحيات
    let sig = security::verify_package(&mut archive).inspect_err(|e| {
        let _ = db.audit_add("sig_invalid", &manifest.id, e);
    })?;
    let report = security::analyze(&manifest);
    if !report.undeclared.is_empty() {
        let _ = db.audit_add(
            "install_rejected",
            &manifest.id,
            &format!("undeclared: {}", report.undeclared.join(",")),
        );
        return Err(format!(
            "الأداة تستخدم صلاحيات لم تعلنها في manifest: {}",
            report.undeclared.join("، ")
        ));
    }

    let existing = db.tool_get(&manifest.id)?;
    let prev_status = if existing.is_some() {
        Some(security::effective_status(
            db,
            db.security_get(&manifest.id)?.as_ref(),
        ))
    } else {
        None
    };
    let mut updated_from = None;
    if let Some(ex) = &existing {
        if ex.source == "builtin" {
            return Err("لا يمكن استبدال أداة مدمجة بحزمة محلية".into());
        }
        let old = Version::parse(&ex.version).map_err(|e| e.to_string())?;
        let new = Version::parse(&manifest.version).map_err(|e| e.to_string())?;
        if new == old {
            return Err(format!("الإصدار {} مثبّت بالفعل", ex.version));
        }
        if new < old {
            return Err(format!(
                "لا يُسمح بالرجوع إلى إصدار أقدم (المثبّت {}، الحزمة {})",
                ex.version, manifest.version
            ));
        }
        updated_from = Some(ex.version.clone());
    }

    if !manifest.dependencies.is_empty() {
        let installed = db.tools_list()?;
        let missing: Vec<&str> = manifest
            .dependencies
            .iter()
            .filter(|d| !installed.iter().any(|t| &t.id == *d && t.enabled))
            .map(|d| d.as_str())
            .collect();
        if !missing.is_empty() {
            return Err(format!(
                "اعتماديات غير مثبّتة أو متوقفة: {}",
                missing.join("، ")
            ));
        }
    }

    // فك الضغط في مجلد مؤقت ثم استبدال المجلد النهائي
    fs::create_dir_all(tools_dir).map_err(|e| e.to_string())?;
    let final_dir = tools_dir.join(&manifest.id);
    let tmp_dir = tools_dir.join(format!("{}.new", manifest.id));
    if tmp_dir.exists() {
        fs::remove_dir_all(&tmp_dir).map_err(|e| e.to_string())?;
    }
    fs::create_dir_all(&tmp_dir).map_err(|e| e.to_string())?;
    if let Err(e) = extract_all(&mut archive, &tmp_dir) {
        let _ = fs::remove_dir_all(&tmp_dir);
        return Err(e);
    }
    if final_dir.exists() {
        fs::remove_dir_all(&final_dir).map_err(|e| e.to_string())?;
    }
    fs::rename(&tmp_dir, &final_dir).map_err(|e| e.to_string())?;

    let row = ToolRow {
        id: manifest.id.clone(),
        name: manifest.name.clone(),
        developer: manifest.developer.clone(),
        version: manifest.version.clone(),
        description: manifest.description.clone(),
        source: "local".into(),
        enabled: existing.as_ref().map(|e| e.enabled).unwrap_or(true),
        installed_at: now(),
        package_sha256: Some(sha),
        manifest: serde_json::to_string(&manifest).map_err(|e| e.to_string())?,
    };
    if let Err(e) = db.tool_put(&row) {
        if existing.is_none() {
            let _ = fs::remove_dir_all(&final_dir);
        }
        return Err(e);
    }
    let msg = match &updated_from {
        Some(old) => format!("تم التحديث من {old} إلى {}", manifest.version),
        None => format!("تم تثبيت الإصدار {}", manifest.version),
    };
    let _ = db.tool_log_add(&manifest.id, "info", &msg);

    // الصلاحيات: نُبقي الممنوح منها ما زال معلناً فقط؛ الجديدة تحتاج اعتماداً صريحاً من المستخدم
    db.grants_retain(&manifest.id, &manifest.permissions)?;
    security::record_install(db, &manifest, &sig, &report)?;
    let new_status = security::effective_status(db, db.security_get(&manifest.id)?.as_ref());
    let _ = db.audit_add(
        if updated_from.is_some() {
            "update"
        } else {
            "install"
        },
        &manifest.id,
        &format!(
            "v{} sig={} risk={}",
            manifest.version, new_status, report.risk
        ),
    );

    let mut warnings: Vec<String> = Vec::new();
    if prev_status.as_deref() == Some("trusted") && new_status != "trusted" {
        warnings.push(
            "الإصدار السابق كان موقّعاً وموثوقاً، أما هذا الإصدار فليس كذلك — تأكد من مصدر التحديث."
                .into(),
        );
    }
    if report.risk == "high" {
        let n = report
            .findings
            .iter()
            .filter(|f| f.severity == "high")
            .count();
        warnings.push(format!(
            "الفحص الساكن: خطورة عالية ({n} نمطاً حساساً). راجع التفاصيل قبل منح الصلاحيات."
        ));
    }

    Ok(InstallOutcome {
        tool: security::hydrate(db, to_info(row)?),
        updated_from,
        warnings,
    })
}

pub fn uninstall(db: &Db, tools_dir: &Path, id: &str) -> Result<(), String> {
    let row = db
        .tool_get(id)?
        .ok_or_else(|| format!("الأداة '{id}' غير مثبّتة"))?;
    if row.source == "builtin" {
        return Err("الأدوات المدمجة لا تُزال، يمكن إيقافها فقط".into());
    }
    let dependents: Vec<String> = list(db)?
        .into_iter()
        .filter(|t| t.manifest.dependencies.iter().any(|d| d == id))
        .map(|t| t.id)
        .collect();
    if !dependents.is_empty() {
        return Err(format!("الأداة مطلوبة لدى: {}", dependents.join("، ")));
    }
    match fs::remove_dir_all(tools_dir.join(id)) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(e.to_string()),
    }
    db.tool_delete(id)?;
    let _ = db.audit_add("uninstall", id, &format!("v{}", row.version));
    Ok(())
}

pub fn set_enabled(db: &Db, id: &str, enabled: bool) -> Result<(), String> {
    if !db.tool_set_enabled(id, enabled)? {
        return Err(format!("الأداة '{id}' غير مثبّتة"));
    }
    let _ = db.tool_log_add(
        id,
        "info",
        if enabled {
            "تم تفعيل الأداة"
        } else {
            "تم إيقاف الأداة"
        },
    );
    let _ = db.audit_add(if enabled { "enable" } else { "disable" }, id, "");
    Ok(())
}

// ---------------- الأدوات المدمجة ----------------

const BUILTIN: [(&str, &str); 4] = [
    ("net", include_str!("../builtin/net.json")),
    ("docker", include_str!("../builtin/docker.json")),
    ("db", include_str!("../builtin/db.json")),
    ("sys", include_str!("../builtin/sys.json")),
];

/// manifests الأدوات المدمجة بعد التحليل (تستعملها الاختبارات).
#[allow(dead_code)]
pub fn builtin_manifests() -> Result<Vec<Manifest>, String> {
    BUILTIN
        .iter()
        .map(|(name, raw)| {
            serde_json::from_str::<Manifest>(raw).map_err(|e| format!("أداة مدمجة {name}: {e}"))
        })
        .collect()
}

/// يسجّل الأدوات المدمجة (أو يحدّثها عند ترقية التطبيق) مع الحفاظ على حالة التفعيل.
pub fn install_builtins(db: &Db, app_version: &str) -> Result<(), String> {
    for (name, raw) in BUILTIN {
        let m: Manifest =
            serde_json::from_str(raw).map_err(|e| format!("أداة مدمجة {name}: {e}"))?;
        validate(&m, app_version).map_err(|e| format!("أداة مدمجة {name}: {e}"))?;
        let existing = db.tool_get(&m.id)?;
        if let Some(ex) = &existing {
            if ex.source != "builtin" {
                continue; // لا نمس أداة محلية بالمعرّف نفسه
            }
        }
        let row = ToolRow {
            id: m.id.clone(),
            name: m.name.clone(),
            developer: m.developer.clone(),
            version: m.version.clone(),
            description: m.description.clone(),
            source: "builtin".into(),
            enabled: existing.as_ref().map(|e| e.enabled).unwrap_or(true),
            installed_at: existing.map(|e| e.installed_at).unwrap_or_else(now),
            package_sha256: None,
            manifest: serde_json::to_string(&m).map_err(|e| e.to_string())?,
        };
        db.tool_put(&row)?;
        security::record_builtin(db, &m)?;
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// الاختبارات (مستقلة عن النظام ما عدا التحقق من النظام الحالي)
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::io::Write;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    const APP: &str = "0.3.0";

    fn tmp() -> PathBuf {
        let p = std::env::temp_dir().join(format!(
            "tarpkg-test-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&p).unwrap();
        p
    }

    fn manifest_value(id: &str, version: &str) -> serde_json::Value {
        json!({
            "id": id,
            "name": "أداة اختبار",
            "developer": "Tester",
            "version": version,
            "description": "وصف",
            "platforms": ["windows", "linux", "macos"],
            "kind": "command",
            "engine": "shell",
            "permissions": ["process.run"],
            "actions": [
                { "id": "go", "title": "تشغيل", "shell": "any", "steps": ["echo hi"] }
            ]
        })
    }

    fn make_pkg(files: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        let opt = zip::write::SimpleFileOptions::default();
        for (name, data) in files {
            w.start_file(*name, opt).unwrap();
            w.write_all(data).unwrap();
        }
        w.finish().unwrap().into_inner()
    }

    fn pkg_for(v: &serde_json::Value) -> Vec<u8> {
        make_pkg(&[("manifest.json", v.to_string().into_bytes())])
    }

    fn db() -> Db {
        Db::open_in_memory().unwrap()
    }

    #[test]
    fn builtin_manifests_are_valid_and_unique() {
        let mut ids = Vec::new();
        for (name, raw) in BUILTIN {
            let m: Manifest = serde_json::from_str(raw).unwrap_or_else(|e| panic!("{name}: {e}"));
            validate_inner(&m, APP, false).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(m.id, name);
            ids.push(m.id);
        }
        let mut sorted = ids.clone();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted.len(), ids.len());
    }

    #[test]
    fn install_and_list() {
        let d = tmp();
        let db = db();
        let out =
            install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.0.0")), APP).unwrap();
        assert_eq!(out.tool.id, "hello");
        assert_eq!(out.updated_from, None);
        assert_eq!(out.tool.source, "local");
        assert!(out.tool.enabled);
        assert!(out.tool.package_sha256.is_some());
        assert!(d.join("hello").join("manifest.json").is_file());
        let tools = list(&db).unwrap();
        assert_eq!(tools.len(), 1);
        assert_eq!(
            tools[0].manifest.actions[0].steps,
            vec!["echo hi".to_string()]
        );
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn update_rules() {
        let d = tmp();
        let db = db();
        install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.2.0")), APP).unwrap();
        assert!(
            install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.2.0")), APP).is_err()
        );
        assert!(
            install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.1.0")), APP).is_err()
        );
        let up =
            install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.3.0")), APP).unwrap();
        assert_eq!(up.updated_from.as_deref(), Some("1.2.0"));
        assert_eq!(list(&db).unwrap()[0].version, "1.3.0");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn enabled_flag_survives_update() {
        let d = tmp();
        let db = db();
        install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.0.0")), APP).unwrap();
        set_enabled(&db, "hello", false).unwrap();
        install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.0.1")), APP).unwrap();
        assert!(!list(&db).unwrap()[0].enabled);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn zip_slip_is_rejected() {
        let d = tmp();
        let tools = d.join("tools");
        fs::create_dir_all(&tools).unwrap();
        let db = db();
        let pkg = make_pkg(&[
            (
                "manifest.json",
                manifest_value("evil", "1.0.0").to_string().into_bytes(),
            ),
            ("../evil.txt", b"x".to_vec()),
        ]);
        let err = install_package(&db, &tools, &pkg, APP).unwrap_err();
        assert!(err.contains("غير آمن"), "got: {err}");
        assert!(!d.join("evil.txt").exists());
        assert!(list(&db).unwrap().is_empty());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn missing_or_broken_manifest_is_rejected() {
        let d = tmp();
        let db = db();
        assert!(
            install_package(&db, &d, &make_pkg(&[("readme.txt", b"x".to_vec())]), APP).is_err()
        );
        assert!(install_package(
            &db,
            &d,
            &make_pkg(&[("manifest.json", b"{not json".to_vec())]),
            APP
        )
        .is_err());
        assert!(install_package(&db, &d, b"not a zip at all", APP).is_err());
        assert!(list(&db).unwrap().is_empty());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn invalid_manifests_are_rejected() {
        let d = tmp();
        let db = db();
        let cases: Vec<(&str, serde_json::Value)> = vec![
            ("bad id", {
                let mut v = manifest_value("Bad_ID", "1.0.0");
                v["id"] = json!("Bad_ID");
                v
            }),
            ("bad semver", manifest_value("okid", "1.0")),
            ("no process.run", {
                let mut v = manifest_value("okid", "1.0.0");
                v["permissions"] = json!(["network"]);
                v
            }),
            ("unknown permission", {
                let mut v = manifest_value("okid", "1.0.0");
                v["permissions"] = json!(["process.run", "root.everything"]);
                v
            }),
            ("unsupported kind", {
                let mut v = manifest_value("okid", "1.0.0");
                v["kind"] = json!("wasm");
                v
            }),
            ("app too old", {
                let mut v = manifest_value("okid", "1.0.0");
                v["minAppVersion"] = json!("9.0.0");
                v
            }),
            ("newline in step", {
                let mut v = manifest_value("okid", "1.0.0");
                v["actions"][0]["steps"] = json!(["echo a\necho b"]);
                v
            }),
            ("no actions", {
                let mut v = manifest_value("okid", "1.0.0");
                v["actions"] = json!([]);
                v
            }),
            ("duplicate action ids", {
                let mut v = manifest_value("okid", "1.0.0");
                v["actions"] = json!([
                    { "id": "a", "title": "x", "steps": ["echo 1"] },
                    { "id": "a", "title": "y", "steps": ["echo 2"] }
                ]);
                v
            }),
        ];
        for (label, v) in cases {
            let r = install_package(&db, &d, &pkg_for(&v), APP);
            assert!(r.is_err(), "case should fail: {label}");
        }
        assert!(list(&db).unwrap().is_empty());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn uninstall_removes_files_and_row() {
        let d = tmp();
        let db = db();
        install_package(&db, &d, &pkg_for(&manifest_value("hello", "1.0.0")), APP).unwrap();
        uninstall(&db, &d, "hello").unwrap();
        assert!(!d.join("hello").exists());
        assert!(list(&db).unwrap().is_empty());
        assert!(uninstall(&db, &d, "hello").is_err());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn builtin_cannot_be_removed_or_replaced() {
        let d = tmp();
        let db = db();
        install_builtins(&db, APP).unwrap();
        assert_eq!(list(&db).unwrap().len(), BUILTIN.len());
        assert!(uninstall(&db, &d, "net").is_err());
        assert!(install_package(&db, &d, &pkg_for(&manifest_value("net", "9.0.0")), APP).is_err());
        // الإيقاف مسموح ويُحفظ عبر إعادة تسجيل المدمجة
        set_enabled(&db, "net", false).unwrap();
        install_builtins(&db, APP).unwrap();
        let net = list(&db)
            .unwrap()
            .into_iter()
            .find(|t| t.id == "net")
            .unwrap();
        assert!(!net.enabled);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn dependencies_are_enforced() {
        let d = tmp();
        let db = db();
        let mut b = manifest_value("tool-b", "1.0.0");
        b["dependencies"] = json!(["tool-a"]);
        assert!(install_package(&db, &d, &pkg_for(&b), APP).is_err());
        install_package(&db, &d, &pkg_for(&manifest_value("tool-a", "1.0.0")), APP).unwrap();
        install_package(&db, &d, &pkg_for(&b), APP).unwrap();
        assert!(uninstall(&db, &d, "tool-a").is_err());
        uninstall(&db, &d, "tool-b").unwrap();
        uninstall(&db, &d, "tool-a").unwrap();
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn tool_log_roundtrip() {
        let db = db();
        db.tool_log_add("hello", "info", "one").unwrap();
        db.tool_log_add("hello", "warn", "two").unwrap();
        let rows = db.tool_log_recent("hello", 10).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].message, "two");
    }
}
