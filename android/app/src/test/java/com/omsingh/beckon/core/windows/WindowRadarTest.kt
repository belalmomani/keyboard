package com.omsingh.beckon.core.windows

import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.text.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WindowRadarTest {

    private val results = MutableSharedFlow<CommandResult>(extraBufferCapacity = 16)
    private val sent = CopyOnWriteArrayList<InputEvent>()
    private var queries = 0

    private fun radar(attempts: Int = 3, onSend: (InputEvent) -> Unit = {}) = WindowRadar(
        sendListQuery = { queries++ },
        dispatch = { sent += it; onSend(it) },
        results = results,
        answerWaitMs = 60,
        attempts = attempts,
        newId = { 0x40000001 },
    )

    @Test
    fun `the list is empty until the PC sends one, and is forgotten when cleared`() {
        val r = radar()
        assertNull(r.windows.value)
        r.refresh()
        assertEquals(1, queries)
        val list = listOf(RemoteWindow(1, "Code.exe", "main.rs", true))
        r.onList(list)
        assertEquals(list, r.windows.value)
        r.clear()
        assertNull(r.windows.value)
    }

    @Test
    fun `the PC saying done means the window is in front`() = runBlocking {
        val r = radar(onSend = { results.tryEmit(CommandResult(0x40000001, CommandResult.Status.DONE, 0)) })
        assertEquals(FocusResult.Done, r.focus(42))
        assertEquals(listOf<InputEvent>(InputEvent.WindowFocus(0x40000001, 42)), sent.toList())
    }

    @Test
    fun `a refusal means the window is gone, a failure means windows would not let it come`() = runBlocking {
        val gone = radar(onSend = { results.tryEmit(CommandResult(0x40000001, CommandResult.Status.REJECTED, 0)) })
        assertEquals(FocusResult.Gone, gone.focus(1))
        val failed = radar(onSend = { results.tryEmit(CommandResult(0x40000001, CommandResult.Status.FAILED, 0)) })
        assertEquals(FocusResult.Failed, failed.focus(1))
    }

    @Test
    fun `a lost request is sent again and answered once, not acted on twice`() = runBlocking {
        var tries = 0
        val r = radar { if (++tries == 3) results.tryEmit(CommandResult(0x40000001, CommandResult.Status.DONE, 0)) }
        assertEquals(FocusResult.Done, r.focus(5))
        assertEquals(3, sent.size)
    }

    @Test
    fun `no answer after every try is reported as no answer, not as success`() = runBlocking {
        val r = radar(attempts = 2)
        assertEquals(FocusResult.NoAnswer, r.focus(5))
        assertEquals(2, sent.size)
    }

    @Test
    fun `an answer for another request is ignored`() = runBlocking {
        val r = radar(attempts = 1, onSend = { results.tryEmit(CommandResult(99, CommandResult.Status.DONE, 0)) })
        assertEquals(FocusResult.NoAnswer, r.focus(5))
    }

    @Test
    fun `a received notice is not an ending`() = runBlocking {
        val r = radar(attempts = 1, onSend = { results.tryEmit(CommandResult(0x40000001, CommandResult.Status.RECEIVED, 0)) })
        assertEquals(FocusResult.NoAnswer, r.focus(5))
    }

    @Test
    fun `program names lose their extension`() {
        assertEquals("Code", RemoteWindow(1, "Code.exe", "", false).program)
        assertEquals("explorer", RemoteWindow(1, "explorer.EXE", "", false).program)
        assertTrue(RemoteWindow(1, "", "x", false).program.isEmpty())
    }
}
