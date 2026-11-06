//! The UDP server: discovery, pairing, encrypted sessions and input dispatch.

use crate::clipboard::{truncate_to_char_boundary, ClipboardFactory, ClipboardHandle};
use crate::commands::Begin;
use crate::discovery;
use crate::input::InputWorker;
use crate::invite::{self, Invite};
use crate::pairing::{Admission, Pairing, PairingMode};
use crate::screen::{self, Picture, ScreenControl, Streamer};
use crate::sessions::{Session, SessionTable};
use crate::text_block::Accepted;
use beckon_crypto::{
    compute_fingerprint, CryptoError, HandshakeOutcome, IdentityStore, NoiseResponder, PairingStore,
};
use beckon_platform::InputBackend;
use beckon_protocol::*;
use bytes::BytesMut;
use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::Mutex;
use tokio::time::Instant;
use tracing::{debug, error, info, warn};

/// Socket receive buffer requested from the OS. A Wi-Fi stall releases a whole
/// backlog of packets at once; the stock buffers (64 KiB on Windows, ~208 KiB on
/// Linux) overflow and silently drop them. The OS may grant less than asked.
const RECEIVE_BUFFER_BYTES: usize = 1 << 20;

/// A transport datagram is at least: tag (1) + nonce (8) + AEAD tag (16).
const MIN_TRANSPORT_PACKET: usize = 1 + 8 + 16;

/// Largest clipboard text sent to a phone. Replies travel in one UDP datagram;
/// anything much past a path MTU is fragmented and routinely dropped on Wi-Fi,
/// and the phone's receive buffer is only 2 KiB, so a long clipboard used to
/// vanish without trace. A clipped answer beats none.
pub const MAX_CLIPBOARD_REPLY_BYTES: usize = 1200;

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub bind: SocketAddr,
    /// Directory holding `identity.key` and `trusted_clients.json`.
    pub config_dir: PathBuf,
    pub hostname: String,
    pub pairing: PairingMode,
    /// Join the discovery multicast group and announce on the LAN.
    pub discovery: bool,
    pub announce_interval: Duration,
    /// A session silent for this long is forgotten.
    pub session_idle_timeout: Duration,
    /// Keys and buttons a session holds are released after this much silence.
    pub held_input_timeout: Duration,
    pub maintenance_interval: Duration,
    pub max_sessions: usize,
}

impl ServerConfig {
    pub fn new(config_dir: PathBuf) -> Self {
        Self {
            bind: SocketAddr::from(([0, 0, 0, 0], BECKON_PORT)),
            config_dir,
            hostname: "Desktop-PC".to_owned(),
            pairing: PairingMode::WhenUnpaired(crate::pairing::DEFAULT_PAIRING_WINDOW),
            discovery: true,
            announce_interval: Duration::from_secs(3),
            session_idle_timeout: Duration::from_secs(60),
            held_input_timeout: Duration::from_secs(10),
            maintenance_interval: Duration::from_secs(1),
            max_sessions: 16,
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ServerError {
    #[error("{0}")]
    Bind(String),
    #[error("identity or pairing storage problem: {0}")]
    Crypto(#[from] CryptoError),
    #[error("could not start a worker thread: {0}")]
    Thread(#[from] io::Error),
}

fn bind_error(addr: SocketAddr, err: &io::Error) -> ServerError {
    let hint = match err.kind() {
        io::ErrorKind::AddrInUse => " (is another Beckon server already running?)",
        io::ErrorKind::PermissionDenied => " (ports below 1024 need elevated rights)",
        _ => "",
    };
    ServerError::Bind(format!("cannot listen on UDP {addr}: {err}{hint}"))
}

struct Identity {
    private: [u8; 32],
    public: [u8; 32],
    fingerprint: String,
}

struct State {
    pairing: Pairing,
    sessions: SessionTable,
}

struct Shared {
    socket: Arc<UdpSocket>,
    identity: Identity,
    state: Mutex<State>,
    input: InputWorker,
    clipboard: ClipboardHandle,
    backend_name: &'static str,
    config: ServerConfig,
}

pub struct Server {
    shared: Arc<Shared>,
}

/// A point-in-time summary for the console.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Status {
    pub sessions: usize,
    pub paired_devices: usize,
    pub pairing_window: Option<Duration>,
    /// Time left on the pairing invitation (the QR code), if one has been made.
    pub invitation: Option<Duration>,
    pub accepts_any_device: bool,
}

/// A cloneable handle for controlling a running server (used by the console).
#[derive(Clone)]
pub struct ServerHandle {
    shared: Arc<Shared>,
}

impl ServerHandle {
    pub async fn open_pairing(&self, duration: Duration) {
        self.shared
            .state
            .lock()
            .await
            .pairing
            .open_window(Instant::now(), duration);
    }

    pub async fn close_pairing(&self) {
        self.shared.state.lock().await.pairing.close_window();
    }

    /// Whether the owner of this PC lets paired phones see its screen.
    pub fn screen_view_allowed(&self) -> bool {
        screen::allowed(&self.shared.config.config_dir)
    }

    /// Lets paired phones see the screen, or stops them. A phone that is looking stops getting pictures at once.
    pub fn set_screen_view(&self, on: bool) -> io::Result<()> {
        screen::set_allowed(&self.shared.config.config_dir, on)
    }

    /// How many phones are looking at this PC's screen right now.
    pub async fn screen_viewers(&self) -> usize {
        let state = self.shared.state.lock().await;
        state
            .sessions
            .values()
            .filter(|session| session.screen.as_ref().is_some_and(|view| !view.stopped()))
            .count()
    }

    pub async fn status(&self) -> Status {
        let state = self.shared.state.lock().await;
        Status {
            sessions: state.sessions.len(),
            paired_devices: state.pairing.paired_count(),
            pairing_window: state.pairing.window_remaining(Instant::now()),
            invitation: state.pairing.invitation_remaining(Instant::now()),
            accepts_any_device: state.pairing.accepts_any_device(),
        }
    }

    /// Makes a pairing invitation for a QR code: a one-time token that lets the phone which scans
    /// it pair even though no pairing window is open. It is good for `ttl`, for one phone, and
    /// replaces any invitation made before it.
    pub async fn create_invite(&self, ttl: Duration) -> Invite {
        let token = self
            .shared
            .state
            .lock()
            .await
            .pairing
            .invite(Instant::now(), ttl);
        let port = self
            .shared
            .socket
            .local_addr()
            .map(|addr| addr.port())
            .unwrap_or(BECKON_PORT);
        let addresses: Vec<std::net::Ipv4Addr> = beckon_platform::netif::local_ipv4_networks()
            .iter()
            .map(|network| network.ip)
            .collect();
        Invite {
            addresses: invite::best_addresses(&addresses),
            url: invite::build_url(
                &self.shared.identity.public,
                &token,
                port,
                &self.shared.config.hostname,
                &addresses,
            ),
            expires_in: ttl,
        }
    }

    /// Fingerprints of every paired device.
    pub async fn paired_fingerprints(&self) -> Vec<String> {
        let state = self.shared.state.lock().await;
        state
            .pairing
            .paired_devices()
            .iter()
            .map(compute_fingerprint)
            .collect()
    }

    /// Unpairs every device and ends their sessions. Returns how many were paired.
    pub async fn forget_all(&self) -> Result<usize, CryptoError> {
        let (count, releases) = {
            let mut state = self.shared.state.lock().await;
            let count = state.pairing.paired_count();
            state.pairing.forget_all()?;
            (count, state.sessions.clear())
        };
        self.shared.input.send_all(releases);
        Ok(count)
    }
}

impl Server {
    /// Loads (or creates) the identity, binds the socket and starts the worker
    /// threads. Nothing is served until [`run`](Self::run).
    pub async fn bind(
        config: ServerConfig,
        input: Box<dyn InputBackend>,
        clipboard: ClipboardFactory,
    ) -> Result<Self, ServerError> {
        let (private, public) =
            IdentityStore::load_or_generate(&config.config_dir.join("identity.key"))?;
        let pairing_store =
            PairingStore::load_or_default(config.config_dir.join("trusted_clients.json"));

        let socket = UdpSocket::bind(config.bind)
            .await
            .map_err(|e| bind_error(config.bind, &e))?;
        // Needed to send discovery broadcasts from this socket; not fatal if refused.
        let _ = socket.set_broadcast(true);
        if let Err(err) = socket2::SockRef::from(&socket).set_recv_buffer_size(RECEIVE_BUFFER_BYTES)
        {
            debug!("could not enlarge the receive buffer: {err}");
        }

        let backend_name = input.name();
        let shared = Shared {
            socket: Arc::new(socket),
            identity: Identity {
                private,
                public,
                fingerprint: compute_fingerprint(&public),
            },
            state: Mutex::new(State {
                pairing: Pairing::new(pairing_store, config.pairing, Instant::now()),
                sessions: SessionTable::new(config.max_sessions),
            }),
            input: InputWorker::spawn(input)?,
            clipboard: ClipboardHandle::spawn(clipboard)?,
            backend_name,
            config,
        };
        Ok(Self {
            shared: Arc::new(shared),
        })
    }

    pub fn handle(&self) -> ServerHandle {
        ServerHandle {
            shared: Arc::clone(&self.shared),
        }
    }

    pub fn local_addr(&self) -> io::Result<SocketAddr> {
        self.shared.socket.local_addr()
    }

    pub fn public_key(&self) -> [u8; 32] {
        self.shared.identity.public
    }

    pub fn fingerprint(&self) -> &str {
        &self.shared.identity.fingerprint
    }

    pub fn hostname(&self) -> &str {
        &self.shared.config.hostname
    }

    /// Name of the input backend in use, for the startup banner.
    pub fn input_backend_name(&self) -> &'static str {
        self.shared.backend_name
    }

    /// Serves until `shutdown` completes, then releases anything clients left
    /// held down and stops the worker threads.
    pub async fn run(self, shutdown: impl Future<Output = ()>) {
        let shared = self.shared;

        let mut tasks = vec![tokio::spawn(maintenance(Arc::clone(&shared)))];
        if shared.config.discovery {
            let port = shared.socket.local_addr().map_or(BECKON_PORT, |a| a.port());
            tasks.push(tokio::spawn(discovery::run(
                Arc::clone(&shared.socket),
                shared.config.hostname.clone(),
                port,
                shared.config.announce_interval,
            )));
        }

        let mut buf = vec![0u8; 65_535];
        tokio::pin!(shutdown);
        loop {
            tokio::select! {
                () = &mut shutdown => break,
                received = shared.socket.recv_from(&mut buf) => match received {
                    Ok((len, src)) => handle_packet(&shared, &buf[..len], src).await,
                    // On Windows an ICMP "port unreachable" for an earlier send surfaces
                    // as a failed receive. It says nothing about this datagram.
                    Err(err) if err.kind() == io::ErrorKind::ConnectionReset => {}
                    Err(err) => {
                        error!("receive failed: {err}");
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                },
            }
        }

        for task in &tasks {
            task.abort();
        }
        let releases = shared.state.lock().await.sessions.release_all();
        shared.input.send_all(releases);
        shared.input.shutdown();
    }
}

/// Periodic housekeeping: forgets idle sessions, releases keys and buttons that
/// a vanished client left held, and reports when the pairing window closes.
async fn maintenance(shared: Arc<Shared>) {
    let mut ticker = tokio::time::interval(shared.config.maintenance_interval);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut window_was_open = false;

    loop {
        ticker.tick().await;
        let now = Instant::now();

        let (expired, releases, window_open) = {
            let mut state = shared.state.lock().await;
            let unfinished = state.sessions.expire_blocks(std::time::Instant::now());
            if unfinished > 0 {
                debug!("{unfinished} long text(s) dropped: their pieces stopped arriving");
            }
            let (expired, mut releases) = state
                .sessions
                .expire_idle(now, shared.config.session_idle_timeout);
            releases.extend(
                state
                    .sessions
                    .release_stale_holds(now, shared.config.held_input_timeout),
            );
            (
                expired,
                releases,
                state.pairing.is_open(now) && !state.pairing.accepts_any_device(),
            )
        };

        if expired > 0 {
            info!("{expired} idle session(s) closed");
        }
        if !releases.is_empty() {
            info!(
                "released {} key/button(s) left held by a device that went quiet",
                releases.len()
            );
            shared.input.send_all(releases);
        }
        if window_was_open && !window_open {
            info!(
                "pairing window closed; only paired devices can connect \
                 (to add one, use the 'pair' console command or restart with --pair)"
            );
        }
        window_was_open = window_open;
    }
}

async fn handle_packet(shared: &Arc<Shared>, packet: &[u8], src: SocketAddr) {
    let Some(&tag) = packet.first() else { return };
    match tag {
        WIRE_DISCOVERY_PROBE => {
            if discovery::is_discovery_probe(packet) {
                debug!("discovery probe from {src}");
                let (tagged, raw) = discovery::pong_packets(&shared.config.hostname);
                let _ = shared.socket.send_to(&tagged, src).await;
                let _ = shared.socket.send_to(&raw, src).await;
            }
        }
        // Our own announcements, echoed back by broadcast/multicast loopback.
        WIRE_DISCOVERY_REPLY => {}
        b'T' if discovery::is_pong_echo(packet) => {}
        WIRE_PAIRING_INTRO_REQ => {
            // Public by design: the phone needs the key to authenticate this server.
            // It confers nothing: connecting still requires being paired.
            let mut reply = Vec::with_capacity(1 + 32);
            reply.push(WIRE_PAIRING_INTRO_RESP);
            reply.extend_from_slice(&shared.identity.public);
            match shared.socket.send_to(&reply, src).await {
                Ok(_) => debug!("sent pairing intro to {src}"),
                Err(err) => warn!("could not send pairing intro to {src}: {err}"),
            }
        }
        WIRE_HANDSHAKE_INIT => handshake(shared, packet, src).await,
        WIRE_TRANSPORT => transport(shared, packet, src).await,
        _ => debug!("unknown packet tag 0x{tag:02X} from {src}"),
    }
}

async fn handshake(shared: &Arc<Shared>, packet: &[u8], src: SocketAddr) {
    // The Diffie-Hellman work runs before the state lock is taken, so a flood of
    // handshakes cannot stall packets from connected clients.
    let HandshakeOutcome {
        reply,
        client_key,
        transport,
        payload: token,
    } = match NoiseResponder::new(shared.identity.private)
        .respond_handshake_with_payload(&packet[1..])
    {
        Ok(ok) => ok,
        Err(err) => {
            warn!("handshake from {src} failed: {err}");
            return;
        }
    };
    let device = compute_fingerprint(&client_key);
    let now = Instant::now();

    let (admission, save_error, releases) = {
        let mut state = shared.state.lock().await;
        // A phone that scanned the QR code carries its token inside the handshake.
        let (admission, save_error) = state.pairing.admit_with_token(&client_key, &token, now);
        let releases = if admission.is_allowed() {
            state
                .sessions
                .insert(src, Session::new(transport, client_key, now))
        } else {
            Vec::new()
        };
        (admission, save_error, releases)
    };
    shared.input.send_all(releases);

    if let Some(err) = save_error {
        warn!("device {device} connected but could not be saved as paired: {err}");
    }
    match admission {
        Admission::Known => info!("device {device} connected from {src}"),
        Admission::NewlyPaired => info!(
            "paired new device {device} ({src}). Pairing is closed again; \
             type 'pair' to add another."
        ),
        Admission::Rejected => {
            warn!(
                "refused {src}: device {device} has not been paired. Type 'pair' in this \
                 console (or start the server with --pair) to let it connect."
            );
            // Tell the phone now rather than leaving it to time out.
            let _ = shared.socket.send_to(&[WIRE_PAIRING_REJECTED], src).await;
            return;
        }
    }

    let mut out = Vec::with_capacity(1 + reply.len());
    out.push(WIRE_HANDSHAKE_RESP);
    out.extend_from_slice(&reply);
    let _ = shared.socket.send_to(&out, src).await;
}

async fn transport(shared: &Arc<Shared>, packet: &[u8], src: SocketAddr) {
    if packet.len() < MIN_TRANSPORT_PACKET {
        debug!(
            "transport packet from {src} too short ({} bytes)",
            packet.len()
        );
        return;
    }
    let nonce = u64::from_le_bytes(packet[1..9].try_into().expect("slice is 8 bytes"));
    let ciphertext = &packet[9..];

    let message = {
        let mut state = shared.state.lock().await;
        let Some(session) = state.sessions.get_mut(&src) else {
            debug!("transport packet from {src} without a session");
            return;
        };
        let plaintext = match session.transport.decrypt(nonce, ciphertext) {
            Ok(plaintext) => plaintext,
            Err(err) => {
                debug!("dropping packet from {src} (nonce {nonce}): {err}");
                return;
            }
        };
        // Only an authenticated packet counts as sign of life, so spoofed junk
        // cannot keep a dead session (and its held keys) alive.
        session.last_seen = Instant::now();
        match ClientMessage::decode(&plaintext) {
            Ok(message) => {
                session.held.observe(&message);
                message
            }
            Err(err) => {
                debug!("undecodable message from {src}: {err}");
                return;
            }
        }
    };

    match message {
        ClientMessage::ClipboardSet(text) => shared.clipboard.set(text),
        ClientMessage::ClipboardGet => {
            // The clipboard can be slow; never make the receive loop wait on it.
            let shared = Arc::clone(shared);
            tokio::spawn(async move {
                let Some(text) = shared.clipboard.get().await else {
                    return;
                };
                let clipped = truncate_to_char_boundary(&text, MAX_CLIPBOARD_REPLY_BYTES);
                if clipped.len() < text.len() {
                    warn!(
                        "clipboard text is {} bytes; sending the first {} (a UDP datagram cannot carry more)",
                        text.len(),
                        clipped.len()
                    );
                }
                send_reply(
                    &shared,
                    src,
                    &ServerMessage::ClipboardData(clipped.to_owned()),
                )
                .await;
            });
        }
        // Now-playing metadata is not implemented yet: report "nothing playing".
        ClientMessage::NowPlayingQuery => {
            send_reply(
                shared,
                src,
                &ServerMessage::NowPlaying(NowPlayingState::default()),
            )
            .await;
        }
        ClientMessage::HostInfoQuery => {
            send_reply(shared, src, &ServerMessage::HostInfo(host_info())).await;
        }
        ClientMessage::TextBlockChunk {
            request_id,
            index,
            total,
            text,
        } => text_block_chunk(shared, src, request_id, index, total, text).await,
        ClientMessage::TextBlockCancel { request_id } => {
            let result = {
                let mut state = shared.state.lock().await;
                state
                    .sessions
                    .get_mut(&src)
                    .and_then(|session| session.blocks.cancel(request_id))
            };
            // `None`: it is being typed, and typing will say how it ended.
            if let Some((status, value)) = result {
                let result = ServerMessage::CommandResult {
                    request_id,
                    status,
                    value,
                };
                send_reply(shared, src, &result).await;
            }
        }
        ClientMessage::ActiveWindowQuery => {
            // Asking the system can take a moment; never make the receive loop wait on it. The
            // answer goes only to the phone that asked, and nothing is kept.
            let shared = Arc::clone(shared);
            tokio::spawn(async move {
                let window = tokio::task::spawn_blocking(beckon_platform::active_window)
                    .await
                    .ok()
                    .flatten();
                send_reply(&shared, src, &ServerMessage::ActiveWindow(window)).await;
            });
        }
        ClientMessage::WindowListQuery => {
            // Like the front-window question: asked of the system off the receive loop, answered only to
            // the phone that asked, and nothing is kept.
            let shared = Arc::clone(shared);
            tokio::spawn(async move {
                let windows = tokio::task::spawn_blocking(beckon_platform::open_windows)
                    .await
                    .unwrap_or_default();
                send_reply(&shared, src, &ServerMessage::WindowList(windows)).await;
            });
        }
        ClientMessage::RunCommand {
            request_id,
            command,
        } => {
            let begin = {
                let mut state = shared.state.lock().await;
                match state.sessions.get_mut(&src) {
                    Some(session) => session.commands.begin(request_id),
                    None => return,
                }
            };
            let result = move |status: CommandStatus| ServerMessage::CommandResult {
                request_id,
                status,
                value: 0,
            };
            match begin {
                Begin::InFlight => {}
                Begin::Repeat(status) => send_reply(shared, src, &result(status)).await,
                Begin::Run => {
                    let outcome = shared.input.run(command);
                    let shared = Arc::clone(shared);
                    tokio::spawn(async move {
                        // The input thread is gone: nothing was done that anyone can vouch for.
                        let status = outcome.await.unwrap_or(CommandStatus::Failed);
                        {
                            let mut state = shared.state.lock().await;
                            if let Some(session) = state.sessions.get_mut(&src) {
                                session.commands.finish(request_id, status);
                            }
                        }
                        send_reply(&shared, src, &result(status)).await;
                    });
                }
            }
        }
        ClientMessage::ScreenView(request) => screen_view(shared, src, request).await,
        ClientMessage::WindowFocus { request_id, handle } => {
            let shared = Arc::clone(shared);
            tokio::spawn(async move {
                let status = tokio::task::spawn_blocking(move || focus_listed_window(handle))
                    .await
                    .unwrap_or(CommandStatus::Failed);
                let result = ServerMessage::CommandResult {
                    request_id,
                    status,
                    value: 0,
                };
                send_reply(&shared, src, &result).await;
            });
        }
        ClientMessage::TypedEdit {
            seq,
            backspaces,
            text,
        } => {
            // Applied once, in order. An edit that is early (an earlier one was lost) or late (a copy of one
            // already applied) is not applied, and the answer says which edit is wanted.
            let next = {
                let mut state = shared.state.lock().await;
                let Some(session) = state.sessions.get_mut(&src) else {
                    return;
                };
                if seq == session.typing_next {
                    session.typing_next = seq.wrapping_add(1);
                    shared.input.send(ClientMessage::TypedEdit {
                        seq,
                        backspaces,
                        text,
                    });
                }
                session.typing_next
            };
            send_reply(shared, src, &ServerMessage::TypedAck { next }).await;
        }
        other => shared.input.send(other),
    }
}

/// A phone starts, keeps going or stops looking at the screen.
async fn screen_view(shared: &Arc<Shared>, src: SocketAddr, request: ScreenViewRequest) {
    let status = |state: ScreenState, width: u16, height: u16| ServerMessage::ScreenStatus {
        state,
        width,
        height,
    };
    match request {
        ScreenViewRequest::Stop => {
            let running = {
                let mut state = shared.state.lock().await;
                state
                    .sessions
                    .get_mut(&src)
                    .and_then(|session| session.screen.take())
            };
            if let Some(control) = running {
                control.stop();
            }
            send_reply(shared, src, &status(ScreenState::Stopped, 0, 0)).await;
        }
        ScreenViewRequest::Start { max_width, fps } => {
            if !beckon_platform::SCREEN_CAPTURE_SUPPORTED {
                send_reply(shared, src, &status(ScreenState::Unsupported, 0, 0)).await;
                return;
            }
            // Only the owner of this PC can allow it (the console's `screen on`), never a phone.
            if !screen::allowed(&shared.config.config_dir) {
                send_reply(shared, src, &status(ScreenState::Disabled, 0, 0)).await;
                return;
            }
            let max_width = max_width.clamp(160, MAX_SCREEN_WIDTH);
            let fps = fps.clamp(1, MAX_SCREEN_FPS);
            let now = std::time::Instant::now();
            let begun = {
                let mut state = shared.state.lock().await;
                let Some(session) = state.sessions.get_mut(&src) else {
                    return;
                };
                if let Some(running) = session
                    .screen
                    .as_ref()
                    .filter(|running| !running.stopped() && running.same_as(max_width, fps))
                {
                    // The same request again: the phone is still looking.
                    running.touch(now);
                    Err(running.size())
                } else {
                    if let Some(old) = session.screen.take() {
                        old.stop();
                    }
                    let control = ScreenControl::new(max_width, fps, now);
                    session.screen = Some(Arc::clone(&control));
                    Ok(control)
                }
            };
            match begun {
                Ok(control) => {
                    tokio::spawn(stream_screen(Arc::clone(shared), src, control));
                }
                // The phone may not have heard that pictures are coming: say so again.
                Err(Some((width, height))) => {
                    send_reply(shared, src, &status(ScreenState::Started, width, height)).await;
                }
                Err(None) => {}
            }
        }
    }
}

/// What one look at the screen came to.
enum Grab {
    Picture(Picture),
    Same,
    Failed(String),
}

/// Sends pictures of the screen to one phone until it stops, goes quiet, is disconnected, or the owner takes the
/// permission back.
async fn stream_screen(shared: Arc<Shared>, dst: SocketAddr, control: Arc<ScreenControl>) {
    info!("{dst} is looking at this PC's screen");
    let max_width = usize::from(control.max_width);
    let interval = Duration::from_millis(1000 / u64::from(control.fps.max(1)));
    let mut streamer = Streamer::default();
    let mut announced: Option<(u16, u16)> = None;
    loop {
        if control.stopped()
            || control.forgotten(std::time::Instant::now())
            || !screen::allowed(&shared.config.config_dir)
        {
            break;
        }
        // The phone must still be connected, and this must still be its view (a new session starts without one).
        let mine = {
            let state = shared.state.lock().await;
            state
                .sessions
                .get(&dst)
                .and_then(|session| session.screen.as_ref())
                .is_some_and(|current| Arc::ptr_eq(current, &control))
        };
        if !mine {
            break;
        }
        let begun = tokio::time::Instant::now();
        let looked = tokio::task::spawn_blocking(move || {
            let grab = match beckon_platform::capture_screen() {
                Ok(image) => match streamer.prepare(
                    &image.bgra,
                    image.width,
                    image.height,
                    max_width,
                    std::time::Instant::now(),
                ) {
                    Ok(Some(picture)) => Grab::Picture(picture),
                    Ok(None) => Grab::Same,
                    Err(error) => Grab::Failed(format!("{error:?}")),
                },
                Err(reason) => Grab::Failed(reason),
            };
            (streamer, grab)
        })
        .await;
        let Ok((back, grab)) = looked else {
            break;
        };
        streamer = back;
        match grab {
            Grab::Same => {}
            Grab::Failed(reason) => {
                warn!("could not copy the screen for {dst}: {reason}");
                let failed = ServerMessage::ScreenStatus {
                    state: ScreenState::Failed,
                    width: 0,
                    height: 0,
                };
                send_reply(&shared, dst, &failed).await;
                break;
            }
            Grab::Picture(picture) => {
                if announced != Some((picture.width, picture.height)) {
                    control.set_size(picture.width, picture.height);
                    let started = ServerMessage::ScreenStatus {
                        state: ScreenState::Started,
                        width: picture.width,
                        height: picture.height,
                    };
                    send_reply(&shared, dst, &started).await;
                    announced = Some((picture.width, picture.height));
                }
                send_picture(&shared, dst, &control, &picture).await;
            }
        }
        tokio::time::sleep(interval.saturating_sub(begun.elapsed())).await;
    }
    {
        let mut state = shared.state.lock().await;
        if let Some(session) = state.sessions.get_mut(&dst) {
            if session
                .screen
                .as_ref()
                .is_some_and(|current| Arc::ptr_eq(current, &control))
            {
                session.screen = None;
            }
        }
    }
    info!("{dst} stopped looking at this PC's screen");
}

/// Sends one picture as the pieces it is cut into, a few at a time.
async fn send_picture(
    shared: &Shared,
    dst: SocketAddr,
    control: &ScreenControl,
    picture: &Picture,
) {
    let Some(pieces) = screen::split_picture(&picture.data) else {
        return;
    };
    let Ok(count) = u16::try_from(pieces.len()) else {
        return;
    };
    for (index, piece) in pieces.into_iter().enumerate() {
        if control.stopped() {
            return;
        }
        let Ok(index) = u16::try_from(index) else {
            return;
        };
        let message = ServerMessage::ScreenFrame {
            frame_id: picture.id,
            index,
            count,
            width: picture.width,
            height: picture.height,
            data: piece.to_vec(),
        };
        send_reply(shared, dst, &message).await;
        // A Wi-Fi link drops what arrives in one burst, so the pieces go out a few at a time.
        if index % 12 == 11 {
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    }
}

/// Brings `handle` to the front, but only if it is one of the windows Beckon lists: a phone cannot name an
/// arbitrary handle. `Done` only when the window is in front afterwards.
fn focus_listed_window(handle: u64) -> CommandStatus {
    if !beckon_platform::open_windows()
        .iter()
        .any(|window| window.handle == handle)
    {
        return CommandStatus::Rejected;
    }
    match beckon_platform::focus_window(handle) {
        Ok(()) => CommandStatus::Done,
        Err(reason) => {
            tracing::debug!("window not brought to the front: {reason}");
            CommandStatus::Failed
        }
    }
}

/// One piece of a long text. When the last one arrives the text is typed (in order with the keys
/// sent before it), and the phone is told how it went: `Received` as typing starts, then `Done`,
/// `Failed` or `Cancelled` once the system has actually taken it. A phone that repeats itself gets
/// the same answer and the text is not typed twice (see [`crate::text_block`]).
async fn text_block_chunk(
    shared: &Arc<Shared>,
    src: SocketAddr,
    request_id: u32,
    index: u16,
    total: u16,
    text: String,
) {
    let accepted = {
        let mut state = shared.state.lock().await;
        let Some(session) = state.sessions.get_mut(&src) else {
            return;
        };
        session
            .blocks
            .accept(request_id, index, total, text, std::time::Instant::now())
    };
    let result = move |status: CommandStatus, value: u32| ServerMessage::CommandResult {
        request_id,
        status,
        value,
    };
    match accepted {
        Accepted::Waiting | Accepted::Busy => {}
        Accepted::Rejected => {
            send_reply(shared, src, &result(CommandStatus::Rejected, 0)).await;
        }
        Accepted::Repeat(status, value) => {
            send_reply(shared, src, &result(status, value)).await;
        }
        Accepted::Complete { text, cancel } => {
            send_reply(shared, src, &result(CommandStatus::Received, 0)).await;
            let outcome = shared.input.type_block(text, cancel);
            let shared = Arc::clone(shared);
            tokio::spawn(async move {
                let (status, typed) = match outcome.await {
                    Ok(done) => (done.status, done.typed),
                    // The input thread is gone: nothing was typed that anyone can vouch for.
                    Err(_) => (CommandStatus::Failed, 0),
                };
                {
                    let mut state = shared.state.lock().await;
                    if let Some(session) = state.sessions.get_mut(&src) {
                        session.blocks.finish(request_id, status, typed);
                    }
                }
                send_reply(&shared, src, &result(status, typed)).await;
            });
        }
    }
}

/// What this server tells a phone about itself: the OS it runs on, the features
/// it offers (so the app can hide what is not implemented) and its version.
pub fn host_info() -> HostInfo {
    HostInfo {
        os: HostOs::current(),
        // Now-playing metadata is not implemented yet, so it is not advertised.
        capabilities: advertised_capabilities(),
        version: parse_version(env!("CARGO_PKG_VERSION")),
    }
}

/// The optional features this server offers. Long texts work everywhere input does; the window in
/// front can only be asked for on Windows so far, and a phone must not be told otherwise.
fn advertised_capabilities() -> u8 {
    let mut caps = capabilities::CLIPBOARD
        | capabilities::TEXT_BLOCK
        | capabilities::COMMANDS
        | capabilities::RELIABLE_TYPING;
    if beckon_platform::ACTIVE_WINDOW_SUPPORTED {
        caps |= capabilities::ACTIVE_WINDOW | capabilities::WINDOW_LIST;
    }
    // Offered wherever the screen can be copied; whether the owner allows it is answered when a phone asks.
    if beckon_platform::SCREEN_CAPTURE_SUPPORTED {
        caps |= capabilities::SCREEN;
    }
    caps
}

/// `"2.1.7"` -> `(2, 1, 7)`. Pre-release suffixes (`2.1.7-rc1`) are ignored and
/// missing or oversized components saturate, so this never panics.
fn parse_version(version: &str) -> (u8, u8, u8) {
    let mut parts = version
        .split(|c: char| !c.is_ascii_digit())
        .filter(|part| !part.is_empty())
        .map(|part| {
            part.parse::<u32>()
                .map_or(u8::MAX, |n| u8::try_from(n).unwrap_or(u8::MAX))
        });
    (
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
    )
}

/// `[WIRE_TRANSPORT][nonce: u64 LE][ciphertext]`
pub fn frame_transport(nonce: u64, ciphertext: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(1 + 8 + ciphertext.len());
    out.push(WIRE_TRANSPORT);
    out.extend_from_slice(&nonce.to_le_bytes());
    out.extend_from_slice(ciphertext);
    out
}

async fn send_reply(shared: &Shared, dst: SocketAddr, message: &ServerMessage) {
    let mut plain = BytesMut::new();
    if let Err(err) = message.encode(&mut plain) {
        warn!("could not encode reply for {dst}: {err}");
        return;
    }
    let datagram = {
        let state = shared.state.lock().await;
        let Some(session) = state.sessions.get(&dst) else {
            return;
        };
        match session.transport.encrypt(&plain) {
            Ok((nonce, ciphertext)) => frame_transport(nonce, &ciphertext),
            Err(err) => {
                warn!("could not encrypt reply for {dst}: {err}");
                return;
            }
        }
    };
    let _ = shared.socket.send_to(&datagram, dst).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transport_frames_carry_tag_nonce_then_ciphertext() {
        let frame = frame_transport(0x0102_0304_0506_0708, &[0xAA, 0xBB]);
        assert_eq!(
            frame,
            vec![WIRE_TRANSPORT, 8, 7, 6, 5, 4, 3, 2, 1, 0xAA, 0xBB],
            "nonce is little-endian"
        );
    }

    #[test]
    fn version_strings_become_triplets() {
        assert_eq!(parse_version("2.1.7"), (2, 1, 7));
        assert_eq!(parse_version("2.0.0"), (2, 0, 0));
        assert_eq!(parse_version("3.4.5-rc.1"), (3, 4, 5));
        assert_eq!(parse_version("1.2"), (1, 2, 0));
        assert_eq!(parse_version(""), (0, 0, 0));
        assert_eq!(parse_version("999.1000.70000"), (255, 255, 255));
    }

    #[test]
    fn host_info_reports_this_build() {
        let info = host_info();
        assert_eq!(info.os, HostOs::current());
        assert!(info.supports(capabilities::CLIPBOARD));
        assert!(info.supports(capabilities::TEXT_BLOCK));
        assert_eq!(
            info.supports(capabilities::ACTIVE_WINDOW),
            beckon_platform::ACTIVE_WINDOW_SUPPORTED,
            "only advertised where it can be answered"
        );
        assert_eq!(
            info.supports(capabilities::WINDOW_LIST),
            beckon_platform::ACTIVE_WINDOW_SUPPORTED
        );
        assert!(
            !info.supports(capabilities::NOW_PLAYING),
            "not implemented yet"
        );
        assert_eq!(info.version, parse_version(env!("CARGO_PKG_VERSION")));
    }

    #[test]
    fn bind_errors_explain_the_likely_cause() {
        let addr: SocketAddr = "0.0.0.0:5000".parse().unwrap();
        let busy = bind_error(addr, &io::Error::from(io::ErrorKind::AddrInUse)).to_string();
        assert!(
            busy.contains("5000") && busy.contains("already running"),
            "{busy}"
        );
        let denied =
            bind_error(addr, &io::Error::from(io::ErrorKind::PermissionDenied)).to_string();
        assert!(denied.contains("elevated"), "{denied}");
    }

    #[test]
    fn default_config_is_locked_down_after_first_run() {
        let config = ServerConfig::new(PathBuf::from("x"));
        assert_eq!(config.bind.port(), BECKON_PORT);
        assert!(matches!(config.pairing, PairingMode::WhenUnpaired(_)));
        assert!(config.held_input_timeout < config.session_idle_timeout);
    }
}
