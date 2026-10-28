package com.omsingh.beckon.core.text

import java.util.Locale

/**
 * Makes text comparable for searching, in Arabic and Latin script alike.
 *
 * Searching "احمد" should find "أحمد", and "الصلاة" should find the same word written with its
 * vowel marks. So for *comparison only* (what is stored and typed is never changed) this:
 *
 *  - lower-cases (language-neutral, so Turkish dotted i does not surprise anyone),
 *  - removes Arabic vowel marks (U+064B–U+065F, U+0670) and the elongation stroke (U+0640),
 *  - unifies the forms of alef (أ إ آ ٱ → ا), ya (ى → ي) and ta marbuta (ة → ه),
 *  - turns Arabic-Indic (٠–٩) and Persian (۰–۹) digits into 0–9,
 *  - drops invisible direction marks (U+200E, U+200F, U+202A–U+202E, U+2066–U+2069) and
 *    zero-width joiners, which are in the text to steer display and are not part of a word,
 *  - folds runs of white space into one space.
 */
object TextNormalizer {

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var lastWasSpace = true
        for (c in text.lowercase(Locale.ROOT)) {
            val mapped: Char? = when (c) {
                in 'ً'..'ٟ', 'ٰ', 'ـ' -> null
                '\u200E', '\u200F', '\u200B', '\u200C', '\u200D', '\u061C', '\uFEFF' -> null
                in '\u202A'..'\u202E', in '\u2066'..'\u2069' -> null
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            }
            if (mapped == null) continue
            if (mapped.isWhitespace()) {
                if (!lastWasSpace) out.append(' ')
                lastWasSpace = true
            } else {
                out.append(mapped)
                lastWasSpace = false
            }
        }
        return out.toString().trimEnd()
    }

    /** The words of a search, normalised. Empty for a blank query. */
    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }
}
