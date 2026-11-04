package com.omsingh.beckon.core.interfaces

import com.omsingh.beckon.core.storage.AtomicTextFile
import java.io.File

/**
 * Keeps how many times each letter was used on the fractal keyboard, on the phone only. One line per letter,
 * `code point=count`. Nothing about order or words is kept.
 */
class FractalUsageStore(file: File) {
    private val storage = AtomicTextFile(file)

    fun load(): MutableMap<Char, Int> {
        val counts = HashMap<Char, Int>()
        val text = storage.read() ?: return counts
        for (line in text.lineSequence()) {
            val parts = line.split('=')
            if (parts.size != 2) continue
            val code = parts[0].trim().toIntOrNull() ?: continue
            val count = parts[1].trim().toIntOrNull() ?: continue
            if (code !in 0..0xFFFF || count <= 0) continue
            counts[code.toChar()] = count.coerceAtMost(MAX_COUNT)
        }
        return counts
    }

    fun save(counts: Map<Char, Int>): Boolean =
        storage.write(counts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key.code}=${it.value}" })

    fun delete() {
        storage.write("")
    }

    private companion object {
        const val MAX_COUNT = 1_000_000
    }
}
