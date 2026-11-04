package com.omsingh.beckon.core.text

import com.omsingh.beckon.core.input.InputEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** What the PC says about a long text: `0x83` on the wire. */
data class CommandResult(val requestId: Int, val status: Status, val value: Int) {
    enum class Status {
        DONE, FAILED, CANCELLED, REJECTED,

        /** Every piece arrived and typing has started. Not an end. */
        RECEIVED,
        ;

        val isFinal: Boolean get() = this != RECEIVED

        companion object {
            /** An unknown code from a newer PC counts as a failure rather than as a success. */
            fun fromWire(code: Int): Status = when (code) {
                0 -> DONE
                1 -> FAILED
                2 -> CANCELLED
                3 -> REJECTED
                4 -> RECEIVED
                else -> FAILED
            }
        }
    }
}

/** How sending a long text ended. Only [Outcome.DONE] means the PC typed all of it. */
data class TextBlockResult(val outcome: Outcome, val typed: Int, val length: Int) {
    enum class Outcome {
        /** The PC's system took every character. */
        DONE,

        /** The PC tried and could not finish. [typed] characters went in first. */
        FAILED,
        CANCELLED,

        /** The PC refused it (too many at once, or pieces that did not agree). */
        REJECTED,

        /** No answer. The text may or may not have been typed, in whole or in part. */
        TIMEOUT,

        /** Too long to send at all. Nothing was sent. */
        TOO_LONG,
    }
}

/** Where a long text is on its way. */
enum class TextBlockStage { SENDING, WAITING, TYPING }

/**
 * Sends a long text to the PC and says truthfully how it ended.
 *
 * UDP loses and repeats datagrams, so this does what UDP does not:
 *
 *  1. The text is cut into pieces ([TextBlockCodec]) and every piece is sent.
 *  2. If the PC does not answer, every piece is sent again (the PC ignores pieces it has, and
 *     answers again for a text it already finished, without typing it twice).
 *  3. The PC answers `RECEIVED` when it has the whole text and starts typing, then `DONE`,
 *     `FAILED` or `CANCELLED` when the system has actually taken it (or not).
 *
 * Only one text is sent at a time per instance.
 */
class TextBlockSender(
    private val dispatch: (InputEvent) -> Unit,
    private val results: Flow<CommandResult>,
    private val timing: Timing = Timing(),
    private val newId: () -> Int = defaultIds(),
) {
    data class Timing(
        /** How long to wait for any answer to a transmission before sending it again. */
        val answerWaitMs: Long = 1_500,
        val attempts: Int = 3,
        /** A moment between pieces so that a burst does not overrun the PC's socket. */
        val paceMs: Long = 2,
        /** How long typing may take once the PC has said it started. */
        val typingTimeoutMs: Long = 60_000,
        /** How long to wait for the end of a text that was told to stop. */
        val cancelWaitMs: Long = 2_000,
    )

    @Volatile private var currentId: Int? = null

    /** Whether a text is being sent right now. */
    val busy: Boolean get() = currentId != null

    /** Asks the PC to stop. The result of the text being sent then ends as `CANCELLED`. */
    fun cancel() {
        currentId?.let { dispatch(InputEvent.TextBlockCancel(it)) }
    }

    suspend fun send(text: String, onStage: (TextBlockStage) -> Unit = {}): TextBlockResult {
        val pieces = TextBlockCodec.split(text)
            ?: return TextBlockResult(TextBlockResult.Outcome.TOO_LONG, 0, text.length)
        val length = text.codePointCount(0, text.length)
        if (pieces.isEmpty()) return TextBlockResult(TextBlockResult.Outcome.DONE, 0, 0)
        check(currentId == null) { "a text is already being sent" }

        val id = newId()
        currentId = id
        try {
            return coroutineScope {
                val inbox = Channel<CommandResult>(Channel.UNLIMITED)
                // Listening starts before anything is sent, so that no answer can slip past.
                val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                    results.filter { it.requestId == id }.collect { inbox.trySend(it) }
                }
                try {
                    converse(id, pieces, length, inbox, onStage)
                } finally {
                    listener.cancel()
                }
            }
        } finally {
            currentId = null
        }
    }

    private suspend fun converse(
        id: Int,
        pieces: List<String>,
        length: Int,
        inbox: Channel<CommandResult>,
        onStage: (TextBlockStage) -> Unit,
    ): TextBlockResult {
        repeat(timing.attempts) {
            onStage(TextBlockStage.SENDING)
            pieces.forEachIndexed { index, piece ->
                dispatch(InputEvent.TextBlockChunk(id, index, pieces.size, piece))
                if (timing.paceMs > 0 && index < pieces.lastIndex) delay(timing.paceMs)
            }
            onStage(TextBlockStage.WAITING)

            var typing = false
            var waitMs = timing.answerWaitMs
            while (true) {
                val answer = withTimeoutOrNull(waitMs) { inbox.receive() } ?: break
                if (answer.status == CommandResult.Status.RECEIVED) {
                    if (!typing) {
                        typing = true
                        onStage(TextBlockStage.TYPING)
                        waitMs = timing.typingTimeoutMs
                    }
                    continue
                }
                return finished(answer, length)
            }
            // Heard that typing started, but not how it ended: sending again would only be answered
            // the same way, so give up waiting instead.
            if (typing) return TextBlockResult(TextBlockResult.Outcome.TIMEOUT, 0, length)
        }
        return TextBlockResult(TextBlockResult.Outcome.TIMEOUT, 0, length)
    }

    private fun finished(answer: CommandResult, length: Int): TextBlockResult {
        val outcome = when (answer.status) {
            CommandResult.Status.DONE -> TextBlockResult.Outcome.DONE
            CommandResult.Status.FAILED -> TextBlockResult.Outcome.FAILED
            CommandResult.Status.CANCELLED -> TextBlockResult.Outcome.CANCELLED
            CommandResult.Status.REJECTED -> TextBlockResult.Outcome.REJECTED
            CommandResult.Status.RECEIVED -> error("not an end")
        }
        return TextBlockResult(outcome, answer.value.coerceIn(0, length), length)
    }

    companion object {
        /** Ids that do not repeat within a run and do not start at the same place twice. */
        fun defaultIds(): () -> Int {
            val next = AtomicInteger((Math.random() * Int.MAX_VALUE).toInt())
            return { next.incrementAndGet() }
        }
    }
}
