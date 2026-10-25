use bytes::{Buf, BufMut, BytesMut};
use thiserror::Error;

pub const WIRE_HANDSHAKE_INIT: u8 = 0xC0;
pub const WIRE_HANDSHAKE_RESP: u8 = 0xC1;
pub const WIRE_TRANSPORT: u8 = 0xC2;
pub const WIRE_DISCOVERY_PROBE: u8 = 0xC3;
pub const WIRE_DISCOVERY_REPLY: u8 = 0xC4;
pub const WIRE_PAIRING_INTRO_REQ: u8 = 0xC5;
pub const WIRE_PAIRING_INTRO_RESP: u8 = 0xC6;
/// Sent instead of a handshake response when the server will not accept this
/// device (it has not been paired and pairing is closed). Clients that predate
/// it see an unexpected reply and fail the handshake at once instead of timing out.
pub const WIRE_PAIRING_REJECTED: u8 = 0xC7;

/// The release this build belongs to, such as `2.0.0-alpha.4`. The release workflow passes the whole tag as
/// `BECKON_RELEASE_VERSION`; a build made any other way is the version in `Cargo.toml`, which has no pre-release
/// part. It is what the program says it is, and what update checks compare with.
pub const RELEASE_VERSION: &str = match option_env!("BECKON_RELEASE_VERSION") {
    Some(version) if !version.is_empty() => version,
    _ => env!("CARGO_PKG_VERSION"),
};

pub const BECKON_PORT: u16 = 5000;
pub const BECKON_MULTICAST_GROUP: &str = "239.255.42.67";
pub const BECKON_DISCOVERY_MAGIC: &[u8; 8] = &[0x54, 0xE7, 0x9A, 0x03, 0x21, 0xC8, 0xBE, 0xFE];
pub const BECKON_DISCOVERY_MAGIC_ALT: &[u8; 8] = b"TELEPAD!";

pub const MSG_TYPE_MOUSE_MOVE: u8 = 0x01;
pub const MSG_TYPE_MOUSE_BUTTON: u8 = 0x02;
pub const MSG_TYPE_SCROLL: u8 = 0x03;
pub const MSG_TYPE_KEY_PRESS: u8 = 0x04;
pub const MSG_TYPE_KEY_RELEASE: u8 = 0x05;
pub const MSG_TYPE_TEXT_INPUT: u8 = 0x06;
pub const MSG_TYPE_MEDIA_CMD: u8 = 0x07;
pub const MSG_TYPE_VOLUME_CMD: u8 = 0x08;
pub const MSG_TYPE_LOCK_SCREEN: u8 = 0x09;
pub const MSG_TYPE_CLIPBOARD_GET: u8 = 0x0E;
pub const MSG_TYPE_CLIPBOARD_SET: u8 = 0x0F;
pub const MSG_TYPE_LAUNCH_ACTION: u8 = 0x10;
pub const MSG_TYPE_NOW_PLAYING_Q: u8 = 0x11;
pub const MSG_TYPE_HOST_INFO_Q: u8 = 0x12;
/// One piece of a long text (see [`split_text_block`]). Added in 2.1: older servers ignore it.
pub const MSG_TYPE_TEXT_BLOCK_CHUNK: u8 = 0x13;
pub const MSG_TYPE_ACTIVE_WINDOW_Q: u8 = 0x14;
pub const MSG_TYPE_TEXT_BLOCK_CANCEL: u8 = 0x15;
/// Asks for the list of open windows. Added in 2.1: older servers ignore it.
pub const MSG_TYPE_WINDOW_LIST_Q: u8 = 0x16;
/// Brings one window to the front (`[u32 request id][u64 window handle]`), answered with a result.
pub const MSG_TYPE_WINDOW_FOCUS: u8 = 0x17;
/// Runs one command and says how it went (`[u32 request id][u8 kind][u8 mods][u16 usage]`), see [`RemoteCommand`].
pub const MSG_TYPE_RUN_COMMAND: u8 = 0x18;

pub const MSG_TYPE_CLIPBOARD_DATA: u8 = 0x80;
pub const MSG_TYPE_NOW_PLAYING: u8 = 0x81;
pub const MSG_TYPE_HOST_INFO: u8 = 0x82;
pub const MSG_TYPE_COMMAND_RESULT: u8 = 0x83;
pub const MSG_TYPE_ACTIVE_WINDOW: u8 = 0x84;
pub const MSG_TYPE_WINDOW_LIST: u8 = 0x85;

/// Most windows one list carries. A list is one datagram, and has to stay well under a Wi-Fi path MTU.
pub const MAX_WINDOW_LIST_ENTRIES: usize = 12;
/// Longest program name in a window list, in UTF-8 bytes.
pub const MAX_WINDOW_LIST_PROCESS_BYTES: usize = 32;
/// Longest window title in a window list, in UTF-8 bytes.
pub const MAX_WINDOW_LIST_TITLE_BYTES: usize = 48;

/// Largest piece of a long text carried by one datagram. A transport datagram has to stay well under
/// a Wi-Fi path MTU (about 1.4 KB) or it is fragmented and routinely lost.
pub const MAX_TEXT_CHUNK_BYTES: usize = 900;
/// Most pieces one long text may be split into.
pub const MAX_TEXT_BLOCK_CHUNKS: u16 = 80;
/// Largest long text, in UTF-8 bytes.
pub const MAX_TEXT_BLOCK_BYTES: usize = MAX_TEXT_CHUNK_BYTES * MAX_TEXT_BLOCK_CHUNKS as usize;
/// Longest process name or window title carried in an [`ActiveWindowInfo`], in UTF-8 bytes.
pub const MAX_ACTIVE_WINDOW_FIELD_BYTES: usize = 200;

#[derive(Error, Debug, PartialEq, Eq)]
pub enum ProtocolError {
    #[error("Datagram is empty")]
    EmptyPacket,
    #[error("Buffer too short: expected at least {expected}, got {actual}")]
    BufferTooShort { expected: usize, actual: usize },
    #[error("Unknown envelope tag: 0x{0:02X}")]
    UnknownEnvelopeTag(u8),
    #[error("Unknown message type: 0x{0:02X}")]
    UnknownMessageType(u8),
    #[error("Invalid UTF-8 payload: {0}")]
    InvalidUtf8(#[from] std::string::FromUtf8Error),
    #[error("Unknown enum value {0}")]
    InvalidEnumValue(u8),
    #[error("Payload exceeds maximum size: {size} > {max}")]
    PayloadTooLarge { size: usize, max: usize },
    #[error("Text block chunk is malformed (bad index, total or size)")]
    InvalidChunk,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MouseButtonKind {
    Left = 0x00,
    Right = 0x01,
    Middle = 0x02,
}

impl TryFrom<u8> for MouseButtonKind {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::Left),
            0x01 => Ok(Self::Right),
            0x02 => Ok(Self::Middle),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MediaAction {
    PlayPause = 0x00,
    Next = 0x01,
    Prev = 0x02,
    Stop = 0x03,
}

impl TryFrom<u8> for MediaAction {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::PlayPause),
            0x01 => Ok(Self::Next),
            0x02 => Ok(Self::Prev),
            0x03 => Ok(Self::Stop),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum VolumeDirection {
    Up = 0x00,
    Down = 0x01,
    Mute = 0x02,
}

impl TryFrom<u8> for VolumeDirection {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::Up),
            0x01 => Ok(Self::Down),
            0x02 => Ok(Self::Mute),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum SystemAction {
    ShowDesktop = 0x00,
    TaskView = 0x01,
    Browser = 0x02,
    FileManager = 0x03,
    TaskManager = 0x04,
    Screenshot = 0x05,
}

impl TryFrom<u8> for SystemAction {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::ShowDesktop),
            0x01 => Ok(Self::TaskView),
            0x02 => Ok(Self::Browser),
            0x03 => Ok(Self::FileManager),
            0x04 => Ok(Self::TaskManager),
            0x05 => Ok(Self::Screenshot),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

/// Operating system of the machine running the server. Lets the phone show the
/// right key names (Win / Command / Super) and send the right shortcuts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[repr(u8)]
pub enum HostOs {
    /// Not reported, or an operating system this version does not know. Clients
    /// should behave as they did before hosts reported anything (Windows).
    #[default]
    Unknown = 0,
    Windows = 1,
    MacOs = 2,
    Linux = 3,
}

impl HostOs {
    /// The OS this binary was built for.
    pub fn current() -> Self {
        if cfg!(windows) {
            Self::Windows
        } else if cfg!(target_os = "macos") {
            Self::MacOs
        } else if cfg!(target_os = "linux") {
            Self::Linux
        } else {
            Self::Unknown
        }
    }
}

impl From<u8> for HostOs {
    /// Never fails: a newer server may report an OS this version has not heard of.
    fn from(val: u8) -> Self {
        match val {
            1 => Self::Windows,
            2 => Self::MacOs,
            3 => Self::Linux,
            _ => Self::Unknown,
        }
    }
}

/// Feature bits in [`HostInfo::capabilities`].
pub mod capabilities {
    /// The server can report what is playing (`NowPlaying` replies carry real data).
    pub const NOW_PLAYING: u8 = 0x01;
    /// The server can read and write the clipboard.
    pub const CLIPBOARD: u8 = 0x02;
    /// The server types long texts sent in pieces and confirms the result (`TextBlockChunk`).
    pub const TEXT_BLOCK: u8 = 0x04;
    /// The server can say which program and window is in front (`ActiveWindowQuery`).
    pub const ACTIVE_WINDOW: u8 = 0x08;
    /// The server can list open windows and bring one to the front (`WindowListQuery`, `WindowFocus`).
    pub const WINDOW_LIST: u8 = 0x10;
    /// The server runs a command and confirms the result (`RunCommand`).
    pub const COMMANDS: u8 = 0x20;
}

/// What a server tells a phone about itself.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct HostInfo {
    pub os: HostOs,
    /// Bitmask of [`capabilities`].
    pub capabilities: u8,
    /// Server version as `(major, minor, patch)`.
    pub version: (u8, u8, u8),
}

impl HostInfo {
    pub fn supports(&self, capability: u8) -> bool {
        self.capabilities & capability != 0
    }
}

pub mod modifiers {
    pub const LCTRL: u8 = 0x01;
    pub const LSHIFT: u8 = 0x02;
    pub const LALT: u8 = 0x04;
    pub const LMETA: u8 = 0x08;
    pub const RCTRL: u8 = 0x10;
    pub const RSHIFT: u8 = 0x20;
    pub const RALT: u8 = 0x40;
    pub const RMETA: u8 = 0x80;
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct NowPlayingState {
    pub is_playing: bool,
    pub position_ms: Option<i64>,
    pub duration_ms: Option<i64>,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub source_app: Option<String>,
}

/// How a request that the server confirms (such as a long text) ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum CommandStatus {
    /// Everything was done.
    Done = 0,
    /// The operating system refused, or something went wrong part of the way. `value` says how far it got.
    Failed = 1,
    /// The phone cancelled it. `value` says how far it got.
    Cancelled = 2,
    /// Not started: too large, too many at once, or inconsistent pieces.
    Rejected = 3,
    /// Every piece arrived and typing has started. Not an end: a `Done`, `Failed` or `Cancelled` follows.
    Received = 4,
}

impl CommandStatus {
    /// Whether this is the last word on a request.
    pub fn is_final(self) -> bool {
        self != Self::Received
    }
}

impl TryFrom<u8> for CommandStatus {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0 => Ok(Self::Done),
            1 => Ok(Self::Failed),
            2 => Ok(Self::Cancelled),
            3 => Ok(Self::Rejected),
            4 => Ok(Self::Received),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

/// The program and window in front on the PC.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ActiveWindowInfo {
    /// The program's file name, such as `Code.exe`.
    pub process: String,
    /// The window's title. May be empty, and may name documents: it is only ever sent when asked for.
    pub title: String,
}

/// A command the phone can have the PC run and report on (`ClientMessage::RunCommand`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RemoteCommand {
    /// Press `usage` (a HID key code) with `mods` (see [`modifiers`]) held, then let go.
    Chord {
        mods: u8,
        usage: u16,
    },
    Media(MediaAction),
    Volume(VolumeDirection),
    System(SystemAction),
    LockScreen,
}

impl RemoteCommand {
    fn parts(self) -> (u8, u8, u16) {
        match self {
            Self::Chord { mods, usage } => (0, mods, usage),
            Self::Media(action) => (1, 0, action as u16),
            Self::Volume(direction) => (2, 0, direction as u16),
            Self::System(action) => (3, 0, action as u16),
            Self::LockScreen => (4, 0, 0),
        }
    }

    fn from_parts(kind: u8, mods: u8, usage: u16) -> Result<Self, ProtocolError> {
        let small =
            |value: u16| u8::try_from(value).map_err(|_| ProtocolError::InvalidEnumValue(0xFF));
        match kind {
            0 => Ok(Self::Chord { mods, usage }),
            1 => Ok(Self::Media(MediaAction::try_from(small(usage)?)?)),
            2 => Ok(Self::Volume(VolumeDirection::try_from(small(usage)?)?)),
            3 => Ok(Self::System(SystemAction::try_from(small(usage)?)?)),
            4 => Ok(Self::LockScreen),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

/// One open window in a [`ServerMessage::WindowList`].
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct WindowEntry {
    /// The system's handle for the window, to be sent back in `WindowFocus`. Only good while the window lives.
    pub handle: u64,
    /// The program's file name, such as `Code.exe`.
    pub process: String,
    /// The window's title, clipped. Only ever sent when asked for, and never kept.
    pub title: String,
    /// Whether this is the window in front.
    pub foreground: bool,
}

/// The longest prefix of `text` that is at most `max_bytes` of UTF-8, never cut inside a character.
pub fn truncate_utf8(text: &str, max_bytes: usize) -> &str {
    let mut end = text.len().min(max_bytes);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// Splits a long text into pieces of at most [`MAX_TEXT_CHUNK_BYTES`] bytes each, cut only between
/// characters (so every piece is valid UTF-8 by itself, and joining them in order gives the text back
/// exactly: nothing is normalised, reordered or dropped, which keeps right-to-left text and marks like
/// U+200F intact). Returns `PayloadTooLarge` when the text needs more than [`MAX_TEXT_BLOCK_CHUNKS`].
pub fn split_text_block(text: &str) -> Result<Vec<String>, ProtocolError> {
    if text.len() > MAX_TEXT_BLOCK_BYTES {
        return Err(ProtocolError::PayloadTooLarge {
            size: text.len(),
            max: MAX_TEXT_BLOCK_BYTES,
        });
    }
    let mut pieces = Vec::new();
    let mut rest = text;
    while !rest.is_empty() {
        let piece = truncate_utf8(rest, MAX_TEXT_CHUNK_BYTES);
        pieces.push(piece.to_owned());
        rest = &rest[piece.len()..];
    }
    Ok(pieces)
}

#[derive(Debug, Clone, PartialEq)]
pub enum ClientMessage {
    MouseMove {
        dx: i16,
        dy: i16,
    },
    MouseButton {
        button: MouseButtonKind,
        pressed: bool,
    },
    Scroll {
        delta: i16,
    },
    KeyPress {
        keycode: u16,
        mods: u8,
    },
    KeyRelease {
        keycode: u16,
        mods: u8,
    },
    TextInput(String),
    MediaCmd(MediaAction),
    VolumeCmd(VolumeDirection),
    LockScreen,
    ClipboardGet,
    ClipboardSet(String),
    LaunchAction(SystemAction),
    NowPlayingQuery,
    HostInfoQuery,
    /// Piece `index` (from 0) of `total` of a long text. `request_id` names the text: the pieces of one
    /// text share it, and the server answers with a [`ServerMessage::CommandResult`] carrying it.
    TextBlockChunk {
        request_id: u32,
        index: u16,
        total: u16,
        text: String,
    },
    /// Stop typing the text `request_id`, or forget its pieces if typing has not started.
    TextBlockCancel {
        request_id: u32,
    },
    ActiveWindowQuery,
    /// Ask which windows are open.
    WindowListQuery,
    /// Bring the window with this handle to the front. Answered with a `CommandResult` for `request_id`.
    WindowFocus {
        request_id: u32,
        handle: u64,
    },
    /// Run `command` and answer with a `CommandResult` for `request_id` once the system has taken it.
    /// The same `request_id` sent again is answered again and not run again.
    RunCommand {
        request_id: u32,
        command: RemoteCommand,
    },
}

impl ClientMessage {
    pub fn encode(&self, buf: &mut BytesMut) -> Result<(), ProtocolError> {
        match self {
            Self::MouseMove { dx, dy } => {
                buf.put_u8(MSG_TYPE_MOUSE_MOVE);
                buf.put_i16_le(*dx);
                buf.put_i16_le(*dy);
            }
            Self::MouseButton { button, pressed } => {
                buf.put_u8(MSG_TYPE_MOUSE_BUTTON);
                buf.put_u8(*button as u8);
                buf.put_u8(if *pressed { 1 } else { 0 });
            }
            Self::Scroll { delta } => {
                buf.put_u8(MSG_TYPE_SCROLL);
                buf.put_i16_le(*delta);
            }
            Self::KeyPress { keycode, mods } => {
                buf.put_u8(MSG_TYPE_KEY_PRESS);
                buf.put_u16_le(*keycode);
                buf.put_u8(*mods);
            }
            Self::KeyRelease { keycode, mods } => {
                buf.put_u8(MSG_TYPE_KEY_RELEASE);
                buf.put_u16_le(*keycode);
                buf.put_u8(*mods);
            }
            Self::TextInput(text) => {
                let bytes = text.as_bytes();
                if bytes.len() > u16::MAX as usize {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: bytes.len(),
                        max: u16::MAX as usize,
                    });
                }
                buf.put_u8(MSG_TYPE_TEXT_INPUT);
                buf.put_u16_le(bytes.len() as u16);
                buf.put_slice(bytes);
            }
            Self::MediaCmd(action) => {
                buf.put_u8(MSG_TYPE_MEDIA_CMD);
                buf.put_u8(*action as u8);
            }
            Self::VolumeCmd(direction) => {
                buf.put_u8(MSG_TYPE_VOLUME_CMD);
                buf.put_u8(*direction as u8);
            }
            Self::LockScreen => {
                buf.put_u8(MSG_TYPE_LOCK_SCREEN);
            }
            Self::ClipboardGet => {
                buf.put_u8(MSG_TYPE_CLIPBOARD_GET);
            }
            Self::ClipboardSet(text) => {
                let bytes = text.as_bytes();
                if bytes.len() > u16::MAX as usize {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: bytes.len(),
                        max: u16::MAX as usize,
                    });
                }
                buf.put_u8(MSG_TYPE_CLIPBOARD_SET);
                buf.put_u16_le(bytes.len() as u16);
                buf.put_slice(bytes);
            }
            Self::LaunchAction(action) => {
                buf.put_u8(MSG_TYPE_LAUNCH_ACTION);
                buf.put_u8(*action as u8);
            }
            Self::NowPlayingQuery => {
                buf.put_u8(MSG_TYPE_NOW_PLAYING_Q);
            }
            Self::HostInfoQuery => {
                buf.put_u8(MSG_TYPE_HOST_INFO_Q);
            }
            Self::TextBlockChunk {
                request_id,
                index,
                total,
                text,
            } => {
                let bytes = text.as_bytes();
                if *total == 0 || *total > MAX_TEXT_BLOCK_CHUNKS || index >= total {
                    return Err(ProtocolError::InvalidChunk);
                }
                if bytes.len() > MAX_TEXT_CHUNK_BYTES {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: bytes.len(),
                        max: MAX_TEXT_CHUNK_BYTES,
                    });
                }
                buf.put_u8(MSG_TYPE_TEXT_BLOCK_CHUNK);
                buf.put_u32_le(*request_id);
                buf.put_u16_le(*index);
                buf.put_u16_le(*total);
                buf.put_u16_le(bytes.len() as u16);
                buf.put_slice(bytes);
            }
            Self::TextBlockCancel { request_id } => {
                buf.put_u8(MSG_TYPE_TEXT_BLOCK_CANCEL);
                buf.put_u32_le(*request_id);
            }
            Self::ActiveWindowQuery => {
                buf.put_u8(MSG_TYPE_ACTIVE_WINDOW_Q);
            }
            Self::WindowListQuery => {
                buf.put_u8(MSG_TYPE_WINDOW_LIST_Q);
            }
            Self::WindowFocus { request_id, handle } => {
                buf.put_u8(MSG_TYPE_WINDOW_FOCUS);
                buf.put_u32_le(*request_id);
                buf.put_u64_le(*handle);
            }
            Self::RunCommand {
                request_id,
                command,
            } => {
                let (kind, mods, usage) = command.parts();
                buf.put_u8(MSG_TYPE_RUN_COMMAND);
                buf.put_u32_le(*request_id);
                buf.put_u8(kind);
                buf.put_u8(mods);
                buf.put_u16_le(usage);
            }
        }
        Ok(())
    }

    pub fn decode(mut buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.is_empty() {
            return Err(ProtocolError::EmptyPacket);
        }
        let msg_type = buf.get_u8();
        match msg_type {
            MSG_TYPE_MOUSE_MOVE => {
                if buf.len() < 4 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 4,
                        actual: buf.len(),
                    });
                }
                let dx = buf.get_i16_le();
                let dy = buf.get_i16_le();
                Ok(Self::MouseMove { dx, dy })
            }
            MSG_TYPE_MOUSE_BUTTON => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let button = MouseButtonKind::try_from(buf.get_u8())?;
                let pressed = buf.get_u8() != 0;
                Ok(Self::MouseButton { button, pressed })
            }
            MSG_TYPE_SCROLL => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let delta = buf.get_i16_le();
                Ok(Self::Scroll { delta })
            }
            MSG_TYPE_KEY_PRESS => {
                if buf.len() < 3 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 3,
                        actual: buf.len(),
                    });
                }
                let keycode = buf.get_u16_le();
                let mods = buf.get_u8();
                Ok(Self::KeyPress { keycode, mods })
            }
            MSG_TYPE_KEY_RELEASE => {
                if buf.len() < 3 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 3,
                        actual: buf.len(),
                    });
                }
                let keycode = buf.get_u16_le();
                let mods = buf.get_u8();
                Ok(Self::KeyRelease { keycode, mods })
            }
            MSG_TYPE_TEXT_INPUT => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let len = buf.get_u16_le() as usize;
                if buf.len() < len {
                    return Err(ProtocolError::BufferTooShort {
                        expected: len,
                        actual: buf.len(),
                    });
                }
                let text = String::from_utf8(buf[..len].to_vec())?;
                Ok(Self::TextInput(text))
            }
            MSG_TYPE_MEDIA_CMD => {
                if buf.is_empty() {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 1,
                        actual: 0,
                    });
                }
                let action = MediaAction::try_from(buf.get_u8())?;
                Ok(Self::MediaCmd(action))
            }
            MSG_TYPE_VOLUME_CMD => {
                if buf.is_empty() {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 1,
                        actual: 0,
                    });
                }
                let direction = VolumeDirection::try_from(buf.get_u8())?;
                Ok(Self::VolumeCmd(direction))
            }
            MSG_TYPE_LOCK_SCREEN => Ok(Self::LockScreen),
            MSG_TYPE_CLIPBOARD_GET => Ok(Self::ClipboardGet),
            MSG_TYPE_CLIPBOARD_SET => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let len = buf.get_u16_le() as usize;
                if buf.len() < len {
                    return Err(ProtocolError::BufferTooShort {
                        expected: len,
                        actual: buf.len(),
                    });
                }
                let text = String::from_utf8(buf[..len].to_vec())?;
                Ok(Self::ClipboardSet(text))
            }
            MSG_TYPE_LAUNCH_ACTION => {
                if buf.is_empty() {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 1,
                        actual: 0,
                    });
                }
                let action = SystemAction::try_from(buf.get_u8())?;
                Ok(Self::LaunchAction(action))
            }
            MSG_TYPE_NOW_PLAYING_Q => Ok(Self::NowPlayingQuery),
            MSG_TYPE_HOST_INFO_Q => Ok(Self::HostInfoQuery),
            MSG_TYPE_TEXT_BLOCK_CHUNK => {
                const FIXED: usize = 4 + 2 + 2 + 2;
                if buf.len() < FIXED {
                    return Err(ProtocolError::BufferTooShort {
                        expected: FIXED,
                        actual: buf.len(),
                    });
                }
                let request_id = buf.get_u32_le();
                let index = buf.get_u16_le();
                let total = buf.get_u16_le();
                let len = buf.get_u16_le() as usize;
                if total == 0 || total > MAX_TEXT_BLOCK_CHUNKS || index >= total {
                    return Err(ProtocolError::InvalidChunk);
                }
                if len > MAX_TEXT_CHUNK_BYTES {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: len,
                        max: MAX_TEXT_CHUNK_BYTES,
                    });
                }
                if buf.len() < len {
                    return Err(ProtocolError::BufferTooShort {
                        expected: len,
                        actual: buf.len(),
                    });
                }
                let text = String::from_utf8(buf[..len].to_vec())?;
                Ok(Self::TextBlockChunk {
                    request_id,
                    index,
                    total,
                    text,
                })
            }
            MSG_TYPE_TEXT_BLOCK_CANCEL => {
                if buf.len() < 4 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 4,
                        actual: buf.len(),
                    });
                }
                Ok(Self::TextBlockCancel {
                    request_id: buf.get_u32_le(),
                })
            }
            MSG_TYPE_ACTIVE_WINDOW_Q => Ok(Self::ActiveWindowQuery),
            MSG_TYPE_WINDOW_LIST_Q => Ok(Self::WindowListQuery),
            MSG_TYPE_WINDOW_FOCUS => {
                if buf.len() < 12 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 12,
                        actual: buf.len(),
                    });
                }
                Ok(Self::WindowFocus {
                    request_id: buf.get_u32_le(),
                    handle: buf.get_u64_le(),
                })
            }
            MSG_TYPE_RUN_COMMAND => {
                if buf.len() < 8 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 8,
                        actual: buf.len(),
                    });
                }
                let request_id = buf.get_u32_le();
                let kind = buf.get_u8();
                let mods = buf.get_u8();
                let usage = buf.get_u16_le();
                Ok(Self::RunCommand {
                    request_id,
                    command: RemoteCommand::from_parts(kind, mods, usage)?,
                })
            }
            other => Err(ProtocolError::UnknownMessageType(other)),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum ServerMessage {
    ClipboardData(String),
    NowPlaying(NowPlayingState),
    HostInfo(HostInfo),
    /// How the request `request_id` is going or ended. `value` is the number of characters typed so far for
    /// a long text.
    CommandResult {
        request_id: u32,
        status: CommandStatus,
        value: u32,
    },
    /// The program and window in front, or `None` when the server cannot tell (nothing is in front, or this
    /// system does not allow asking).
    ActiveWindow(Option<ActiveWindowInfo>),
    /// The open windows, the one in front marked, at most [`MAX_WINDOW_LIST_ENTRIES`]. Longer lists are cut.
    WindowList(Vec<WindowEntry>),
}

impl ServerMessage {
    pub fn encode(&self, buf: &mut BytesMut) -> Result<(), ProtocolError> {
        match self {
            Self::ClipboardData(text) => {
                let bytes = text.as_bytes();
                if bytes.len() > u16::MAX as usize {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: bytes.len(),
                        max: u16::MAX as usize,
                    });
                }
                buf.put_u8(MSG_TYPE_CLIPBOARD_DATA);
                buf.put_u16_le(bytes.len() as u16);
                buf.put_slice(bytes);
                Ok(())
            }
            Self::NowPlaying(state) => {
                buf.put_u8(MSG_TYPE_NOW_PLAYING);
                let mut flags: u8 = 0;
                if state.is_playing {
                    flags |= 0x01;
                }
                if state.title.is_some() {
                    flags |= 0x02;
                }
                if state.artist.is_some() {
                    flags |= 0x04;
                }
                if state.album.is_some() {
                    flags |= 0x08;
                }
                if state.source_app.is_some() {
                    flags |= 0x10;
                }
                buf.put_u8(flags);
                buf.put_i64_le(state.position_ms.unwrap_or(-1));
                buf.put_i64_le(state.duration_ms.unwrap_or(-1));

                let encode_str = |b: &mut BytesMut, s: &Option<String>| {
                    if let Some(str_val) = s {
                        if str_val.is_empty() {
                            b.put_u8(0);
                        } else {
                            let mut end = str_val.len().min(255);
                            while !str_val.is_char_boundary(end) {
                                end -= 1;
                            }
                            b.put_u8(end as u8);
                            b.put_slice(&str_val.as_bytes()[..end]);
                        }
                    } else {
                        b.put_u8(0);
                    }
                };

                encode_str(buf, &state.title);
                encode_str(buf, &state.artist);
                encode_str(buf, &state.album);
                encode_str(buf, &state.source_app);
                Ok(())
            }
            Self::HostInfo(info) => {
                buf.put_u8(MSG_TYPE_HOST_INFO);
                buf.put_u8(info.os as u8);
                buf.put_u8(info.capabilities);
                buf.put_u8(info.version.0);
                buf.put_u8(info.version.1);
                buf.put_u8(info.version.2);
                Ok(())
            }
            Self::CommandResult {
                request_id,
                status,
                value,
            } => {
                buf.put_u8(MSG_TYPE_COMMAND_RESULT);
                buf.put_u32_le(*request_id);
                buf.put_u8(*status as u8);
                buf.put_u32_le(*value);
                Ok(())
            }
            Self::WindowList(windows) => {
                buf.put_u8(MSG_TYPE_WINDOW_LIST);
                let shown = &windows[..windows.len().min(MAX_WINDOW_LIST_ENTRIES)];
                buf.put_u8(shown.len() as u8);
                for window in shown {
                    buf.put_u64_le(window.handle);
                    buf.put_u8(u8::from(window.foreground));
                    let process = truncate_utf8(&window.process, MAX_WINDOW_LIST_PROCESS_BYTES);
                    buf.put_u8(process.len() as u8);
                    buf.put_slice(process.as_bytes());
                    let title = truncate_utf8(&window.title, MAX_WINDOW_LIST_TITLE_BYTES);
                    buf.put_u8(title.len() as u8);
                    buf.put_slice(title.as_bytes());
                }
                Ok(())
            }
            Self::ActiveWindow(window) => {
                buf.put_u8(MSG_TYPE_ACTIVE_WINDOW);
                match window {
                    None => buf.put_u8(0),
                    Some(info) => {
                        buf.put_u8(1);
                        for field in [&info.process, &info.title] {
                            let clipped = truncate_utf8(field, MAX_ACTIVE_WINDOW_FIELD_BYTES);
                            buf.put_u8(clipped.len() as u8);
                            buf.put_slice(clipped.as_bytes());
                        }
                    }
                }
                Ok(())
            }
        }
    }

    pub fn decode(mut buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.is_empty() {
            return Err(ProtocolError::EmptyPacket);
        }
        let msg_type = buf.get_u8();
        match msg_type {
            MSG_TYPE_CLIPBOARD_DATA => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let len = buf.get_u16_le() as usize;
                if buf.len() < len {
                    return Err(ProtocolError::BufferTooShort {
                        expected: len,
                        actual: buf.len(),
                    });
                }
                let text = String::from_utf8(buf[..len].to_vec())?;
                Ok(Self::ClipboardData(text))
            }
            MSG_TYPE_NOW_PLAYING => {
                if buf.len() < 17 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 17,
                        actual: buf.len(),
                    });
                }
                let flags = buf.get_u8();
                let is_playing = (flags & 0x01) != 0;
                let title_present = (flags & 0x02) != 0;
                let artist_present = (flags & 0x04) != 0;
                let album_present = (flags & 0x08) != 0;
                let source_app_present = (flags & 0x10) != 0;
                let pos = buf.get_i64_le();
                let dur = buf.get_i64_le();

                let read_str =
                    |b: &mut &[u8], present: bool| -> Result<Option<String>, ProtocolError> {
                        if b.is_empty() {
                            return Ok(if present { Some(String::new()) } else { None });
                        }
                        let l = b.get_u8() as usize;
                        if l == 0 {
                            return Ok(if present { Some(String::new()) } else { None });
                        }
                        if b.len() < l {
                            return Err(ProtocolError::BufferTooShort {
                                expected: l,
                                actual: b.len(),
                            });
                        }
                        let s = String::from_utf8(b[..l].to_vec())?;
                        b.advance(l);
                        Ok(Some(s))
                    };

                let title = read_str(&mut buf, title_present)?;
                let artist = read_str(&mut buf, artist_present)?;
                let album = read_str(&mut buf, album_present)?;
                let source_app = read_str(&mut buf, source_app_present)?;

                Ok(Self::NowPlaying(NowPlayingState {
                    is_playing,
                    position_ms: if pos >= 0 { Some(pos) } else { None },
                    duration_ms: if dur >= 0 { Some(dur) } else { None },
                    title,
                    artist,
                    album,
                    source_app,
                }))
            }
            MSG_TYPE_HOST_INFO => {
                // Later versions may append fields; read what we know and ignore the rest.
                if buf.len() < 5 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 5,
                        actual: buf.len(),
                    });
                }
                let os = HostOs::from(buf.get_u8());
                let capabilities = buf.get_u8();
                let version = (buf.get_u8(), buf.get_u8(), buf.get_u8());
                Ok(Self::HostInfo(HostInfo {
                    os,
                    capabilities,
                    version,
                }))
            }
            MSG_TYPE_COMMAND_RESULT => {
                const FIXED: usize = 4 + 1 + 4;
                if buf.len() < FIXED {
                    return Err(ProtocolError::BufferTooShort {
                        expected: FIXED,
                        actual: buf.len(),
                    });
                }
                let request_id = buf.get_u32_le();
                let status = CommandStatus::try_from(buf.get_u8())?;
                let value = buf.get_u32_le();
                Ok(Self::CommandResult {
                    request_id,
                    status,
                    value,
                })
            }
            MSG_TYPE_ACTIVE_WINDOW => {
                if buf.is_empty() {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 1,
                        actual: 0,
                    });
                }
                if buf.get_u8() & 1 == 0 {
                    return Ok(Self::ActiveWindow(None));
                }
                let mut fields = [String::new(), String::new()];
                for field in &mut fields {
                    if buf.is_empty() {
                        return Err(ProtocolError::BufferTooShort {
                            expected: 1,
                            actual: 0,
                        });
                    }
                    let len = buf.get_u8() as usize;
                    if buf.len() < len {
                        return Err(ProtocolError::BufferTooShort {
                            expected: len,
                            actual: buf.len(),
                        });
                    }
                    *field = String::from_utf8(buf[..len].to_vec())?;
                    buf.advance(len);
                }
                let [process, title] = fields;
                Ok(Self::ActiveWindow(Some(ActiveWindowInfo {
                    process,
                    title,
                })))
            }
            MSG_TYPE_WINDOW_LIST => {
                let short = |actual: usize, expected: usize| ProtocolError::BufferTooShort {
                    expected,
                    actual,
                };
                if buf.is_empty() {
                    return Err(short(0, 1));
                }
                let count = buf.get_u8() as usize;
                if count > MAX_WINDOW_LIST_ENTRIES {
                    return Err(short(count, MAX_WINDOW_LIST_ENTRIES));
                }
                let mut windows = Vec::with_capacity(count);
                for _ in 0..count {
                    if buf.len() < 10 {
                        return Err(short(buf.len(), 10));
                    }
                    let handle = buf.get_u64_le();
                    let foreground = buf.get_u8() & 1 == 1;
                    let mut fields = [String::new(), String::new()];
                    for field in &mut fields {
                        if buf.is_empty() {
                            return Err(short(0, 1));
                        }
                        let len = buf.get_u8() as usize;
                        if buf.len() < len {
                            return Err(short(buf.len(), len));
                        }
                        *field = String::from_utf8(buf[..len].to_vec())?;
                        buf.advance(len);
                    }
                    let [process, title] = fields;
                    windows.push(WindowEntry {
                        handle,
                        process,
                        title,
                        foreground,
                    });
                }
                Ok(Self::WindowList(windows))
            }
            other => Err(ProtocolError::UnknownMessageType(other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mouse_move_roundtrip() {
        let original = ClientMessage::MouseMove { dx: -125, dy: 450 };
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_mouse_button_roundtrip() {
        let original = ClientMessage::MouseButton {
            button: MouseButtonKind::Right,
            pressed: true,
        };
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_scroll_roundtrip() {
        let original = ClientMessage::Scroll { delta: -120 };
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_key_press_and_release_roundtrip() {
        let press = ClientMessage::KeyPress {
            keycode: 0x41,
            mods: modifiers::LCTRL | modifiers::LSHIFT,
        };
        let mut buf = BytesMut::new();
        press.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(press, decoded);

        let release = ClientMessage::KeyRelease {
            keycode: 0x41,
            mods: 0,
        };
        buf.clear();
        release.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(release, decoded);
    }

    #[test]
    fn test_text_input_roundtrip() {
        let original = ClientMessage::TextInput("Hello Beckon v2! \u{1F680}".into());
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_payload_too_large_rejected() {
        let huge_text = "a".repeat(70000);
        let msg = ClientMessage::TextInput(huge_text);
        let mut buf = BytesMut::new();
        assert!(matches!(
            msg.encode(&mut buf),
            Err(ProtocolError::PayloadTooLarge { .. })
        ));
    }

    #[test]
    fn test_all_control_messages_roundtrip() {
        let msgs = [
            ClientMessage::MediaCmd(MediaAction::PlayPause),
            ClientMessage::VolumeCmd(VolumeDirection::Up),
            ClientMessage::LockScreen,
            ClientMessage::ClipboardGet,
            ClientMessage::ClipboardSet("Copy text".into()),
            ClientMessage::LaunchAction(SystemAction::TaskView),
            ClientMessage::NowPlayingQuery,
        ];
        for msg in msgs {
            let mut buf = BytesMut::new();
            msg.encode(&mut buf).unwrap();
            let decoded = ClientMessage::decode(&buf).unwrap();
            assert_eq!(msg, decoded);
        }
    }

    #[test]
    fn host_info_query_roundtrip() {
        let mut buf = BytesMut::new();
        ClientMessage::HostInfoQuery.encode(&mut buf).unwrap();
        assert_eq!(&buf[..], &[MSG_TYPE_HOST_INFO_Q]);
        assert_eq!(
            ClientMessage::decode(&buf).unwrap(),
            ClientMessage::HostInfoQuery
        );
    }

    #[test]
    fn host_info_roundtrip_and_wire_layout() {
        let info = HostInfo {
            os: HostOs::Linux,
            capabilities: capabilities::CLIPBOARD,
            version: (2, 1, 7),
        };
        let mut buf = BytesMut::new();
        ServerMessage::HostInfo(info).encode(&mut buf).unwrap();
        // [type][os][caps][major][minor][patch]
        assert_eq!(&buf[..], &[0x82, 3, 0x02, 2, 1, 7]);
        assert_eq!(
            ServerMessage::decode(&buf).unwrap(),
            ServerMessage::HostInfo(info)
        );
        assert!(info.supports(capabilities::CLIPBOARD));
        assert!(!info.supports(capabilities::NOW_PLAYING));
    }

    #[test]
    fn host_info_tolerates_extra_trailing_fields_from_newer_servers() {
        let bytes = [MSG_TYPE_HOST_INFO, 2, 0x03, 3, 0, 0, 0xAA, 0xBB, 0xCC];
        let ServerMessage::HostInfo(info) = ServerMessage::decode(&bytes).unwrap() else {
            panic!("expected host info");
        };
        assert_eq!(info.os, HostOs::MacOs);
        assert_eq!(info.version, (3, 0, 0));
        assert!(info.supports(capabilities::NOW_PLAYING) && info.supports(capabilities::CLIPBOARD));
    }

    #[test]
    fn host_info_with_an_unknown_os_decodes_as_unknown_instead_of_failing() {
        let ServerMessage::HostInfo(info) =
            ServerMessage::decode(&[MSG_TYPE_HOST_INFO, 200, 0, 1, 2, 3]).unwrap()
        else {
            panic!("expected host info");
        };
        assert_eq!(info.os, HostOs::Unknown);
        assert_eq!(HostOs::from(0), HostOs::Unknown);
        assert_eq!(HostOs::from(1), HostOs::Windows);
        assert_eq!(HostOs::from(2), HostOs::MacOs);
        assert_eq!(HostOs::from(3), HostOs::Linux);
    }

    #[test]
    fn truncated_host_info_is_rejected() {
        for len in 1..6 {
            let bytes = &[MSG_TYPE_HOST_INFO, 1, 0, 1, 2, 3][..len];
            assert!(
                matches!(
                    ServerMessage::decode(bytes),
                    Err(ProtocolError::BufferTooShort { .. })
                ),
                "len {len}"
            );
        }
    }

    #[test]
    fn current_host_os_matches_the_build_target() {
        let os = HostOs::current();
        if cfg!(windows) {
            assert_eq!(os, HostOs::Windows);
        } else if cfg!(target_os = "macos") {
            assert_eq!(os, HostOs::MacOs);
        } else if cfg!(target_os = "linux") {
            assert_eq!(os, HostOs::Linux);
        }
    }

    #[test]
    fn test_clipboard_data_roundtrip() {
        let original = ServerMessage::ClipboardData("Sync clipboard data \u{1F4CB}".into());
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ServerMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_now_playing_roundtrip() {
        let original = ServerMessage::NowPlaying(NowPlayingState {
            is_playing: true,
            position_ms: Some(42000),
            duration_ms: Some(180000),
            title: Some("Song Title".into()),
            artist: Some("Artist Name".into()),
            album: Some("Album Name".into()),
            source_app: Some("Spotify".into()),
        });
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ServerMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_now_playing_some_empty_string_roundtrip() {
        let original = ServerMessage::NowPlaying(NowPlayingState {
            is_playing: false,
            position_ms: None,
            duration_ms: None,
            title: Some("".into()),
            artist: None,
            album: Some("".into()),
            source_app: None,
        });
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let ServerMessage::NowPlaying(decoded) = ServerMessage::decode(&buf).unwrap() else {
            panic!("expected NowPlaying");
        };
        assert_eq!(decoded.title, Some("".to_string()));
        assert_eq!(decoded.artist, None);
        assert_eq!(decoded.album, Some("".to_string()));
        assert_eq!(decoded.source_app, None);
    }

    #[test]
    fn test_korean_now_playing_truncation_preserves_utf8() {
        // "a" followed by 100 Korean characters (3 bytes each = 301 bytes)
        let long_korean = format!("a{}", "\u{AC00}".repeat(100));
        let original = ServerMessage::NowPlaying(NowPlayingState {
            is_playing: true,
            position_ms: Some(1000),
            duration_ms: Some(2000),
            title: Some(long_korean),
            artist: Some("Artist".into()),
            album: None,
            source_app: None,
        });
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let ServerMessage::NowPlaying(decoded) = ServerMessage::decode(&buf).unwrap() else {
            panic!("expected NowPlaying");
        };
        let decoded_title = decoded.title.unwrap();
        assert!(decoded_title.len() <= 255);
        assert!(decoded_title.starts_with('a'));
        // Verify valid UTF-8 character boundary: string does not contain broken sequences
        assert!(decoded_title.chars().all(|c| c == 'a' || c == '\u{AC00}'));
    }

    // ── Long texts, results and the window in front ─────────────────────

    fn encode_client(message: &ClientMessage) -> BytesMut {
        let mut buf = BytesMut::new();
        message.encode(&mut buf).unwrap();
        buf
    }

    #[test]
    fn text_block_chunk_wire_layout_and_roundtrip() {
        let message = ClientMessage::TextBlockChunk {
            request_id: 0x0A0B_0C0D,
            index: 1,
            total: 3,
            text: "hi".into(),
        };
        let buf = encode_client(&message);
        // [type][id: u32][index: u16][total: u16][len: u16][utf-8]
        assert_eq!(
            &buf[..],
            &[0x13, 0x0D, 0x0C, 0x0B, 0x0A, 1, 0, 3, 0, 2, 0, b'h', b'i']
        );
        assert_eq!(ClientMessage::decode(&buf).unwrap(), message);
    }

    #[test]
    fn text_block_chunk_rejects_inconsistent_or_oversized_pieces() {
        let bad = |index, total, text: &str| ClientMessage::TextBlockChunk {
            request_id: 1,
            index,
            total,
            text: text.into(),
        };
        let mut buf = BytesMut::new();
        for message in [
            bad(0, 0, "x"),
            bad(3, 3, "x"),
            bad(0, MAX_TEXT_BLOCK_CHUNKS + 1, "x"),
        ] {
            assert_eq!(
                message.encode(&mut buf),
                Err(ProtocolError::InvalidChunk),
                "{message:?}"
            );
        }
        assert!(matches!(
            bad(0, 1, &"a".repeat(MAX_TEXT_CHUNK_BYTES + 1)).encode(&mut buf),
            Err(ProtocolError::PayloadTooLarge { .. })
        ));
        // The decoder is as strict as the encoder: a hostile phone cannot send what we would not.
        let wire = |index: u16, total: u16, len: u16| {
            let mut v = vec![MSG_TYPE_TEXT_BLOCK_CHUNK, 1, 0, 0, 0];
            v.extend_from_slice(&index.to_le_bytes());
            v.extend_from_slice(&total.to_le_bytes());
            v.extend_from_slice(&len.to_le_bytes());
            v.extend(std::iter::repeat_n(b'a', len as usize));
            v
        };
        assert_eq!(
            ClientMessage::decode(&wire(5, 5, 1)),
            Err(ProtocolError::InvalidChunk)
        );
        assert_eq!(
            ClientMessage::decode(&wire(0, 0, 1)),
            Err(ProtocolError::InvalidChunk)
        );
        assert!(matches!(
            ClientMessage::decode(&wire(0, 1, 901)),
            Err(ProtocolError::PayloadTooLarge { .. })
        ));
        let mut truncated = wire(0, 1, 10);
        truncated.truncate(truncated.len() - 1);
        assert!(matches!(
            ClientMessage::decode(&truncated),
            Err(ProtocolError::BufferTooShort { .. })
        ));
        // A piece that is cut inside a character is not valid UTF-8 and is refused.
        let mut cut = wire(0, 1, 0);
        cut[9] = 1;
        cut.push(0xD8);
        assert!(matches!(
            ClientMessage::decode(&cut),
            Err(ProtocolError::InvalidUtf8(_))
        ));
    }

    #[test]
    fn cancel_and_active_window_query_roundtrip() {
        let cancel = ClientMessage::TextBlockCancel { request_id: 77 };
        let buf = encode_client(&cancel);
        assert_eq!(&buf[..], &[0x15, 77, 0, 0, 0]);
        assert_eq!(ClientMessage::decode(&buf).unwrap(), cancel);
        assert!(matches!(
            ClientMessage::decode(&[0x15, 1, 2]),
            Err(ProtocolError::BufferTooShort { .. })
        ));

        let buf = encode_client(&ClientMessage::ActiveWindowQuery);
        assert_eq!(&buf[..], &[0x14]);
        assert_eq!(
            ClientMessage::decode(&buf).unwrap(),
            ClientMessage::ActiveWindowQuery
        );
    }

    #[test]
    fn splitting_a_text_loses_nothing_and_never_cuts_a_character() {
        let samples = [
            String::new(),
            "short".to_owned(),
            "a".repeat(MAX_TEXT_CHUNK_BYTES),
            "a".repeat(MAX_TEXT_CHUNK_BYTES + 1),
            // Arabic is two bytes a letter, with marks and a right-to-left mark in between.
            "مرحباً بالعالم \u{200F}(Beckon) ١٢٣ ".repeat(200),
            // Four-byte characters, so the cut has to back up to a boundary.
            "😀👨\u{200D}👩\u{200D}👧".repeat(150),
            "line one\r\nسطر ثانٍ\n\tindented\n".repeat(60),
        ];
        for text in samples {
            let pieces = split_text_block(&text).unwrap();
            assert!(pieces.len() <= MAX_TEXT_BLOCK_CHUNKS as usize);
            for piece in &pieces {
                assert!(!piece.is_empty());
                assert!(piece.len() <= MAX_TEXT_CHUNK_BYTES);
            }
            assert_eq!(
                pieces.concat(),
                text,
                "joining the pieces gives the text back"
            );
            // And each piece survives the wire on its own.
            for (index, piece) in pieces.iter().enumerate() {
                let message = ClientMessage::TextBlockChunk {
                    request_id: 9,
                    index: index as u16,
                    total: pieces.len() as u16,
                    text: piece.clone(),
                };
                assert_eq!(
                    ClientMessage::decode(&encode_client(&message)).unwrap(),
                    message
                );
            }
        }
        assert!(split_text_block("").unwrap().is_empty());
    }

    #[test]
    fn a_text_over_the_limit_is_refused_whole() {
        assert!(split_text_block(&"a".repeat(MAX_TEXT_BLOCK_BYTES)).is_ok());
        assert!(matches!(
            split_text_block(&"a".repeat(MAX_TEXT_BLOCK_BYTES + 1)),
            Err(ProtocolError::PayloadTooLarge { .. })
        ));
    }

    #[test]
    fn command_result_wire_layout_and_roundtrip() {
        let result = ServerMessage::CommandResult {
            request_id: 0x0102_0304,
            status: CommandStatus::Failed,
            value: 258,
        };
        let mut buf = BytesMut::new();
        result.encode(&mut buf).unwrap();
        // [type][id: u32][status][value: u32]
        assert_eq!(&buf[..], &[0x83, 4, 3, 2, 1, 1, 2, 1, 0, 0]);
        assert_eq!(ServerMessage::decode(&buf).unwrap(), result);

        for status in [
            CommandStatus::Done,
            CommandStatus::Failed,
            CommandStatus::Cancelled,
            CommandStatus::Rejected,
            CommandStatus::Received,
        ] {
            assert_eq!(CommandStatus::try_from(status as u8).unwrap(), status);
        }
        assert!(CommandStatus::Done.is_final() && !CommandStatus::Received.is_final());
        assert!(matches!(
            ServerMessage::decode(&[0x83, 1, 0, 0, 0, 9, 0, 0, 0, 0]),
            Err(ProtocolError::InvalidEnumValue(9))
        ));
        assert!(matches!(
            ServerMessage::decode(&[0x83, 1, 0, 0, 0, 0]),
            Err(ProtocolError::BufferTooShort { .. })
        ));
    }

    #[test]
    fn active_window_roundtrips_and_clips_what_is_too_long() {
        let none = ServerMessage::ActiveWindow(None);
        let mut buf = BytesMut::new();
        none.encode(&mut buf).unwrap();
        assert_eq!(&buf[..], &[0x84, 0]);
        assert_eq!(ServerMessage::decode(&buf).unwrap(), none);

        let some = ServerMessage::ActiveWindow(Some(ActiveWindowInfo {
            process: "Code.exe".into(),
            title: "ملاحظات - Visual Studio Code".into(),
        }));
        buf.clear();
        some.encode(&mut buf).unwrap();
        assert_eq!(ServerMessage::decode(&buf).unwrap(), some);

        let long = ServerMessage::ActiveWindow(Some(ActiveWindowInfo {
            process: "p".into(),
            title: "ع".repeat(500),
        }));
        buf.clear();
        long.encode(&mut buf).unwrap();
        let ServerMessage::ActiveWindow(Some(decoded)) = ServerMessage::decode(&buf).unwrap()
        else {
            panic!("expected a window");
        };
        assert!(decoded.title.len() <= MAX_ACTIVE_WINDOW_FIELD_BYTES);
        assert!(
            decoded.title.chars().all(|c| c == 'ع'),
            "cut between characters"
        );

        for len in 1..buf.len() {
            let cut = &buf[..len];
            if len >= 2 {
                assert!(ServerMessage::decode(cut).is_err(), "len {len}");
            }
        }
    }

    #[test]
    fn the_new_capabilities_do_not_collide_with_the_old_ones() {
        let all = [
            capabilities::NOW_PLAYING,
            capabilities::CLIPBOARD,
            capabilities::TEXT_BLOCK,
            capabilities::ACTIVE_WINDOW,
        ];
        let combined = all.iter().fold(0u8, |acc, bit| {
            assert_eq!(acc & bit, 0, "bits overlap");
            acc | bit
        });
        assert_eq!(combined, 0x0F);
    }

    #[test]
    fn window_list_queries_roundtrip() {
        let buf = encode_client(&ClientMessage::WindowListQuery);
        assert_eq!(
            ClientMessage::decode(&buf).unwrap(),
            ClientMessage::WindowListQuery
        );
        let focus = ClientMessage::WindowFocus {
            request_id: 9,
            handle: 0x0123_4567_89AB_CDEF,
        };
        let buf = encode_client(&focus);
        assert_eq!(ClientMessage::decode(&buf).unwrap(), focus);
        assert!(ClientMessage::decode(&buf[..buf.len() - 1]).is_err());
    }

    #[test]
    fn run_command_roundtrips_for_every_kind() {
        let commands = [
            RemoteCommand::Chord {
                mods: modifiers::LCTRL | modifiers::LSHIFT,
                usage: 0x1B,
            },
            RemoteCommand::Media(MediaAction::Next),
            RemoteCommand::Volume(VolumeDirection::Mute),
            RemoteCommand::System(SystemAction::TaskView),
            RemoteCommand::LockScreen,
        ];
        for command in commands {
            let message = ClientMessage::RunCommand {
                request_id: 0xDEAD_BEEF,
                command,
            };
            let buf = encode_client(&message);
            assert_eq!(buf.len(), 9);
            assert_eq!(ClientMessage::decode(&buf).unwrap(), message);
            assert!(ClientMessage::decode(&buf[..buf.len() - 1]).is_err());
        }
    }

    #[test]
    fn run_command_with_an_unknown_kind_or_value_is_refused() {
        let mut buf = BytesMut::new();
        buf.put_u8(MSG_TYPE_RUN_COMMAND);
        buf.put_u32_le(1);
        buf.put_u8(9);
        buf.put_u8(0);
        buf.put_u16_le(0);
        assert!(ClientMessage::decode(&buf).is_err());

        let mut buf = BytesMut::new();
        buf.put_u8(MSG_TYPE_RUN_COMMAND);
        buf.put_u32_le(1);
        buf.put_u8(1); // media
        buf.put_u8(0);
        buf.put_u16_le(0x0101); // not a media action, and not a byte
        assert!(ClientMessage::decode(&buf).is_err());
    }

    fn entry(n: u64, process: &str, title: &str) -> WindowEntry {
        WindowEntry {
            handle: n,
            process: process.into(),
            title: title.into(),
            foreground: n == 1,
        }
    }

    #[test]
    fn window_list_roundtrips_with_arabic_titles() {
        let list = ServerMessage::WindowList(vec![
            entry(1, "Code.exe", "main.rs"),
            entry(2, "WINWORD.EXE", "تقرير الشهر.docx"),
        ]);
        let mut buf = BytesMut::new();
        list.encode(&mut buf).unwrap();
        assert_eq!(ServerMessage::decode(&buf).unwrap(), list);
    }

    #[test]
    fn window_list_is_cut_to_fit_one_datagram() {
        let many: Vec<WindowEntry> = (0..40)
            .map(|n| entry(n, &"p".repeat(100), &"ع".repeat(100)))
            .collect();
        let mut buf = BytesMut::new();
        ServerMessage::WindowList(many).encode(&mut buf).unwrap();
        assert!(buf.len() <= 1200, "{} bytes", buf.len());
        let ServerMessage::WindowList(decoded) = ServerMessage::decode(&buf).unwrap() else {
            panic!("not a list");
        };
        assert_eq!(decoded.len(), MAX_WINDOW_LIST_ENTRIES);
        // Cut inside no character: every title is still valid text of whole letters.
        assert!(decoded.iter().all(|w| w.title.chars().all(|c| c == 'ع')));
        assert!(decoded[0].title.len() <= MAX_WINDOW_LIST_TITLE_BYTES);
    }

    #[test]
    fn a_damaged_window_list_is_rejected() {
        let list = ServerMessage::WindowList(vec![entry(1, "Code.exe", "main.rs")]);
        let mut buf = BytesMut::new();
        list.encode(&mut buf).unwrap();
        for cut in 1..buf.len() {
            assert!(ServerMessage::decode(&buf[..cut]).is_err(), "cut at {cut}");
        }
        assert!(ServerMessage::decode(&[MSG_TYPE_WINDOW_LIST, 200]).is_err());
    }
}
