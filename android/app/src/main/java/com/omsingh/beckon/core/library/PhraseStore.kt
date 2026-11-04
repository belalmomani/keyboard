package com.omsingh.beckon.core.library

import com.omsingh.beckon.core.storage.AtomicTextFile
import java.io.File

/** Keeps the library on the phone, in the app's own private storage. */
class PhraseStore(file: File) {
    private val storage = AtomicTextFile(file)

    /** What was saved, an empty library if nothing was, or an empty one (with the old file kept aside) if it is unreadable. */
    fun load(): LibraryData {
        val text = storage.read() ?: return LibraryData()
        return LibraryJson.read(text) ?: run {
            storage.keepAside()
            LibraryData()
        }
    }

    fun save(data: LibraryData): Boolean = storage.write(LibraryJson.write(data))
}
