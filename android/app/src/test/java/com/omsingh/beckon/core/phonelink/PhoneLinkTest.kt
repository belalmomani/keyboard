package com.omsingh.beckon.core.phonelink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import kotlin.concurrent.thread

class PhoneLinkTest {

    // ── The code ─────────────────────────────────────────────────────

    @Test
    fun `a code reads back as it was written`() {
        val invite = LinkInvite.create(listOf("192.168.1.20", "10.0.0.4"), 40123, "Belal's phone ☎")
        val read = LinkInvite.parse(invite.toUri())
        assertEquals(LinkInvite.Read.Valid(invite), read)
    }

    @Test
    fun `text that is not a link code, or is a damaged or newer one, is told apart`() {
        assertEquals(LinkInvite.Read.NotALink, LinkInvite.parse("https://example.com"))
        assertEquals(LinkInvite.Read.NotALink, LinkInvite.parse("beckon://pair?v=1"))
        assertEquals(LinkInvite.Read.NeedsNewerApp, LinkInvite.parse("beckon://link?v=9&k=x&p=1&h=1.2.3.4"))
        assertEquals(LinkInvite.Read.Damaged, LinkInvite.parse("beckon://link?v=1&k=short&p=5000&h=1.2.3.4"))
        val ok = LinkInvite.create(listOf("10.0.0.1"), 5000, null).toUri()
        assertEquals(LinkInvite.Read.Damaged, LinkInvite.parse(ok.replace("p=5000", "p=0")))
        assertEquals(LinkInvite.Read.Damaged, LinkInvite.parse(ok.replace("h=10.0.0.1", "h=evil.example.com")))
        assertEquals(LinkInvite.Read.Damaged, LinkInvite.parse(ok.replace("h=10.0.0.1", "h=999.1.1.1")))
    }

    @Test
    fun `the secret is never printed`() {
        val invite = LinkInvite.create(listOf("10.0.0.1"), 5000, "x")
        val key = invite.toUri().substringAfter("k=").substringBefore('&')
        assertFalse(invite.toString().contains(key))
    }

    // ── The messages ─────────────────────────────────────────────────

    @Test
    fun `every message survives a round trip`() {
        val cases = listOf(
            LinkMessage.Hello("هاتفي"),
            LinkMessage.Accepted("Main"),
            LinkMessage.Rejected,
            LinkMessage.FileStart(7, 1234567890123L, "صورة.jpg"),
            LinkMessage.FileEnd(7),
            LinkMessage.FileResult(7, true),
            LinkMessage.FileResult(8, false),
            LinkMessage.ScreenEnded,
            LinkMessage.Ping,
            LinkMessage.Bye,
        )
        for (message in cases) assertEquals(message, LinkMessage.decode(LinkMessage.encode(message)))
        val data = LinkMessage.decode(LinkMessage.encode(LinkMessage.FileData(3, byteArrayOf(1, 2, 3)))) as LinkMessage.FileData
        assertEquals(3, data.id)
        assertArrayEquals(byteArrayOf(1, 2, 3), data.bytes)
        val frame = LinkMessage.decode(LinkMessage.encode(LinkMessage.Frame(1080, 2340, byteArrayOf(9, 8)))) as LinkMessage.Frame
        assertEquals(1080, frame.width)
        assertEquals(2340, frame.height)
        assertArrayEquals(byteArrayOf(9, 8), frame.jpeg)
    }

    @Test
    fun `malformed messages are ignored, never trusted`() {
        assertNull(LinkMessage.decode(ByteArray(0)))
        assertNull(LinkMessage.decode(byteArrayOf(0x7F)))
        assertNull(LinkMessage.decode(byteArrayOf(0x10, 0, 0))) // file start cut short
        assertNull(LinkMessage.decode(byteArrayOf(0x12, 0, 0, 0))) // file end cut short
        assertNull(LinkMessage.decode(byteArrayOf(0x20, 0, 0, 0, 5))) // a picture 0 wide
        assertNull(LinkMessage.decode(byteArrayOf(0x01) + ByteArray(LinkMessage.MAX_NAME_BYTES + 1)))
    }

    @Test
    fun `a long name is cut on a character boundary`() {
        val long = "😀".repeat(200)
        val hello = LinkMessage.decode(LinkMessage.encode(LinkMessage.Hello(long))) as LinkMessage.Hello
        assertTrue(hello.name.isNotEmpty())
        assertTrue(hello.name.toByteArray(Charsets.UTF_8).size <= LinkMessage.MAX_NAME_BYTES)
        assertEquals(hello.name, String(hello.name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    // ── The encrypted conversation ───────────────────────────────────

    private class Pair2(val sender: LinkChannel?, val receiver: LinkChannel?, val problem: Throwable?)

    private fun connectChannels(senderSecret: ByteArray, receiverSecret: ByteArray): Pair2 {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            var receiver: LinkChannel? = null
            var problem: Throwable? = null
            val t = thread {
                try {
                    val s = server.accept()
                    receiver = LinkChannel.asReceiver(s.getInputStream(), s.getOutputStream(), receiverSecret)
                } catch (e: Throwable) {
                    problem = e
                }
            }
            val client = Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
            val sender = LinkChannel.asSender(client.getInputStream(), client.getOutputStream(), senderSecret)
            t.join(3000)
            return Pair2(sender, receiver, problem)
        }
    }

    @Test
    fun `phones with the same secret talk both ways`() {
        val secret = ByteArray(32) { it.toByte() }
        val both = connectChannels(secret, secret)
        both.sender!!.send(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), both.receiver!!.receive())
        both.receiver.send(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), both.sender.receive())
        for (i in 0 until 50) {
            both.sender.send(byteArrayOf(i.toByte()))
            assertArrayEquals(byteArrayOf(i.toByte()), both.receiver.receive())
        }
    }

    @Test
    fun `a phone that did not see the code cannot make a record that opens`() {
        val both = connectChannels(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        both.sender!!.send(byteArrayOf(1))
        try {
            both.receiver!!.receive()
            throw AssertionError("a record sealed with the wrong secret opened")
        } catch (_: LinkSecurityException) {
        }
    }

    @Test
    fun `what travels is not the plain message`() {
        val secret = ByteArray(32) { 7 }
        val wire = java.io.ByteArrayOutputStream()
        // Only the receiver's reply is needed to finish the handshake: 16 bytes.
        val fromReceiver = ByteArray(16) { 3 }
        val sender = LinkChannel.asSender(ByteArrayInputStream(fromReceiver), wire, secret)
        val before = wire.size()
        val secretText = "my secret message, long enough to find".toByteArray()
        sender.send(secretText)
        val sealed = wire.toByteArray().copyOfRange(before, wire.size())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("my secret message"))
        assertEquals(4 + secretText.size + 16, sealed.size)
    }

    // ── Kept files ───────────────────────────────────────────────────

    private fun tempDir(): File = Files.createTempDirectory("beckon-link").toFile()

    @Test
    fun `a name from the other phone can never leave the folder`() {
        assertEquals("passwd", ReceivedFiles.safeName("../../etc/passwd"))
        assertEquals("x.txt", ReceivedFiles.safeName("C:\\Users\\a\\x.txt"))
        assertEquals("file", ReceivedFiles.safeName("..."))
        assertEquals("file", ReceivedFiles.safeName("   "))
        assertEquals("a b.jpg", ReceivedFiles.safeName("a<>:\"|?* b.jpg"))
        assertTrue(ReceivedFiles.safeName("x".repeat(500) + ".png").endsWith(".png"))
        assertTrue(ReceivedFiles.safeName("x".repeat(500) + ".png").length <= 100)
    }

    @Test
    fun `a file is kept only when every announced byte arrived, and same names are kept apart`() {
        val files = ReceivedFiles(tempDir())
        val a = files.begin(1, 3, "pic.jpg").also { it.write(byteArrayOf(1, 2, 3)) }.finish(0)!!
        val b = files.begin(2, 1, "pic.jpg").also { it.write(byteArrayOf(9)) }.finish(0)!!
        assertEquals("pic.jpg", a.name)
        assertEquals("pic (2).jpg", b.name)
        assertTrue(a.isImage)
        val short = files.begin(3, 5, "short.bin").also { it.write(byteArrayOf(1)) }
        assertNull(short.finish(0))
        assertEquals(2, files.list().size) // no leftovers of the failed one
        val over = files.begin(4, 1, "over.bin")
        try {
            over.write(byteArrayOf(1, 2))
            throw AssertionError("wrote more than announced")
        } catch (_: java.io.IOException) {
        }
        over.abandon()
        try {
            files.begin(5, ReceivedFiles.MAX_FILE_BYTES + 1, "huge")
            throw AssertionError("accepted a huge file")
        } catch (_: java.io.IOException) {
        }
    }

    // ── Two phones, end to end ───────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun waitFor(what: String, limitMs: Long = 5_000, test: () -> Boolean) {
        val end = System.currentTimeMillis() + limitMs
        while (System.currentTimeMillis() < end) {
            if (test()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun host(dir: File = tempDir(), timing: LinkHost.Timing = LinkHost.Timing()) = LinkHost(ReceivedFiles(dir), scope, timing)

    @Test
    fun `a phone that scanned the code is asked about, then sends a picture of its screen and a file`() = runBlocking {
        val dir = tempDir()
        val host = host(dir)
        val invite = host.start(listOf("127.0.0.1"), "Main")
        val client = LinkClient(scope)
        val linked = scope.async { client.connect(invite, "Other phone") }

        waitFor("the question") { host.state.value is HostState.Asking }
        assertEquals("Other phone", (host.state.value as HostState.Asking).deviceName)
        assertEquals(ClientState.WaitingForApproval, client.state.value)
        host.accept()
        assertTrue(linked.await())
        assertEquals(ClientState.Connected("Main"), client.state.value)
        waitFor("connected") { host.state.value is HostState.Connected }

        assertTrue(client.sendFrame(720, 1280, byteArrayOf(0x42, 0x43)))
        waitFor("the picture") { host.screen.value != null }
        assertEquals(720, host.screen.value!!.width)
        assertArrayEquals(byteArrayOf(0x42, 0x43), host.screen.value!!.jpeg)

        val content = ByteArray(300_000).also { Random(5).nextBytes(it) }
        val ok = client.sendFile("../holiday.jpg", content.size.toLong(), { ByteArrayInputStream(content) })
        assertTrue(ok)
        val kept = host.received.value.first()
        assertEquals("holiday.jpg", kept.name)
        assertTrue(kept.isImage)
        assertArrayEquals(content, File(kept.path).readBytes())
        assertEquals(dir.canonicalPath, File(kept.path).parentFile.canonicalPath)

        client.screenEnded()
        waitFor("screen gone") { host.screen.value == null }
        client.close()
        waitFor("the end") { host.state.value == HostState.Closed(CloseReason.FINISHED) }
    }

    @Test
    fun `a refused phone is told so, and the code is spent`() = runBlocking {
        val host = host()
        val invite = host.start(listOf("127.0.0.1"), "Main")
        val client = LinkClient(scope)
        val linked = scope.async { client.connect(invite, "Other") }
        waitFor("the question") { host.state.value is HostState.Asking }
        host.reject()
        assertFalse(linked.await())
        assertEquals(ClientState.Failed(FailReason.REJECTED), client.state.value)
        waitFor("spent") { host.state.value == HostState.Closed(CloseReason.REJECTED) }
        // The same code cannot be used again.
        val again = LinkClient(scope)
        assertFalse(again.connect(invite, "Other"))
        assertEquals(ClientState.Failed(FailReason.UNREACHABLE), again.state.value)
    }

    @Test
    fun `a phone with a wrong code never gets a question, and three tries spend the code`() = runBlocking {
        val host = host()
        val invite = host.start(listOf("127.0.0.1"), "Main")
        val wrong = LinkInvite(invite.hosts, invite.port, ByteArray(32) { 9 }, null)
        repeat(3) {
            val client = LinkClient(scope, LinkClient.Timing(answerMs = 2_000))
            assertFalse(client.connect(wrong, "Intruder"))
            assertTrue(host.state.value !is HostState.Asking)
        }
        waitFor("spent") { host.state.value == HostState.Closed(CloseReason.TOO_MANY_TRIES) }
    }

    @Test
    fun `a code that nobody uses expires`() {
        val host = host(timing = LinkHost.Timing(codeLifetimeMs = 200))
        host.start(listOf("127.0.0.1"), null)
        waitFor("expiry") { host.state.value == HostState.Closed(CloseReason.EXPIRED) }
    }

    @Test
    fun `stopping the host ends a link at once`() = runBlocking {
        val host = host()
        val invite = host.start(listOf("127.0.0.1"), "Main")
        val client = LinkClient(scope)
        val linked = scope.async { client.connect(invite, "Other") }
        waitFor("the question") { host.state.value is HostState.Asking }
        host.accept()
        assertTrue(linked.await())
        host.stop()
        waitFor("client notices") { client.state.value is ClientState.Failed }
        assertEquals(HostState.Idle, host.state.value)
    }
}
