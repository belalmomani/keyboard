package com.omsingh.beckon.core.clipboard

import com.omsingh.beckon.core.text.TextNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a clip came from. */
enum class ClipSource { PHONE, PC }

data class ClipEntry(
    val id: String,
    val text: String,
    val pinned: Boolean,
    val copiedAt: Long,
    val source: ClipSource,
)

/** What happened to text offered to the history. */
sealed interface Recorded {
    data object Added : Recorded

    /** It was already there: it moved to the top. */
    data object Moved : Recorded
    data object Empty : Recorded
    data object TooLong : Recorded
    data class Sensitive(val reason: SensitiveText.Reason) : Recorded
}

/**
 * What was copied on the phone (and brought from the PC), newest first, for finding and reusing.
 *
 *  - Text that looks like a secret is never kept ([SensitiveText]).
 *  - The same text twice is one entry, moved to the top.
 *  - Pinned entries stay until they are unpinned or deleted; the rest are dropped oldest first beyond
 *    [maxUnpinned].
 *  - It holds nothing on its own accord: the caller decides what to offer it, and whether to save it.
 *
 * Not thread-safe: use from one thread.
 */
class ClipboardHistory(
    initial: List<ClipEntry> = emptyList(),
    private val maxUnpinned: Int = MAX_UNPINNED,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val _entries = MutableStateFlow(initial)
    val entries: StateFlow<List<ClipEntry>> = _entries.asStateFlow()

    fun record(text: String, source: ClipSource, now: Long, markedSensitive: Boolean = false): Recorded {
        if (text.isBlank()) return Recorded.Empty
        if (text.length > MAX_CHARS) return Recorded.TooLong
        SensitiveText.reasonToSkip(text, markedSensitive)?.let { return Recorded.Sensitive(it) }

        val current = _entries.value
        val existing = current.firstOrNull { it.text == text }
        val result = if (existing != null) {
            val moved = existing.copy(copiedAt = now)
            _entries.value = listOf(moved) + current.filterNot { it.id == existing.id }
            Recorded.Moved
        } else {
            val entry = ClipEntry(newId(), text, pinned = false, copiedAt = now, source = source)
            _entries.value = trim(listOf(entry) + current)
            Recorded.Added
        }
        return result
    }

    fun setPinned(id: String, pinned: Boolean) {
        _entries.value = trim(_entries.value.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun delete(id: String) {
        _entries.value = _entries.value.filterNot { it.id == id }
    }

    /** Empties the history. Pinned entries stay unless [includePinned]. */
    fun clear(includePinned: Boolean = false) {
        _entries.value = if (includePinned) emptyList() else _entries.value.filter { it.pinned }
    }

    /** Entries matching every word of [query] (pinned first, then newest first). All of them for a blank query. */
    fun search(query: String): List<ClipEntry> {
        val tokens = TextNormalizer.tokens(query)
        val matching = if (tokens.isEmpty()) {
            _entries.value
        } else {
            _entries.value.filter { entry ->
                val text = TextNormalizer.normalize(entry.text)
                tokens.all { it in text }
            }
        }
        // sortedByDescending is stable, so within each group the newest-first order holds.
        return matching.sortedByDescending { it.pinned }
    }

    private fun trim(entries: List<ClipEntry>): List<ClipEntry> {
        var unpinned = 0
        return entries.filter { entry ->
            entry.pinned || ++unpinned <= maxUnpinned
        }
    }

    companion object {
        const val MAX_UNPINNED = 50
        const val MAX_CHARS = 20_000
    }
}
