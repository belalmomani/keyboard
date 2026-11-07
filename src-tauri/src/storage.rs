//! Storage Engine — تخزين محلي عبر SQLite:
//! الإعدادات، المفضلة، سجل تنفيذ الأوامر، وسجل الجلسات.

use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;
use std::collections::HashMap;
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

const HISTORY_CAP: i64 = 1000;

pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct HistoryEntry {
    pub id: i64,
    pub item_id: Option<String>,
    pub command: String,
    pub shell: String,
    pub ts: i64,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct FrequentEntry {
    pub item_id: Option<String>,
    pub command: String,
    pub shell: String,
    pub count: i64,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct SessionRow {
    pub id: i64,
    pub shell: String,
    pub started_at: i64,
    pub ended_at: Option<i64>,
    pub exit_code: Option<i64>,
}

/// صف أداة مثبّتة. manifest هو JSON الخام كما خُزّن بعد التحقق.
#[derive(Debug, Clone, PartialEq)]
pub struct ToolRow {
    pub id: String,
    pub name: String,
    pub developer: String,
    pub version: String,
    pub description: String,
    /// "builtin" أو "local"
    pub source: String,
    pub enabled: bool,
    pub installed_at: i64,
    pub package_sha256: Option<String>,
    pub manifest: String,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct ToolLogRow {
    pub id: i64,
    pub ts: i64,
    pub level: String,
    pub message: String,
}

/// نوع التوقيع المخزّن: "unsigned" | "signed" | "builtin". الثقة تُحسب ديناميكياً من الناشرين الموثوقين.
#[derive(Debug, Clone, PartialEq)]
pub struct SecurityRow {
    pub tool_id: String,
    pub kind: String,
    pub key_id: Option<String>,
    pub public_key: Option<String>,
    pub signer: Option<String>,
    /// "low" | "medium" | "high"
    pub risk: String,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TrustedKey {
    pub key_id: String,
    pub name: String,
    pub public_key: String,
    pub added_at: i64,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct AuditRow {
    pub id: i64,
    pub ts: i64,
    pub kind: String,
    pub subject: String,
    pub detail: String,
}

fn tool_from_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<ToolRow> {
    Ok(ToolRow {
        id: r.get(0)?,
        name: r.get(1)?,
        developer: r.get(2)?,
        version: r.get(3)?,
        description: r.get(4)?,
        source: r.get(5)?,
        enabled: r.get::<_, i64>(6)? != 0,
        installed_at: r.get(7)?,
        package_sha256: r.get(8)?,
        manifest: r.get(9)?,
    })
}

const TOOL_COLS: &str =
    "id, name, developer, version, description, source, enabled, installed_at, package_sha256, manifest";

pub struct Db {
    conn: Mutex<Connection>,
    /// معرّف جلسة الطرفية (s1, s2…) ← رقم صفها في جدول sessions
    open_sessions: Mutex<HashMap<String, i64>>,
}

impl Db {
    pub fn open(path: &Path) -> Result<Self, String> {
        Self::init(Connection::open(path).map_err(err)?)
    }

    #[allow(dead_code)]
    pub fn open_in_memory() -> Result<Self, String> {
        Self::init(Connection::open_in_memory().map_err(err)?)
    }

    fn init(conn: Connection) -> Result<Self, String> {
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
             );
             CREATE TABLE IF NOT EXISTS favorites (
                item_id TEXT PRIMARY KEY,
                added_at INTEGER NOT NULL
             );
             CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id TEXT,
                command TEXT NOT NULL,
                shell TEXT NOT NULL,
                ts INTEGER NOT NULL
             );
             CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shell TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                exit_code INTEGER
             );
             CREATE TABLE IF NOT EXISTS tools (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                developer TEXT NOT NULL,
                version TEXT NOT NULL,
                description TEXT NOT NULL,
                source TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                installed_at INTEGER NOT NULL,
                package_sha256 TEXT,
                manifest TEXT NOT NULL
             );
             CREATE TABLE IF NOT EXISTS tool_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tool_id TEXT NOT NULL,
                ts INTEGER NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL
             );
             CREATE TABLE IF NOT EXISTS tool_grants (
                tool_id TEXT NOT NULL,
                permission TEXT NOT NULL,
                decided_at INTEGER NOT NULL,
                PRIMARY KEY (tool_id, permission)
             );
             CREATE TABLE IF NOT EXISTS tool_security (
                tool_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                key_id TEXT,
                public_key TEXT,
                signer TEXT,
                risk TEXT NOT NULL
             );
             CREATE TABLE IF NOT EXISTS tool_reports (
                tool_id TEXT NOT NULL,
                version TEXT NOT NULL,
                ts INTEGER NOT NULL,
                report TEXT NOT NULL,
                PRIMARY KEY (tool_id, version)
             );
             CREATE TABLE IF NOT EXISTS trusted_keys (
                key_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                public_key TEXT NOT NULL,
                added_at INTEGER NOT NULL
             );
             CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                subject TEXT NOT NULL,
                detail TEXT NOT NULL
             );",
        )
        .map_err(err)?;
        Ok(Self {
            conn: Mutex::new(conn),
            open_sessions: Mutex::new(HashMap::new()),
        })
    }

    fn c(&self) -> Result<MutexGuard<'_, Connection>, String> {
        self.conn
            .lock()
            .map_err(|_| "database lock error".to_string())
    }

    // ---- الإعدادات ----
    pub fn setting_get(&self, key: &str) -> Result<Option<String>, String> {
        self.c()?
            .query_row(
                "SELECT value FROM settings WHERE key = ?1",
                params![key],
                |r| r.get::<_, String>(0),
            )
            .optional()
            .map_err(err)
    }

    pub fn setting_set(&self, key: &str, value: &str) -> Result<(), String> {
        self.c()?
            .execute(
                "INSERT INTO settings (key, value) VALUES (?1, ?2)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                params![key, value],
            )
            .map_err(err)?;
        Ok(())
    }

    // ---- المفضلة ----
    pub fn favorites_list(&self) -> Result<Vec<String>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare("SELECT item_id FROM favorites ORDER BY added_at, item_id")
            .map_err(err)?;
        let rows = st.query_map([], |r| r.get::<_, String>(0)).map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    /// يعيد true إذا أصبح العنصر مفضلاً بعد العملية.
    pub fn favorite_toggle(&self, item_id: &str) -> Result<bool, String> {
        let c = self.c()?;
        let exists: Option<i64> = c
            .query_row(
                "SELECT 1 FROM favorites WHERE item_id = ?1",
                params![item_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(err)?;
        if exists.is_some() {
            c.execute("DELETE FROM favorites WHERE item_id = ?1", params![item_id])
                .map_err(err)?;
            Ok(false)
        } else {
            c.execute(
                "INSERT INTO favorites (item_id, added_at) VALUES (?1, ?2)",
                params![item_id, now()],
            )
            .map_err(err)?;
            Ok(true)
        }
    }

    // ---- سجل تنفيذ الأوامر ----
    pub fn history_add(
        &self,
        item_id: Option<&str>,
        command: &str,
        shell: &str,
    ) -> Result<(), String> {
        let command = command.trim();
        if command.is_empty() {
            return Ok(());
        }
        let c = self.c()?;
        c.execute(
            "INSERT INTO history (item_id, command, shell, ts) VALUES (?1, ?2, ?3, ?4)",
            params![item_id, command, shell, now()],
        )
        .map_err(err)?;
        c.execute(
            "DELETE FROM history WHERE id <= (SELECT MAX(id) FROM history) - ?1",
            params![HISTORY_CAP],
        )
        .map_err(err)?;
        Ok(())
    }

    pub fn history_recent(&self, limit: u32) -> Result<Vec<HistoryEntry>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare(
                "SELECT id, item_id, command, shell, ts FROM history
                 ORDER BY id DESC LIMIT ?1",
            )
            .map_err(err)?;
        let rows = st
            .query_map(params![limit as i64], |r| {
                Ok(HistoryEntry {
                    id: r.get(0)?,
                    item_id: r.get(1)?,
                    command: r.get(2)?,
                    shell: r.get(3)?,
                    ts: r.get(4)?,
                })
            })
            .map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    pub fn history_frequent(&self, limit: u32) -> Result<Vec<FrequentEntry>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare(
                "SELECT item_id, command, shell, COUNT(*) AS cnt FROM history
                 GROUP BY command, shell
                 ORDER BY cnt DESC, MAX(id) DESC LIMIT ?1",
            )
            .map_err(err)?;
        let rows = st
            .query_map(params![limit as i64], |r| {
                Ok(FrequentEntry {
                    item_id: r.get(0)?,
                    command: r.get(1)?,
                    shell: r.get(2)?,
                    count: r.get(3)?,
                })
            })
            .map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    pub fn history_clear(&self) -> Result<(), String> {
        self.c()?.execute("DELETE FROM history", []).map_err(err)?;
        Ok(())
    }

    // ---- الأدوات ----
    pub fn tool_get(&self, id: &str) -> Result<Option<ToolRow>, String> {
        let sql = format!("SELECT {TOOL_COLS} FROM tools WHERE id = ?1");
        self.c()?
            .query_row(&sql, params![id], tool_from_row)
            .optional()
            .map_err(err)
    }

    pub fn tools_list(&self) -> Result<Vec<ToolRow>, String> {
        let c = self.c()?;
        let sql = format!("SELECT {TOOL_COLS} FROM tools ORDER BY source DESC, name");
        let mut st = c.prepare(&sql).map_err(err)?;
        let rows = st.query_map([], tool_from_row).map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    /// إدراج أو تحديث أداة (المفتاح: id).
    pub fn tool_put(&self, t: &ToolRow) -> Result<(), String> {
        self.c()?
            .execute(
                "INSERT INTO tools (id, name, developer, version, description, source, enabled,
                                    installed_at, package_sha256, manifest)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
                 ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    developer = excluded.developer,
                    version = excluded.version,
                    description = excluded.description,
                    source = excluded.source,
                    enabled = excluded.enabled,
                    installed_at = excluded.installed_at,
                    package_sha256 = excluded.package_sha256,
                    manifest = excluded.manifest",
                params![
                    t.id,
                    t.name,
                    t.developer,
                    t.version,
                    t.description,
                    t.source,
                    t.enabled as i64,
                    t.installed_at,
                    t.package_sha256,
                    t.manifest
                ],
            )
            .map_err(err)?;
        Ok(())
    }

    pub fn tool_delete(&self, id: &str) -> Result<(), String> {
        let c = self.c()?;
        c.execute("DELETE FROM tools WHERE id = ?1", params![id])
            .map_err(err)?;
        for table in ["tool_log", "tool_grants", "tool_security", "tool_reports"] {
            c.execute(
                &format!("DELETE FROM {table} WHERE tool_id = ?1"),
                params![id],
            )
            .map_err(err)?;
        }
        Ok(())
    }

    // ---- الأمان: الصلاحيات الممنوحة ----
    pub fn grants_get(&self, tool_id: &str) -> Result<Vec<String>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare("SELECT permission FROM tool_grants WHERE tool_id = ?1 ORDER BY permission")
            .map_err(err)?;
        let rows = st
            .query_map(params![tool_id], |r| r.get::<_, String>(0))
            .map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    /// يستبدل مجموعة الصلاحيات الممنوحة للأداة بالمجموعة المعطاة.
    pub fn grants_set(&self, tool_id: &str, perms: &[String]) -> Result<(), String> {
        let c = self.c()?;
        c.execute(
            "DELETE FROM tool_grants WHERE tool_id = ?1",
            params![tool_id],
        )
        .map_err(err)?;
        for p in perms {
            c.execute(
                "INSERT OR REPLACE INTO tool_grants (tool_id, permission, decided_at)
                 VALUES (?1, ?2, ?3)",
                params![tool_id, p, now()],
            )
            .map_err(err)?;
        }
        Ok(())
    }

    /// يحذف الصلاحيات الممنوحة التي لم تعد معلنة (عند التحديث).
    pub fn grants_retain(&self, tool_id: &str, keep: &[String]) -> Result<(), String> {
        let current = self.grants_get(tool_id)?;
        let kept: Vec<String> = current.into_iter().filter(|p| keep.contains(p)).collect();
        self.grants_set(tool_id, &kept)
    }

    // ---- الأمان: توقيع الأداة وخطورتها ----
    pub fn security_put(&self, s: &SecurityRow) -> Result<(), String> {
        self.c()?
            .execute(
                "INSERT OR REPLACE INTO tool_security (tool_id, kind, key_id, public_key, signer, risk)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![s.tool_id, s.kind, s.key_id, s.public_key, s.signer, s.risk],
            )
            .map_err(err)?;
        Ok(())
    }

    pub fn security_get(&self, tool_id: &str) -> Result<Option<SecurityRow>, String> {
        self.c()?
            .query_row(
                "SELECT tool_id, kind, key_id, public_key, signer, risk FROM tool_security
                 WHERE tool_id = ?1",
                params![tool_id],
                |r| {
                    Ok(SecurityRow {
                        tool_id: r.get(0)?,
                        kind: r.get(1)?,
                        key_id: r.get(2)?,
                        public_key: r.get(3)?,
                        signer: r.get(4)?,
                        risk: r.get(5)?,
                    })
                },
            )
            .optional()
            .map_err(err)
    }

    // ---- الأمان: تقارير الفحص ----
    pub fn report_put(&self, tool_id: &str, version: &str, json: &str) -> Result<(), String> {
        self.c()?
            .execute(
                "INSERT OR REPLACE INTO tool_reports (tool_id, version, ts, report)
                 VALUES (?1, ?2, ?3, ?4)",
                params![tool_id, version, now(), json],
            )
            .map_err(err)?;
        Ok(())
    }

    pub fn report_latest(&self, tool_id: &str) -> Result<Option<String>, String> {
        self.c()?
            .query_row(
                "SELECT report FROM tool_reports WHERE tool_id = ?1 ORDER BY ts DESC, rowid DESC LIMIT 1",
                params![tool_id],
                |r| r.get::<_, String>(0),
            )
            .optional()
            .map_err(err)
    }

    // ---- الأمان: الناشرون الموثوقون ----
    pub fn trusted_list(&self) -> Result<Vec<TrustedKey>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare("SELECT key_id, name, public_key, added_at FROM trusted_keys ORDER BY added_at, key_id")
            .map_err(err)?;
        let rows = st
            .query_map([], |r| {
                Ok(TrustedKey {
                    key_id: r.get(0)?,
                    name: r.get(1)?,
                    public_key: r.get(2)?,
                    added_at: r.get(3)?,
                })
            })
            .map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    pub fn trusted_get(&self, key_id: &str) -> Result<Option<TrustedKey>, String> {
        self.c()?
            .query_row(
                "SELECT key_id, name, public_key, added_at FROM trusted_keys WHERE key_id = ?1",
                params![key_id],
                |r| {
                    Ok(TrustedKey {
                        key_id: r.get(0)?,
                        name: r.get(1)?,
                        public_key: r.get(2)?,
                        added_at: r.get(3)?,
                    })
                },
            )
            .optional()
            .map_err(err)
    }

    pub fn trusted_add(&self, k: &TrustedKey) -> Result<(), String> {
        self.c()?
            .execute(
                "INSERT OR REPLACE INTO trusted_keys (key_id, name, public_key, added_at)
                 VALUES (?1, ?2, ?3, ?4)",
                params![k.key_id, k.name, k.public_key, k.added_at],
            )
            .map_err(err)?;
        Ok(())
    }

    pub fn trusted_remove(&self, key_id: &str) -> Result<bool, String> {
        let n = self
            .c()?
            .execute(
                "DELETE FROM trusted_keys WHERE key_id = ?1",
                params![key_id],
            )
            .map_err(err)?;
        Ok(n > 0)
    }

    // ---- سجل التدقيق (العمليات الحساسة) ----
    pub fn audit_add(&self, kind: &str, subject: &str, detail: &str) -> Result<(), String> {
        let detail: String = detail.chars().take(500).collect();
        let c = self.c()?;
        c.execute(
            "INSERT INTO audit_log (ts, kind, subject, detail) VALUES (?1, ?2, ?3, ?4)",
            params![now(), kind, subject, detail],
        )
        .map_err(err)?;
        c.execute(
            "DELETE FROM audit_log WHERE id <= (SELECT MAX(id) FROM audit_log) - 1000",
            [],
        )
        .map_err(err)?;
        Ok(())
    }

    /// عدد صفوف جدول من قائمة بيضاء (لا يُمرَّر اسم من الخارج إلى SQL).
    pub fn count_rows(&self, table: &str) -> Result<i64, String> {
        const ALLOWED: [&str; 8] = [
            "favorites",
            "history",
            "sessions",
            "tools",
            "tool_log",
            "tool_grants",
            "trusted_keys",
            "audit_log",
        ];
        if !ALLOWED.contains(&table) {
            return Err("جدول غير مسموح".into());
        }
        let sql = format!("SELECT COUNT(*) FROM {table}");
        self.c()?.query_row(&sql, [], |r| r.get(0)).map_err(err)
    }

    pub fn audit_recent(&self, limit: u32) -> Result<Vec<AuditRow>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare(
                "SELECT id, ts, kind, subject, detail FROM audit_log ORDER BY id DESC LIMIT ?1",
            )
            .map_err(err)?;
        let rows = st
            .query_map(params![limit as i64], |r| {
                Ok(AuditRow {
                    id: r.get(0)?,
                    ts: r.get(1)?,
                    kind: r.get(2)?,
                    subject: r.get(3)?,
                    detail: r.get(4)?,
                })
            })
            .map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    /// يعيد false إذا لم توجد الأداة.
    pub fn tool_set_enabled(&self, id: &str, enabled: bool) -> Result<bool, String> {
        let n = self
            .c()?
            .execute(
                "UPDATE tools SET enabled = ?1 WHERE id = ?2",
                params![enabled as i64, id],
            )
            .map_err(err)?;
        Ok(n > 0)
    }

    pub fn tool_log_add(&self, tool_id: &str, level: &str, message: &str) -> Result<(), String> {
        let msg: String = message.chars().take(500).collect();
        let c = self.c()?;
        c.execute(
            "INSERT INTO tool_log (tool_id, ts, level, message) VALUES (?1, ?2, ?3, ?4)",
            params![tool_id, now(), level, msg],
        )
        .map_err(err)?;
        // نحتفظ بآخر 200 سطراً لكل أداة
        c.execute(
            "DELETE FROM tool_log WHERE tool_id = ?1 AND id <= (
                SELECT COALESCE(MAX(id), 0) FROM tool_log WHERE tool_id = ?1) - 200",
            params![tool_id],
        )
        .map_err(err)?;
        Ok(())
    }

    pub fn tool_log_recent(&self, tool_id: &str, limit: u32) -> Result<Vec<ToolLogRow>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare(
                "SELECT id, ts, level, message FROM tool_log
                 WHERE tool_id = ?1 ORDER BY id DESC LIMIT ?2",
            )
            .map_err(err)?;
        let rows = st
            .query_map(params![tool_id, limit as i64], |r| {
                Ok(ToolLogRow {
                    id: r.get(0)?,
                    ts: r.get(1)?,
                    level: r.get(2)?,
                    message: r.get(3)?,
                })
            })
            .map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }

    // ---- سجل الجلسات ----
    pub fn session_started(&self, sid: &str, shell: &str) -> Result<(), String> {
        let rowid = {
            let c = self.c()?;
            c.execute(
                "INSERT INTO sessions (shell, started_at) VALUES (?1, ?2)",
                params![shell, now()],
            )
            .map_err(err)?;
            c.last_insert_rowid()
        };
        self.open_sessions
            .lock()
            .map_err(|_| "session map lock error".to_string())?
            .insert(sid.to_string(), rowid);
        Ok(())
    }

    pub fn session_ended(&self, sid: &str, code: Option<u32>) -> Result<(), String> {
        let rowid = self
            .open_sessions
            .lock()
            .map_err(|_| "session map lock error".to_string())?
            .remove(sid);
        if let Some(rowid) = rowid {
            self.c()?
                .execute(
                    "UPDATE sessions SET ended_at = ?1, exit_code = ?2 WHERE id = ?3",
                    params![now(), code.map(|c| c as i64), rowid],
                )
                .map_err(err)?;
        }
        Ok(())
    }

    pub fn sessions_recent(&self, limit: u32) -> Result<Vec<SessionRow>, String> {
        let c = self.c()?;
        let mut st = c
            .prepare(
                "SELECT id, shell, started_at, ended_at, exit_code FROM sessions
                 ORDER BY id DESC LIMIT ?1",
            )
            .map_err(err)?;
        let rows = st
            .query_map(params![limit as i64], |r| {
                Ok(SessionRow {
                    id: r.get(0)?,
                    shell: r.get(1)?,
                    started_at: r.get(2)?,
                    ended_at: r.get(3)?,
                    exit_code: r.get(4)?,
                })
            })
            .map_err(err)?;
        let out = rows.collect::<Result<Vec<_>, _>>().map_err(err);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Db {
        Db::open_in_memory().expect("in-memory db")
    }

    #[test]
    fn settings_roundtrip() {
        let d = db();
        assert_eq!(d.setting_get("mode").unwrap(), None);
        d.setting_set("mode", "smart").unwrap();
        assert_eq!(d.setting_get("mode").unwrap().as_deref(), Some("smart"));
        d.setting_set("mode", "pro").unwrap();
        assert_eq!(d.setting_get("mode").unwrap().as_deref(), Some("pro"));
    }

    #[test]
    fn favorites_toggle() {
        let d = db();
        assert!(d.favorite_toggle("net-ip").unwrap());
        assert!(d.favorite_toggle("sys-os").unwrap());
        assert_eq!(d.favorites_list().unwrap().len(), 2);
        assert!(!d.favorite_toggle("net-ip").unwrap());
        assert_eq!(d.favorites_list().unwrap(), vec!["sys-os".to_string()]);
    }

    #[test]
    fn history_recent_and_frequent() {
        let d = db();
        d.history_add(Some("a"), "ipconfig /all", "cmd").unwrap();
        d.history_add(Some("a"), "ipconfig /all", "cmd").unwrap();
        d.history_add(None, "git status", "powershell").unwrap();
        let recent = d.history_recent(10).unwrap();
        assert_eq!(recent.len(), 3);
        assert_eq!(recent[0].command, "git status");
        let freq = d.history_frequent(10).unwrap();
        assert_eq!(freq[0].command, "ipconfig /all");
        assert_eq!(freq[0].count, 2);
    }

    #[test]
    fn history_ignores_empty_and_clears() {
        let d = db();
        d.history_add(None, "   ", "cmd").unwrap();
        assert!(d.history_recent(10).unwrap().is_empty());
        d.history_add(None, "hostname", "cmd").unwrap();
        assert_eq!(d.history_recent(10).unwrap().len(), 1);
        d.history_clear().unwrap();
        assert!(d.history_recent(10).unwrap().is_empty());
    }

    #[test]
    fn sessions_lifecycle() {
        let d = db();
        d.session_started("s1", "powershell").unwrap();
        d.session_ended("s1", Some(3)).unwrap();
        let rows = d.sessions_recent(10).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].shell, "powershell");
        assert_eq!(rows[0].exit_code, Some(3));
        assert!(rows[0].ended_at.is_some());
        // إنهاء جلسة غير معروفة لا يسبب خطأ
        d.session_ended("zzz", None).unwrap();
    }
}
