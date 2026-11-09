package com.omsingh.beckon.ui.screens.tvremote

import android.app.Application
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omsingh.beckon.R
import com.omsingh.beckon.core.tvremote.FoundTv
import com.omsingh.beckon.core.tvremote.TvDiscovery
import com.omsingh.beckon.core.tvremote.TvIdentity
import com.omsingh.beckon.core.tvremote.TvKnown
import com.omsingh.beckon.core.tvremote.TvProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class TvPhase { CHOOSE, WORKING, CODE, CONTROL }

data class TvRemoteState(
    val phase: TvPhase = TvPhase.CHOOSE,
    val found: List<FoundTv> = emptyList(),
    val known: List<FoundTv> = emptyList(),
    val tv: FoundTv? = null,
    @StringRes val message: Int? = null,
)

/** Controlling an Android TV or Google TV over Wi-Fi: finding it, pairing with a code, then the remote. */
class TvRemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val discovery = TvDiscovery(app)
    private val known = TvKnown(app)
    private val directory = File(app.filesDir, "tvremote")
    private val identity by lazy { TvIdentity.loadOrCreate(directory) }

    private val _state = MutableStateFlow(TvRemoteState(known = known.list()))
    val state: StateFlow<TvRemoteState> = _state.asStateFlow()

    private var pairing: TvProtocol.Pairing? = null
    private var remote: TvProtocol.Remote? = null

    init {
        discovery.start()
        viewModelScope.launch {
            discovery.found.collect { list -> _state.update { it.copy(found = list) } }
        }
    }

    /** A TV was chosen (from the lists, or typed as an address): connect if it is paired already, else pair. */
    fun pick(tv: FoundTv) {
        if (_state.value.phase == TvPhase.WORKING) return
        if (known.isPaired(tv.host)) connect(tv) else pair(tv)
    }

    private fun connect(tv: FoundTv) {
        _state.update { it.copy(phase = TvPhase.WORKING, tv = tv, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var holder: TvProtocol.Remote? = null
                val made = TvProtocol.connect(identity, tv.host) { holder?.let { lost(it) } }
                holder = made
                remote = made
                delay(1_500)
                if (made.heard) {
                    _state.update { it.copy(phase = TvPhase.CONTROL) }
                } else {
                    // A TV that does not know this phone says nothing and hangs up: it has to be paired again.
                    made.close()
                    remote = null
                    known.forget(tv.host)
                    pair(tv)
                }
            } catch (_: Exception) {
                _state.update { it.copy(phase = TvPhase.CHOOSE, message = R.string.tv_remote_cannot_reach, known = known.list()) }
            }
        }
    }

    private fun lost(which: TvProtocol.Remote) {
        if (remote !== which) return
        remote = null
        _state.update {
            if (it.phase == TvPhase.CONTROL) it.copy(phase = TvPhase.CHOOSE, message = R.string.tv_remote_lost) else it
        }
    }

    private fun pair(tv: FoundTv) {
        _state.update { it.copy(phase = TvPhase.WORKING, tv = tv, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pairing?.close()
                pairing = TvProtocol.beginPairing(identity, tv.host, "Beckon " + (Build.MODEL ?: "Android"))
                _state.update { it.copy(phase = TvPhase.CODE, message = null) }
            } catch (_: Exception) {
                _state.update { it.copy(phase = TvPhase.CHOOSE, message = R.string.tv_remote_cannot_reach) }
            }
        }
    }

    /** The code the person read off the TV. */
    fun submitCode(code: String) {
        val tv = _state.value.tv ?: return
        val current = pairing ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (current.finish(code)) {
                TvProtocol.Pairing.Result.PAIRED -> {
                    pairing = null
                    known.remember(tv)
                    _state.update { it.copy(known = known.list()) }
                    connect(tv)
                }
                TvProtocol.Pairing.Result.WRONG_CODE -> _state.update { it.copy(message = R.string.tv_remote_wrong_code) }
                TvProtocol.Pairing.Result.FAILED -> {
                    pairing = null
                    _state.update { it.copy(phase = TvPhase.CHOOSE, message = R.string.tv_remote_pairing_failed) }
                }
            }
        }
    }

    fun press(key: Int) {
        val current = remote ?: return
        viewModelScope.launch(Dispatchers.IO) { current.press(key) }
    }

    fun type(text: String) {
        val current = remote ?: return
        viewModelScope.launch(Dispatchers.IO) { current.type(text) }
    }

    fun forget(tv: FoundTv) {
        known.forget(tv.host)
        _state.update { it.copy(known = known.list()) }
    }

    /** Back to the list of TVs. */
    fun leave() {
        val oldPairing = pairing
        val old = remote
        pairing = null
        remote = null
        closeAway(oldPairing, old)
        _state.update { it.copy(phase = TvPhase.CHOOSE, tv = null, message = null, known = known.list()) }
    }

    override fun onCleared() {
        discovery.stop()
        closeAway(pairing, remote)
    }

    // Closing a secure socket can touch the network, which the main thread may not.
    private fun closeAway(vararg things: AutoCloseable?) {
        Thread {
            things.forEach { try { it?.close() } catch (_: Exception) {} }
        }.start()
    }
}
