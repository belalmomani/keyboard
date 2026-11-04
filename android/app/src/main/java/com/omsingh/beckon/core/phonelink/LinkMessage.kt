package com.omsingh.beckon.core.phonelink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the two phones say to each other, once opened. */
sealed interface LinkMessage {
    /** Sender to receiver, first: who is asking. */
    data class Hello(val name: String) : LinkMessage

    /** Receiver to sender: the person on this phone allowed it. */
    data class Accepted(val name: String) : LinkMessage

    /** Receiver to sender: the person on this phone did not. */
    data object Rejected : LinkMessage

    /** A file is coming: [size] bytes, in pieces, then [FileEnd]. */
    data class FileStart(val id: Int, val size: Long, val name: String) : LinkMessage

    class FileData(val id: Int, val bytes: ByteArray) : LinkMessage

    data class FileEnd(val id: Int) : LinkMessage

    /** Receiver to sender: the file [id] is saved ([ok]) or could not be. */
    data class FileResult(val id: Int, val ok: Boolean) : LinkMessage

    /** One picture of the sender's screen, as a JPEG. */
    class Frame(val width: Int, val height: Int, val jpeg: ByteArray) : LinkMessage

    /** The sender stopped showing its screen. */
    data object ScreenEnded : LinkMessage

    /** Sent now and then so that a silent link is noticed. */
    data object Ping : LinkMessage

    data object Bye : LinkMessage

    companion object {
        private const val HELLO: Byte = 0x01
        private const val ACCEPTED: Byte = 0x02
        private const val REJECTED: Byte = 0x03
        private const val FILE_START: Byte = 0x10
        private const val FILE_DATA: Byte = 0x11
        private const val FILE_END: Byte = 0x12
        private const val FILE_RESULT: Byte = 0x13
        private const val FRAME: Byte = 0x20
        private const val SCREEN_ENDED: Byte = 0x21
        private const val PING: Byte = 0x30
        private const val BYE: Byte = 0x31

        const val MAX_NAME_BYTES = 200
        const val MAX_FILE_NAME_BYTES = 400

        fun encode(message: LinkMessage): ByteArray = when (message) {
            is Hello -> withText(HELLO, message.name, MAX_NAME_BYTES)
            is Accepted -> withText(ACCEPTED, message.name, MAX_NAME_BYTES)
            Rejected -> byteArrayOf(REJECTED)
            is FileStart -> {
                val name = clip(message.name, MAX_FILE_NAME_BYTES)
                buffer(1 + 4 + 8 + name.size).put(FILE_START).putInt(message.id).putLong(message.size).put(name).array()
            }
            is FileData -> buffer(1 + 4 + message.bytes.size).put(FILE_DATA).putInt(message.id).put(message.bytes).array()
            is FileEnd -> buffer(5).put(FILE_END).putInt(message.id).array()
            is FileResult -> buffer(6).put(FILE_RESULT).putInt(message.id).put(if (message.ok) 1 else 0).array()
            is Frame -> buffer(1 + 2 + 2 + message.jpeg.size)
                .put(FRAME).putShort(message.width.toShort()).putShort(message.height.toShort()).put(message.jpeg).array()
            ScreenEnded -> byteArrayOf(SCREEN_ENDED)
            Ping -> byteArrayOf(PING)
            Bye -> byteArrayOf(BYE)
        }

        /** Null for anything malformed or unknown: it is ignored, never trusted. */
        fun decode(bytes: ByteArray): LinkMessage? {
            if (bytes.isEmpty()) return null
            val body = ByteBuffer.wrap(bytes, 1, bytes.size - 1).order(ByteOrder.BIG_ENDIAN)
            return try {
                when (bytes[0]) {
                    HELLO -> Hello(text(body, MAX_NAME_BYTES) ?: return null)
                    ACCEPTED -> Accepted(text(body, MAX_NAME_BYTES) ?: return null)
                    REJECTED -> Rejected
                    FILE_START -> {
                        if (body.remaining() < 12) return null
                        val id = body.int
                        val size = body.long
                        if (size < 0) return null
                        FileStart(id, size, text(body, MAX_FILE_NAME_BYTES) ?: return null)
                    }
                    FILE_DATA -> {
                        if (body.remaining() < 4) return null
                        val id = body.int
                        FileData(id, ByteArray(body.remaining()).also { body.get(it) })
                    }
                    FILE_END -> if (body.remaining() == 4) FileEnd(body.int) else null
                    FILE_RESULT -> if (body.remaining() == 5) FileResult(body.int, body.get() != 0.toByte()) else null
                    FRAME -> {
                        if (body.remaining() < 4) return null
                        val width = body.short.toInt() and 0xFFFF
                        val height = body.short.toInt() and 0xFFFF
                        if (width == 0 || height == 0) return null
                        Frame(width, height, ByteArray(body.remaining()).also { body.get(it) })
                    }
                    SCREEN_ENDED -> ScreenEnded
                    PING -> Ping
                    BYE -> Bye
                    else -> null
                }
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

        private fun withText(tag: Byte, text: String, max: Int): ByteArray {
            val bytes = clip(text, max)
            return buffer(1 + bytes.size).put(tag).put(bytes).array()
        }

        /** The text as UTF-8, cut to [max] bytes on a character boundary. */
        private fun clip(text: String, max: Int): ByteArray {
            var bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= max) return bytes
            var end = text.length
            while (bytes.size > max && end > 0) {
                end--
                if (end > 0 && text[end - 1].isHighSurrogate()) end--
                bytes = text.substring(0, end).toByteArray(Charsets.UTF_8)
            }
            return bytes
        }

        private fun text(body: ByteBuffer, max: Int): String? {
            if (body.remaining() > max) return null
            val bytes = ByteArray(body.remaining()).also { body.get(it) }
            return String(bytes, Charsets.UTF_8)
        }
    }
}
