//! Long texts sent by the phone in pieces: putting them back together, typing them, and remembering
//! how each one ended.
//!
//! A phone can send a whole paragraph (a saved phrase, an email) in one go. It does not fit in one
//! UDP datagram, so the phone cuts it into pieces (see [`beckon_protocol::split_text_block`]), and
//! the PC puts them back together here. Datagrams get lost and repeated, so everything here is
//! built around two promises:
//!
//!  * **A text is typed once.** The phone sends the pieces again when no answer comes. Pieces that
//!    were already here change nothing, and a text that was already typed is answered again, not
//!    typed again.
//!  * **The answer is the truth.** `Done` is sent only after the operating system has taken the
//!    text, and says how many characters were typed, so that a failure part of the way through can
//!    be told from a success.
//!
//! This module is pure bookkeeping (the caller passes the time in), so every rule is a plain test.

use beckon_protocol::{CommandStatus, MAX_TEXT_BLOCK_CHUNKS};
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// How many texts one phone may have half-received at once.
pub const MAX_IN_FLIGHT: usize = 4;
/// A text whose pieces stopped arriving is forgotten after this long. (The phone sends them again.)
pub const ASSEMBLY_TIMEOUT: Duration = Duration::from_secs(20);
/// How many finished texts are remembered, to answer a phone that asks again.
const REMEMBERED: usize = 32;
/// Characters typed in one call to the operating system. Small enough that cancelling is quick and
/// that one call cannot overflow what the system accepts at once.
const TYPING_RUN_CHARS: usize = 200;

/// The HID usage of the Enter key.
pub const KEY_ENTER: u16 = 0x28;
/// The HID usage of the Tab key.
pub const KEY_TAB: u16 = 0x2B;

/// What to do with a piece that has just arrived.
#[derive(Debug)]
pub enum Accepted {
    /// Kept. More pieces are needed.
    Waiting,
    /// This text is already being typed. Nothing to do: the result will come.
    Busy,
    /// This text was already finished (or cancelled). Say so again; do not type it again.
    Repeat(CommandStatus, u32),
    /// Refused: pieces that contradict each other, or too many texts at once.
    Rejected,
    /// Every piece is here. Type `text`, and stop if `cancel` is set.
    Complete {
        text: String,
        cancel: Arc<AtomicBool>,
    },
}

struct Assembling {
    total: u16,
    parts: Vec<Option<String>>,
    have: u16,
    started: Instant,
    typing: bool,
    cancel: Arc<AtomicBool>,
}

#[derive(Clone, Copy)]
struct Finished {
    id: u32,
    status: CommandStatus,
    value: u32,
}

/// The long texts of one phone.
#[derive(Default)]
pub struct BlockTracker {
    in_flight: HashMap<u32, Assembling>,
    finished: VecDeque<Finished>,
}

impl BlockTracker {
    pub fn accept(
        &mut self,
        request_id: u32,
        index: u16,
        total: u16,
        text: String,
        now: Instant,
    ) -> Accepted {
        if let Some(done) = self.finished.iter().find(|f| f.id == request_id) {
            return Accepted::Repeat(done.status, done.value);
        }
        if total == 0 || total > MAX_TEXT_BLOCK_CHUNKS || index >= total {
            return Accepted::Rejected;
        }
        if let Some(block) = self.in_flight.get(&request_id) {
            if block.typing {
                return Accepted::Busy;
            }
            if block.total != total {
                // The pieces do not belong together: drop the lot rather than type a mixture.
                self.in_flight.remove(&request_id);
                return Accepted::Rejected;
            }
        } else if self.in_flight.len() >= MAX_IN_FLIGHT {
            return Accepted::Rejected;
        }

        let block = self
            .in_flight
            .entry(request_id)
            .or_insert_with(|| Assembling {
                total,
                parts: vec![None; usize::from(total)],
                have: 0,
                started: now,
                typing: false,
                cancel: Arc::new(AtomicBool::new(false)),
            });
        let slot = &mut block.parts[usize::from(index)];
        if slot.is_none() {
            *slot = Some(text);
            block.have += 1;
        }
        if block.have < block.total {
            return Accepted::Waiting;
        }

        block.typing = true;
        let text = block.parts.iter_mut().filter_map(Option::take).collect();
        Accepted::Complete {
            text,
            cancel: Arc::clone(&block.cancel),
        }
    }

    /// Typing ended. Remembers the result so that a repeated request is answered, not typed again.
    pub fn finish(&mut self, request_id: u32, status: CommandStatus, value: u32) {
        self.in_flight.remove(&request_id);
        self.remember(request_id, status, value);
    }

    /// The phone asked to stop. Returns the result to send right away when the text had not started
    /// being typed; when it had, typing is told to stop and its own result follows.
    pub fn cancel(&mut self, request_id: u32) -> Option<(CommandStatus, u32)> {
        if let Some(done) = self.finished.iter().find(|f| f.id == request_id) {
            return Some((done.status, done.value));
        }
        if let Some(block) = self.in_flight.get(&request_id) {
            if block.typing {
                block.cancel.store(true, Ordering::SeqCst);
                return None;
            }
            self.in_flight.remove(&request_id);
        }
        // Remembered even for an unknown text, so that pieces still on their way cannot start it.
        self.remember(request_id, CommandStatus::Cancelled, 0);
        Some((CommandStatus::Cancelled, 0))
    }

    /// Forgets texts that stopped arriving. Returns how many.
    pub fn expire(&mut self, now: Instant) -> usize {
        let before = self.in_flight.len();
        self.in_flight
            .retain(|_, b| b.typing || now.saturating_duration_since(b.started) < ASSEMBLY_TIMEOUT);
        before - self.in_flight.len()
    }

    fn remember(&mut self, id: u32, status: CommandStatus, value: u32) {
        self.finished.retain(|f| f.id != id);
        if self.finished.len() >= REMEMBERED {
            self.finished.pop_front();
        }
        self.finished.push_back(Finished { id, status, value });
    }
}

/// One thing to do to type a text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Step {
    /// Characters to type as text.
    Text(String),
    /// A key to tap, by HID usage.
    Key(u16),
}

impl Step {
    /// How many characters this step types (a key counts as one).
    fn typed(&self) -> u32 {
        match self {
            Self::Text(text) => text.chars().count() as u32,
            Self::Key(_) => 1,
        }
    }
}

/// Turns a text into what to do on the keyboard. A line break (`\n`, `\r\n` or a lone `\r`) is the
/// Enter key and a tab is the Tab key, because typing those as characters does not do the same in
/// every program. Everything else is typed as it is, in order, so that Arabic, other right-to-left
/// text, marks that change direction and emoji arrive untouched.
pub fn plan_typing(text: &str) -> Vec<Step> {
    let mut steps = Vec::new();
    let mut run = String::new();
    let mut run_chars = 0;

    fn flush(steps: &mut Vec<Step>, run: &mut String, run_chars: &mut usize) {
        if !run.is_empty() {
            steps.push(Step::Text(std::mem::take(run)));
            *run_chars = 0;
        }
    }

    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\r' | '\n' => {
                if c == '\r' && chars.peek() == Some(&'\n') {
                    chars.next();
                }
                flush(&mut steps, &mut run, &mut run_chars);
                steps.push(Step::Key(KEY_ENTER));
            }
            '\t' => {
                flush(&mut steps, &mut run, &mut run_chars);
                steps.push(Step::Key(KEY_TAB));
            }
            other => {
                run.push(other);
                run_chars += 1;
                if run_chars >= TYPING_RUN_CHARS {
                    flush(&mut steps, &mut run, &mut run_chars);
                }
            }
        }
    }
    flush(&mut steps, &mut run, &mut run_chars);
    steps
}

/// How typing a text ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BlockOutcome {
    pub status: CommandStatus,
    /// Characters typed before it ended.
    pub typed: u32,
}

/// Performs `steps` one by one with `perform`, checking `cancel` before each. Stops at the first
/// failure, so that a text is never typed with a piece missing from the middle.
pub fn run_steps<E>(
    steps: &[Step],
    cancel: &AtomicBool,
    mut perform: impl FnMut(&Step) -> Result<(), E>,
) -> BlockOutcome {
    let mut typed = 0;
    for step in steps {
        if cancel.load(Ordering::SeqCst) {
            return BlockOutcome {
                status: CommandStatus::Cancelled,
                typed,
            };
        }
        if perform(step).is_err() {
            return BlockOutcome {
                status: CommandStatus::Failed,
                typed,
            };
        }
        typed += step.typed();
    }
    BlockOutcome {
        status: CommandStatus::Done,
        typed,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use beckon_protocol::split_text_block;

    fn t0() -> Instant {
        Instant::now()
    }

    fn feed(
        tracker: &mut BlockTracker,
        id: u32,
        pieces: &[String],
        order: &[usize],
    ) -> Vec<String> {
        let mut completed = Vec::new();
        for &i in order {
            if let Accepted::Complete { text, .. } =
                tracker.accept(id, i as u16, pieces.len() as u16, pieces[i].clone(), t0())
            {
                completed.push(text);
            }
        }
        completed
    }

    #[test]
    fn pieces_in_any_order_make_the_text_exactly_once() {
        let text = "مرحباً \u{200F}بكم — hello 🚀\n".repeat(120);
        let pieces = split_text_block(&text).unwrap();
        assert!(pieces.len() > 2);

        let forward: Vec<usize> = (0..pieces.len()).collect();
        let backward: Vec<usize> = forward.iter().rev().copied().collect();
        // Every piece sent three times over, interleaved: a phone that retries before an answer.
        let repeated: Vec<usize> = forward
            .iter()
            .chain(&backward)
            .chain(&forward)
            .copied()
            .collect();

        for order in [forward, backward, repeated] {
            let mut tracker = BlockTracker::default();
            let completed = feed(&mut tracker, 7, &pieces, &order);
            assert_eq!(
                completed,
                vec![text.clone()],
                "complete exactly once, text intact"
            );
        }
    }

    #[test]
    fn a_missing_piece_means_waiting_not_typing() {
        let pieces = split_text_block(&"x".repeat(2000)).unwrap();
        let mut tracker = BlockTracker::default();
        let all_but_last: Vec<usize> = (0..pieces.len() - 1).collect();
        assert!(feed(&mut tracker, 1, &pieces, &all_but_last).is_empty());
        assert_eq!(feed(&mut tracker, 1, &pieces, &[pieces.len() - 1]).len(), 1);
    }

    #[test]
    fn a_finished_text_is_answered_again_and_never_typed_again() {
        let pieces = split_text_block("hello").unwrap();
        let mut tracker = BlockTracker::default();
        assert_eq!(feed(&mut tracker, 5, &pieces, &[0]).len(), 1);
        // While it is being typed, a repeated piece changes nothing.
        assert!(matches!(
            tracker.accept(5, 0, 1, "hello".into(), t0()),
            Accepted::Busy
        ));
        tracker.finish(5, CommandStatus::Done, 5);
        match tracker.accept(5, 0, 1, "hello".into(), t0()) {
            Accepted::Repeat(CommandStatus::Done, 5) => {}
            other => panic!("expected a repeat, got {other:?}"),
        }
    }

    #[test]
    fn a_failure_is_remembered_with_how_far_it_got() {
        let mut tracker = BlockTracker::default();
        assert!(matches!(
            tracker.accept(9, 0, 1, "abc".into(), t0()),
            Accepted::Complete { .. }
        ));
        tracker.finish(9, CommandStatus::Failed, 2);
        assert!(matches!(
            tracker.accept(9, 0, 1, "abc".into(), t0()),
            Accepted::Repeat(CommandStatus::Failed, 2)
        ));
    }

    #[test]
    fn pieces_that_contradict_each_other_are_refused_and_dropped() {
        let mut tracker = BlockTracker::default();
        assert!(matches!(
            tracker.accept(3, 0, 2, "a".into(), t0()),
            Accepted::Waiting
        ));
        assert!(matches!(
            tracker.accept(3, 1, 3, "b".into(), t0()),
            Accepted::Rejected
        ));
        // Nothing of the first one is left to be mixed with a later one.
        assert!(matches!(
            tracker.accept(3, 1, 2, "b".into(), t0()),
            Accepted::Waiting
        ));
        for (index, total) in [(0, 0), (2, 2), (0, MAX_TEXT_BLOCK_CHUNKS + 1)] {
            assert!(matches!(
                tracker.accept(4, index, total, "x".into(), t0()),
                Accepted::Rejected
            ));
        }
    }

    #[test]
    fn only_a_few_texts_can_be_half_received_at_once() {
        let mut tracker = BlockTracker::default();
        for id in 0..MAX_IN_FLIGHT as u32 {
            assert!(matches!(
                tracker.accept(id, 0, 2, "a".into(), t0()),
                Accepted::Waiting
            ));
        }
        assert!(matches!(
            tracker.accept(99, 0, 2, "a".into(), t0()),
            Accepted::Rejected
        ));
        // The ones already started carry on.
        assert!(matches!(
            tracker.accept(0, 1, 2, "b".into(), t0()),
            Accepted::Complete { .. }
        ));
    }

    #[test]
    fn texts_that_stopped_arriving_are_forgotten_but_typing_ones_are_not() {
        let start = t0();
        let mut tracker = BlockTracker::default();
        tracker.accept(1, 0, 2, "a".into(), start);
        assert!(matches!(
            tracker.accept(2, 0, 1, "b".into(), start),
            Accepted::Complete { .. }
        ));
        assert_eq!(tracker.expire(start + ASSEMBLY_TIMEOUT / 2), 0);
        assert_eq!(tracker.expire(start + ASSEMBLY_TIMEOUT * 2), 1);
        // The typing one is still there to receive its result.
        assert!(matches!(
            tracker.accept(2, 0, 1, "b".into(), start),
            Accepted::Busy
        ));
    }

    #[test]
    fn cancelling_before_typing_forgets_the_pieces_and_blocks_late_ones() {
        let mut tracker = BlockTracker::default();
        tracker.accept(1, 0, 2, "a".into(), t0());
        assert_eq!(tracker.cancel(1), Some((CommandStatus::Cancelled, 0)));
        assert!(matches!(
            tracker.accept(1, 1, 2, "b".into(), t0()),
            Accepted::Repeat(CommandStatus::Cancelled, 0)
        ));
        // A cancel that overtook the pieces still stops them.
        assert_eq!(tracker.cancel(2), Some((CommandStatus::Cancelled, 0)));
        assert!(matches!(
            tracker.accept(2, 0, 1, "x".into(), t0()),
            Accepted::Repeat(CommandStatus::Cancelled, 0)
        ));
    }

    #[test]
    fn cancelling_while_typing_sets_the_flag_and_leaves_the_answer_to_typing() {
        let mut tracker = BlockTracker::default();
        let Accepted::Complete { cancel, .. } = tracker.accept(1, 0, 1, "abc".into(), t0()) else {
            panic!("expected the text");
        };
        assert!(!cancel.load(Ordering::SeqCst));
        assert_eq!(tracker.cancel(1), None);
        assert!(cancel.load(Ordering::SeqCst));
        tracker.finish(1, CommandStatus::Cancelled, 1);
        assert_eq!(tracker.cancel(1), Some((CommandStatus::Cancelled, 1)));
    }

    #[test]
    fn the_memory_of_finished_texts_is_bounded() {
        let mut tracker = BlockTracker::default();
        for id in 0..1000u32 {
            tracker.finish(id, CommandStatus::Done, 1);
        }
        assert!(tracker.finished.len() <= REMEMBERED);
        // The newest are still remembered.
        assert!(matches!(
            tracker.accept(999, 0, 1, "x".into(), t0()),
            Accepted::Repeat(CommandStatus::Done, 1)
        ));
    }

    // ── Typing plans ────────────────────────────────────────────────────

    #[test]
    fn line_breaks_are_enter_and_tabs_are_tab() {
        assert_eq!(
            plan_typing("a\r\nb\rc\nd\te"),
            vec![
                Step::Text("a".into()),
                Step::Key(KEY_ENTER),
                Step::Text("b".into()),
                Step::Key(KEY_ENTER),
                Step::Text("c".into()),
                Step::Key(KEY_ENTER),
                Step::Text("d".into()),
                Step::Key(KEY_TAB),
                Step::Text("e".into()),
            ]
        );
        assert_eq!(plan_typing(""), vec![]);
        assert_eq!(
            plan_typing("\n\n"),
            vec![Step::Key(KEY_ENTER), Step::Key(KEY_ENTER)]
        );
    }

    /// What a plan types, with the keys written as `\n` and `\t`.
    fn typed_back(steps: &[Step]) -> String {
        steps
            .iter()
            .map(|s| match s {
                Step::Text(t) => t.clone(),
                Step::Key(KEY_ENTER) => "\n".to_owned(),
                Step::Key(KEY_TAB) => "\t".to_owned(),
                Step::Key(other) => panic!("unexpected key {other}"),
            })
            .collect()
    }

    #[test]
    fn a_plan_types_every_character_in_order_for_arabic_and_mixed_text() {
        let text = "السلام عليكم ورحمة الله\n\u{202B}نص (English) ١٢٣\u{202C}\t\u{200F}!\n😀👨\u{200D}👩\u{200D}👧 e\u{301}\n";
        let long = text.repeat(80);
        for sample in [text, long.as_str()] {
            let steps = plan_typing(sample);
            assert_eq!(typed_back(&steps), sample.replace("\r\n", "\n"));
            for step in &steps {
                if let Step::Text(t) = step {
                    assert!(!t.is_empty());
                    assert!(t.chars().count() <= TYPING_RUN_CHARS);
                    assert!(!t.contains(['\n', '\r', '\t']));
                }
            }
        }
    }

    #[test]
    fn a_long_line_is_typed_in_runs_that_do_not_cut_a_character() {
        let line = "ع".repeat(TYPING_RUN_CHARS * 2 + 5);
        let steps = plan_typing(&line);
        assert_eq!(steps.len(), 3);
        assert_eq!(typed_back(&steps), line);
    }

    // ── Running a plan ──────────────────────────────────────────────────

    #[test]
    fn running_a_plan_reports_done_and_how_much_was_typed() {
        let steps = plan_typing("ab\ncd");
        let mut seen = Vec::new();
        let outcome = run_steps(&steps, &AtomicBool::new(false), |s| -> Result<(), ()> {
            seen.push(s.clone());
            Ok(())
        });
        assert_eq!(seen, steps);
        assert_eq!(
            outcome,
            BlockOutcome {
                status: CommandStatus::Done,
                typed: 5
            }
        );
    }

    #[test]
    fn a_failure_stops_the_text_and_says_how_far_it_got() {
        let steps = plan_typing("ab\ncd\nef");
        let mut calls = 0;
        let outcome = run_steps(&steps, &AtomicBool::new(false), |_| {
            calls += 1;
            if calls == 4 {
                Err("the system said no")
            } else {
                Ok(())
            }
        });
        assert_eq!(calls, 4, "nothing is attempted after a failure");
        assert_eq!(
            outcome,
            BlockOutcome {
                status: CommandStatus::Failed,
                typed: 5
            },
            "\"ab\", Enter and \"cd\" were typed; the second Enter was refused"
        );
    }

    #[test]
    fn cancelling_stops_before_the_next_step() {
        let steps = plan_typing("ab\ncd\nef");
        let cancel = AtomicBool::new(false);
        let mut calls = 0;
        let outcome = run_steps(&steps, &cancel, |_| -> Result<(), ()> {
            calls += 1;
            if calls == 2 {
                cancel.store(true, Ordering::SeqCst);
            }
            Ok(())
        });
        assert_eq!(calls, 2);
        assert_eq!(
            outcome,
            BlockOutcome {
                status: CommandStatus::Cancelled,
                typed: 3
            }
        );

        let already = AtomicBool::new(true);
        let outcome = run_steps(&steps, &already, |_| -> Result<(), ()> {
            panic!("must not type anything")
        });
        assert_eq!(outcome.status, CommandStatus::Cancelled);
        assert_eq!(outcome.typed, 0);
    }
}
