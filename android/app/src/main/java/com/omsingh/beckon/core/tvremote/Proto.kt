package com.omsingh.beckon.core.tvremote

import java.io.ByteArrayOutputStream

/**
 * The little of Protocol Buffers the TV's remote protocol needs: whole numbers and byte strings under a field
 * number, nested one in another. Written by hand so the app needs no library for it.
 */
internal object Proto {

    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    /** A whole number (not negative) under [field]. */
    fun int(field: Int, value: Int): ByteArray = varint((field shl 3).toLong()) + varint(value.toLong())

    /** Bytes under [field]: also what a nested message and a string are. */
    fun bytes(field: Int, value: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

    fun string(field: Int, value: String): ByteArray = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** What was found under each field number, in order: numbers as [Long], everything else as [ByteArray]. */
    class Fields(private val found: Map<Int, List<Any>>) {
        fun long(field: Int): Long? = found[field]?.firstOrNull { it is Long } as Long?
        fun bytes(field: Int): ByteArray? = found[field]?.firstOrNull { it is ByteArray } as ByteArray?
        fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
        fun message(field: Int): Fields? = bytes(field)?.let { parse(it) }
        fun has(field: Int): Boolean = found.containsKey(field)
    }

    /** Reads one message. Anything it cannot make sense of ends the reading; what came before is kept. */
    fun parse(data: ByteArray): Fields {
        val found = LinkedHashMap<Int, MutableList<Any>>()
        var pos = 0
        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
        while (pos < data.size) {
            val tag = readVarint() ?: break
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> found.getOrPut(field) { mutableListOf() }.add(readVarint() ?: break)
                2 -> {
                    val length = readVarint()?.toInt() ?: break
                    if (length < 0 || pos + length > data.size) break
                    found.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + length))
                    pos += length
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> break
            }
        }
        return Fields(found)
    }
}
