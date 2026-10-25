package com.omsingh.beckon.core.windows

import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.text.CommandResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** One open window on the PC, as the radar lists it. */
data class RemoteWindow(
    /** The system's handle for it. Only good while the window lives, and only for [WindowRadar.focus]. */
    val handle: Long,
    /** The program's file name such as `Code.exe`. */
    val process: String,
    val title: String,
    val foreground: Boolean,
) {
    /** The program's name without `.exe`, for showing. */
    val program: String get() = process.removeSuffix(".exe").removeSuffix(".EXE")
}

/** How bringing a window to the front ended. Only [Done] means the PC found it in front afterwards. */
enum class FocusResult {
    /** The PC checked, and the window is in front. */
    Done,

    /** The window is open but Windows did not let it come to the front. */
    Failed,

    /** The PC does not know that window any more: it was closed, or never listed. */
    Gone,

    /** No answer. It may or may not have come to the front. */
    NoAnswer,
}

/**
 * The windows open on the PC, and bringing one to the front with an answer the PC gives.
 *
 * [refresh] asks; the list arrives through [onList]. [focus] sends the request, and sends it again if no
 * answer comes (bringing a window to the front twice is harmless, and UDP loses datagrams), until the PC
 * says how it went. Nothing is kept except the last list, in memory.
 */
class WindowRadar(
    private val sendListQuery: () -> Unit,
    private val dispatch: (InputEvent) -> Unit,
    private val results: Flow<CommandResult>,
    private val answerWaitMs: Long = 1_200,
    private val attempts: Int = 3,
    private val newId: () -> Int = defaultIds(),
) {
    private val _windows = MutableStateFlow<List<RemoteWindow>?>(null)

    /** The last list the PC sent, or null if none has come. */
    val windows: StateFlow<List<RemoteWindow>?> = _windows.asStateFlow()

    fun refresh() = sendListQuery()

    fun onList(list: List<RemoteWindow>) {
        _windows.value = list
    }

    fun clear() {
        _windows.value = null
    }

    suspend fun focus(handle: Long): FocusResult = coroutineScope {
        val id = newId()
        val inbox = Channel<CommandResult>(Channel.UNLIMITED)
        // Listening starts before anything is sent, so that no answer can slip past.
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            results.filter { it.requestId == id && it.status.isFinal }.collect { inbox.trySend(it) }
        }
        try {
            repeat(attempts) {
                dispatch(InputEvent.WindowFocus(id, handle))
                val answer = withTimeoutOrNull(answerWaitMs) { inbox.receive() }
                if (answer != null) return@coroutineScope map(answer)
            }
            FocusResult.NoAnswer
        } finally {
            listener.cancel()
        }
    }

    private fun map(result: CommandResult): FocusResult = when (result.status) {
        CommandResult.Status.DONE -> FocusResult.Done
        CommandResult.Status.REJECTED -> FocusResult.Gone
        else -> FocusResult.Failed
    }

    companion object {
        /** Ids start high and apart from the long-text ones, so an answer is never taken for the wrong request. */
        fun defaultIds(): () -> Int {
            val next = AtomicInteger(0x4000_0000 + (Math.random() * 0x1000_0000).toInt())
            return { next.incrementAndGet() }
        }
    }
}
