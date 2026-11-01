package com.omsingh.beckon.core.wifi

import com.omsingh.beckon.core.host.HostInfo
import com.omsingh.beckon.core.host.HostInfoCodec
import com.omsingh.beckon.core.screen.ScreenState
import com.omsingh.beckon.core.media.NowPlayingState
import com.omsingh.beckon.core.text.CommandResult
import com.omsingh.beckon.core.windows.ActiveWindow
import com.omsingh.beckon.core.windows.RemoteWindow
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

    /** The windows open on the PC, the one in front marked. */
    data class WindowList(val windows: List<RemoteWindow>) : ServerMessage

    /** The PC has applied every typed edit numbered below [next], and wants [next] now. */
    data class TypedAck(val next: Int) : ServerMessage

    /** How the screen view stands. For [ScreenState.STARTED], [width] and [height] are the size of the pictures. */
    data class ScreenStatus(val state: ScreenState, val width: Int, val height: Int) : ServerMessage

    /**
     * Piece [index] of [count] of picture [frameId], which is [width] by [height] pixels. [data] is a part of a
     * zlib stream (see `ScreenDecoder`).
     */
    class ScreenPiece(
        val frameId: Int,
        val index: Int,
        val count: Int,
        val width: Int,
        val height: Int,
        val data: ByteArray,
    ) : ServerMessage
}

/**
 * Reads the PC's replies.
 *
 *  - `[0x80][u16 length][utf-8 text]` clipboard
 *  - `[0x81][flags][i64 position][i64 duration]{[u8 length][utf-8]}x4` now playing
 *  - `[0x82]...` host info (see [HostInfoCodec])
 *  - `[0x83][u32 id][u8 status][u32 value]` result of a long text
 *  - `[0x84][u8 present]{[u8 length][utf-8]}x2` window in front: program, then title
 *  - `[0x85][u8 count]{[u64 handle][u8 flags]{[u8 length][utf-8]}x2}xcount` open windows: program, then title
 *  - `[0x86][u8 state][u16 width][u16 height]` screen view status
 *  - `[0x88][u32 next]` typed edits applied so far
 *  - `[0x87][u32 picture][u16 index][u16 count][u16 width][u16 height][bytes]` one piece of a picture of the screen
 *
 * Anything malformed, truncated or unknown yields null and is ignored: a packet
 * that fails to parse must never be able to crash the receive loop.
 */
object ServerMessages {
    const val CLIPBOARD_DATA: Byte = 0x80.toByte()
    const val NOW_PLAYING: Byte = 0x81.toByte()
    const val COMMAND_RESULT: Byte = 0x83.toByte()
    const val ACTIVE_WINDOW: Byte = 0x84.toByte()
    const val WINDOW_LIST: Byte = 0x85.toByte()
    const val SCREEN_STATUS: Byte = 0x86.toByte()
    const val SCREEN_FRAME: Byte = 0x87.toByte()
    const val TYPED_ACK: Byte = 0x88.toByte()
    private const val SCREEN_PIECE_HEADER = 1 + 4 + 2 + 2 + 2 + 2
    private const val MAX_SCREEN_PIECES = 800
    private const val MAX_SCREEN_PIECE_BYTES = 1000
    private const val MAX_WINDOWS = 12

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
            WINDOW_LIST -> parseWindowList(buf, len)
            SCREEN_STATUS -> parseScreenStatus(buf, len)
            SCREEN_FRAME -> parseScreenPiece(buf, len)
            TYPED_ACK -> parseTypedAck(buf, len)
            else -> null
        }
    }

    private fun parseTypedAck(buf: ByteArray, len: Int): ServerMessage? {
        if (len < 5) return null
        return ServerMessage.TypedAck(ByteBuffer.wrap(buf, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    private fun parseScreenStatus(buf: ByteArray, len: Int): ServerMessage? {
        if (len < 6) return null
        val state = ScreenState.fromWire(buf[1].toInt() and 0xFF) ?: return null
        val bb = ByteBuffer.wrap(buf, 2, len - 2).order(ByteOrder.LITTLE_ENDIAN)
        return ServerMessage.ScreenStatus(state, bb.short.toInt() and 0xFFFF, bb.short.toInt() and 0xFFFF)
    }

    private fun parseScreenPiece(buf: ByteArray, len: Int): ServerMessage? {
        if (len < SCREEN_PIECE_HEADER || len - SCREEN_PIECE_HEADER > MAX_SCREEN_PIECE_BYTES) return null
        val bb = ByteBuffer.wrap(buf, 1, len - 1).order(ByteOrder.LITTLE_ENDIAN)
        val frameId = bb.int
        val index = bb.short.toInt() and 0xFFFF
        val count = bb.short.toInt() and 0xFFFF
        val width = bb.short.toInt() and 0xFFFF
        val height = bb.short.toInt() and 0xFFFF
        // A piece that says it is outside its own picture is not one of the PC's.
        if (count < 1 || count > MAX_SCREEN_PIECES || index >= count) return null
        return ServerMessage.ScreenPiece(
            frameId, index, count, width, height,
            buf.copyOfRange(SCREEN_PIECE_HEADER, len),
        )
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

    private fun parseWindowList(buf: ByteArray, len: Int): ServerMessage? {
        if (len < 2) return null
        val count = buf[1].toInt() and 0xFF
        if (count > MAX_WINDOWS) return null
        var at = 2
        val windows = ArrayList<RemoteWindow>(count)
        repeat(count) {
            if (at + 10 > len) return null
            val handle = ByteBuffer.wrap(buf, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val foreground = buf[at + 8].toInt() and 1 == 1
            at += 9
            val fields = ArrayList<String>(2)
            repeat(2) {
                if (at >= len) return null
                val n = buf[at].toInt() and 0xFF
                at += 1
                if (at + n > len) return null
                fields += String(buf, at, n, Charsets.UTF_8)
                at += n
            }
            windows += RemoteWindow(handle, fields[0], fields[1], foreground)
        }
        return ServerMessage.WindowList(windows)
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
