package com.omsingh.beckon.core.storage

import java.io.File
import java.io.IOException

/**
 * A small text file that is replaced as a whole or not at all.
 *
 * [write] puts the new text in a neighbouring file and renames it over the old one, so a crash or a
 * full disk in the middle leaves the previous version in place. A file that cannot be understood is
 * moved aside by [keepAside] (as `name.bad`) rather than being overwritten, so that nothing a person
 * saved is destroyed by a bug.
 */
class AtomicTextFile(private val file: File) {

    /** The file's text, or null if there is no file or it cannot be read. */
    fun read(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    }

    /** Returns false (and leaves the old file alone) if the text could not be written. */
    fun write(text: String): Boolean {
        val temporary = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            temporary.writeText(text, Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                // Some file systems will not rename over an existing file.
                file.delete()
                if (!temporary.renameTo(file)) throw IOException("rename failed")
            }
            true
        } catch (_: IOException) {
            temporary.delete()
            false
        }
    }

    /** Moves the file out of the way, for a file that is not what it should be. */
    fun keepAside() {
        val aside = File(file.parentFile, file.name + ".bad")
        aside.delete()
        file.renameTo(aside)
    }
}
