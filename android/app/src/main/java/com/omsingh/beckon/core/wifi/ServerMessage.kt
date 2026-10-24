package com.omsingh.beckon.core.wifi

import com.omsingh.beckon.core.host.HostInfo
import com.omsingh.beckon.core.host.HostInfoCodec
import com.omsingh.beckon.core.media.NowPlayingState
import com.omsingh.beckon.core.text.CommandResult
import com.omsingh.beckon.core.windows.ActiveWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A message the PC sends to the phone, once decrypted. */
sealed interface ServerMessage {
    /** The PC's clipboard text, answering a clipboard request. */
    data class Clipboard(val text: String) : ServerMessage

    data class NowPlaying(val state: NowPlayingState) : ServerMessage

    data class Host(val info: HostInfo) : ServerMessage

    /** How a long text sent in pieces is going, or how it ended. */
    data class Result(val result: CommandResult) : ServerMessage

    /** The program and window in front on the PC, or null when the PC could not say. */
    data class Window(val window: ActiveWindow?) : ServerMessage
}

/**
 * Reads the PC's replies.
 *
 *  - `[0x80][u16 length][utf-8 text]` clipboard
 *  - `[0x81][flags][i64 position][i64 duration]{[u8 length][utf-8]}x4` now playing
 *  - `[0x82]...` host info (see [HostInfoCodec])
 *  - `[0x83][u32 id][u8 status][u32 value]` result of a long text
 *  - `[0x84][u8 present]{[u8 length][utf-8]}x2` window in front: program, then title
 *
 * Anything malformed, truncated or unknown yields null and is ignored: a packet
 * that fails to parse must never be able to crash the receive loop.
 */
object ServerMessages {
    const val CLIPBOARD_DATA: Byte = 0x80.toByte()
    const val NOW_PLAYING: Byte = 0x81.toByte()
    const val COMMAND_RESULT: Byte = 0x83.toByte()
    const val ACTIVE_WINDOW: Byte = 0x84.toByte()

    private const val NOW_PLAYING_FIXED = 1 + 1 + 8 + 8
    private const val FLAG_PLAYING = 0x01

    fun parse(buf: ByteArray, len: Int, nowMs: Long): ServerMessage? {
        if (len < 1 || len > buf.size) return null
        return when (buf[0]) {
            CLIPBOARD_DATA -> parseClipboard(buf, len)
            NOW_PLAYING -> parseNowPlaying(buf, len, nowMs)
            HostInfoCodec.MSG_HOST_INFO -> HostInfoCodec.parse(buf, len)?.let { ServerMessage.Host(it) }
            COMMAND_RESULT -> parseResult(buf, len)
            ACTIVE_WINDOW -> parseWindow(buf, len)
            else -> null
        }
    }

    private fun parseResult(buf: ByteArray, len: Int): ServerMessage? {
        if (len < 10) return null
        val bb = ByteBuffer.wrap(buf, 1, len - 1).order(ByteOrder.LITTLE_ENDIAN)
        val id = bb.int
        val status = CommandResult.Status.fromWire(bb.get().toInt() and 0xFF)
        val value = bb.int
        // A value that does not fit a signed int is nonsense for a character count: treat it as none.
        return ServerMessage.Result(CommandResult(id, status, if (value < 0) 0 else value))
    }

    private fun parseWindow(buf: ByteArray, len: Int): ServerMessage? {
        if (len < 2) return null
        if (buf[1].toInt() and 1 == 0) return ServerMessage.Window(null)
        var at = 2
        val fields = ArrayList<String>(2)
        repeat(2) {
            if (at >= len) return null
            val n = buf[at].toInt() and 0xFF
            at += 1
            if (at + n > len) return null
            fields += String(buf, at, n, Charsets.UTF_8)
            at += n
        }
        return ServerMessage.Window(ActiveWindow(process = fields[0], title = fields[1]))
    }

    private fun parseClipboard(buf: ByteArray, len: Int): ServerMessage? {
        if (len < 3) return null
        val textLen = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0xFF) shl 8)
        if (3 + textLen > len) return null
        return ServerMessage.Clipboard(String(buf, 3, textLen, Charsets.UTF_8))
    }

    private fun parseNowPlaying(buf: ByteArray, len: Int, nowMs: Long): ServerMessage? {
        if (len < NOW_PLAYING_FIXED) return null
        val bb = ByteBuffer.wrap(buf, 1, len - 1).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.get().toInt() and 0xFF
        val position = bb.long
        val duration = bb.long

        // Four strings follow; an absent one is a zero length (or the packet simply ends).
        fun readString(): String? {
            if (bb.remaining() < 1) return null
            val n = bb.get().toInt() and 0xFF
            if (n == 0 || bb.remaining() < n) return null
            val bytes = ByteArray(n)
            bb.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        return ServerMessage.NowPlaying(
            NowPlayingState(
                title = readString(),
                artist = readString(),
                album = readString(),
                sourceApp = readString(),
                isPlaying = flags and FLAG_PLAYING != 0,
                positionMs = position.takeIf { it >= 0 },
                durationMs = duration.takeIf { it >= 0 },
                sampledAtMs = nowMs,
            ),
        )
    }
}
