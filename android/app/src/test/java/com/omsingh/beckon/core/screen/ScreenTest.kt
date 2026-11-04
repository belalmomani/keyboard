package com.omsingh.beckon.core.screen

import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.wifi.ServerMessage
import com.omsingh.beckon.core.wifi.ServerMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Deflater

class ScreenTest {

    /** What the PC does to a picture: filter each row, then zlib. */
    private fun encode(rgb: ByteArray, width: Int, height: Int): ByteArray {
        val rowBytes = width * 3
        val filtered = ByteArray(rgb.size)
        for (row in 0 until height) {
            val s = row * rowBytes
            for (i in 0 until rowBytes) {
                filtered[s + i] = if (i < 3) rgb[s + i] else (rgb[s + i] - rgb[s + i - 3]).toByte()
            }
        }
        val deflater = Deflater()
        deflater.setInput(filtered)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun gradient(width: Int, height: Int): ByteArray =
        ByteArray(width * height * 3) { i -> ((i / 3 % width) * 4 + (i % 3) * 50 + (i / (width * 3) % 7) * 9).toByte() }

    private fun pixel(rgb: ByteArray, index: Int): Int =
        (0xFF shl 24) or ((rgb[index * 3].toInt() and 0xFF) shl 16) or
            ((rgb[index * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[index * 3 + 2].toInt() and 0xFF)

    @Test
    fun `a picture opens to the colours that were sent`() {
        val rgb = gradient(40, 30)
        val picture = ScreenDecoder.decode(EncodedPicture(1, 40, 30, encode(rgb, 40, 30)))
        assertNotNull(picture)
        picture!!
        assertEquals(40 * 30, picture.pixels.size)
        for (i in picture.pixels.indices) assertEquals("pixel $i", pixel(rgb, i), picture.pixels[i])
    }

    @Test
    fun `a picture made by the PC's own compressor opens exactly`() {
        // 64 by 48 pixels, written by the Rust server's compressor and checked there against real zlib.
        val sent = Base64.getDecoder().decode("eJxjMEphGcqA0zoXt+QQAEIuZbglhwCQ9q3HLTkEgEpYB27JIQB04yfilhwCwCxjFm7JIQBGy8+BBaPl58CC0fJzYMFo+TmwYLT8HFgwWn4OLBgtPwcWjJafAwtGy8+BBaPl58CC0fJzYMFo+TmwYLT8HFgwWn4OLBgtPwcWjJafAwtGy8+BBaPl58CC0fJzYMFo+TmwYKiXnwD4rrjA")
        val picture = ScreenDecoder.decode(EncodedPicture(1, 64, 48, sent))
        assertNotNull(picture)
        picture!!
        val rgb = ByteArray(64 * 48 * 3) { i -> ((i / 3 % 64) * 4 + (i % 3) * 50 + (i / 192 % 7) * 9).toByte() }
        for (i in picture.pixels.indices) assertEquals("pixel $i", pixel(rgb, i), picture.pixels[i])
    }

    @Test
    fun `a picture that does not hold together is refused`() {
        val rgb = gradient(16, 8)
        val good = encode(rgb, 16, 8)
        assertNotNull(ScreenDecoder.decode(EncodedPicture(1, 16, 8, good)))
        // Cut short, bytes damaged, or sizes that do not match what is inside.
        assertNull(ScreenDecoder.decode(EncodedPicture(1, 16, 8, good.copyOf(good.size - 6))))
        assertNull(ScreenDecoder.decode(EncodedPicture(1, 16, 8, ByteArray(40) { 7 })))
        assertNull(ScreenDecoder.decode(EncodedPicture(1, 16, 9, good)))
        assertNull(ScreenDecoder.decode(EncodedPicture(1, 16, 7, good)))
        assertNull(ScreenDecoder.decode(EncodedPicture(1, 0, 8, good)))
        // Bigger than any picture the PC sends.
        assertNull(ScreenDecoder.decode(EncodedPicture(1, 4000, 4000, good)))
    }

    private fun pieces(data: ByteArray, size: Int): List<ByteArray> =
        data.toList().chunked(size).map { it.toByteArray() }

    @Test
    fun `pieces in any order make the picture, and only the last one completes it`() {
        val data = ByteArray(2500) { (it * 7).toByte() }
        val parts = pieces(data, 1000)
        assertEquals(3, parts.size)
        val assembler = PictureAssembler()
        assertNull(assembler.accept(5, 2, 3, 10, 10, parts[2]))
        assertNull(assembler.accept(5, 0, 3, 10, 10, parts[0]))
        // The same piece twice changes nothing.
        assertNull(assembler.accept(5, 0, 3, 10, 10, parts[0]))
        val done = assembler.accept(5, 1, 3, 10, 10, parts[1])
        assertNotNull(done)
        assertEquals(5, done!!.id)
        assertTrue(data.contentEquals(done.bytes))
    }

    @Test
    fun `a newer picture drops the unfinished one, and older pieces are ignored`() {
        val assembler = PictureAssembler()
        assertNull(assembler.accept(1, 0, 2, 4, 4, byteArrayOf(1)))
        // Picture 2 starts before 1 finished: 1 is given up.
        assertNull(assembler.accept(2, 0, 2, 4, 4, byteArrayOf(2)))
        assertNull(assembler.accept(1, 1, 2, 4, 4, byteArrayOf(1)))
        val done = assembler.accept(2, 1, 2, 4, 4, byteArrayOf(3))
        assertNotNull(done)
        assertEquals(2, done!!.id)
        // A late piece of an older or the same picture is not taken for a new one.
        assertNull(assembler.accept(1, 0, 1, 4, 4, byteArrayOf(9)))
        assertNull(assembler.accept(2, 0, 1, 4, 4, byteArrayOf(9)))
        // Numbers wrap around: the one after the largest is the smallest.
        assertNotNull(assembler.accept(Int.MAX_VALUE, 0, 1, 4, 4, byteArrayOf(1)))
        assertNotNull(assembler.accept(Int.MIN_VALUE, 0, 1, 4, 4, byteArrayOf(1)))
    }

    @Test
    fun `pieces that contradict themselves or the picture are refused`() {
        val assembler = PictureAssembler()
        assertNull(assembler.accept(1, 0, 0, 4, 4, byteArrayOf(1)))
        assertNull(assembler.accept(1, 2, 2, 4, 4, byteArrayOf(1)))
        assertNull(assembler.accept(1, 0, PictureAssembler.MAX_PIECES + 1, 4, 4, byteArrayOf(1)))
        assertNull(assembler.accept(1, 0, 2, 0, 4, byteArrayOf(1)))
        assertNull(assembler.accept(1, 0, 2, 9999, 4, byteArrayOf(1)))
        // Same picture, but the second piece claims another size.
        assertNull(assembler.accept(7, 0, 2, 4, 4, byteArrayOf(1)))
        assertNull(assembler.accept(7, 1, 3, 4, 4, byteArrayOf(1)))
        assertNull(assembler.accept(7, 1, 2, 8, 4, byteArrayOf(1)))
        assertNotNull(assembler.accept(7, 1, 2, 4, 4, byteArrayOf(1)))
    }

    private fun wire(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `the PC's screen messages are read, and damaged ones are not`() {
        val status = ServerMessages.parse(wire(0x86, 0, 0x80, 0x02, 0x68, 0x01), 6, 0)
        assertEquals(ServerMessage.ScreenStatus(ScreenState.STARTED, 640, 360), status)
        assertEquals(ScreenState.DISABLED, (ServerMessages.parse(wire(0x86, 2, 0, 0, 0, 0), 6, 0) as ServerMessage.ScreenStatus).state)
        assertNull(ServerMessages.parse(wire(0x86, 9, 0, 0, 0, 0), 6, 0))
        assertNull(ServerMessages.parse(wire(0x86, 0, 0, 0), 4, 0))

        // Picture 0x01020304, piece 1 of 3, 640 by 360, two bytes of picture.
        val piece = ServerMessages.parse(
            wire(0x87, 4, 3, 2, 1, 1, 0, 3, 0, 0x80, 0x02, 0x68, 0x01, 0xAA, 0xBB), 15, 0,
        ) as ServerMessage.ScreenPiece
        assertEquals(0x01020304, piece.frameId)
        assertEquals(1, piece.index)
        assertEquals(3, piece.count)
        assertEquals(640, piece.width)
        assertEquals(360, piece.height)
        assertTrue(byteArrayOf(0xAA.toByte(), 0xBB.toByte()).contentEquals(piece.data))
        // A piece outside its picture, or cut inside its header, is nothing.
        assertNull(ServerMessages.parse(wire(0x87, 4, 3, 2, 1, 3, 0, 3, 0, 0x80, 0x02, 0x68, 0x01), 13, 0))
        assertNull(ServerMessages.parse(wire(0x87, 4, 3, 2, 1, 0, 0, 0, 0, 0x80, 0x02, 0x68, 0x01), 13, 0))
        assertNull(ServerMessages.parse(wire(0x87, 4, 3, 2, 1, 0, 0), 7, 0))
    }

    // ── The client ───────────────────────────────────────────────────

    private val sent = CopyOnWriteArrayList<InputEvent>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun client(giveUpAfterMs: Long = 400) = ScreenViewClient(
        dispatch = { sent += it },
        scope = scope,
        firstAnswerWaitMs = 40,
        askAgainMs = 80,
        giveUpAfterMs = giveUpAfterMs,
    )

    private fun starts() = sent.filterIsInstance<InputEvent.ScreenView>().filter { it.start }
    private fun stops() = sent.filterIsInstance<InputEvent.ScreenView>().filter { !it.start }

    private fun send(client: ScreenViewClient, id: Int, rgb: ByteArray, width: Int, height: Int) {
        val parts = pieces(encode(rgb, width, height), 30)
        parts.forEachIndexed { i, part -> client.onPiece(id, i, parts.size, width, height, part) }
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("gave up waiting for $what")
    }

    @Test
    fun `it asks again while waiting, shows a picture, and stops when told`() = runBlocking {
        val c = client()
        assertEquals(ScreenViewState.Off, c.state.value)
        c.start()
        assertEquals(ScreenViewState.Waiting, c.state.value)
        waitFor("a repeated request") { starts().size >= 2 }
        assertEquals(640, starts().first().maxWidth)

        send(c, 1, gradient(12, 6), 12, 6)
        waitFor("a picture") { c.state.value is ScreenViewState.Showing }
        val shown = (c.state.value as ScreenViewState.Showing).picture
        assertEquals(12, shown.width)
        assertEquals(6, shown.height)

        c.stop()
        assertEquals(ScreenViewState.Off, c.state.value)
        assertEquals(1, stops().size)
        val before = starts().size
        delay(200)
        assertEquals("it does not go on asking", before, starts().size)
    }

    @Test
    fun `a PC that says no ends it, with the reason`() = runBlocking {
        val c = client()
        c.start()
        c.onStatus(ScreenState.DISABLED)
        assertEquals(ScreenViewState.Unavailable(ScreenViewState.Reason.NotAllowed), c.state.value)
        val before = starts().size
        delay(200)
        assertEquals(before, starts().size)
        c.start()
        c.onStatus(ScreenState.UNSUPPORTED)
        assertEquals(ScreenViewState.Unavailable(ScreenViewState.Reason.Unsupported), c.state.value)
        c.start()
        c.onStatus(ScreenState.FAILED)
        assertEquals(ScreenViewState.Unavailable(ScreenViewState.Reason.Failed), c.state.value)
    }

    @Test
    fun `a PC that never answers is reported, not waited for forever`() = runBlocking {
        val c = client(giveUpAfterMs = 150)
        c.start()
        waitFor("giving up") { c.state.value is ScreenViewState.Unavailable }
        assertEquals(ScreenViewState.Unavailable(ScreenViewState.Reason.NoAnswer), c.state.value)
        // It can be tried again.
        c.start()
        assertEquals(ScreenViewState.Waiting, c.state.value)
        c.stop()
    }

    @Test
    fun `pieces that arrive when nobody asked are ignored, and a lost connection forgets the picture`() = runBlocking {
        val c = client()
        send(c, 1, gradient(8, 4), 8, 4)
        delay(100)
        assertEquals(ScreenViewState.Off, c.state.value)

        c.start()
        send(c, 2, gradient(8, 4), 8, 4)
        waitFor("a picture") { c.state.value is ScreenViewState.Showing }
        c.reset()
        assertEquals(ScreenViewState.Off, c.state.value)
        assertTrue("a lost connection says nothing", stops().isEmpty())
    }

    @Test
    fun `a damaged picture is not shown`() = runBlocking {
        val c = client()
        c.start()
        c.onPiece(1, 0, 1, 8, 4, ByteArray(50) { 3 })
        delay(150)
        assertEquals(ScreenViewState.Waiting, c.state.value)
        c.stop()
    }
}
