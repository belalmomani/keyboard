package com.omsingh.beckon.ui.screens.remote

import android.app.Application
import com.omsingh.beckon.connection.ConnectionManager
import com.omsingh.beckon.connection.TextSendResult
import com.omsingh.beckon.core.clipboard.ClipEntry
import com.omsingh.beckon.core.clipboard.ClipSource
import com.omsingh.beckon.core.clipboard.ClipboardHistory
import com.omsingh.beckon.core.clipboard.ClipboardHistoryStore
import com.omsingh.beckon.core.clipboard.Recorded
import com.omsingh.beckon.core.library.LibraryError
import com.omsingh.beckon.core.library.LibraryData
import com.omsingh.beckon.core.library.PhraseLibrary
import com.omsingh.beckon.core.library.PhraseStore
import com.omsingh.beckon.core.text.TextBlockResult
import com.omsingh.beckon.core.text.TextBlockStage
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

/** What the Library tab shows about a text being sent to the PC. */
sealed interface SendUi {
    data object Idle : SendUi

    /** Waiting for the person to confirm: the text has line breaks, each of which presses Enter on the PC. */
    data class Confirm(val text: String, val phraseId: String?) : SendUi
    data class Sending(val stage: TextBlockStage) : SendUi
    data class Finished(val result: TextSendResult) : SendUi
}

/**
 * The saved phrases, the clipboard history and sending text to the PC, for the Library tab.
 *
 * Both are kept in the app's private storage only. The clipboard history is off until the person turns it on,
 * and turning it off deletes it. Nothing here is sent anywhere except text the person chose to send.
 */
class LibraryController(
    app: Application,
    private val scope: CoroutineScope,
    private val manager: ConnectionManager,
    private val historyEnabled: () -> Boolean,
    private val setHistoryEnabled: (Boolean) -> Unit,
) {
    private val phraseStore = PhraseStore(File(app.filesDir, "phrases.json"))
    private val historyStore = ClipboardHistoryStore(File(app.filesDir, "clipboard_history.json"))

    val library = PhraseLibrary(phraseStore.load())
    val history = ClipboardHistory(if (historyEnabled()) historyStore.load() else emptyList())

    private val _send = MutableStateFlow<SendUi>(SendUi.Idle)
    val send: StateFlow<SendUi> = _send.asStateFlow()

    private val _historyOn = MutableStateFlow(historyEnabled())
    val historyOn: StateFlow<Boolean> = _historyOn.asStateFlow()

    private var sendJob: Job? = null
    private var listener: AutoCloseable? = null

    init {
        library.data.drop(1).onEach { saveLater(SAVE_PHRASES) }.launchIn(scope)
        history.entries.drop(1).onEach { if (_historyOn.value) saveLater(SAVE_HISTORY) }.launchIn(scope)
        // Text brought over from the PC joins the history, when the history is on.
        manager.pcClipboard.onEach { text ->
            if (text != null && _historyOn.value) history.record(text, ClipSource.PC, System.currentTimeMillis())
        }.launchIn(scope)
    }

    // ── Phrases ──────────────────────────────────────────────────────

    fun addPhrase(title: String, text: String, folderId: String?): LibraryError? = library.add(title, text, folderId)
    fun updatePhrase(id: String, title: String, text: String, folderId: String?): LibraryError? =
        library.update(id, title, text, folderId)

    // ── Clipboard history ────────────────────────────────────────────

    fun setHistory(on: Boolean) {
        _historyOn.value = on
        setHistoryEnabled(on)
        if (on) {
            startListening()
            captureNow()
        } else {
            stopListening()
            history.clear(includePinned = true)
            historyStore.delete()
        }
    }

    /** Starts noting what is copied on the phone, while the app is in front. */
    fun startListening() {
        if (!_historyOn.value || listener != null) return
        listener = manager.clipboard.addChangeListener { captureNow() }
    }

    fun stopListening() {
        listener?.close()
        listener = null
    }

    /** Offers what is on the phone's clipboard now to the history. */
    fun captureNow(): Recorded? {
        if (!_historyOn.value) return null
        val clip = manager.clipboard.peekPhoneClip() ?: return null
        return history.record(clip.text, ClipSource.PHONE, System.currentTimeMillis(), clip.markedSensitive)
    }

    fun copyToPhone(text: String) = manager.clipboard.setPhoneText(text)

    fun searchHistory(query: String): List<ClipEntry> = history.search(query)

    // ── Sending ──────────────────────────────────────────────────────

    /** Asks to send [text]. A text with line breaks waits for confirmation first. */
    fun requestSend(text: String, phraseId: String? = null) {
        if (sendJob?.isActive == true) return
        if (text.any { it == '\n' || it == '\r' }) {
            _send.value = SendUi.Confirm(text, phraseId)
        } else {
            startSend(text, phraseId)
        }
    }

    fun confirmSend() {
        val pending = _send.value as? SendUi.Confirm ?: return
        startSend(pending.text, pending.phraseId)
    }

    /** Dismisses the confirmation, or stops a text being sent (the PC stops typing it). */
    fun cancelSend() {
        when (_send.value) {
            is SendUi.Confirm, is SendUi.Finished -> _send.value = SendUi.Idle
            is SendUi.Sending -> manager.cancelText()
            SendUi.Idle -> Unit
        }
    }

    private fun startSend(text: String, phraseId: String?) {
        _send.value = SendUi.Sending(TextBlockStage.SENDING)
        sendJob = scope.launch {
            val result = manager.sendText(text) { stage -> _send.value = SendUi.Sending(stage) }
            val done = (result as? TextSendResult.Confirmed)?.result?.outcome == TextBlockResult.Outcome.DONE
            if ((done || result == TextSendResult.Unconfirmed) && phraseId != null) library.markUsed(phraseId)
            _send.value = SendUi.Finished(result)
        }
    }

    // ── Saving ───────────────────────────────────────────────────────

    private var saving: Job? = null
    private var pending = 0

    private fun saveLater(what: Int) {
        pending = pending or what
        saving?.cancel()
        saving = scope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    /** Writes anything not written yet. Called when the screen goes away. */
    fun saveNow() {
        val what = pending
        pending = 0
        if (what and SAVE_PHRASES != 0) phraseStore.save(library.data.value)
        if (what and SAVE_HISTORY != 0 && _historyOn.value) historyStore.save(history.entries.value)
    }

    fun close() {
        stopListening()
        saveNow()
    }

    val phrases: StateFlow<LibraryData> get() = library.data

    private companion object {
        const val SAVE_PHRASES = 1
        const val SAVE_HISTORY = 2
        const val SAVE_DELAY_MS = 600L
    }
}
