package com.omsingh.beckon.core.commands

import com.omsingh.beckon.core.storage.AtomicTextFile
import java.io.File

/** Keeps the quick-control setup on the phone, in the app's own private storage. */
class CommandBoardStore(file: File) {
    private val storage = AtomicTextFile(file)

    /** What was saved, or the defaults if nothing was (or the file is unreadable: it is then kept aside). */
    fun load(): Board {
        val text = storage.read() ?: return Board()
        return CommandBoardJson.read(text) ?: Board().also { storage.keepAside() }
    }

    fun save(board: Board): Boolean = storage.write(CommandBoardJson.write(board))
}
