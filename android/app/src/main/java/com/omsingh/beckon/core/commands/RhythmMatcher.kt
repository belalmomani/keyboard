package com.omsingh.beckon.core.commands

/** What a finished run of taps came to. */
sealed interface RhythmResult {
    data class Match(val pattern: String, val commandId: String) : RhythmResult
    data class NoMatch(val pattern: String) : RhythmResult
}

/**
 * Turns taps on the rhythm pad into a pattern of short (`.`) and long (`-`) taps, and a command.
 *
 * A tap held for [longMs] or more is long. The run ends when [gapMs] passes with no tap ([poll]) or when
 * it already has [MAX_TAPS] taps, and the whole run must equal a bound pattern: a run is never read as
 * part of another. Time comes in as a parameter, so there are no timers in here to test.
 */
class RhythmMatcher(
    private val bindings: () -> List<RhythmBinding>,
    private val longMs: Long = 350,
    private val gapMs: Long = 800,
) {
    private val taps = StringBuilder()
    private var lastEndAt = 0L

    /** The taps so far, such as `.-.`; empty when none. */
    val pattern: String get() = taps.toString()

    /** A tap that lasted [heldMs] and ended at [atMs]. Returns the result if this tap ended the run. */
    fun tap(heldMs: Long, atMs: Long): RhythmResult? {
        // A tap after a long pause starts a new run (poll normally ended the old one first).
        if (taps.isNotEmpty() && atMs - lastEndAt > gapMs) taps.clear()
        taps.append(if (heldMs >= longMs) '-' else '.')
        lastEndAt = atMs
        return if (taps.length >= MAX_TAPS) finish() else null
    }

    /** Called regularly: ends the run once the gap has passed. */
    fun poll(atMs: Long): RhythmResult? =
        if (taps.isNotEmpty() && atMs - lastEndAt >= gapMs) finish() else null

    fun reset() = taps.clear().let { }

    private fun finish(): RhythmResult {
        val pattern = taps.toString()
        taps.clear()
        val binding = bindings().firstOrNull { it.pattern == pattern }
        return if (binding != null) RhythmResult.Match(pattern, binding.commandId) else RhythmResult.NoMatch(pattern)
    }

    companion object {
        const val MAX_TAPS = 6
    }
}
