package com.omsingh.beckon.core.interfaces

/** What the person has used the fractal keyboard enough to deserve. */
enum class UsageLevel(val shortcuts: Int) {
    /** New: only groups, so the keyboard is small and nothing has to be learned. */
    BEGINNER(0),

    /** Used a little: the three most used letters are one tap away. */
    LEARNING(3),

    /** Used a lot: the six most used letters are one tap away. */
    FAMILIAR(6),
    ;

    companion object {
        const val LEARNING_AT = 30
        const val FAMILIAR_AT = 200

        fun of(totalTaps: Int): UsageLevel = when {
            totalTaps >= FAMILIAR_AT -> FAMILIAR
            totalTaps >= LEARNING_AT -> LEARNING
            else -> BEGINNER
        }
    }
}

/** A choice on the keyboard: one letter, or a group of letters that opens into more choices. */
data class FractalNode(val label: String, val chars: List<Char>) {
    val isLeaf: Boolean get() = chars.size == 1
    val char: Char get() = chars.single()
}

/** The letters the keyboard offers. */
object FractalLayouts {
    val LATIN: List<Char> = ('a'..'z').toList()
    val ARABIC: List<Char> = "ابتثجحخدذرزسشصضطظعغفقكلمنهوية".toList()
}

/**
 * A keyboard that shows few keys at a time: a handful of groups of letters, then the letters of the group
 * that was tapped. With use, the letters typed most climb to the first level beside the groups
 * ([UsageLevel]). Only how many times each letter was used is counted, never what was typed in order.
 */
class FractalKeyboard(
    private val layout: List<Char>,
    private val counts: MutableMap<Char, Int> = HashMap(),
) {
    val total: Int get() = counts.values.sum()
    val level: UsageLevel get() = UsageLevel.of(total)

    /** A copy of the counts, for saving. */
    fun snapshot(): Map<Char, Int> = counts.toMap()

    /** The first choices: the most used letters (by level), then groups of the other letters. */
    fun top(): List<FractalNode> {
        val shortcuts = layout
            .filter { (counts[it] ?: 0) > 0 }
            .sortedWith(compareByDescending<Char> { counts[it] ?: 0 }.thenBy { layout.indexOf(it) })
            .take(level.shortcuts)
        val rest = layout.filterNot { it in shortcuts }
        return shortcuts.map { leaf(it) } + groups(rest)
    }

    /** What opens when [node] is tapped: its letters, or smaller groups if there are many. */
    fun open(node: FractalNode): List<FractalNode> =
        if (node.chars.size <= MAX_CHOICES) node.chars.map(::leaf) else groups(node.chars)

    /** A letter was typed. */
    fun record(char: Char) {
        if (char in layout) counts[char] = (counts[char] ?: 0) + 1
    }

    private fun leaf(c: Char) = FractalNode(c.toString(), listOf(c))

    private fun groups(chars: List<Char>): List<FractalNode> {
        if (chars.isEmpty()) return emptyList()
        if (chars.size <= MAX_CHOICES) return chars.map(::leaf)
        val parts = minOf(MAX_CHOICES, (chars.size + 4) / 5)
        val size = chars.size / parts
        val extra = chars.size % parts
        val result = ArrayList<FractalNode>(parts)
        var at = 0
        for (i in 0 until parts) {
            val n = size + if (i < extra) 1 else 0
            val part = chars.subList(at, at + n)
            at += n
            result += FractalNode(part.first().toString() + "–" + part.last(), part.toList())
        }
        return result
    }

    companion object {
        const val MAX_CHOICES = 6
    }
}
