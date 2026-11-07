//! Terminal Engine — جلسات طرفية حقيقية عبر pseudo-terminal.
//! على Windows يستخدم portable-pty واجهة ConPTY الأصلية، ولا توجد أي محاكاة للأوامر.

use portable_pty::{native_pty_system, ChildKiller, CommandBuilder, MasterPty, PtySize};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

/// حدث صادر من جلسة.
#[derive(Debug, Clone)]
pub enum SessionEvent {
    Output {
        id: String,
        data: Vec<u8>,
    },
    /// code = None إذا تعذّر الحصول على رمز الخروج.
    Exit {
        id: String,
        code: Option<u32>,
    },
}

pub type Sink = Arc<dyn Fn(SessionEvent) + Send + Sync>;

/// وصف العملية المراد تشغيلها داخل الطرفية.
#[derive(Debug, Clone)]
pub struct SpawnSpec {
    pub program: String,
    pub args: Vec<String>,
    pub cwd: Option<PathBuf>,
}

struct Session {
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    killer: Box<dyn ChildKiller + Send + Sync>,
}

pub struct TerminalEngine {
    sessions: Mutex<HashMap<String, Session>>,
    counter: AtomicU64,
}

const LOCK_ERR: &str = "internal lock error";

impl TerminalEngine {
    pub fn new() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            counter: AtomicU64::new(0),
        }
    }

    /// يفتح جلسة جديدة ويعيد معرّفها.
    pub fn open(
        &self,
        spec: &SpawnSpec,
        cols: u16,
        rows: u16,
        sink: Sink,
    ) -> Result<String, String> {
        let pair = native_pty_system()
            .openpty(PtySize {
                rows: rows.max(2),
                cols: cols.max(2),
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| format!("failed to open pty: {e}"))?;

        let mut cmd = CommandBuilder::new(&spec.program);
        cmd.args(&spec.args);
        if let Some(cwd) = &spec.cwd {
            cmd.cwd(cwd);
        }
        cmd.env("TERM", "xterm-256color");

        let mut child = pair
            .slave
            .spawn_command(cmd)
            .map_err(|e| format!("failed to start '{}': {e}", spec.program))?;
        drop(pair.slave);

        let killer = child.clone_killer();
        let mut reader = pair
            .master
            .try_clone_reader()
            .map_err(|e| format!("failed to clone reader: {e}"))?;
        let writer = pair
            .master
            .take_writer()
            .map_err(|e| format!("failed to take writer: {e}"))?;

        let id = format!("s{}", self.counter.fetch_add(1, Ordering::SeqCst) + 1);

        // خيط القراءة: يمرر البايتات الخام كما هي (الفك يتم في الواجهة).
        {
            let sink = sink.clone();
            let id = id.clone();
            thread::spawn(move || {
                let mut buf = [0u8; 8192];
                loop {
                    match reader.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => sink(SessionEvent::Output {
                            id: id.clone(),
                            data: buf[..n].to_vec(),
                        }),
                    }
                }
            });
        }

        // خيط الانتظار: يرسل رمز الخروج الفعلي. على ConPTY قد لا يصل EOF للقارئ
        // عند انتهاء العملية، لذلك لا نعتمد عليه لاكتشاف الانتهاء.
        {
            let id = id.clone();
            thread::spawn(move || {
                let code = child.wait().ok().map(|s| s.exit_code());
                // مهلة قصيرة ليصل آخر خرج قبل حدث الانتهاء.
                thread::sleep(Duration::from_millis(150));
                sink(SessionEvent::Exit { id, code });
            });
        }

        self.sessions
            .lock()
            .map_err(|_| LOCK_ERR.to_string())?
            .insert(
                id.clone(),
                Session {
                    master: pair.master,
                    writer,
                    killer,
                },
            );
        Ok(id)
    }

    pub fn write(&self, id: &str, data: &[u8]) -> Result<(), String> {
        let mut map = self.sessions.lock().map_err(|_| LOCK_ERR.to_string())?;
        let s = map
            .get_mut(id)
            .ok_or_else(|| format!("unknown session {id}"))?;
        s.writer.write_all(data).map_err(|e| e.to_string())?;
        s.writer.flush().map_err(|e| e.to_string())
    }

    pub fn resize(&self, id: &str, cols: u16, rows: u16) -> Result<(), String> {
        let map = self.sessions.lock().map_err(|_| LOCK_ERR.to_string())?;
        let s = map.get(id).ok_or_else(|| format!("unknown session {id}"))?;
        s.master
            .resize(PtySize {
                rows: rows.max(2),
                cols: cols.max(2),
                pixel_width: 0,
                pixel_height: 0,
            })
            .map_err(|e| e.to_string())
    }

    /// يوقف العملية الجارية (الجلسة تبقى مسجلة حتى close).
    pub fn kill(&self, id: &str) -> Result<(), String> {
        let mut map = self.sessions.lock().map_err(|_| LOCK_ERR.to_string())?;
        let s = map
            .get_mut(id)
            .ok_or_else(|| format!("unknown session {id}"))?;
        match s.killer.kill() {
            Ok(()) => Ok(()),
            // portable-pty على Windows قد يعيد "نجحت العملية (os error 0)" كخطأ رغم نجاح الإيقاف
            Err(e) if e.raw_os_error() == Some(0) => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }

    /// يغلق الجلسة ويحرر مواردها.
    pub fn close(&self, id: &str) -> Result<(), String> {
        let session = self
            .sessions
            .lock()
            .map_err(|_| LOCK_ERR.to_string())?
            .remove(id);
        match session {
            Some(mut s) => {
                let _ = s.killer.kill(); // قد تكون انتهت أصلاً
                drop(s);
                Ok(())
            }
            None => Err(format!("unknown session {id}")),
        }
    }

    pub fn close_all(&self) {
        let drained: Vec<Session> = match self.sessions.lock() {
            Ok(mut m) => m.drain().map(|(_, v)| v).collect(),
            Err(_) => return,
        };
        for mut s in drained {
            let _ = s.killer.kill();
        }
    }
}

impl Default for TerminalEngine {
    fn default() -> Self {
        Self::new()
    }
}

/// المجلد الافتراضي لبدء الجلسات: مجلد المستخدم.
pub fn default_cwd() -> Option<PathBuf> {
    for var in ["USERPROFILE", "HOME"] {
        if let Some(v) = std::env::var_os(var) {
            let p = PathBuf::from(v);
            if p.is_dir() {
                return Some(p);
            }
        }
    }
    None
}

/// يحوّل اسم الصدفة إلى أمر تشغيل فعلي. الأوامر التي يكتبها المستخدم لا تُعدَّل.
pub fn shell_spec(kind: &str, cwd: Option<PathBuf>) -> Result<SpawnSpec, String> {
    match kind {
        #[cfg(windows)]
        "powershell" => Ok(SpawnSpec {
            program: "powershell.exe".into(),
            args: vec![
                "-NoLogo".into(),
                "-NoExit".into(),
                "-Command".into(),
                // ترميز الخرج UTF-8 ليظهر Unicode والعربية بشكل صحيح
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8".into(),
            ],
            cwd,
        }),
        #[cfg(windows)]
        "cmd" => Ok(SpawnSpec {
            program: "cmd.exe".into(),
            args: vec!["/K".into(), "chcp 65001>nul".into()],
            cwd,
        }),
        #[cfg(not(windows))]
        "default" => Ok(SpawnSpec {
            program: std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".into()),
            args: vec![],
            cwd,
        }),
        other => Err(format!("unsupported shell on this platform: {other}")),
    }
}

// ---------------------------------------------------------------------------
// الاختبارات: تعمل على Windows فقط (ConPTY) وتُشغَّل في GitHub Actions.
// ---------------------------------------------------------------------------
#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use std::sync::mpsc::{channel, Receiver};
    use std::time::Instant;

    const T: Duration = Duration::from_secs(30);

    fn strip_ansi(s: &str) -> String {
        let mut out = String::new();
        let mut it = s.chars().peekable();
        while let Some(c) = it.next() {
            if c == '\u{1b}' {
                match it.peek() {
                    Some('[') => {
                        it.next();
                        while let Some(&n) = it.peek() {
                            it.next();
                            if ('@'..='~').contains(&n) {
                                break;
                            }
                        }
                    }
                    Some(']') => {
                        it.next();
                        while let Some(n) = it.next() {
                            if n == '\u{7}' {
                                break;
                            }
                            if n == '\u{1b}' {
                                it.next();
                                break;
                            }
                        }
                    }
                    Some(_) => {
                        it.next();
                    }
                    None => {}
                }
            } else if c != '\r' {
                out.push(c);
            }
        }
        out
    }

    struct Capture<'a> {
        engine: &'a TerminalEngine,
        id: String,
        rx: Receiver<SessionEvent>,
        bytes: Vec<u8>,
        exit: Option<Option<u32>>,
        dsr_replied: usize,
    }

    impl<'a> Capture<'a> {
        fn start(engine: &'a TerminalEngine, spec: &SpawnSpec) -> Self {
            let (tx, rx) = channel();
            let tx = Mutex::new(tx);
            let sink: Sink = Arc::new(move |ev| {
                let _ = tx.lock().unwrap().send(ev);
            });
            let id = engine.open(spec, 200, 40, sink).expect("open session");
            Capture {
                engine,
                id,
                rx,
                bytes: vec![],
                exit: None,
                dsr_replied: 0,
            }
        }

        /// ConPTY قد يطلب موضع المؤشر (ESC[6n)؛ xterm.js يردّ تلقائياً، وهنا نردّ نحن.
        fn reply_dsr(&mut self) {
            let raw = String::from_utf8_lossy(&self.bytes).to_string();
            let n = raw.matches("\u{1b}[6n").count();
            while self.dsr_replied < n {
                let _ = self.engine.write(&self.id, b"\x1b[1;1R");
                self.dsr_replied += 1;
            }
        }

        fn pump(&mut self, timeout: Duration) {
            match self.rx.recv_timeout(timeout) {
                Ok(SessionEvent::Output { data, .. }) => {
                    self.bytes.extend(data);
                    self.reply_dsr();
                }
                Ok(SessionEvent::Exit { code, .. }) => self.exit = Some(code),
                Err(_) => {}
            }
        }

        fn text(&self) -> String {
            strip_ansi(&String::from_utf8_lossy(&self.bytes))
        }

        fn wait_for(&mut self, needle: &str, timeout: Duration) -> bool {
            let start = Instant::now();
            loop {
                if self.text().contains(needle) {
                    return true;
                }
                match timeout.checked_sub(start.elapsed()) {
                    None => return false,
                    Some(left) => self.pump(left.min(Duration::from_millis(200))),
                }
            }
        }

        fn wait_exit(&mut self, timeout: Duration) -> Option<Option<u32>> {
            let start = Instant::now();
            loop {
                if self.exit.is_some() {
                    return self.exit;
                }
                match timeout.checked_sub(start.elapsed()) {
                    None => return None,
                    Some(left) => self.pump(left.min(Duration::from_millis(200))),
                }
            }
        }
    }

    fn ps(command: &str, cwd: Option<PathBuf>) -> SpawnSpec {
        SpawnSpec {
            program: "powershell.exe".into(),
            args: vec![
                "-NoLogo".into(),
                "-NoProfile".into(),
                "-Command".into(),
                command.into(),
            ],
            cwd,
        }
    }

    // 1) فتح جلسة PowerShell حقيقية + 2) تنفيذ أمر فعلي + 3) نتيجة صحيحة
    #[test]
    fn t01_powershell_runs_real_command() {
        let e = TerminalEngine::new();
        let mut c = Capture::start(&e, &ps("Write-Output ('TAR_'+(6*7))", None));
        assert!(c.wait_for("TAR_42", T), "output was: {:?}", c.text());
        assert_eq!(c.wait_exit(T), Some(Some(0)), "output: {:?}", c.text());
    }

    // 5) عدة أوامر متتابعة في جلسة تفاعلية (عبر shell_spec الحقيقي)
    #[test]
    fn t02_interactive_sequential_commands() {
        let e = TerminalEngine::new();
        let spec = shell_spec("powershell", default_cwd()).unwrap();
        let mut c = Capture::start(&e, &spec);
        assert!(c.wait_for("PS ", T), "no prompt; output: {:?}", c.text());
        e.write(&c.id, b"Write-Output ('TAR_'+(6*7))\r").unwrap();
        assert!(c.wait_for("TAR_42", T), "output: {:?}", c.text());
        e.write(&c.id, b"Write-Output ('TAR_'+(7*7))\r").unwrap();
        assert!(c.wait_for("TAR_49", T), "output: {:?}", c.text());
        e.write(&c.id, b"exit 0\r").unwrap();
        assert_eq!(c.wait_exit(T), Some(Some(0)), "output: {:?}", c.text());
    }

    // 4) التعامل مع خطأ فعلي ورمز خروج
    #[test]
    fn t03_real_error_and_exit_code() {
        let e = TerminalEngine::new();
        let mut c = Capture::start(
            &e,
            &ps("Get-Item C:\\tar_missing_path_xyz -ErrorAction Stop", None),
        );
        assert!(
            c.wait_for("tar_missing_path_xyz", T),
            "output: {:?}",
            c.text()
        );
        assert_eq!(c.wait_exit(T), Some(Some(1)), "output: {:?}", c.text());

        let mut c2 = Capture::start(&e, &ps("exit 7", None));
        assert_eq!(c2.wait_exit(T), Some(Some(7)), "output: {:?}", c2.text());
    }

    // CMD حقيقي
    #[test]
    fn t04_cmd_runs_real_command() {
        let e = TerminalEngine::new();
        let spec = SpawnSpec {
            program: "cmd.exe".into(),
            args: vec!["/C".into(), "echo TAR_CMD & exit /b 3".into()],
            cwd: None,
        };
        let mut c = Capture::start(&e, &spec);
        assert!(c.wait_for("TAR_CMD", T), "output: {:?}", c.text());
        assert_eq!(c.wait_exit(T), Some(Some(3)), "output: {:?}", c.text());
    }

    // 6) إيقاف عملية جارية
    #[test]
    fn t05_kill_running_process() {
        let e = TerminalEngine::new();
        let mut c = Capture::start(&e, &ps("Start-Sleep -Seconds 120", None));
        thread::sleep(Duration::from_secs(2));
        let t0 = Instant::now();
        e.kill(&c.id).unwrap();
        assert!(
            c.wait_exit(Duration::from_secs(15)).is_some(),
            "process did not stop"
        );
        assert!(t0.elapsed() < Duration::from_secs(15));
    }

    // 7) مسار يحتوي مسافات
    #[test]
    fn t06_path_with_spaces() {
        let dir = std::env::temp_dir().join("Terminal AR spaced dir");
        std::fs::create_dir_all(&dir).unwrap();
        let e = TerminalEngine::new();
        let mut c = Capture::start(&e, &ps("(Get-Location).Path", Some(dir.clone())));
        assert!(
            c.wait_for("Terminal AR spaced dir", T),
            "output: {:?}",
            c.text()
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    // 8) النص العربي (Unicode) يمر سليماً عبر ConPTY
    #[test]
    fn t07_arabic_text() {
        let e = TerminalEngine::new();
        let mut c = Capture::start(&e, &ps("Write-Output 'مرحبا'; Write-Output 'العالم'", None));
        assert!(c.wait_for("مرحبا", T), "output: {:?}", c.text());
        assert!(c.wait_for("العالم", T), "output: {:?}", c.text());
    }

    // أمر طويل (4000 حرف)
    #[test]
    fn t08_long_command() {
        let e = TerminalEngine::new();
        let long = "z".repeat(4000);
        let cmd = format!("$s='{long}'; Write-Output ('TAR_LEN_'+$s.Length)");
        let mut c = Capture::start(&e, &ps(&cmd, None));
        assert!(c.wait_for("TAR_LEN_4000", T), "output: {:?}", c.text());
    }

    // تغيير الحجم والإغلاق ومعرّف غير موجود
    #[test]
    fn t09_resize_close_and_unknown_session() {
        let e = TerminalEngine::new();
        let c = Capture::start(&e, &ps("Start-Sleep -Seconds 60", None));
        assert!(e.resize(&c.id, 120, 30).is_ok());
        assert!(e.close(&c.id).is_ok());
        assert!(e.write(&c.id, b"x").is_err());
        assert!(e.write("nope", b"x").is_err());
    }
}
