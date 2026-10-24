package com.omsingh.beckon.core.library

import com.omsingh.beckon.core.text.TextBlockCodec
import com.omsingh.beckon.core.text.TextNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A saved sentence or paragraph, ready to be typed on the PC in one go. */
data class Phrase(
    val id: String,
    val title: String,
    val text: String,
    /** The folder it is in, or null for the top level. */
    val folderId: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
)

data class PhraseFolder(val id: String, val name: String)

/** Everything the library holds. Immutable: every change makes a new one. */
data class LibraryData(
    val folders: List<PhraseFolder> = emptyList(),
    val phrases: List<Phrase> = emptyList(),
)

/** Why a change was refused. Nothing is changed when one is returned. */
enum class LibraryError {
    EMPTY_TEXT,
    TEXT_TOO_LONG,
    TITLE_TOO_LONG,
    TOO_MANY_PHRASES,
    FOLDER_NAME_EMPTY,
    FOLDER_NAME_TOO_LONG,
    FOLDER_NAME_TAKEN,
    TOO_MANY_FOLDERS,
    NOT_FOUND,
}

/** Which phrases to list. */
sealed interface PhraseScope {
    data object All : PhraseScope
    data object Favorites : PhraseScope

    /** Phrases that are in no folder. */
    data object Unfiled : PhraseScope
    data class Folder(val id: String) : PhraseScope
}

/**
 * The saved phrases and their folders, with the rules for changing them.
 *
 * Time and ids come in as functions, so every rule is a plain test. Not thread-safe: use from one
 * thread (the view model's).
 */
class PhraseLibrary(
    initial: LibraryData = LibraryData(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val _data = MutableStateFlow(initial)
    val data: StateFlow<LibraryData> = _data.asStateFlow()

    /** Adds a phrase. A blank [title] becomes the start of the text. Returns the error, or null. */
    fun add(title: String, text: String, folderId: String? = null, favorite: Boolean = false): LibraryError? {
        validate(title, text)?.let { return it }
        val current = _data.value
        if (current.phrases.size >= MAX_PHRASES) return LibraryError.TOO_MANY_PHRASES
        if (folderId != null && current.folders.none { it.id == folderId }) return LibraryError.NOT_FOUND
        val time = now()
        val phrase = Phrase(
            id = newId(),
            title = titleFor(title, text),
            text = text,
            folderId = folderId,
            favorite = favorite,
            createdAt = time,
            updatedAt = time,
        )
        _data.value = current.copy(phrases = current.phrases + phrase)
        return null
    }

    fun update(id: String, title: String, text: String, folderId: String?): LibraryError? {
        validate(title, text)?.let { return it }
        val current = _data.value
        if (current.phrases.none { it.id == id }) return LibraryError.NOT_FOUND
        if (folderId != null && current.folders.none { it.id == folderId }) return LibraryError.NOT_FOUND
        val time = now()
        _data.value = current.copy(
            phrases = current.phrases.map {
                if (it.id == id) it.copy(title = titleFor(title, text), text = text, folderId = folderId, updatedAt = time) else it
            },
        )
        return null
    }

    fun delete(id: String) {
        val current = _data.value
        _data.value = current.copy(phrases = current.phrases.filterNot { it.id == id })
    }

    fun toggleFavorite(id: String) = change(id) { it.copy(favorite = !it.favorite) }

    /** The phrase was typed on the PC: it moves up in the list of recent ones. */
    fun markUsed(id: String) = change(id) { it.copy(useCount = it.useCount + 1, lastUsedAt = now()) }

    fun addFolder(name: String): LibraryError? {
        val clean = name.trim()
        if (clean.isEmpty()) return LibraryError.FOLDER_NAME_EMPTY
        if (clean.length > MAX_FOLDER_NAME) return LibraryError.FOLDER_NAME_TOO_LONG
        val current = _data.value
        if (current.folders.size >= MAX_FOLDERS) return LibraryError.TOO_MANY_FOLDERS
        if (nameTaken(current, clean, except = null)) return LibraryError.FOLDER_NAME_TAKEN
        _data.value = current.copy(folders = current.folders + PhraseFolder(newId(), clean))
        return null
    }

    fun renameFolder(id: String, name: String): LibraryError? {
        val clean = name.trim()
        if (clean.isEmpty()) return LibraryError.FOLDER_NAME_EMPTY
        if (clean.length > MAX_FOLDER_NAME) return LibraryError.FOLDER_NAME_TOO_LONG
        val current = _data.value
        if (current.folders.none { it.id == id }) return LibraryError.NOT_FOUND
        if (nameTaken(current, clean, except = id)) return LibraryError.FOLDER_NAME_TAKEN
        _data.value = current.copy(folders = current.folders.map { if (it.id == id) it.copy(name = clean) else it })
        return null
    }

    /** Deletes a folder. Its phrases are kept, moved to the top level: deleting a folder never deletes text. */
    fun deleteFolder(id: String) {
        val current = _data.value
        _data.value = LibraryData(
            folders = current.folders.filterNot { it.id == id },
            phrases = current.phrases.map { if (it.folderId == id) it.copy(folderId = null) else it },
        )
    }

    fun phrase(id: String): Phrase? = _data.value.phrases.firstOrNull { it.id == id }

    private fun change(id: String, edit: (Phrase) -> Phrase) {
        val current = _data.value
        if (current.phrases.none { it.id == id }) return
        _data.value = current.copy(phrases = current.phrases.map { if (it.id == id) edit(it) else it })
    }

    private fun validate(title: String, text: String): LibraryError? = when {
        text.isBlank() -> LibraryError.EMPTY_TEXT
        !TextBlockCodec.fits(text) -> LibraryError.TEXT_TOO_LONG
        title.trim().length > MAX_TITLE -> LibraryError.TITLE_TOO_LONG
        else -> null
    }

    private fun nameTaken(data: LibraryData, name: String, except: String?): Boolean {
        val wanted = TextNormalizer.normalize(name)
        return data.folders.any { it.id != except && TextNormalizer.normalize(it.name) == wanted }
    }

    companion object {
        const val MAX_PHRASES = 500
        const val MAX_FOLDERS = 50
        const val MAX_TITLE = 80
        const val MAX_FOLDER_NAME = 40
        private const val DERIVED_TITLE = 40

        /** What a phrase is called when no title was given: the first line of its text, cut short. */
        fun titleFor(title: String, text: String): String {
            val given = title.trim()
            if (given.isNotEmpty()) return given
            val firstLine = text.trim().lineSequence().first().trim()
            val codePoints = firstLine.codePointCount(0, firstLine.length)
            if (codePoints <= DERIVED_TITLE) return firstLine
            val end = firstLine.offsetByCodePoints(0, DERIVED_TITLE)
            return firstLine.substring(0, end).trimEnd() + "…"
        }
    }
}

/** Finding and ordering phrases. */
object PhraseSearch {

    /**
     * The phrases in [scope] that match [query] (every word of it, anywhere in the title or text, ignoring
     * case, Arabic vowel marks and the different forms of alef, ya and ta marbuta). Best matches first; with
     * no query, favourites first, then the most recently used, then the most recently changed.
     */
    fun find(data: LibraryData, query: String, scope: PhraseScope = PhraseScope.All): List<Phrase> {
        val inScope = data.phrases.filter {
            when (scope) {
                PhraseScope.All -> true
                PhraseScope.Favorites -> it.favorite
                PhraseScope.Unfiled -> it.folderId == null
                is PhraseScope.Folder -> it.folderId == scope.id
            }
        }
        val tokens = TextNormalizer.tokens(query)
        val byUse = compareByDescending<Phrase> { it.favorite }
            .thenByDescending { it.lastUsedAt }
            .thenByDescending { it.updatedAt }
        if (tokens.isEmpty()) return inScope.sortedWith(byUse)

        val scored = ArrayList<Pair<Phrase, Int>>()
        for (phrase in inScope) {
            val title = TextNormalizer.normalize(phrase.title)
            val text = TextNormalizer.normalize(phrase.text)
            if (tokens.any { it !in title && it !in text }) continue
            var score = 0
            for (token in tokens) {
                score += if (title.startsWith(token)) 3 else if (token in title) 2 else 1
            }
            scored += phrase to score
        }
        return scored
            .sortedWith(compareByDescending<Pair<Phrase, Int>> { it.second }.thenBy(byUse) { it.first })
            .map { it.first }
    }
}
