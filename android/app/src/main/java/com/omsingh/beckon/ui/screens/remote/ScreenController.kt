package com.omsingh.beckon.ui.screens.remote

import com.omsingh.beckon.connection.ConnectionManager
import com.omsingh.beckon.core.screen.ScreenViewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the Screen tab needs: the state of the view, and starting and stopping it. */
class ScreenController(private val manager: ConnectionManager) {
    val state: StateFlow<ScreenViewState> = manager.screenState

    // Kept here, not in the tab: turning the phone rebuilds the tab, and full screen must survive that.
    val fullscreen = MutableStateFlow(false)
    val landscape = MutableStateFlow(false)
    val control = MutableStateFlow(false)

    /** Asks the PC for pictures of its screen. */
    fun start() = manager.startScreenView()

    /** Sharper pictures while they are shown full screen; the usual ones when it is left. */
    fun setWide(wide: Boolean) = manager.setScreenWide("fullscreen", wide)

    /** Stops them, and tells the PC. */
    fun stop() = manager.stopScreenView()
}
