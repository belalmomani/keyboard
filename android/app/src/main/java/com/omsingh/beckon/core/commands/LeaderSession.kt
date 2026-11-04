package com.omsingh.beckon.core.commands

/** What happens after a key in leader mode. */
sealed interface LeaderStep {
    /** The keys so far lead to more: [next] says which key leads where (a key and what it does or opens). */
    data class Pending(val typed: String, val next: List<LeaderChoice>) : LeaderStep

    /** The sequence is complete: run [commandId]. */
    data class Run(val commandId: String) : LeaderStep

    /** No sequence goes that way. The session starts over. */
    data object NoMatch : LeaderStep
}

/** A key that can be pressed next, and the command it runs, or null if it leads on to more keys. */
data class LeaderChoice(val key: Char, val commandId: String?)

/**
 * The state of one leader-mode sequence: press a key, see what can follow, and so on until a command is
 * reached. A pause longer than [timeoutMs] starts the sequence over, so a forgotten half sequence cannot
 * swallow the next one.
 */
class LeaderSession(
    private val bindings: () -> List<LeaderBinding>,
    private val now: () -> Long,
    private val timeoutMs: Long = 4_000,
) {
    private var typed = ""
    private var lastAt = 0L

    /** The choices for the first key. */
    fun start(): LeaderStep.Pending {
        typed = ""
        lastAt = now()
        return pending()
    }

    fun press(key: Char): LeaderStep {
        val at = now()
        if (typed.isNotEmpty() && at - lastAt > timeoutMs) typed = ""
        lastAt = at
        val sequence = typed + key.lowercaseChar()
        val exact = bindings().firstOrNull { it.keys == sequence }
        if (exact != null) {
            typed = ""
            return LeaderStep.Run(exact.commandId)
        }
        if (bindings().none { it.keys.startsWith(sequence) }) {
            typed = ""
            return LeaderStep.NoMatch
        }
        typed = sequence
        return pending()
    }

    fun reset() {
        typed = ""
    }

    private fun pending(): LeaderStep.Pending {
        val choices = LinkedHashMap<Char, String?>()
        for (b in bindings()) {
            if (!b.keys.startsWith(typed) || b.keys.length <= typed.length) continue
            val key = b.keys[typed.length]
            val leaf = b.keys.length == typed.length + 1
            // A key that is both a leaf and a prefix cannot exist (the board refuses it), so a leaf wins.
            choices[key] = if (leaf) b.commandId else choices[key]
        }
        return LeaderStep.Pending(typed, choices.map { (k, v) -> LeaderChoice(k, v) }.sortedBy { it.key })
    }
}
