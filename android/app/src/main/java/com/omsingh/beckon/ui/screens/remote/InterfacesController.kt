package com.omsingh.beckon.ui.screens.remote

import android.app.Application
import com.omsingh.beckon.connection.ConnectionManager
import com.omsingh.beckon.core.interfaces.FractalKeyboard
import com.omsingh.beckon.core.interfaces.FractalLayouts
import com.omsingh.beckon.core.interfaces.FractalUsageStore
import com.omsingh.beckon.core.windows.ActiveWindow
import com.omsingh.beckon.core.windows.RemoteWindow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What the cockpit, the cube and the fractal keyboard need besides what the remote screens already have. */
class InterfacesController(
    app: Application,
    private val manager: ConnectionManager,
) {
    private val store = FractalUsageStore(File(app.filesDir, "fractal_usage.txt"))
    private val counts: MutableMap<Char, Int> = store.load()

    /** Latin and Arabic letters share one table of counts, since no letter is in both. */
    val latin = FractalKeyboard(FractalLayouts.LATIN, counts)
    val arabic = FractalKeyboard(FractalLayouts.ARABIC, counts)

    private var unsaved = 0

    /** A letter was typed on the fractal keyboard. */
    fun recordLetter(keyboard: FractalKeyboard, char: Char) {
        keyboard.record(char)
        if (++unsaved >= SAVE_EVERY) save()
    }

    fun save() {
        if (unsaved == 0) return
        unsaved = 0
        store.save(counts)
    }

    /** Forgets what was learned. */
    fun forgetUsage() {
        counts.clear()
        unsaved = 0
        store.delete()
    }

    // ── The PC, for the cockpit ──────────────────────────────────────

    val activeWindow: StateFlow<ActiveWindow?> = manager.activeWindow
    val windows: StateFlow<List<RemoteWindow>?> = manager.openWindows
    val canSwitchWindows: Boolean get() = manager.canSwitchWindows
    val canSeeFrontWindow: Boolean
        get() = manager.hostInfo.value.capabilities.activeWindow

    /** The cockpit asks for the PC's front program while it is on screen. */
    fun watchFrontWindow(on: Boolean) = manager.requestActiveWindow("cockpit", on)

    fun refreshWindows() = manager.refreshWindows()

    fun close() {
        watchFrontWindow(false)
        save()
    }

    private companion object {
        const val SAVE_EVERY = 10
    }
}
