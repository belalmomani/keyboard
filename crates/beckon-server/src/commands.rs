//! Commands the phone asks the PC to run and be told about (`RunCommand`).
//!
//! UDP may repeat a datagram, and a phone that hears nothing sends its request again. A key press or
//! a "lock the screen" must not happen twice because of that, so every request id is run once: while
//! it runs, repeats are ignored, and afterwards repeats are answered with the same result.

use crate::input::apply;
use beckon_platform::{InputBackend, PlatformError};
use beckon_protocol::{ClientMessage, CommandStatus, RemoteCommand};
use std::collections::{HashMap, VecDeque};

/// How many finished requests are remembered per phone.
const REMEMBERED: usize = 64;

/// What to do with a request that arrived.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Begin {
    /// New: run it.
    Run,
    /// It is running now; its answer will come.
    InFlight,
    /// Already done: answer again with this, and do not run it.
    Repeat(CommandStatus),
}

/// The requests of one phone.
#[derive(Debug, Default)]
pub struct CommandLog {
    running: Vec<u32>,
    finished: HashMap<u32, CommandStatus>,
    order: VecDeque<u32>,
}

impl CommandLog {
    pub fn begin(&mut self, request_id: u32) -> Begin {
        if let Some(status) = self.finished.get(&request_id) {
            return Begin::Repeat(*status);
        }
        if self.running.contains(&request_id) {
            return Begin::InFlight;
        }
        self.running.push(request_id);
        Begin::Run
    }

    pub fn finish(&mut self, request_id: u32, status: CommandStatus) {
        self.running.retain(|id| *id != request_id);
        if self.finished.insert(request_id, status).is_none() {
            self.order.push_back(request_id);
        }
        while self.order.len() > REMEMBERED {
            if let Some(old) = self.order.pop_front() {
                self.finished.remove(&old);
            }
        }
    }
}

/// Runs `command` on a backend. `Done` means the system took every step.
pub fn run_command(backend: &mut dyn InputBackend, command: RemoteCommand) -> CommandStatus {
    let result: Result<(), PlatformError> = match command {
        RemoteCommand::Chord { mods, usage } => backend
            .key(usage, mods, true)
            .and_then(|()| backend.key(usage, mods, false)),
        RemoteCommand::Media(action) => {
            apply(backend, &ClientMessage::MediaCmd(action)).unwrap_or(Ok(()))
        }
        RemoteCommand::Volume(direction) => {
            apply(backend, &ClientMessage::VolumeCmd(direction)).unwrap_or(Ok(()))
        }
        RemoteCommand::System(action) => {
            apply(backend, &ClientMessage::LaunchAction(action)).unwrap_or(Ok(()))
        }
        RemoteCommand::LockScreen => apply(backend, &ClientMessage::LockScreen).unwrap_or(Ok(())),
    };
    match result {
        Ok(()) => CommandStatus::Done,
        Err(_) => CommandStatus::Failed,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use beckon_platform::{InputCall, RecordingBackend};
    use beckon_protocol::{modifiers, MediaAction};

    #[test]
    fn a_request_runs_once_and_repeats_get_the_same_answer() {
        let mut log = CommandLog::default();
        assert_eq!(log.begin(1), Begin::Run);
        assert_eq!(log.begin(1), Begin::InFlight);
        log.finish(1, CommandStatus::Done);
        assert_eq!(log.begin(1), Begin::Repeat(CommandStatus::Done));
        assert_eq!(log.begin(2), Begin::Run);
    }

    #[test]
    fn a_failure_is_remembered_as_a_failure() {
        let mut log = CommandLog::default();
        assert_eq!(log.begin(5), Begin::Run);
        log.finish(5, CommandStatus::Failed);
        assert_eq!(log.begin(5), Begin::Repeat(CommandStatus::Failed));
    }

    #[test]
    fn only_the_latest_requests_are_remembered() {
        let mut log = CommandLog::default();
        for id in 0..(REMEMBERED as u32 + 10) {
            assert_eq!(log.begin(id), Begin::Run);
            log.finish(id, CommandStatus::Done);
        }
        assert_eq!(log.finished.len(), REMEMBERED);
        assert_eq!(log.begin(0), Begin::Run, "the oldest is forgotten");
        assert_eq!(
            log.begin(REMEMBERED as u32 + 9),
            Begin::Repeat(CommandStatus::Done)
        );
    }

    #[test]
    fn a_chord_presses_and_releases_with_its_modifiers() {
        let mut backend = RecordingBackend::new();
        let calls = backend.calls();
        let status = run_command(
            &mut backend,
            RemoteCommand::Chord {
                mods: modifiers::LALT,
                usage: 0x2B,
            },
        );
        assert_eq!(status, CommandStatus::Done);
        assert_eq!(
            *calls.lock().unwrap(),
            vec![
                InputCall::Key {
                    usage: 0x2B,
                    mods: modifiers::LALT,
                    pressed: true
                },
                InputCall::Key {
                    usage: 0x2B,
                    mods: modifiers::LALT,
                    pressed: false
                },
            ]
        );
    }

    #[test]
    fn media_and_lock_reach_the_backend() {
        let mut backend = RecordingBackend::new();
        let calls = backend.calls();
        assert_eq!(
            run_command(&mut backend, RemoteCommand::Media(MediaAction::Next)),
            CommandStatus::Done
        );
        assert_eq!(
            run_command(&mut backend, RemoteCommand::LockScreen),
            CommandStatus::Done
        );
        assert_eq!(
            *calls.lock().unwrap(),
            vec![InputCall::Media(MediaAction::Next), InputCall::LockScreen]
        );
    }

    struct Refusing;
    impl InputBackend for Refusing {
        fn name(&self) -> &'static str {
            "refusing"
        }
        fn mouse_move(&mut self, _: i16, _: i16) -> beckon_platform::Result<()> {
            Ok(())
        }
        fn mouse_button(
            &mut self,
            _: beckon_protocol::MouseButtonKind,
            _: bool,
        ) -> beckon_platform::Result<()> {
            Ok(())
        }
        fn scroll(&mut self, _: i16) -> beckon_platform::Result<()> {
            Ok(())
        }
        fn key(&mut self, _: u16, _: u8, _: bool) -> beckon_platform::Result<()> {
            Err(PlatformError::Os("no".into()))
        }
        fn text(&mut self, _: &str) -> beckon_platform::Result<()> {
            Ok(())
        }
        fn media(&mut self, _: MediaAction) -> beckon_platform::Result<()> {
            Err(PlatformError::Os("no".into()))
        }
        fn volume(&mut self, _: beckon_protocol::VolumeDirection) -> beckon_platform::Result<()> {
            Ok(())
        }
        fn launch(&mut self, _: beckon_protocol::SystemAction) -> beckon_platform::Result<()> {
            Ok(())
        }
        fn lock_screen(&mut self) -> beckon_platform::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn when_the_system_refuses_the_answer_is_failed_not_done() {
        let mut backend = Refusing;
        assert_eq!(
            run_command(&mut backend, RemoteCommand::Chord { mods: 0, usage: 4 }),
            CommandStatus::Failed
        );
        assert_eq!(
            run_command(&mut backend, RemoteCommand::Media(MediaAction::Stop)),
            CommandStatus::Failed
        );
    }
}
