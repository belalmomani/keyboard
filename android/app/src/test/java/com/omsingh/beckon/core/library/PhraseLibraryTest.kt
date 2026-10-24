package com.omsingh.beckon.core.library

import com.omsingh.beckon.core.text.TextBlockCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhraseLibraryTest {

    private var clock = 1_000L
    private var counter = 0
    private val library = PhraseLibrary(now = { clock }, newId = { "id${++counter}" })

    private fun titles(scope: PhraseScope = PhraseScope.All, query: String = "") =
        PhraseSearch.find(library.data.value, query, scope).map { it.title }

    // ── Adding and changing ───────────────────────────────────────────

    @Test
    fun `a phrase keeps its text exactly, with line breaks and right-to-left marks`() {
        val text = "السلام عليكم‏\n\nمع تحياتي،\r\nأحمد 🌿"
        assertNull(library.add("تحية", text))
        assertEquals(text, library.data.value.phrases.single().text)
    }

    @Test
    fun `a blank title is taken from the first line of the text`() {
        library.add("", "\n  First line here\nsecond line")
        library.add("   ", "ع".repeat(100))
        val titles = library.data.value.phrases.map { it.title }
        assertEquals("First line here", titles[0])
        assertEquals("ع".repeat(40) + "…", titles[1])
    }

    @Test
    fun `empty text, long text and long titles are refused and change nothing`() {
        assertEquals(LibraryError.EMPTY_TEXT, library.add("t", "  \n "))
        assertEquals(LibraryError.TEXT_TOO_LONG, library.add("t", "a".repeat(TextBlockCodec.MAX_TEXT_BYTES + 1)))
        assertEquals(LibraryError.TITLE_TOO_LONG, library.add("t".repeat(PhraseLibrary.MAX_TITLE + 1), "x"))
        assertEquals(LibraryError.NOT_FOUND, library.add("t", "x", folderId = "nope"))
        assertTrue(library.data.value.phrases.isEmpty())
    }

    @Test
    fun `the number of phrases is bounded`() {
        repeat(PhraseLibrary.MAX_PHRASES) { assertNull(library.add("p$it", "text $it")) }
        assertEquals(LibraryError.TOO_MANY_PHRASES, library.add("one more", "x"))
    }

    @Test
    fun `updating changes the text and the time, and nothing else is lost`() {
        library.add("Greeting", "Hello", favorite = true)
        val id = library.data.value.phrases.single().id
        clock = 5_000
        assertNull(library.update(id, "Greeting 2", "Hello there", null))
        val p = library.phrase(id)!!
        assertEquals("Greeting 2", p.title)
        assertEquals("Hello there", p.text)
        assertEquals(1_000L, p.createdAt)
        assertEquals(5_000L, p.updatedAt)
        assertTrue(p.favorite)
        assertEquals(LibraryError.NOT_FOUND, library.update("missing", "t", "x", null))
        assertEquals(LibraryError.EMPTY_TEXT, library.update(id, "t", "", null))
        assertEquals("Hello there", library.phrase(id)!!.text)
    }

    @Test
    fun `deleting removes only that phrase`() {
        library.add("a", "1"); library.add("b", "2")
        library.delete(library.data.value.phrases.first().id)
        assertEquals(listOf("b"), titles())
    }

    // ── Folders ───────────────────────────────────────────────────────

    @Test
    fun `folder names are checked, also by how they would be found`() {
        assertNull(library.addFolder(" العمل "))
        assertEquals("العمل", library.data.value.folders.single().name)
        assertEquals(LibraryError.FOLDER_NAME_EMPTY, library.addFolder("  "))
        assertEquals(LibraryError.FOLDER_NAME_TAKEN, library.addFolder("العمل"))
        assertNull(library.addFolder("البيت"))
        assertEquals(LibraryError.FOLDER_NAME_TOO_LONG, library.addFolder("x".repeat(PhraseLibrary.MAX_FOLDER_NAME + 1)))
    }

    @Test
    fun `renaming a folder cannot take another folders name`() {
        library.addFolder("Work"); library.addFolder("Home")
        val (work, home) = library.data.value.folders
        assertEquals(LibraryError.FOLDER_NAME_TAKEN, library.renameFolder(home.id, "work"))
        assertNull(library.renameFolder(work.id, "Work"))
        assertNull(library.renameFolder(home.id, "House"))
        assertEquals(LibraryError.NOT_FOUND, library.renameFolder("x", "Y"))
    }

    @Test
    fun `deleting a folder keeps its phrases at the top level`() {
        library.addFolder("Work")
        val folder = library.data.value.folders.single()
        library.add("in", "text", folderId = folder.id)
        library.add("out", "text")
        library.deleteFolder(folder.id)
        assertTrue(library.data.value.folders.isEmpty())
        assertEquals(setOf("in", "out"), titles().toSet())
        assertTrue(library.data.value.phrases.all { it.folderId == null })
    }

    @Test
    fun `scopes list favourites, one folder, or what is in no folder`() {
        library.addFolder("Work")
        val folder = library.data.value.folders.single()
        library.add("a", "1", folderId = folder.id, favorite = true)
        library.add("b", "2", folderId = folder.id)
        library.add("c", "3", favorite = true)
        assertEquals(setOf("a", "c"), titles(PhraseScope.Favorites).toSet())
        assertEquals(setOf("a", "b"), titles(PhraseScope.Folder(folder.id)).toSet())
        assertEquals(listOf("c"), titles(PhraseScope.Unfiled))
        assertEquals(3, titles(PhraseScope.All).size)
    }

    // ── Favourites, use and order ─────────────────────────────────────

    @Test
    fun `with no query favourites come first, then the most recently used`() {
        library.add("old", "1"); library.add("recent", "2"); library.add("fav", "3")
        val (old, recent, fav) = library.data.value.phrases
        library.toggleFavorite(fav.id)
        clock = 2_000; library.markUsed(old.id)
        clock = 3_000; library.markUsed(recent.id)
        assertEquals(listOf("fav", "recent", "old"), titles())
        library.toggleFavorite(fav.id)
        assertEquals(listOf("recent", "old", "fav"), titles())
        assertEquals(1, library.phrase(old.id)!!.useCount)
    }

    // ── Searching ─────────────────────────────────────────────────────

    @Test
    fun `search needs every word, in the title or the text`() {
        library.add("Meeting reply", "Thanks, I can attend on Monday")
        library.add("Out of office", "I am away until Monday")
        assertEquals(listOf("Meeting reply"), titles(query = "monday attend"))
        assertEquals(2, titles(query = "MONDAY").size)
        assertTrue(titles(query = "tuesday").isEmpty())
    }

    @Test
    fun `search finds arabic whatever the form of the letters or marks`() {
        library.add("ترحيب", "أهلاً وسهلاً بكم في مدرسة الحي")
        library.add("شكر", "شكراً جزيلاً")
        assertEquals(listOf("ترحيب"), titles(query = "اهلا"))
        assertEquals(listOf("ترحيب"), titles(query = "مدرسه"))
        assertEquals(listOf("شكر"), titles(query = "جزيلا"))
    }

    @Test
    fun `a title match ranks above a match in the text`() {
        library.add("Other", "the invoice is attached")
        library.add("Invoice", "please pay")
        assertEquals(listOf("Invoice", "Other"), titles(query = "invoice"))
    }

    // ── Saving ────────────────────────────────────────────────────────

    @Test
    fun `the json document keeps everything, in any script`() {
        library.addFolder("العمل")
        val folder = library.data.value.folders.single()
        library.add("تحية", "مرحباً‏\n😀 \"quoted\" \\ back\tslash", folderId = folder.id, favorite = true)
        library.markUsed(library.data.value.phrases.single().id)

        val back = LibraryJson.read(LibraryJson.write(library.data.value))
        assertEquals(library.data.value, back)
    }

    @Test
    fun `reading skips what is broken and keeps the rest`() {
        val text = """{"version":9,"future":true,"folders":[{"id":"f","name":"A"},{"id":3}],
            "phrases":[{"id":"p1","title":"ok","text":"fine","folderId":"gone","extra":1},
                       {"id":"p2","title":"no text"},
                       {"id":"p3","title":"ok2","text":"fine2","folderId":"f","favorite":true}]}"""
        val data = LibraryJson.read(text)!!
        assertEquals(listOf("f"), data.folders.map { it.id })
        assertEquals(listOf("p1", "p3"), data.phrases.map { it.id })
        assertNull("a folder that is gone leaves the phrase at the top", data.phrases[0].folderId)
        assertEquals("f", data.phrases[1].folderId)
        assertTrue(data.phrases[1].favorite)
    }

    @Test
    fun `text that is not a library document is not read as one`() {
        assertNull(LibraryJson.read("not json at all"))
        assertNull(LibraryJson.read("[1,2,3]"))
        assertNull(LibraryJson.read(""))
        assertEquals(LibraryData(), LibraryJson.read("{}"))
    }

    @Test
    fun `the store saves, loads, and keeps an unreadable file aside instead of overwriting it`() {
        val dir = File.createTempFile("beckon", "dir").also { it.delete(); it.mkdirs() }
        val file = File(dir, "phrases.json")
        val store = PhraseStore(file)
        assertEquals(LibraryData(), store.load())

        library.add("t", "نص")
        assertTrue(store.save(library.data.value))
        assertEquals(library.data.value, PhraseStore(file).load())
        assertTrue("no temporary file is left behind", !File(dir, "phrases.json.tmp").exists())

        file.writeText("{{ broken")
        assertEquals(LibraryData(), store.load())
        assertTrue(File(dir, "phrases.json.bad").readText() == "{{ broken")
        assertNotNull(dir)
        dir.deleteRecursively()
    }
}
