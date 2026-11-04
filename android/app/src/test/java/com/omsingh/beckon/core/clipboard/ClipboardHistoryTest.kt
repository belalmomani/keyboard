package com.omsingh.beckon.core.clipboard

import com.omsingh.beckon.core.clipboard.SensitiveText.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipboardHistoryTest {

    private var counter = 0
    private fun history(max: Int = 50, initial: List<ClipEntry> = emptyList()) =
        ClipboardHistory(initial, max) { "id${++counter}" }

    private fun ClipboardHistory.texts() = entries.value.map { it.text }

    @Test
    fun `newest first, and the same text twice is one entry moved to the top`() {
        val h = history()
        assertEquals(Recorded.Added, h.record("one", ClipSource.PHONE, 1))
        h.record("two", ClipSource.PHONE, 2)
        assertEquals(Recorded.Moved, h.record("one", ClipSource.PHONE, 3))
        assertEquals(listOf("one", "two"), h.texts())
        assertEquals(3L, h.entries.value.first().copiedAt)
    }

    @Test
    fun `blank and oversized text is not kept`() {
        val h = history()
        assertEquals(Recorded.Empty, h.record("  \n", ClipSource.PHONE, 1))
        assertEquals(Recorded.TooLong, h.record("a".repeat(ClipboardHistory.MAX_CHARS + 1), ClipSource.PHONE, 1))
        assertTrue(h.texts().isEmpty())
    }

    @Test
    fun `secrets are never kept`() {
        val h = history()
        assertEquals(Recorded.Sensitive(Reason.CARD_NUMBER), h.record("4111 1111 1111 1111", ClipSource.PHONE, 1))
        assertEquals(Recorded.Sensitive(Reason.ONE_TIME_CODE), h.record("482913", ClipSource.PHONE, 1))
        assertEquals(Recorded.Sensitive(Reason.MARKED_BY_APP), h.record("anything", ClipSource.PHONE, 1, markedSensitive = true))
        assertTrue(h.texts().isEmpty())
        assertEquals(Recorded.Added, h.record("a normal note", ClipSource.PHONE, 1))
    }

    @Test
    fun `the oldest unpinned entries are dropped, pinned ones stay`() {
        val h = history(max = 3)
        for (i in 1..3) h.record("note $i", ClipSource.PHONE, i.toLong())
        h.setPinned(h.entries.value.last().id, true) // note 1
        for (i in 4..8) h.record("note $i", ClipSource.PHONE, i.toLong())
        assertEquals(listOf("note 8", "note 7", "note 6", "note 1"), h.texts())
    }

    @Test
    fun `search needs every word, ignores arabic marks, and lists pinned first`() {
        val h = history()
        h.record("رقم الحساب في البنك", ClipSource.PHONE, 1)
        h.record("Meeting link: example.org/room", ClipSource.PC, 2)
        h.record("another room booking", ClipSource.PHONE, 3)
        h.setPinned(h.entries.value.first { it.text.startsWith("Meeting") }.id, true)
        assertEquals(listOf("Meeting link: example.org/room", "another room booking"), h.search("ROOM").map { it.text })
        assertEquals(listOf("رقم الحساب في البنك"), h.search("الحسَاب بنك").map { it.text })
        assertEquals(3, h.search("").size)
        assertTrue(h.search("zzz").isEmpty())
    }

    @Test
    fun `delete and clear, which keeps pinned entries unless told otherwise`() {
        val h = history()
        h.record("a", ClipSource.PHONE, 1); h.record("b", ClipSource.PHONE, 2); h.record("c", ClipSource.PHONE, 3)
        h.delete(h.entries.value.first { it.text == "b" }.id)
        h.setPinned(h.entries.value.first { it.text == "a" }.id, true)
        h.clear()
        assertEquals(listOf("a"), h.texts())
        h.clear(includePinned = true)
        assertTrue(h.texts().isEmpty())
    }

    @Test
    fun `what is saved reads back, and deleting the store removes every trace`() {
        val dir = File.createTempFile("beckon", "dir").also { it.delete(); it.mkdirs() }
        val file = File(dir, "clips.json")
        val store = ClipboardHistoryStore(file)
        val h = history()
        h.record("مرحباً ‏😀\n\"x\"", ClipSource.PC, 5)
        h.setPinned(h.entries.value.single().id, true)
        assertTrue(store.save(h.entries.value))
        assertEquals(h.entries.value, ClipboardHistoryStore(file).load())

        file.writeText("garbage")
        assertEquals(emptyList<ClipEntry>(), store.load())
        assertTrue(File(dir, "clips.json.bad").exists())
        store.delete()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    // ── What counts as a secret ───────────────────────────────────────

    @Test
    fun `card numbers need to pass the card check, so other numbers are kept`() {
        assertEquals(Reason.CARD_NUMBER, SensitiveText.reasonToSkip("4111-1111-1111-1111"))
        assertEquals(Reason.CARD_NUMBER, SensitiveText.reasonToSkip("٤١١١ ١١١١ ١١١١ ١١١١"))
        assertNull(SensitiveText.reasonToSkip("4111 1111 1111 1112"))
        assertNull(SensitiveText.reasonToSkip("Order 1234567890123"))
    }

    @Test
    fun `labelled passwords, keys and tokens are recognised in english and arabic`() {
        assertEquals(Reason.LABELLED_SECRET, SensitiveText.reasonToSkip("password: hunter2"))
        assertEquals(Reason.LABELLED_SECRET, SensitiveText.reasonToSkip("API_KEY=abc123"))
        assertEquals(Reason.LABELLED_SECRET, SensitiveText.reasonToSkip("كلمة المرور: س١٢٣٤"))
        assertEquals(Reason.PRIVATE_KEY, SensitiveText.reasonToSkip("-----BEGIN RSA PRIVATE KEY-----\nMIIB"))
        assertEquals(Reason.TOKEN, SensitiveText.reasonToSkip("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals(Reason.TOKEN, SensitiveText.reasonToSkip("d41d8cd98f00b204e9800998ecf8427e"))
        assertEquals(Reason.TOKEN, SensitiveText.reasonToSkip("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"))
    }

    @Test
    fun `ordinary text, links and short numbers are kept`() {
        for (ok in listOf(
            "Call me at 0791234567", "https://example.org/some/long/path/that/goes/on/and/on",
            "The password policy changed today", "اجتماع الساعة 5", "12", "123456789"
        )) assertNull(ok, SensitiveText.reasonToSkip(ok))
    }
}
