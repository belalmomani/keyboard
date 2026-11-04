package com.omsingh.beckon.core.commands

import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.text.CommandResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** One command as the PC takes it (see `RemoteCommand` in the protocol): [kind] 0 keys, 1 media, 2 volume, 3 system, 4 lock. */
data class RemoteCall(val kind: Int, val mods: Int, val usage: Int) {
    companion object {
        const val KEYS = 0
        const val MEDIA = 1
        const val VOLUME = 2
        const val SYSTEM = 3
        const val LOCK = 4
    }
}

/** How a command that the PC reports on ended. Only [Done] means the PC's system took it. */
enum class RunOutcome {
    Done,

    /** The PC tried and the system refused. */
    Failed,

    /** The PC would not start it. */
    Rejected,

    /** No answer: the connection may have dropped. It may or may not have run. */
    NoAnswer,
}

/**
 * Runs a command on the PC and reports what the PC says.
 *
 * The request is sent again when no answer comes (UDP loses datagrams). The PC runs a request id once and
 * answers repeats with the same result, so asking twice cannot press a key twice. After [attempts] with no
 * answer the outcome is [RunOutcome.NoAnswer], which says honestly that it is not known.
 */
class CommandRunner(
    private val dispatch: (InputEvent) -> Unit,
    private val results: Flow<CommandResult>,
    private val answerWaitMs: Long = 1_200,
    private val attempts: Int = 3,
    private val newId: () -> Int = defaultIds(),
) {
    suspend fun run(call: RemoteCall): RunOutcome = coroutineScope {
        val id = newId()
        val inbox = Channel<CommandResult>(Channel.UNLIMITED)
        // Listening starts before anything is sent, so that no answer can slip past.
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            results.filter { it.requestId == id && it.status.isFinal }.collect { inbox.trySend(it) }
        }
        try {
            repeat(attempts) {
                dispatch(InputEvent.RunCommand(id, call.kind, call.mods, call.usage))
                val answer = withTimeoutOrNull(answerWaitMs) { inbox.receive() }
                if (answer != null) return@coroutineScope map(answer)
            }
            RunOutcome.NoAnswer
        } finally {
            listener.cancel()
        }
    }

    private fun map(result: CommandResult): RunOutcome = when (result.status) {
        CommandResult.Status.DONE -> RunOutcome.Done
        CommandResult.Status.REJECTED -> RunOutcome.Rejected
        else -> RunOutcome.Failed
    }

    companion object {
        /** Ids start high and apart from the long-text and window ones, so an answer is never taken for another request. */
        fun defaultIds(): () -> Int {
            val next = AtomicInteger(0x2000_0000 + (Math.random() * 0x1000_0000).toInt())
            return { next.incrementAndGet() }
        }
    }
}
