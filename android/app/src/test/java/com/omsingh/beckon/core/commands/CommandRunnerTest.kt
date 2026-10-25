package com.omsingh.beckon.core.commands

import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.text.CommandResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CommandRunnerTest {

    private val results = MutableSharedFlow<CommandResult>(extraBufferCapacity = 16)
    private val sent = CopyOnWriteArrayList<InputEvent>()
    private val call = RemoteCall(RemoteCall.KEYS, 4, 0x2B)

    private fun runner(attempts: Int = 3, onSend: (InputEvent) -> Unit = {}) = CommandRunner(
        dispatch = { sent += it; onSend(it) },
        results = results,
        answerWaitMs = 60,
        attempts = attempts,
        newId = { 77 },
    )

    private fun answer(status: CommandResult.Status) = results.tryEmit(CommandResult(77, status, 0))

    @Test
    fun `the PC saying done is done, and what was asked is what was sent`() = runBlocking {
        val r = runner(onSend = { answer(CommandResult.Status.DONE) })
        assertEquals(RunOutcome.Done, r.run(call))
        assertEquals(listOf<InputEvent>(InputEvent.RunCommand(77, RemoteCall.KEYS, 4, 0x2B)), sent.toList())
    }

    @Test
    fun `a failure and a refusal are told apart from success`() = runBlocking {
        assertEquals(RunOutcome.Failed, runner(onSend = { answer(CommandResult.Status.FAILED) }).run(call))
        assertEquals(RunOutcome.Rejected, runner(onSend = { answer(CommandResult.Status.REJECTED) }).run(call))
    }

    @Test
    fun `a lost request is sent again with the same id until it is answered`() = runBlocking {
        var tries = 0
        val r = runner { if (++tries == 3) answer(CommandResult.Status.DONE) }
        assertEquals(RunOutcome.Done, r.run(call))
        assertEquals(3, sent.size)
        assertEquals(1, sent.map { (it as InputEvent.RunCommand).requestId }.toSet().size)
    }

    @Test
    fun `no answer is reported as no answer, never as done`() = runBlocking {
        assertEquals(RunOutcome.NoAnswer, runner(attempts = 2).run(call))
        assertEquals(2, sent.size)
    }

    @Test
    fun `an answer to another request or a mere notice is not an ending`() = runBlocking {
        val other = runner(attempts = 1, onSend = { results.tryEmit(CommandResult(1, CommandResult.Status.DONE, 0)) })
        assertEquals(RunOutcome.NoAnswer, other.run(call))
        val notice = runner(attempts = 1, onSend = { answer(CommandResult.Status.RECEIVED) })
        assertEquals(RunOutcome.NoAnswer, notice.run(call))
    }
}
