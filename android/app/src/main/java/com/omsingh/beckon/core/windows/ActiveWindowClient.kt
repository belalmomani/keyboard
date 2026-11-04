package com.omsingh.beckon.core.windows

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Asks the PC which program is in front, every couple of seconds, while something on screen wants to
 * know (the keyboard, in automatic typing mode). Nothing is asked otherwise, and what comes back is only
 * held in memory.
 */
class ActiveWindowClient(
    private val sendQuery: () -> Unit,
    private val pollIntervalMs: Long = 2_000L,
) {
    private val _window = MutableStateFlow<ActiveWindow?>(null)

    /** The program in front, or null when not known (not asked yet, or the PC could not say). */
    val window: StateFlow<ActiveWindow?> = _window.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    sendQuery()
                } catch (t: Throwable) {
                    Log.w(TAG, "query failed: ${t.javaClass.simpleName}")
                }
                delay(pollIntervalMs)
            }
        }
    }

    /** Stops asking, and forgets the answer: it is out of date as soon as nobody is watching. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _window.value = null
    }

    fun onUpdate(window: ActiveWindow?) {
        _window.value = window
    }

    private companion object {
        const val TAG = "ActiveWindowClient"
    }
}
