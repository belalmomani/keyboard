package com.omsingh.beckon.core.text

/**
 * Cutting a long text into the pieces that travel to the PC, one datagram each.
 *
 * The limits are the protocol's (`beckon-protocol`: `MAX_TEXT_CHUNK_BYTES`, `MAX_TEXT_BLOCK_CHUNKS`).
 * Pieces are cut only between characters, and a character outside the Basic Multilingual Plane
 * (an emoji, which Kotlin stores as two `Char`s) is never cut in the middle. Joining the pieces in
 * order gives the text back exactly: nothing is normalised, reordered or dropped, so Arabic text,
 * direction marks and joiners arrive as they were written.
 */
object TextBlockCodec {
    const val MAX_CHUNK_BYTES = 900
    const val MAX_CHUNKS = 80
    const val MAX_TEXT_BYTES = MAX_CHUNK_BYTES * MAX_CHUNKS

    /** UTF-8 length of [text] in bytes. */
    fun utf8Size(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            bytes += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(cp)
        }
        return bytes
    }

    fun fits(text: String): Boolean = utf8Size(text) <= MAX_TEXT_BYTES

    /** The pieces of [text], or null when it is too long to send. An empty text has no pieces. */
    fun split(text: String): List<String>? {
        if (!fits(text)) return null
        val pieces = ArrayList<String>()
        var start = 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > MAX_CHUNK_BYTES) {
                pieces += text.substring(start, i)
                start = i
                bytes = 0
            }
            bytes += size
            i += Character.charCount(cp)
        }
        if (start < text.length) pieces += text.substring(start)
        return pieces
    }
}
