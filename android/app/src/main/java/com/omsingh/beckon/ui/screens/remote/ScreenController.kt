package com.omsingh.beckon.ui.screens.remote

import com.omsingh.beckon.connection.ConnectionManager
import com.omsingh.beckon.core.screen.ScreenViewState
import kotlinx.coroutines.flow.StateFlow

/** What the Screen tab needs: the state of the view, and starting and stopping it. */
class ScreenController(private val manager: ConnectionManager) {
    val state: StateFlow<ScreenViewState> = manager.screenState

    /** Asks the PC for pictures of its screen. */
    fun start() = manager.startScreenView()

    /** Stops them, and tells the PC. */
    fun stop() = manager.stopScreenView()
}
