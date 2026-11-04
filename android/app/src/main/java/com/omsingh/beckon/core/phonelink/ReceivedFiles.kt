package com.omsingh.beckon.core.phonelink

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A file another phone sent, now kept on this phone. */
data class ReceivedFile(val name: String, val path: String, val size: Long, val isImage: Boolean, val receivedAt: Long)

/**
 * Where files from another phone are kept, and how their names are made safe.
 *
 * The name comes from the other phone, so it is never trusted: only its last part is used, with characters
 * a file system dislikes removed, so that a name like `../../x` can only ever land in [directory].
 */
class ReceivedFiles(private val directory: File) {

    /** A file being written. Becomes a real file only when [finish] is called with every byte there. */
    inner class Incoming internal constructor(
        val id: Int,
        val declaredSize: Long,
        private val displayName: String,
        private val partial: File,
    ) {
        private val out = FileOutputStream(partial)
        var written = 0L
            private set

        fun write(bytes: ByteArray) {
            if (written + bytes.size > declaredSize) throw IOException("more data than declared")
            out.write(bytes)
            written += bytes.size
        }

        /** Keeps the file, if it is complete. Returns it, or null (and removes what was written). */
        fun finish(nowMs: Long): ReceivedFile? {
            out.close()
            if (written != declaredSize) {
                partial.delete()
                return null
            }
            val target = uniqueFile(displayName)
            if (!partial.renameTo(target)) {
                partial.delete()
                return null
            }
            return ReceivedFile(target.name, target.absolutePath, written, isImageName(target.name), nowMs)
        }

        fun abandon() {
            try { out.close() } catch (_: IOException) {}
            partial.delete()
        }
    }

    fun begin(id: Int, size: Long, name: String): Incoming {
        if (size < 0 || size > MAX_FILE_BYTES) throw IOException("file too large")
        directory.mkdirs()
        if (!directory.isDirectory) throw IOException("no place to keep files")
        return Incoming(id, size, safeName(name), File(directory, ".incoming-$id-${System.nanoTime()}.part"))
    }

    /** The files already kept, newest first. */
    fun list(): List<ReceivedFile> =
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
            .map { ReceivedFile(it.name, it.absolutePath, it.length(), isImageName(it.name), it.lastModified()) }

    private fun uniqueFile(name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var n = 2
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($n)$extension")
            n++
        }
        return candidate
    }

    companion object {
        const val MAX_FILE_BYTES = 1L shl 30 // 1 GiB

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

        fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        fun safeName(raw: String): String {
            val last = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = last
                .filter { it.code >= 32 && it !in "<>:\"|?*" && it != '‮' }
                .trim()
                .trimStart('.')
                .trim()
            val short = if (cleaned.length > 100) {
                val dot = cleaned.lastIndexOf('.')
                if (dot > cleaned.length - 12 && dot > 0) cleaned.take(100 - (cleaned.length - dot)) + cleaned.substring(dot) else cleaned.take(100)
            } else {
                cleaned
            }
            return short.ifEmpty { "file" }
        }
    }
}
