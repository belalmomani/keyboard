package com.omsingh.beckon.core.screen

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** How the PC says the screen view stands (the wire values are the PC's). */
enum class ScreenState(val wire: Int) {
    /** Pictures are on their way. */
    STARTED(0),

    /** The PC stopped sending pictures. */
    STOPPED(1),

    /** The owner of the PC has not allowed phones to see the screen. */
    DISABLED(2),

    /** This kind of computer cannot show its screen to Beckon yet. */
    UNSUPPORTED(3),

    /** The system would not give the PC's screen. */
    FAILED(4),
    ;

    companion object {
        fun fromWire(value: Int): ScreenState? = entries.firstOrNull { it.wire == value }
    }
}

/** A whole picture as the PC sent it, still compressed. */
class EncodedPicture(val id: Int, val width: Int, val height: Int, val bytes: ByteArray)

/** A picture of the PC's screen, ready to draw: [pixels] are ARGB, [width] by [height], rows from the top. */
class ScreenPicture(val id: Int, val width: Int, val height: Int, val pixels: IntArray)

/**
 * Puts the pieces of a picture back together.
 *
 * One picture is put together at a time. A piece of a newer picture drops the unfinished older one (a picture with
 * a lost piece is never going to be whole, and the next one is already on its way), and a piece of an older picture
 * than the last one finished is ignored. Every picture stands alone, so nothing is ever waited for.
 */
class PictureAssembler {
    private var current = 0
    private var building = false
    private var count = 0
    private var width = 0
    private var height = 0
    private var pieces: Array<ByteArray?> = emptyArray()
    private var received = 0
    private var finished: Int? = null

    /** The finished picture when [data] was the last piece it was waiting for, else null. */
    @Synchronized
    fun accept(id: Int, index: Int, count: Int, width: Int, height: Int, data: ByteArray): EncodedPicture? {
        if (count < 1 || count > MAX_PIECES || index !in 0 until count) return null
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        finished?.let { if (!newer(id, it)) return null }
        if (!building || id != current) {
            if (building && !newer(id, current)) return null
            begin(id, count, width, height)
        } else if (count != this.count || width != this.width || height != this.height) {
            return null
        }
        if (pieces[index] == null) {
            pieces[index] = data
            received++
        }
        if (received < count) return null

        var total = 0
        for (piece in pieces) total += piece?.size ?: 0
        val joined = ByteArray(total)
        var at = 0
        for (piece in pieces) {
            val bytes = piece ?: return null
            System.arraycopy(bytes, 0, joined, at, bytes.size)
            at += bytes.size
        }
        finished = id
        building = false
        pieces = emptyArray()
        return EncodedPicture(id, width, height, joined)
    }

    @Synchronized
    fun reset() {
        building = false
        finished = null
        pieces = emptyArray()
        received = 0
    }

    private fun begin(id: Int, count: Int, width: Int, height: Int) {
        current = id
        building = true
        this.count = count
        this.width = width
        this.height = height
        pieces = arrayOfNulls(count)
        received = 0
    }

    /** Whether [a] is a later picture than [b], counting numbers that wrap around. */
    private fun newer(a: Int, b: Int): Boolean = (a - b) > 0

    companion object {
        const val MAX_PIECES = 800
        const val MAX_SIDE = 4096
    }
}

/**
 * Opens a picture: inflates it, undoes the "sub" filter (each byte was sent as its difference from the byte one pixel
 * to the left), and turns the red, green and blue bytes into pixels.
 *
 * A picture that does not hold together, is bigger than any the PC sends, or says one thing and is another is
 * refused (null), never half drawn.
 */
object ScreenDecoder {
    /** The most pixels one picture may have; the PC's pictures are at most 1280 wide. */
    const val MAX_PIXELS = 4_000_000

    fun decode(picture: EncodedPicture): ScreenPicture? {
        val width = picture.width
        val height = picture.height
        if (width < 1 || height < 1 || width.toLong() * height > MAX_PIXELS) return null
        val rowBytes = width * 3
        val expected = rowBytes * height
        val raw = inflate(picture.bytes, expected) ?: return null

        val pixels = IntArray(width * height)
        var out = 0
        for (row in 0 until height) {
            val start = row * rowBytes
            for (i in 3 until rowBytes) {
                raw[start + i] = (raw[start + i] + raw[start + i - 3]).toByte()
            }
            for (x in 0 until width) {
                val p = start + x * 3
                pixels[out++] = (0xFF shl 24) or
                    ((raw[p].toInt() and 0xFF) shl 16) or
                    ((raw[p + 1].toInt() and 0xFF) shl 8) or
                    (raw[p + 2].toInt() and 0xFF)
            }
        }
        return ScreenPicture(picture.id, width, height, pixels)
    }

    /** The bytes of a zlib stream, only if they are exactly [expected] of them and the stream ends there. */
    private fun inflate(bytes: ByteArray, expected: Int): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArray(expected)
            var filled = 0
            while (filled < expected) {
                val n = inflater.inflate(out, filled, expected - filled)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                filled += n
            }
            if (filled != expected) return null
            // It must end here: one more byte would mean the stream is longer than the picture says.
            val extra = ByteArray(1)
            if (inflater.inflate(extra) != 0 || !inflater.finished()) return null
            return out
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
