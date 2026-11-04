package com.omsingh.beckon.phonelink

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.beckon.core.phonelink.ClientState
import com.omsingh.beckon.core.phonelink.LinkInvite
import com.omsingh.beckon.core.phonelink.LocalAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** Which side of the link this phone is playing, as far as the screen is concerned. */
enum class LinkMode { MENU, RECEIVE, SEND }

/** Files being sent: which one, how far, and how many were kept by the other phone. */
data class SendProgress(
    val current: String = "",
    val fraction: Float = 0f,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
)

class PhoneLinkViewModel(app: Application) : AndroidViewModel(app) {
    val hub = PhoneLinkHub.getInstance(app)

    private val _mode = MutableStateFlow(LinkMode.MENU)
    val mode: StateFlow<LinkMode> = _mode.asStateFlow()

    private val _invite = MutableStateFlow<LinkInvite?>(null)

    /** The code being shown, while this phone receives. */
    val invite: StateFlow<LinkInvite?> = _invite.asStateFlow()

    private val _problem = MutableStateFlow<Problem?>(null)
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    private val _progress = MutableStateFlow<SendProgress?>(null)
    val progress: StateFlow<SendProgress?> = _progress.asStateFlow()

    enum class Problem { NO_NETWORK, CANNOT_LISTEN }

    /** This phone will receive: show a code for the other phone to scan. */
    fun receive() {
        _problem.value = null
        val addresses = LocalAddresses.ipv4()
        if (addresses.isEmpty()) {
            _problem.value = Problem.NO_NETWORK
            return
        }
        try {
            _invite.value = hub.host.start(addresses, hub.deviceName)
            _mode.value = LinkMode.RECEIVE
        } catch (_: IOException) {
            _problem.value = Problem.CANNOT_LISTEN
        }
    }

    /** A fresh code, e.g. after the last one was used or expired. */
    fun newCode() = receive()

    /** This phone will send: the camera is opened by the screen. */
    fun send() {
        _problem.value = null
        _mode.value = LinkMode.SEND
    }

    /** The code was scanned. */
    fun connect(invite: LinkInvite) {
        _mode.value = LinkMode.SEND
        viewModelScope.launch { hub.client.connect(invite, hub.deviceName) }
    }

    fun answer(allow: Boolean) {
        if (allow) hub.host.accept() else hub.host.reject()
    }

    /** Sends what the person chose in the system's file picker, one after another. */
    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            var failed = 0
            for ((index, uri) in uris.withIndex()) {
                var name = "file"
                var size = -1L
                try {
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0) cursor.getString(n)?.let { name = it }
                            if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                        }
                    }
                } catch (_: Exception) {
                }
                if (size < 0) {
                    failed++
                    _progress.value = SendProgress(name, 0f, done, uris.size, failed)
                    continue
                }
                _progress.value = SendProgress(name, 0f, done, uris.size, failed)
                val ok = hub.client.sendFile(
                    name = name,
                    size = size,
                    open = { resolver.openInputStream(uri) ?: throw IOException("cannot open") },
                    onProgress = { sent ->
                        _progress.value = SendProgress(name, if (size == 0L) 1f else sent.toFloat() / size, done, uris.size, failed)
                    },
                )
                if (ok) done++ else failed++
                _progress.value = SendProgress(name, 1f, done, uris.size, failed, finished = index == uris.lastIndex)
            }
            _progress.value = SendProgress("", 1f, done, uris.size, failed, finished = true)
        }
    }

    fun clearProgress() {
        _progress.value = null
    }

    /** Back to the start: ends whatever link there is. */
    fun leave() {
        ScreenShareService.stop(getApplication())
        hub.host.stop()
        hub.client.close()
        _invite.value = null
        _progress.value = null
        _problem.value = null
        _mode.value = LinkMode.MENU
    }

    val clientState: StateFlow<ClientState> get() = hub.client.state
}
