package com.omsingh.beckon.core.wifi

/** At most this many bytes of text in one edit, as the PC accepts (see the protocol's `MAX_TYPED_EDIT_BYTES`). */
const val MAX_TYPED_EDIT_BYTES = 800

/**
 * Typing that does not lose or swap letters on a network that does.
 *
 * Wi-Fi drops and reorders packets now and then. Sent loosely, a letter in a lost packet is simply
 * missing on the PC, and two letters that swap places are typed swapped. So each step of typing
 * (erase some characters, then type some text) is numbered, the PC applies the steps strictly in
 * order and each once, and tells the phone the number it wants next. Whatever it has not taken is
 * sent again until it has.
 *
 * Only the logic is here, with time passed in: sending is the caller's. Every method is safe to call
 * from any thread.
 */
class ReliableTyping(
    /** Steps sent without confirmation at once. The rest wait their turn, in order. */
    private val window: Int = 16,
    private val resendAfterMs: Long = 120,
    /** Steps unconfirmed this long are given up on: the link is not there. */
    private val giveUpAfterMs: Long = 10_000,
    private val maxQueued: Int = 1_000,
) {
    /** A step to send, numbered. */
    data class Step(val seq: Int, val backspaces: Int, val text: String)

    private class Pending(var seq: Int, val backspaces: Int, val text: String, val queuedAt: Long) {
        var sentAt = -1L
        fun step() = Step(seq, backspaces, text)
    }

    private val pending = ArrayDeque<Pending>()
    private var nextSeq = 0

    /** Adds a step of typing. Returns what is to be sent now (this step, if the window has room). */
    @Synchronized
    fun add(backspaces: Int, text: String, nowMs: Long): List<Step> {
        if (backspaces <= 0 && text.isEmpty()) return emptyList()
        if (pending.size >= maxQueued) pending.clear() // the link has been gone for a long time
        val erase = backspaces.coerceIn(0, 0xFFFF)
        val pieces = split(text)
        if (pieces.isEmpty()) {
            pending.addLast(Pending(nextSeq++, erase, "", nowMs))
        } else {
            // The erasing goes with the first piece, so it is done before any of the text is typed.
            pieces.forEachIndexed { index, piece ->
                pending.addLast(Pending(nextSeq++, if (index == 0) erase else 0, piece, nowMs))
            }
        }
        return takeUnsent(nowMs)
    }

    /** The PC wants step [next]. Everything before it is done. Returns steps to send now. */
    @Synchronized
    fun onAck(next: Int, nowMs: Long): List<Step> {
        if (pending.isEmpty()) return emptyList()
        val first = pending.first().seq
        val ahead = next - first // how many steps the PC has taken, if it agrees with our numbering
        val outstanding = nextSeq - first
        when {
            // Late news, from before something already confirmed: nothing to learn from it.
            ahead < 0 -> return emptyList()
            ahead <= outstanding -> repeat(ahead) { pending.removeFirst() }
            else -> {
                // The PC's numbering is further on than ours (it is not the conversation we thought):
                // follow it, keeping the text.
                var seq = next
                for (p in pending) {
                    p.seq = seq++
                    p.sentAt = -1L
                }
                nextSeq = seq
            }
        }
        return takeUnsent(nowMs)
    }

    /** Steps sent a while ago and still not confirmed, to be sent again, oldest first. Empty when none are due. */
    @Synchronized
    fun due(nowMs: Long): List<Step> {
        if (pending.isEmpty()) return emptyList()
        if (nowMs - pending.first().queuedAt > giveUpAfterMs) {
            pending.clear()
            return emptyList()
        }
        val oldest = pending.first()
        if (oldest.sentAt < 0) return takeUnsent(nowMs) // after a restart
        if (nowMs - oldest.sentAt < resendAfterMs) return emptyList()
        // The PC takes steps only in order, so everything sent after the missing one goes again too.
        val again = ArrayList<Step>()
        for ((index, p) in pending.withIndex()) {
            if (index >= window) break
            p.sentAt = nowMs
            again += p.step()
        }
        return again
    }

    /** A new conversation with the PC begins: numbering starts again, what was not confirmed is kept and sent. */
    @Synchronized
    fun restart() {
        nextSeq = 0
        for (p in pending) {
            p.seq = nextSeq++
            p.sentAt = -1L
        }
    }

    /** Forgets everything not yet confirmed (the person disconnected). */
    @Synchronized
    fun clear() {
        pending.clear()
        nextSeq = 0
    }

    @Synchronized
    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Sends what the window has room for and has not been sent. */
    private fun takeUnsent(nowMs: Long): List<Step> {
        val out = ArrayList<Step>()
        for ((index, p) in pending.withIndex()) {
            if (index >= window) break
            if (p.sentAt < 0) {
                p.sentAt = nowMs
                out += p.step()
            }
        }
        return out
    }

    private fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val pieces = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var bytes = 0
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val size = utf8Size(cp)
                if (bytes + size > MAX_TYPED_EDIT_BYTES) break
                bytes += size
                end += Character.charCount(cp)
            }
            pieces += text.substring(start, end)
            start = end
        }
        return pieces
    }

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
