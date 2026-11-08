package com.omsingh.beckon.phonelink

import android.graphics.Bitmap
import com.omsingh.beckon.connection.ConnectionManager
import com.omsingh.beckon.core.input.ConnectionState
import com.omsingh.beckon.core.phonelink.ClientState
import com.omsingh.beckon.core.phonelink.LinkClient
import com.omsingh.beckon.core.screen.ScreenViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** How showing the PC's screen on the linked device (a TV, say) stands. */
sealed interface RelayState {
    data object Off : RelayState
    data object Waiting : RelayState
    data object Sending : RelayState
    data class Problem(val reason: ScreenViewState.Reason) : RelayState
    data object NoPc : RelayState
}

/**
 * Passes the pictures of the PC's screen (which this phone already receives, view only) on to the device it is
 * linked with. Nothing is stored; each picture is sent once and dropped. It stops when the link or the PC goes.
 */
class PcScreenRelay(
    private val manager: ConnectionManager,
    private val client: LinkClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RelayState>(RelayState.Off)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var guard: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (manager.connectionState.value !is ConnectionState.Connected) {
            _state.value = RelayState.NoPc
            return
        }
        if (client.state.value !is ClientState.Connected) return
        _state.value = RelayState.Waiting
        manager.pinScreenView(true)
        manager.startScreenView()
        // A TV is read from further away: the PC is asked for its sharper pictures (1280 wide), sent on as they are.
        manager.setScreenWide("relay", true)
        var lastId = Int.MIN_VALUE
        job = scope.launch(start = CoroutineStart.LAZY) {
            manager.screenState.collect { screen ->
                when (screen) {
                    ScreenViewState.Off, ScreenViewState.Waiting -> _state.value = RelayState.Waiting
                    is ScreenViewState.Unavailable -> _state.value = RelayState.Problem(screen.reason)
                    is ScreenViewState.Showing -> {
                        val picture = screen.picture
                        if (picture.id != lastId) {
                            lastId = picture.id
                            val jpeg = withContext(Dispatchers.Default) { encode(picture.pixels, picture.width, picture.height) }
                            val sent = withContext(Dispatchers.IO) { client.sendFrame(picture.width, picture.height, jpeg) }
                            if (sent) _state.value = RelayState.Sending
                        }
                    }
                }
            }
        }
        guard = scope.launch(start = CoroutineStart.LAZY) {
            launch { client.state.collect { if (it !is ClientState.Connected) stop() } }
            launch { manager.connectionState.collect { if (it !is ConnectionState.Connected) stop() } }
        }
        // Both are assigned before either runs, so a stop() from the guard always finds them.
        job?.start()
        guard?.start()
    }

    @Synchronized
    fun stop() {
        val wasRunning = job != null
        job?.cancel()
        guard?.cancel()
        job = null
        guard = null
        if (wasRunning) {
            manager.pinScreenView(false)
            manager.stopScreenView()
            scope.launch(Dispatchers.IO) { client.screenEnded() }
        }
        if (_state.value != RelayState.NoPc || wasRunning) _state.value = RelayState.Off
    }

    private fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
