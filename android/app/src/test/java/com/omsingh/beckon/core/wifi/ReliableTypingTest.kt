package com.omsingh.beckon.core.wifi

import com.omsingh.beckon.core.wifi.ReliableTyping.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliableTypingTest {

    private val typing = ReliableTyping(window = 3, resendAfterMs = 100, giveUpAfterMs = 1_000)

    @Test
    fun `each step is numbered from zero and sent at once`() {
        assertEquals(listOf(Step(0, 0, "a")), typing.add(0, "a", 0))
        assertEquals(listOf(Step(1, 2, "lo ")), typing.add(2, "lo ", 1))
    }

    @Test
    fun `nothing to erase and nothing to type is not a step`() {
        assertTrue(typing.add(0, "", 0).isEmpty())
        assertFalse(typing.hasPending())
    }

    @Test
    fun `a confirmation clears what the PC has taken and nothing is sent again`() {
        typing.add(0, "a", 0)
        typing.add(0, "b", 1)
        assertTrue(typing.onAck(2, 2).isEmpty())
        assertFalse(typing.hasPending())
        assertTrue(typing.due(10_000).isEmpty())
    }

    @Test
    fun `a step the PC did not confirm is sent again with everything after it`() {
        typing.add(0, "a", 0)
        typing.add(0, "b", 10)
        typing.add(0, "c", 20)
        // "a" was lost: the PC still wants 0.
        assertTrue(typing.onAck(0, 30).isEmpty())
        assertTrue("too soon", typing.due(50).isEmpty())
        assertEquals(
            listOf(Step(0, 0, "a"), Step(1, 0, "b"), Step(2, 0, "c")),
            typing.due(150),
        )
        // Once it has them all, nothing is left to send.
        typing.onAck(3, 160)
        assertTrue(typing.due(10_000).isEmpty())
    }

    @Test
    fun `steps beyond the window wait and go out as confirmations make room`() {
        for (c in "abc") typing.add(0, c.toString(), 0)
        assertTrue(typing.add(0, "d", 1).isEmpty())
        assertEquals(listOf(Step(3, 0, "d")), typing.onAck(1, 2))
    }

    @Test
    fun `a late confirmation from before one already received changes nothing`() {
        typing.add(0, "a", 0)
        typing.add(0, "b", 0)
        typing.onAck(1, 5) // "a" done
        assertTrue(typing.onAck(0, 6).isEmpty()) // an old one arrives late
        assertEquals(listOf(Step(1, 0, "b")), typing.due(200))
    }

    @Test
    fun `erasing goes with the first piece of a long text`() {
        val long = "x".repeat(MAX_TYPED_EDIT_BYTES + 10)
        val steps = typing.add(3, long, 0)
        assertEquals(2, steps.size)
        assertEquals(3, steps[0].backspaces)
        assertEquals(MAX_TYPED_EDIT_BYTES, steps[0].text.length)
        assertEquals(0, steps[1].backspaces)
        assertEquals(10, steps[1].text.length)
    }

    @Test
    fun `a long text is never cut inside a character`() {
        val rocket = "🚀" // 4 bytes in UTF-8
        val steps = typing.add(0, rocket.repeat(MAX_TYPED_EDIT_BYTES / 4 + 1), 0)
        assertEquals(2, steps.size)
        assertEquals(rocket.repeat(MAX_TYPED_EDIT_BYTES / 4), steps[0].text)
        assertEquals(rocket, steps[1].text)
    }

    @Test
    fun `after a new connection what was not confirmed is numbered again from zero and sent`() {
        typing.add(0, "a", 0)
        typing.add(0, "b", 0)
        typing.onAck(1, 5)
        typing.restart()
        assertEquals(listOf(Step(0, 0, "b")), typing.due(10))
    }

    @Test
    fun `a PC numbering ahead of ours is followed and the text is kept`() {
        typing.add(0, "a", 0)
        val again = typing.onAck(40, 5)
        assertEquals(listOf(Step(40, 0, "a")), again)
    }

    @Test
    fun `steps the PC never confirms are given up on`() {
        typing.add(0, "a", 0)
        assertTrue(typing.due(5_000).isEmpty())
        assertFalse(typing.hasPending())
    }

    @Test
    fun `numbers continue across the int limit`() {
        // Not reachable in practice, but the arithmetic must not break.
        val t = ReliableTyping(window = 4)
        repeat(3) { t.add(0, "a", 0) }
        t.onAck(3, 1)
        assertFalse(t.hasPending())
    }
}
