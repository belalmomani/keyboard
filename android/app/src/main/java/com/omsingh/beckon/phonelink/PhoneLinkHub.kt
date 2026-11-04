package com.omsingh.beckon.phonelink

import android.app.Application
import android.os.Build
import com.omsingh.beckon.connection.ConnectionManager
import com.omsingh.beckon.core.phonelink.LinkClient
import com.omsingh.beckon.core.phonelink.LinkHost
import com.omsingh.beckon.core.phonelink.ReceivedFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.omsingh.beckon.core.phonelink.ClientState
import com.omsingh.beckon.core.phonelink.HostState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The one place the two ends of a phone-to-phone link live, so that they outlast a screen: a phone that is
 * showing its own screen keeps doing so while the person looks at something else in the app.
 */
class PhoneLinkHub private constructor(private val app: Application) {

    val receivedDirectory: File = (app.getExternalFilesDir("Received") ?: File(app.filesDir, "Received"))

    /** This phone as the one that receives (shows the code). */
    val host = LinkHost(ReceivedFiles(receivedDirectory))

    /** This phone as the one that sends. */
    val client = LinkClient()

    /** This phone passing the PC's screen on to the linked device. */
    val pcRelay: PcScreenRelay by lazy {
        PcScreenRelay(ConnectionManager.getInstance(app), client, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // A link that is open, or waiting for a phone, keeps the app awake in the background (see the service).
        scope.launch {
            combine(client.state, host.state) { c, h ->
                c is ClientState.Connected || c is ClientState.Connecting || c is ClientState.WaitingForApproval ||
                    h is HostState.Waiting || h is HostState.Asking || h is HostState.Connected
            }.collect { active ->
                if (active) LinkKeepAliveService.start(app) else LinkKeepAliveService.stop(app)
            }
        }
    }

    private val _sharingScreen = MutableStateFlow(false)

    /** Whether this phone is showing its screen to the other one right now. */
    val sharingScreen: StateFlow<Boolean> = _sharingScreen.asStateFlow()

    fun setSharingScreen(sharing: Boolean) {
        _sharingScreen.value = sharing
    }

    /** What the other phone is shown as: this phone's model. */
    val deviceName: String
        get() = listOfNotNull(Build.MANUFACTURER?.takeIf { it.isNotBlank() }, Build.MODEL?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .ifBlank { "Android" }
            .take(40)

    companion object {
        @Volatile private var instance: PhoneLinkHub? = null

        fun getInstance(app: Application): PhoneLinkHub =
            instance ?: synchronized(this) { instance ?: PhoneLinkHub(app).also { instance = it } }
    }
}
