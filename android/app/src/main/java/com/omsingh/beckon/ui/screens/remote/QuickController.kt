package com.omsingh.beckon.ui.screens.remote

import android.app.Application
import android.os.SystemClock
import com.omsingh.beckon.connection.ConnectionManager
import com.omsingh.beckon.connection.TextSendResult
import com.omsingh.beckon.core.commands.Command
import com.omsingh.beckon.core.commands.CommandBoard
import com.omsingh.beckon.core.commands.CommandBoardStore
import com.omsingh.beckon.core.commands.CommandPlan
import com.omsingh.beckon.core.commands.CommandPlanner
import com.omsingh.beckon.core.commands.LeaderSession
import com.omsingh.beckon.core.commands.RhythmMatcher
import com.omsingh.beckon.core.commands.RunOutcome
import com.omsingh.beckon.core.host.HostProfile
import com.omsingh.beckon.core.input.ConnectionState
import com.omsingh.beckon.core.windows.FocusResult
import com.omsingh.beckon.core.windows.RemoteWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/** What the quick-control tab says about the last thing it did. */
sealed interface QuickStatus {
    data object Idle : QuickStatus

    /** Sent to the PC by a route that gets no answer (Bluetooth, or a PC program that does not report), so this says only that it went. */
    data class Sent(val command: Command) : QuickStatus

    /** The PC ran the command and said how it went. */
    data class Ran(val command: Command, val outcome: RunOutcome) : QuickStatus

    data object NotConnected : QuickStatus
    data class Text(val command: Command, val result: TextSendResult) : QuickStatus
    data class Window(val window: RemoteWindow, val result: FocusResult) : QuickStatus
    data object WindowsUnavailable : QuickStatus
}

/**
 * Quick control: the command wheel, leader mode, rhythm taps and the window radar.
 *
 * Everything a command does goes through [run], which asks first for the ones that cannot be taken back
 * ([Command.dangerous]). Over Wi-Fi to a PC program that reports, the PC says how every command went: a key
 * combination, a media key, a window brought to the front, a text typed. Anywhere else (Bluetooth, an older PC
 * program) the status says "sent", not "done", because nothing came back.
 */
class QuickController(
    app: Application,
    private val scope: CoroutineScope,
    private val manager: ConnectionManager,
    private val host: () -> HostProfile,
) {
    private val store = CommandBoardStore(File(app.filesDir, "quick_control.json"))
    val board = CommandBoard(store.load())

    private val _status = MutableStateFlow<QuickStatus>(QuickStatus.Idle)
    val status: StateFlow<QuickStatus> = _status.asStateFlow()

    private val _confirming = MutableStateFlow<Command?>(null)

    /** A command waiting for the person to say yes. */
    val confirming: StateFlow<Command?> = _confirming.asStateFlow()

    val leader = LeaderSession(bindings = { board.board.value.leader }, now = SystemClock::elapsedRealtime)
    val rhythm = RhythmMatcher(bindings = { board.board.value.rhythm })

    val windows: StateFlow<List<RemoteWindow>?> = manager.openWindows

    /** Whether the PC can list and switch windows right now. */
    val canSwitchWindows: Boolean get() = manager.canSwitchWindows

    private var saving: Job? = null

    init {
        board.board.drop(1).onEach {
            saving?.cancel()
            saving = scope.launch {
                delay(SAVE_DELAY_MS)
                store.save(board.board.value)
            }
        }.launchIn(scope)
    }

    /** Runs the command named [id], if there is one. */
    fun runById(id: String) {
        board.command(id)?.let(::run)
    }

    fun run(command: Command) {
        if (manager.connectionState.value !is ConnectionState.Connected) {
            _status.value = QuickStatus.NotConnected
        } else if (command.dangerous) {
            _confirming.value = command
        } else {
            execute(command)
        }
    }

    fun confirm() {
        val command = _confirming.value ?: return
        _confirming.value = null
        execute(command)
    }

    fun cancelConfirm() {
        _confirming.value = null
    }

    private fun execute(command: Command) {
        when (val plan = CommandPlanner.plan(command, host())) {
            is CommandPlan.Events -> {
                val call = plan.call
                if (call != null && manager.canConfirmCommands) {
                    scope.launch { _status.value = QuickStatus.Ran(command, manager.runCommand(call)) }
                } else {
                    plan.events.forEach(manager::dispatch)
                    _status.value = QuickStatus.Sent(command)
                }
            }
            is CommandPlan.TypeText -> scope.launch {
                _status.value = QuickStatus.Text(command, manager.sendText(plan.text))
            }
        }
    }

    // ── Windows ──────────────────────────────────────────────────────

    fun refreshWindows() {
        if (canSwitchWindows) manager.refreshWindows() else _status.value = QuickStatus.WindowsUnavailable
    }

    fun focus(window: RemoteWindow) {
        scope.launch {
            val result = manager.focusWindow(window.handle)
            _status.value = QuickStatus.Window(window, result)
            // The list shows which window is in front, so it is asked for again.
            manager.refreshWindows()
        }
    }

    fun clearStatus() {
        _status.value = QuickStatus.Idle
    }

    fun close() {
        saving?.cancel()
        store.save(board.board.value)
    }

    private companion object {
        const val SAVE_DELAY_MS = 600L
    }
}
