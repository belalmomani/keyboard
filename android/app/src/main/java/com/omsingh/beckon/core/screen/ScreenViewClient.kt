package com.omsingh.beckon.core.screen

import android.util.Log
import com.omsingh.beckon.core.input.InputEvent
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
import java.util.concurrent.atomic.AtomicBoolean

/** What the screen view shows right now. */
sealed interface ScreenViewState {
    /** Nothing is asked for. */
    data object Off : ScreenViewState

    /** Pictures were asked for and none has come yet. */
    data object Waiting : ScreenViewState

    /** The latest picture of the PC's screen. */
    class Showing(val picture: ScreenPicture) : ScreenViewState

    /** Nothing will come, and why. */
    data class Unavailable(val reason: Reason) : ScreenViewState

    enum class Reason {
        /** The owner of the PC has not allowed it: it is asked on the PC (`screen on`). */
        NotAllowed,

        /** This kind of PC cannot show its screen to Beckon yet. */
        Unsupported,

        /** The PC could not copy its screen (it is locked, say). */
        Failed,

        /** The PC does not answer. */
        NoAnswer,
    }
}

/**
 * Asks the PC for pictures of its screen and shows the latest.
 *
 * It sends the request at once and again every few seconds while looking: that repeats a request a lost datagram
 * did not bring, and tells the PC the phone is still there (a PC that hears nothing for a while stops sending by
 * itself, so a phone that vanishes costs nothing for long). Nothing is stored; the latest picture is only held in
 * memory, and only while looking.
 */
class ScreenViewClient(
    private val dispatch: (InputEvent) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxWidth: Int = 640,
    private val fps: Int = 3,
    /** What is asked for while the picture is shown big (full screen, or passed on to a TV): sharper, a little slower. */
    private val wideMaxWidth: Int = 1280,
    private val wideFps: Int = 4,
    private val firstAnswerWaitMs: Long = 1_000,
    private val askAgainMs: Long = 4_000,
    private val giveUpAfterMs: Long = 9_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val decode: (EncodedPicture) -> ScreenPicture? = ScreenDecoder::decode,
) {
    private val _state = MutableStateFlow<ScreenViewState>(ScreenViewState.Off)

    /** What to show. */
    val state: StateFlow<ScreenViewState> = _state.asStateFlow()

    private val assembler = PictureAssembler()
    private val decoding = AtomicBoolean(false)

    @Volatile
    private var lastHeard = 0L
    @Volatile
    private var job: Job? = null

    @Volatile
    private var wide = false

    /**
     * Asks for sharper pictures (or the usual ones again). The PC changes over at once; while no view is running
     * this only remembers the choice for the next one.
     */
    fun setWide(on: Boolean) {
        if (wide == on) return
        wide = on
        if (job?.isActive == true) {
            scope.launch { sendStart() }
        }
    }

    private fun sendStart() {
        try {
            dispatch(
                InputEvent.ScreenView(
                    start = true,
                    maxWidth = if (wide) wideMaxWidth else maxWidth,
                    fps = if (wide) wideFps else fps,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not ask for the screen: ${t.javaClass.simpleName}")
        }
    }

    /** Starts asking for pictures. Does nothing while it is already doing so. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        lastHeard = clock()
        assembler.reset()
        _state.value = ScreenViewState.Waiting
        job = scope.launch {
            while (isActive) {
                sendStart()
                delay(if (_state.value is ScreenViewState.Showing) askAgainMs else firstAnswerWaitMs)
                if (clock() - lastHeard > giveUpAfterMs) {
                    _state.value = ScreenViewState.Unavailable(ScreenViewState.Reason.NoAnswer)
                    break
                }
            }
        }
    }

    /** Stops asking, tells the PC, and forgets the last picture. */
    @Synchronized
    fun stop() {
        val running = job?.isActive == true
        job?.cancel()
        job = null
        if (running || _state.value is ScreenViewState.Showing || _state.value is ScreenViewState.Waiting) {
            try {
                dispatch(InputEvent.ScreenView(start = false, maxWidth = 0, fps = 0))
            } catch (t: Throwable) {
                Log.w(TAG, "could not stop the screen: ${t.javaClass.simpleName}")
            }
        }
        assembler.reset()
        wide = false
        _state.value = ScreenViewState.Off
    }

    /** The connection is gone: forget everything, and tell nobody. */
    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
        assembler.reset()
        wide = false
        _state.value = ScreenViewState.Off
    }

    /** The PC said how the view stands. */
    fun onStatus(state: ScreenState) {
        lastHeard = clock()
        when (state) {
            ScreenState.STARTED -> Unit
            ScreenState.STOPPED -> if (job?.isActive != true) _state.value = ScreenViewState.Off
            ScreenState.DISABLED -> giveUp(ScreenViewState.Reason.NotAllowed)
            ScreenState.UNSUPPORTED -> giveUp(ScreenViewState.Reason.Unsupported)
            ScreenState.FAILED -> giveUp(ScreenViewState.Reason.Failed)
        }
    }

    /** A piece of a picture arrived. */
    fun onPiece(id: Int, index: Int, count: Int, width: Int, height: Int, data: ByteArray) {
        if (job?.isActive != true) return
        lastHeard = clock()
        val picture = assembler.accept(id, index, count, width, height, data) ?: return
        // One picture is opened at a time; one that arrives meanwhile is dropped, since a newer one follows.
        if (!decoding.compareAndSet(false, true)) return
        scope.launch {
            try {
                decode(picture)?.let { shown ->
                    if (job?.isActive == true) _state.value = ScreenViewState.Showing(shown)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not open a picture: ${t.javaClass.simpleName}")
            } finally {
                decoding.set(false)
            }
        }
    }

    @Synchronized
    private fun giveUp(reason: ScreenViewState.Reason) {
        job?.cancel()
        job = null
        _state.value = ScreenViewState.Unavailable(reason)
    }

    private companion object {
        const val TAG = "ScreenViewClient"
    }
}
