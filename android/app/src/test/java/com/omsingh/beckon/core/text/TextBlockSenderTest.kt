package com.omsingh.beckon.core.text

import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.text.CommandResult.Status
import com.omsingh.beckon.core.text.TextBlockResult.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** The sender against a stand-in PC that answers the way the real server does. */
class TextBlockSenderTest {

    private val results = MutableSharedFlow<CommandResult>(extraBufferCapacity = 64)
    private val sent = Collections.synchronizedList(mutableListOf<InputEvent>())
    private val fast = TextBlockSender.Timing(answerWaitMs = 120, attempts = 3, paceMs = 0, typingTimeoutMs = 600, cancelWaitMs = 100)

    private fun chunks() = sent.filterIsInstance<InputEvent.TextBlockChunk>()

    /** A PC that answers as [behave] says, once it has seen every piece of an attempt. */
    private fun sender(behave: (attempt: Int, id: Int, chunksSoFar: List<InputEvent.TextBlockChunk>) -> List<CommandResult>?): TextBlockSender {
        var attempt = 0
        return TextBlockSender(
            dispatch = { event ->
                sent += event
                if (event is InputEvent.TextBlockChunk && event.index == event.total - 1) {
                    attempt++
                    val answers = behave(attempt, event.requestId, chunks())
                    if (answers != null) {
                        CoroutineScope(Dispatchers.Default).async {
                            for (a in answers) {
                                delay(5)
                                results.emit(a)
                            }
                        }
                    }
                }
            },
            results = results,
            timing = fast,
            newId = { 77 },
        )
    }

    private val long = "مرحباً بكم — hello 🚀\n".repeat(120)

    @Test
    fun `a text that is typed ends as done with every piece sent once`() = runBlocking {
        val stages = mutableListOf<TextBlockStage>()
        val result = sender { _, id, _ ->
            listOf(CommandResult(id, Status.RECEIVED, 0), CommandResult(id, Status.DONE, 9999))
        }.send(long) { stages += it }

        assertEquals(Outcome.DONE, result.outcome)
        assertEquals("typed is never more than the text", result.length, result.typed)
        val pieces = TextBlockCodec.split(long)!!
        assertEquals(pieces, chunks().map { it.text })
        assertEquals(pieces.indices.toList(), chunks().map { it.index })
        assertTrue(chunks().all { it.requestId == 77 && it.total == pieces.size })
        assertEquals(listOf(TextBlockStage.SENDING, TextBlockStage.WAITING, TextBlockStage.TYPING), stages)
    }

    @Test
    fun `when the first transmission is lost it is sent again and still typed once`() = runBlocking {
        val result = sender { attempt, id, _ ->
            if (attempt == 1) null else listOf(CommandResult(id, Status.RECEIVED, 0), CommandResult(id, Status.DONE, 5))
        }.send("hello")
        assertEquals(Outcome.DONE, result.outcome)
        assertEquals(2, chunks().size)
    }

    @Test
    fun `a failure part of the way is reported as failed with how far it got`() = runBlocking {
        val text = "abcdefghij"
        val result = sender { _, id, _ ->
            listOf(CommandResult(id, Status.RECEIVED, 0), CommandResult(id, Status.FAILED, 4))
        }.send(text)
        assertEquals(TextBlockResult(Outcome.FAILED, 4, 10), result)
    }

    @Test
    fun `refusals and cancellations are reported as they are`() = runBlocking {
        assertEquals(
            Outcome.REJECTED,
            sender { _, id, _ -> listOf(CommandResult(id, Status.REJECTED, 0)) }.send("x").outcome,
        )
        assertEquals(
            Outcome.CANCELLED,
            sender { _, id, _ -> listOf(CommandResult(id, Status.RECEIVED, 0), CommandResult(id, Status.CANCELLED, 3)) }.send("abcdef").outcome,
        )
    }

    @Test
    fun `no answer at all is a timeout, never a success`() = runBlocking {
        val result = sender { _, _, _ -> null }.send("hello")
        assertEquals(Outcome.TIMEOUT, result.outcome)
        assertEquals("sent three times before giving up", 3, chunks().size)
    }

    @Test
    fun `typing that starts and never ends is a timeout and is not sent again`() = runBlocking {
        val result = sender { _, id, _ -> listOf(CommandResult(id, Status.RECEIVED, 0)) }.send("hello")
        assertEquals(Outcome.TIMEOUT, result.outcome)
        assertEquals(1, chunks().size)
    }

    @Test
    fun `answers about other texts are ignored`() = runBlocking {
        val result = sender { _, id, _ ->
            listOf(CommandResult(id + 1, Status.DONE, 5), CommandResult(id, Status.RECEIVED, 0), CommandResult(id, Status.FAILED, 1))
        }.send("hello")
        assertEquals(Outcome.FAILED, result.outcome)
    }

    @Test
    fun `a text that is too long is refused before anything is sent`() = runBlocking {
        val result = sender { _, _, _ -> null }.send("a".repeat(TextBlockCodec.MAX_TEXT_BYTES + 1))
        assertEquals(Outcome.TOO_LONG, result.outcome)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an empty text is done at once without sending`() = runBlocking {
        val result = sender { _, _, _ -> null }.send("")
        assertEquals(TextBlockResult(Outcome.DONE, 0, 0), result)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `cancel asks the PC to stop the text being sent`() = runBlocking {
        val sender = sender { _, id, _ -> listOf(CommandResult(id, Status.RECEIVED, 0)) }
        val running = async { sender.send("hello world") }
        delay(60)
        assertTrue(sender.busy)
        sender.cancel()
        assertTrue(sent.contains(InputEvent.TextBlockCancel(77)))
        results.emit(CommandResult(77, Status.CANCELLED, 2))
        assertEquals(Outcome.CANCELLED, running.await().outcome)
        assertTrue(!sender.busy)
    }

    @Test
    fun `an unknown status from a newer PC is a failure and not a success`() {
        assertEquals(Status.FAILED, Status.fromWire(200))
        assertEquals(Status.DONE, Status.fromWire(0))
        assertEquals(Status.RECEIVED, Status.fromWire(4))
    }
}
