package com.omsingh.beckon.core.phonelink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** How the sending phone's side of a link stands. */
sealed interface ClientState {
    data object Idle : ClientState
    data object Connecting : ClientState

    /** Connected, and the other phone's owner is deciding. */
    data object WaitingForApproval : ClientState

    data class Connected(val hostName: String) : ClientState
    data class Failed(val reason: FailReason) : ClientState
    data object Closed : ClientState
}

enum class FailReason { UNREACHABLE, NOT_THE_CODE, REJECTED, LOST, NO_ANSWER }

/**
 * The sending phone: connects to the phone whose code it scanned, and sends its screen and files.
 */
class LinkClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val connectMs: Int = 3_000,
        val answerMs: Long = 75_000L,
        val pingEveryMs: Long = 5_000L,
        val fileChunk: Int = 32 * 1024,
        val fileResultMs: Long = 30_000L,
    )

    private val _state = MutableStateFlow<ClientState>(ClientState.Idle)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    @Volatile private var channel: LinkChannel? = null
    @Volatile private var socket: Socket? = null
    private var reader: Job? = null
    private var pinger: Job? = null
    private val results = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val nextFileId = AtomicInteger(1)
    private val sendingFrame = AtomicBoolean(false)

    /** Connects and waits for the other phone's owner to answer. Returns true once linked. */
    suspend fun connect(invite: LinkInvite, myName: String): Boolean = withContext(Dispatchers.IO) {
        close(quiet = true)
        _state.value = ClientState.Connecting
        val opened = openSocket(invite)
        if (opened == null) {
            _state.value = ClientState.Failed(FailReason.UNREACHABLE)
            return@withContext false
        }
        socket = opened
        try {
            opened.tcpNoDelay = true
            opened.soTimeout = 0
            val link = LinkChannel.asSender(opened.getInputStream(), opened.getOutputStream(), invite.secret)
            channel = link
            link.send(LinkMessage.encode(LinkMessage.Hello(myName)))
            _state.value = ClientState.WaitingForApproval
            opened.soTimeout = timing.answerMs.toInt()
            val answer = try {
                LinkMessage.decode(link.receive())
            } catch (_: LinkSecurityException) {
                fail(FailReason.NOT_THE_CODE)
                return@withContext false
            } catch (_: java.net.SocketTimeoutException) {
                fail(FailReason.NO_ANSWER)
                return@withContext false
            }
            when (answer) {
                is LinkMessage.Accepted -> {
                    opened.soTimeout = 0
                    _state.value = ClientState.Connected(answer.name.ifBlank { invite.name ?: "" })
                    startBackground(link)
                    true
                }
                LinkMessage.Rejected -> {
                    fail(FailReason.REJECTED)
                    false
                }
                else -> {
                    fail(FailReason.NOT_THE_CODE)
                    false
                }
            }
        } catch (_: IOException) {
            fail(FailReason.LOST)
            false
        }
    }

    private fun openSocket(invite: LinkInvite): Socket? {
        for (host in invite.hosts) {
            val candidate = Socket()
            try {
                candidate.connect(InetSocketAddress(host, invite.port), timing.connectMs)
                return candidate
            } catch (_: IOException) {
                try { candidate.close() } catch (_: IOException) {}
            }
        }
        return null
    }

    private fun fail(reason: FailReason) {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        channel = null
        _state.value = ClientState.Failed(reason)
    }

    private fun startBackground(link: LinkChannel) {
        reader = scope.launch {
            try {
                while (isActive) {
                    val message = LinkMessage.decode(link.receive()) ?: continue
                    when (message) {
                        is LinkMessage.FileResult -> results.remove(message.id)?.complete(message.ok)
                        LinkMessage.Bye -> break
                        else -> Unit
                    }
                }
            } catch (_: IOException) {
                // The link ended, or was tampered with.
            }
            if (channel === link) {
                for (waiting in results.values) waiting.complete(false)
                results.clear()
                if (_state.value is ClientState.Connected) _state.value = ClientState.Failed(FailReason.LOST)
            }
        }
        pinger = scope.launch {
            while (isActive) {
                delay(timing.pingEveryMs)
                try { link.send(LinkMessage.encode(LinkMessage.Ping)) } catch (_: IOException) { return@launch }
            }
        }
    }

    /**
     * Sends one file and waits for the other phone to say it is saved. [open] gives the bytes (it is called
     * once); [onProgress] gets the number of bytes sent so far. Returns true only when the other phone kept it.
     */
    suspend fun sendFile(name: String, size: Long, open: () -> InputStream, onProgress: (Long) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val link = channel ?: return@withContext false
            val id = nextFileId.getAndIncrement()
            val result = CompletableDeferred<Boolean>()
            results[id] = result
            try {
                link.send(LinkMessage.encode(LinkMessage.FileStart(id, size, name)))
                var sent = 0L
                open().use { stream ->
                    val buffer = ByteArray(timing.fileChunk)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        if (sent + n > size) break // the file grew while it was being read: send what was announced
                        link.send(LinkMessage.encode(LinkMessage.FileData(id, buffer.copyOf(n))))
                        sent += n
                        onProgress(sent)
                    }
                }
                link.send(LinkMessage.encode(LinkMessage.FileEnd(id)))
                withTimeoutOrNull(timing.fileResultMs) { result.await() } ?: false
            } catch (_: IOException) {
                false
            } finally {
                results.remove(id)
            }
        }

    /**
     * Sends one picture of the screen, unless the last one is still on its way (a slow network gets fewer
     * pictures, never a growing delay). Returns false if it was skipped or failed.
     */
    fun sendFrame(width: Int, height: Int, jpeg: ByteArray): Boolean {
        val link = channel ?: return false
        if (jpeg.size > LinkChannel.MAX_RECORD_BYTES - 16) return false
        if (!sendingFrame.compareAndSet(false, true)) return false
        return try {
            link.send(LinkMessage.encode(LinkMessage.Frame(width, height, jpeg)))
            true
        } catch (_: IOException) {
            false
        } finally {
            sendingFrame.set(false)
        }
    }

    /** Tells the other phone the screen is no longer shown. Safe to call from any thread. */
    fun screenEnded() {
        val link = channel ?: return
        scope.launch(Dispatchers.IO) {
            try { link.send(LinkMessage.encode(LinkMessage.ScreenEnded)) } catch (_: IOException) {}
        }
    }

    fun close(quiet: Boolean = false) {
        val link = channel
        val closing = socket
        channel = null
        socket = null
        // Writing to the network is not for the thread that called (it may be the screen's).
        if (link != null || closing != null) {
            scope.launch(Dispatchers.IO) {
                try { link?.send(LinkMessage.encode(LinkMessage.Bye)) } catch (_: IOException) {}
                try { closing?.close() } catch (_: IOException) {}
            }
        }
        reader?.cancel(); reader = null
        pinger?.cancel(); pinger = null
        for (waiting in results.values) waiting.complete(false)
        results.clear()
        if (!quiet) _state.value = ClientState.Closed
    }
}
