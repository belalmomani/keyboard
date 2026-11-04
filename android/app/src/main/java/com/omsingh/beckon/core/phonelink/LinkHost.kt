package com.omsingh.beckon.core.phonelink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** How the receiving phone's side of a link stands. */
sealed interface HostState {
    data object Idle : HostState

    /** The code is on screen; nobody has used it yet. */
    data class Waiting(val invite: LinkInvite) : HostState

    /** Another phone scanned the code and says it is [deviceName]: the person decides. */
    data class Asking(val deviceName: String) : HostState

    data class Connected(val deviceName: String) : HostState

    /** Over. The code is spent: a new one is needed to link again. */
    data class Closed(val reason: CloseReason) : HostState
}

enum class CloseReason { FINISHED, REJECTED, EXPIRED, TOO_MANY_TRIES, LOST, FAILED }

/** One picture of the other phone's screen, as a JPEG. */
class LinkPicture(val width: Int, val height: Int, val jpeg: ByteArray, val counter: Long)

/**
 * The receiving phone: shows a code, waits for one phone to scan it, asks the person, and then takes the
 * screen and files that phone sends.
 *
 * The code is good for one phone and a few minutes. After a wrong key is tried a few times, or the phone
 * is refused, or the link ends, it is spent.
 */
class LinkHost(
    private val files: ReceivedFiles,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val timing: Timing = Timing(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Timing(
        val codeLifetimeMs: Long = 5 * 60_000L,
        val handshakeMs: Int = 8_000,
        val askingMs: Long = 60_000L,
        val silenceMs: Int = 25_000,
        val maxWrongTries: Int = 3,
    )

    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _screen = MutableStateFlow<LinkPicture?>(null)

    /** The latest picture of the other phone's screen, or null when it is not being shown. */
    val screen: StateFlow<LinkPicture?> = _screen.asStateFlow()

    private val _received = MutableStateFlow<List<ReceivedFile>>(emptyList())

    /** Everything received (including earlier visits), newest first. */
    val received: StateFlow<List<ReceivedFile>> = _received.asStateFlow()

    /** Progress of the file coming in: bytes so far and total, or null. */
    private val _incoming = MutableStateFlow<Pair<String, Float>?>(null)
    val incoming: StateFlow<Pair<String, Float>?> = _incoming.asStateFlow()

    private var server: ServerSocket? = null
    private var job: Job? = null
    private var decision = CompletableDeferred<Boolean>()
    @Volatile private var epoch = 0
    @Volatile private var activeSocket: Socket? = null
    private var frames = 0L

    /** Starts listening and returns the invitation to show as a QR code. */
    @Synchronized
    fun start(addresses: List<String>, name: String?): LinkInvite {
        stop()
        _received.value = files.list()
        val socket = ServerSocket(0)
        server = socket
        val invite = LinkInvite.create(addresses, socket.localPort, name)
        decision = CompletableDeferred()
        _screen.value = null
        _state.value = HostState.Waiting(invite)
        val mine = ++epoch
        job = scope.launch { serve(socket, invite, mine) }
        return invite
    }

    /** The person allows the phone that is asking. */
    fun accept() {
        decision.complete(true)
    }

    fun reject() {
        decision.complete(false)
    }

    @Synchronized
    fun stop() {
        epoch++
        job?.cancel()
        job = null
        try { server?.close() } catch (_: IOException) {}
        try { activeSocket?.close() } catch (_: IOException) {}
        server = null
        decision.complete(false)
        if (_state.value !is HostState.Closed) _state.value = HostState.Idle
        _screen.value = null
        _incoming.value = null
    }

    private fun serve(socket: ServerSocket, invite: LinkInvite, mine: Int) {
        val deadline = clock() + timing.codeLifetimeMs
        var wrong = 0
        try {
            while (true) {
                val left = deadline - clock()
                if (left <= 0) return close(CloseReason.EXPIRED, mine)
                socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val client = try {
                    socket.accept()
                } catch (_: SocketTimeoutException) {
                    return close(CloseReason.EXPIRED, mine)
                }
                activeSocket = client
                val outcome = handle(client, invite, mine)
                activeSocket = null
                if (epoch != mine) return
                when (outcome) {
                    Outcome.WrongKey -> {
                        wrong++
                        if (wrong >= timing.maxWrongTries) return close(CloseReason.TOO_MANY_TRIES, mine)
                    }
                    Outcome.Noise -> Unit
                    is Outcome.Over -> return close(outcome.reason, mine)
                }
            }
        } catch (_: IOException) {
            // The server socket was closed by stop().
        }
    }

    private sealed interface Outcome {
        data object WrongKey : Outcome
        data object Noise : Outcome
        data class Over(val reason: CloseReason) : Outcome
    }

    private fun close(reason: CloseReason, mine: Int) {
        if (epoch != mine) return
        try { server?.close() } catch (_: IOException) {}
        _screen.value = null
        _incoming.value = null
        _state.value = HostState.Closed(reason)
    }

    private fun handle(client: Socket, invite: LinkInvite, mine: Int): Outcome {
        client.use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timing.handshakeMs
            val channel = try {
                LinkChannel.asReceiver(socket.getInputStream(), socket.getOutputStream(), invite.secret)
            } catch (_: LinkSecurityException) {
                return Outcome.Noise
            } catch (_: IOException) {
                return Outcome.Noise
            }
            val hello = try {
                LinkMessage.decode(channel.receive()) as? LinkMessage.Hello
            } catch (_: LinkSecurityException) {
                return Outcome.WrongKey
            } catch (_: IOException) {
                return Outcome.Noise
            } ?: return Outcome.Noise

            val deviceName = hello.name.ifBlank { "Phone" }
            if (epoch != mine) return Outcome.Over(CloseReason.LOST)
            _state.value = HostState.Asking(deviceName)
            val allowed = waitForDecision()
            return try {
                if (!allowed) {
                    channel.send(LinkMessage.encode(LinkMessage.Rejected))
                    Outcome.Over(CloseReason.REJECTED)
                } else {
                    channel.send(LinkMessage.encode(LinkMessage.Accepted(invite.name ?: "")))
                    if (epoch != mine) return Outcome.Over(CloseReason.LOST)
                    _state.value = HostState.Connected(deviceName)
                    Outcome.Over(converse(socket, channel, mine))
                }
            } catch (_: IOException) {
                Outcome.Over(CloseReason.LOST)
            }
        }
    }

    /** Blocks this I/O thread until the person answers or the time is up. */
    private fun waitForDecision(): Boolean {
        val deferred = decision
        return runBlocking { withTimeoutOrNull(timing.askingMs) { deferred.await() } ?: false }
    }

    private fun converse(socket: Socket, channel: LinkChannel, mine: Int): CloseReason {
        socket.soTimeout = timing.silenceMs
        var open: ReceivedFiles.Incoming? = null
        try {
            while (true) {
                val message = try {
                    LinkMessage.decode(channel.receive())
                } catch (_: SocketTimeoutException) {
                    return CloseReason.LOST
                } catch (_: LinkSecurityException) {
                    return CloseReason.FAILED
                } catch (_: IOException) {
                    return CloseReason.LOST
                } ?: continue

                when (message) {
                    is LinkMessage.Frame -> {
                        _screen.value = LinkPicture(message.width, message.height, message.jpeg, ++frames)
                    }
                    LinkMessage.ScreenEnded -> _screen.value = null
                    is LinkMessage.FileStart -> {
                        open?.abandon()
                        open = try {
                            files.begin(message.id, message.size, message.name).also {
                                _incoming.value = ReceivedFiles.safeName(message.name) to 0f
                            }
                        } catch (_: IOException) {
                            channel.send(LinkMessage.encode(LinkMessage.FileResult(message.id, false)))
                            null
                        }
                        incomingName = ReceivedFiles.safeName(message.name)
                    }
                    is LinkMessage.FileData -> {
                        val current = open
                        if (current != null && current.id == message.id) {
                            try {
                                current.write(message.bytes)
                                _incoming.value = incomingName to
                                    (if (current.declaredSize == 0L) 1f else current.written.toFloat() / current.declaredSize)
                            } catch (_: IOException) {
                                current.abandon()
                                open = null
                                _incoming.value = null
                                channel.send(LinkMessage.encode(LinkMessage.FileResult(message.id, false)))
                            }
                        }
                    }
                    is LinkMessage.FileEnd -> {
                        val current = open
                        if (current != null && current.id == message.id) {
                            val saved = current.finish(clock())
                            open = null
                            _incoming.value = null
                            if (saved != null) _received.value = listOf(saved) + _received.value
                            channel.send(LinkMessage.encode(LinkMessage.FileResult(message.id, saved != null)))
                        }
                    }
                    LinkMessage.Bye -> return CloseReason.FINISHED
                    LinkMessage.Ping -> Unit
                    else -> Unit
                }
            }
        } finally {
            open?.abandon()
            if (epoch == mine) {
                _incoming.value = null
                _screen.value = null
            }
        }
    }

    private var incomingName = ""
}
