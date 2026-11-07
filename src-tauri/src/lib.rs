mod admin;
mod plugin;
mod security;
mod storage;
mod studio;
mod terminal;

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use plugin::{InstallOutcome, ToolInfo};
use security::{LabReport, RunPlan, SecurityInfo};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::Arc;
use storage::{AuditRow, Db, FrequentEntry, HistoryEntry, SessionRow, ToolLogRow, TrustedKey};
use studio::{
    BuildOutcome, ProjectInfo, Runtimes, TestReport, TextCheck, TreeEntry, ValidateReport,
};
use tauri::{AppHandle, Emitter, Manager, State};
use terminal::{default_cwd, shell_spec, SessionEvent, Sink, TerminalEngine};

#[derive(Serialize, Clone)]
struct OutputPayload {
    id: String,
    /// بايتات الخرج الخام بترميز base64 (حتى لا تنقطع أحرف UTF-8 متعددة البايتات).
    data: String,
}

#[derive(Serialize, Clone)]
struct ExitPayload {
    id: String,
    code: Option<u32>,
}

#[derive(Serialize)]
struct AppInfo {
    name: &'static str,
    version: &'static str,
    build: &'static str,
}

fn make_sink(app: AppHandle) -> Sink {
    Arc::new(move |ev| match ev {
        SessionEvent::Output { id, data } => {
            let _ = app.emit(
                "terminal-output",
                OutputPayload {
                    id,
                    data: B64.encode(data),
                },
            );
        }
        SessionEvent::Exit { id, code } => {
            if let Some(db) = app.try_state::<Db>() {
                let _ = db.session_ended(&id, code);
            }
            let _ = app.emit("terminal-exit", ExitPayload { id, code });
        }
    })
}

#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        name: "Terminal AR",
        version: env!("CARGO_PKG_VERSION"),
        build: env!("TAR_BUILD"),
    }
}

// ---------------- الطرفية ----------------

#[tauri::command]
async fn terminal_open(
    app: AppHandle,
    engine: State<'_, TerminalEngine>,
    db: State<'_, Db>,
    kind: String,
    cols: u16,
    rows: u16,
    cwd: Option<String>,
) -> Result<String, String> {
    let cwd = cwd.map(PathBuf::from).or_else(default_cwd);
    let spec = shell_spec(&kind, cwd)?;
    let id = engine.open(&spec, cols, rows, make_sink(app))?;
    let _ = db.session_started(&id, &kind);
    Ok(id)
}

#[tauri::command]
async fn terminal_write(
    engine: State<'_, TerminalEngine>,
    id: String,
    data: String,
) -> Result<(), String> {
    engine.write(&id, data.as_bytes())
}

#[tauri::command]
async fn terminal_resize(
    engine: State<'_, TerminalEngine>,
    id: String,
    cols: u16,
    rows: u16,
) -> Result<(), String> {
    engine.resize(&id, cols, rows)
}

#[tauri::command]
async fn terminal_kill(engine: State<'_, TerminalEngine>, id: String) -> Result<(), String> {
    engine.kill(&id)
}

#[tauri::command]
async fn terminal_close(engine: State<'_, TerminalEngine>, id: String) -> Result<(), String> {
    engine.close(&id)
}

// ---------------- التخزين ----------------

#[tauri::command]
async fn setting_get(db: State<'_, Db>, key: String) -> Result<Option<String>, String> {
    db.setting_get(&key)
}

#[tauri::command]
async fn setting_set(db: State<'_, Db>, key: String, value: String) -> Result<(), String> {
    db.setting_set(&key, &value)
}

#[tauri::command]
async fn favorites_list(db: State<'_, Db>) -> Result<Vec<String>, String> {
    db.favorites_list()
}

#[tauri::command]
async fn favorite_toggle(db: State<'_, Db>, item_id: String) -> Result<bool, String> {
    db.favorite_toggle(&item_id)
}

#[tauri::command]
async fn history_add(
    db: State<'_, Db>,
    item_id: Option<String>,
    command: String,
    shell: String,
) -> Result<(), String> {
    db.history_add(item_id.as_deref(), &command, &shell)
}

#[tauri::command]
async fn history_recent(db: State<'_, Db>, limit: u32) -> Result<Vec<HistoryEntry>, String> {
    db.history_recent(limit)
}

#[tauri::command]
async fn history_frequent(db: State<'_, Db>, limit: u32) -> Result<Vec<FrequentEntry>, String> {
    db.history_frequent(limit)
}

#[tauri::command]
async fn history_clear(db: State<'_, Db>) -> Result<(), String> {
    db.history_clear()
}

#[tauri::command]
async fn sessions_recent(db: State<'_, Db>, limit: u32) -> Result<Vec<SessionRow>, String> {
    db.sessions_recent(limit)
}

// ---------------- الأدوات (Plugin Engine) ----------------

/// مجلد الأدوات المثبّتة داخل بيانات التطبيق (مجلد منفصل لكل أداة).
struct ToolsDir(PathBuf);

#[tauri::command]
async fn tools_list(db: State<'_, Db>) -> Result<Vec<ToolInfo>, String> {
    plugin::list(&db)
}

#[tauri::command]
async fn tool_install(
    db: State<'_, Db>,
    dir: State<'_, ToolsDir>,
    data: String,
) -> Result<InstallOutcome, String> {
    let bytes = B64
        .decode(data.as_bytes())
        .map_err(|_| "بيانات الحزمة غير صالحة".to_string())?;
    plugin::install_package(&db, &dir.0, &bytes, env!("CARGO_PKG_VERSION"))
}

#[tauri::command]
async fn tool_uninstall(
    db: State<'_, Db>,
    dir: State<'_, ToolsDir>,
    id: String,
) -> Result<(), String> {
    plugin::uninstall(&db, &dir.0, &id)
}

#[tauri::command]
async fn tool_set_enabled(
    adm: State<'_, admin::AdminState>,
    db: State<'_, Db>,
    id: String,
    enabled: bool,
) -> Result<(), String> {
    adm.require()
        .map_err(|_| "تفعيل/إيقاف الأدوات من صلاحيات المشرف".to_string())?;
    plugin::set_enabled(&db, &id, enabled)
}

#[tauri::command]
async fn tool_log_add(
    db: State<'_, Db>,
    tool_id: String,
    level: String,
    message: String,
) -> Result<(), String> {
    if !["info", "warn", "error"].contains(&level.as_str()) {
        return Err("invalid log level".into());
    }
    db.tool_log_add(&tool_id, &level, &message)
}

#[tauri::command]
async fn tool_log_recent(
    db: State<'_, Db>,
    tool_id: String,
    limit: u32,
) -> Result<Vec<ToolLogRow>, String> {
    db.tool_log_recent(&tool_id, limit)
}

// ---------------- الأمان (Security Engine) ----------------

#[tauri::command]
async fn tool_security(db: State<'_, Db>, id: String) -> Result<SecurityInfo, String> {
    security::security_info(&db, &id)
}

#[tauri::command]
async fn tool_set_grants(
    adm: State<'_, admin::AdminState>,
    db: State<'_, Db>,
    id: String,
    permissions: Vec<String>,
) -> Result<(), String> {
    adm.require()
        .map_err(|_| "الموافقة على صلاحيات الأدوات من صلاحيات المشرف".to_string())?;
    security::set_grants(&db, &id, &permissions)
}

/// القرار النهائي لتشغيل إجراء: الصلاحيات والفحص الساكن والتأكيد المطلوب. الواجهة تنفذ ما تعيده فقط.
#[tauri::command]
async fn tool_plan_run(
    db: State<'_, Db>,
    dir: State<'_, ToolsDir>,
    tool_id: String,
    action_id: String,
) -> Result<RunPlan, String> {
    security::plan_run(&db, &dir.0, &tool_id, &action_id)
}

#[tauri::command]
async fn tool_run_commit(
    db: State<'_, Db>,
    tool_id: String,
    action_id: String,
) -> Result<(), String> {
    db.audit_add("run", &tool_id, &action_id)
}

#[tauri::command]
async fn tool_rescan(db: State<'_, Db>, id: String) -> Result<LabReport, String> {
    security::rescan(&db, &id)
}

#[tauri::command]
async fn trusted_list(db: State<'_, Db>) -> Result<Vec<TrustedKey>, String> {
    db.trusted_list()
}

#[tauri::command]
async fn trusted_add(
    db: State<'_, Db>,
    name: String,
    public_key: String,
) -> Result<TrustedKey, String> {
    security::trust_add(&db, &name, &public_key)
}

#[tauri::command]
async fn trusted_remove(db: State<'_, Db>, key_id: String) -> Result<(), String> {
    security::trust_remove(&db, &key_id)
}

#[tauri::command]
async fn audit_recent(db: State<'_, Db>, limit: u32) -> Result<Vec<AuditRow>, String> {
    db.audit_recent(limit)
}

// ---------------- المشرف (مخفي) ----------------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AdminInfo {
    version: String,
    data_dir: String,
    tools_dir: String,
    studio_dir: String,
    db_bytes: u64,
    counts: Vec<(String, i64)>,
}

#[tauri::command]
async fn admin_probe(st: State<'_, admin::AdminState>, secret: String) -> Result<bool, String> {
    let st = st.inner().clone();
    tauri::async_runtime::spawn_blocking(move || st.probe(&secret))
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn admin_unlock(
    st: State<'_, admin::AdminState>,
    db: State<'_, Db>,
    secret: String,
    name: String,
    year: String,
) -> Result<(), String> {
    let h = st.inner().clone();
    let r = tauri::async_runtime::spawn_blocking(move || h.unlock(&secret, &name, &year))
        .await
        .map_err(|e| e.to_string())?;
    match &r {
        Ok(()) => {
            let _ = db.audit_add("admin_unlock", "admin", "نجاح");
        }
        Err(_) => {
            let _ = db.audit_add("admin_unlock_fail", "admin", "محاولة فاشلة");
        }
    }
    r
}

#[tauri::command]
async fn admin_lock(st: State<'_, admin::AdminState>) -> Result<(), String> {
    st.lock();
    Ok(())
}

#[tauri::command]
async fn admin_status(st: State<'_, admin::AdminState>) -> Result<bool, String> {
    Ok(st.is_unlocked())
}

#[tauri::command]
async fn admin_info(
    st: State<'_, admin::AdminState>,
    db: State<'_, Db>,
    tools: State<'_, ToolsDir>,
    studio: State<'_, StudioDir>,
) -> Result<AdminInfo, String> {
    st.require()?;
    let data = tools
        .0
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_default();
    let db_bytes = std::fs::metadata(data.join("terminal-ar.db"))
        .map(|m| m.len())
        .unwrap_or(0);
    let mut counts = Vec::new();
    for t in [
        "tools",
        "tool_log",
        "history",
        "sessions",
        "favorites",
        "trusted_keys",
        "audit_log",
    ] {
        counts.push((t.to_string(), db.count_rows(t)?));
    }
    Ok(AdminInfo {
        version: env!("CARGO_PKG_VERSION").to_string(),
        data_dir: data.display().to_string(),
        tools_dir: tools.0.display().to_string(),
        studio_dir: studio.0.display().to_string(),
        db_bytes,
        counts,
    })
}

#[tauri::command]
async fn admin_open_data_dir(
    st: State<'_, admin::AdminState>,
    tools: State<'_, ToolsDir>,
) -> Result<(), String> {
    st.require()?;
    let data = tools.0.parent().ok_or("مسار غير معروف")?.to_path_buf();
    #[cfg(windows)]
    {
        std::process::Command::new("explorer")
            .arg(&data)
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let _ = data;
        Err("فتح المجلد مدعوم على ويندوز فقط حالياً".into())
    }
}

#[tauri::command]
async fn admin_rescan_all(
    st: State<'_, admin::AdminState>,
    db: State<'_, Db>,
) -> Result<Vec<(String, String)>, String> {
    st.require()?;
    let mut out = Vec::new();
    for t in db.tools_list()? {
        let r = match security::rescan(&db, &t.id) {
            Ok(rep) => rep.risk,
            Err(e) => format!("خطأ: {e}"),
        };
        out.push((t.id, r));
    }
    let _ = db.audit_add("admin_rescan_all", "admin", &format!("{} أداة", out.len()));
    Ok(out)
}

#[tauri::command]
async fn admin_audit(
    st: State<'_, admin::AdminState>,
    db: State<'_, Db>,
    limit: u32,
) -> Result<Vec<AuditRow>, String> {
    st.require()?;
    db.audit_recent(limit.min(1000))
}

// ---------------- Studio (المرحلة 5) ----------------

/// مجلد مشاريع الاستوديو داخل بيانات التطبيق.
struct StudioDir(PathBuf);

#[tauri::command]
async fn studio_projects(dir: State<'_, StudioDir>) -> Result<Vec<ProjectInfo>, String> {
    studio::list(&dir.0)
}

#[tauri::command]
async fn studio_create(
    dir: State<'_, StudioDir>,
    id: String,
    name: String,
) -> Result<ProjectInfo, String> {
    studio::create(&dir.0, &id, &name)
}

#[tauri::command]
async fn studio_import(
    dir: State<'_, StudioDir>,
    data: String,
    id: Option<String>,
) -> Result<ProjectInfo, String> {
    let bytes = B64
        .decode(data.as_bytes())
        .map_err(|_| "بيانات الملف غير صالحة".to_string())?;
    studio::import_zip(&dir.0, &bytes, id.as_deref())
}

#[tauri::command]
async fn studio_delete(dir: State<'_, StudioDir>, id: String) -> Result<(), String> {
    studio::delete(&dir.0, &id)
}

#[tauri::command]
async fn studio_tree(dir: State<'_, StudioDir>, id: String) -> Result<Vec<TreeEntry>, String> {
    studio::tree(&dir.0, &id)
}

#[tauri::command]
async fn studio_read(
    dir: State<'_, StudioDir>,
    id: String,
    path: String,
) -> Result<String, String> {
    studio::read_file(&dir.0, &id, &path)
}

#[tauri::command]
async fn studio_write(
    dir: State<'_, StudioDir>,
    id: String,
    path: String,
    content: String,
) -> Result<(), String> {
    studio::write_file(&dir.0, &id, &path, &content)
}

#[tauri::command]
async fn studio_mkdir(dir: State<'_, StudioDir>, id: String, path: String) -> Result<(), String> {
    studio::make_dir(&dir.0, &id, &path)
}

#[tauri::command]
async fn studio_delete_path(
    dir: State<'_, StudioDir>,
    id: String,
    path: String,
) -> Result<(), String> {
    studio::delete_path(&dir.0, &id, &path)
}

#[tauri::command]
async fn studio_rename(
    dir: State<'_, StudioDir>,
    id: String,
    from: String,
    to: String,
) -> Result<(), String> {
    studio::rename_path(&dir.0, &id, &from, &to)
}

#[tauri::command]
async fn studio_validate(dir: State<'_, StudioDir>, id: String) -> Result<ValidateReport, String> {
    studio::validate(&dir.0, &id, env!("CARGO_PKG_VERSION"))
}

#[tauri::command]
async fn studio_build(dir: State<'_, StudioDir>, id: String) -> Result<BuildOutcome, String> {
    studio::build(&dir.0, &id, env!("CARGO_PKG_VERSION"))
}

#[tauri::command]
async fn studio_test(dir: State<'_, StudioDir>, id: String) -> Result<TestReport, String> {
    studio::test(&dir.0, &id, env!("CARGO_PKG_VERSION"))
}

#[tauri::command]
async fn studio_install(
    db: State<'_, Db>,
    studio_dir: State<'_, StudioDir>,
    tools: State<'_, ToolsDir>,
    id: String,
) -> Result<InstallOutcome, String> {
    studio::install(&studio_dir.0, &db, &tools.0, &id, env!("CARGO_PKG_VERSION"))
}

#[tauri::command]
async fn studio_format(dir: State<'_, StudioDir>, id: String) -> Result<(), String> {
    studio::format_manifest(&dir.0, &id)
}

#[tauri::command]
async fn studio_check_text(text: String) -> Result<TextCheck, String> {
    Ok(studio::check_text(&text, env!("CARGO_PKG_VERSION")))
}

#[tauri::command]
async fn tool_install_text(
    db: State<'_, Db>,
    tools: State<'_, ToolsDir>,
    text: String,
) -> Result<InstallOutcome, String> {
    studio::install_text(&db, &tools.0, &text, env!("CARGO_PKG_VERSION"))
}

#[tauri::command]
async fn studio_open_tool(
    studio_dir: State<'_, StudioDir>,
    tools: State<'_, ToolsDir>,
    id: String,
) -> Result<ProjectInfo, String> {
    studio::open_tool(&studio_dir.0, &tools.0, &id)
}

#[tauri::command]
async fn studio_runtimes() -> Result<Runtimes, String> {
    tauri::async_runtime::spawn_blocking(studio::runtimes)
        .await
        .map_err(|e| e.to_string())
}

pub fn run() {
    let app = tauri::Builder::default()
        .manage(TerminalEngine::new())
        .manage(admin::AdminState::default())
        .setup(|app| {
            let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
            std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
            let db = Db::open(&dir.join("terminal-ar.db"))?;
            if let Err(e) = plugin::install_builtins(&db, env!("CARGO_PKG_VERSION")) {
                eprintln!("builtin tools registration failed: {e}");
            }
            let tools_dir = dir.join("tools");
            std::fs::create_dir_all(&tools_dir).map_err(|e| e.to_string())?;
            app.manage(ToolsDir(tools_dir));
            let studio_dir = dir.join("studio");
            std::fs::create_dir_all(&studio_dir).map_err(|e| e.to_string())?;
            app.manage(StudioDir(studio_dir));
            app.manage(db);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            app_info,
            terminal_open,
            terminal_write,
            terminal_resize,
            terminal_kill,
            terminal_close,
            setting_get,
            setting_set,
            favorites_list,
            favorite_toggle,
            history_add,
            history_recent,
            history_frequent,
            history_clear,
            sessions_recent,
            tools_list,
            tool_install,
            tool_uninstall,
            tool_set_enabled,
            tool_log_add,
            tool_log_recent,
            tool_security,
            tool_set_grants,
            tool_plan_run,
            tool_run_commit,
            tool_rescan,
            trusted_list,
            trusted_add,
            trusted_remove,
            audit_recent,
            admin_probe,
            admin_unlock,
            admin_lock,
            admin_status,
            admin_info,
            admin_open_data_dir,
            admin_rescan_all,
            admin_audit,
            studio_projects,
            studio_create,
            studio_import,
            studio_delete,
            studio_tree,
            studio_read,
            studio_write,
            studio_mkdir,
            studio_delete_path,
            studio_rename,
            studio_validate,
            studio_build,
            studio_test,
            studio_install,
            studio_runtimes,
            studio_format,
            studio_check_text,
            tool_install_text,
            studio_open_tool
        ])
        .build(tauri::generate_context!())
        .expect("failed to build Terminal AR");

    app.run(|handle, event| {
        if let tauri::RunEvent::Exit = event {
            handle.state::<TerminalEngine>().close_all();
        }
    });
}
