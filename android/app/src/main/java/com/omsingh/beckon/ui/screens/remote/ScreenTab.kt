package com.omsingh.beckon.ui.screens.remote

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.input.KeyboardSession
import com.omsingh.beckon.R
import com.omsingh.beckon.core.screen.ScreenPicture
import com.omsingh.beckon.core.screen.ScreenViewState
import com.omsingh.beckon.ui.theme.Spacing

/**
 * The PC's screen on the phone: view only. It asks for pictures while this tab is on screen and the app is in
 * front, and stops (telling the PC) the moment either stops being true.
 */
@Composable
fun ScreenTab(
    controller: ScreenController?,
    connected: Boolean,
    modifier: Modifier = Modifier,
    actions: RemoteActions = RemoteActions.None,
    keyboard: KeyboardSession? = null,
) {
    if (controller == null) return
    val state by controller.state.collectAsState()
    val fullscreen by controller.fullscreen.collectAsState()

    // Keyed on `connected`: a reconnect resets the client, so the request must be sent again.
    DisposableEffect(controller, connected) {
        if (connected) controller.start()
        onDispose { controller.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { controller.start() }
    if (fullscreen) FullscreenPicture(state, controller, actions, keyboard, onClose = { controller.fullscreen.value = false })

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                is ScreenViewState.Showing -> PictureView(
                    current.picture, controller, actions, keyboard,
                    onFullscreen = { controller.fullscreen.value = true },
                )
                ScreenViewState.Off, ScreenViewState.Waiting -> Waiting()
                is ScreenViewState.Unavailable -> Unavailable(current.reason, onRetry = controller::start)
            }
        }
        if (state is ScreenViewState.Showing) {
            Text(
                text = stringResource(R.string.screen_view_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PictureView(
    picture: ScreenPicture,
    controller: ScreenController,
    actions: RemoteActions,
    keyboard: KeyboardSession?,
    onFullscreen: () -> Unit,
) {
    val bitmap = remember(picture) {
        Bitmap.createBitmap(picture.pixels, picture.width, picture.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val control by controller.control.collectAsState()
    val direct by controller.direct.collectAsState()
    var typing by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(actions::send)
    val aspect = picture.width.toFloat() / picture.height.coerceAtLeast(1)
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.screen_picture_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (control) Modifier.pointerInput(direct, aspect) { controlGestures(direct, aspect) { send(it) } }
                    else Modifier.clickable(onClick = onFullscreen),
                ),
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (keyboard != null) {
                FilledTonalIconButton(onClick = { controller.control.value = !control; if (control) typing = false }) {
                    Icon(
                        Icons.Rounded.Mouse,
                        contentDescription = stringResource(if (control) R.string.screen_control_off else R.string.screen_control_on),
                        tint = if (control) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                if (control) {
                    FilledTonalIconButton(onClick = { controller.direct.value = !direct }) {
                        Icon(
                            if (direct) Icons.Rounded.TouchApp else Icons.Rounded.Mouse,
                            contentDescription = stringResource(if (direct) R.string.screen_control_use_pad else R.string.screen_control_use_touch),
                        )
                    }
                    FilledTonalIconButton(onClick = { typing = !typing }) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = stringResource(R.string.screen_control_keyboard))
                    }
                }
            }
            FilledTonalIconButton(onClick = onFullscreen) {
                Icon(Icons.Rounded.Fullscreen, contentDescription = stringResource(R.string.screen_fullscreen))
            }
        }
        if (control && typing && keyboard != null) {
            ControlKeyboard(keyboard, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * The picture over the whole screen: sharper (the PC is asked for a bigger one), turned sideways if wanted, with
 * pinch to zoom, drag to move and a double tap to go back to the whole picture. View only, as ever.
 */
@Composable
private fun FullscreenPicture(
    state: ScreenViewState,
    controller: ScreenController,
    actions: RemoteActions,
    keyboard: KeyboardSession?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val landscape by controller.landscape.collectAsState()
    val control by controller.control.collectAsState()
    val direct by controller.direct.collectAsState()
    var typing by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(actions::send)
    val aspect = ((state as? ScreenViewState.Showing)?.picture?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }) ?: 1.6f

    // Sharper pictures while this is open; asked again when a new view starts (a reconnect forgets the choice).
    LaunchedEffect(state is ScreenViewState.Showing || state == ScreenViewState.Waiting) { controller.setWide(true) }
    DisposableEffect(Unit) { onDispose { controller.setWide(false) } }
    DisposableEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The bars are hidden: the picture is all there is.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(
                    if (control) {
                        // Full control: the finger is a pad laid over the picture.
                        Modifier.pointerInput(direct, aspect) { controlGestures(direct, aspect) { send(it) } }
                    } else {
                        Modifier
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    if (scale == 1f) {
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    scale = if (scale > 1f) 1f else 2.5f
                                    offsetX = 0f
                                    offsetY = 0f
                                })
                            }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ScreenViewState.Showing -> {
                    val picture = state.picture
                    val bitmap = remember(picture) {
                        Bitmap.createBitmap(picture.pixels, picture.width, picture.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.screen_picture_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = if (control) 1f else scale,
                                scaleY = if (control) 1f else scale,
                                translationX = if (control) 0f else offsetX,
                                translationY = if (control) 0f else offsetY,
                            ),
                    )
                }
                else -> CircularProgressIndicator(color = Color.White)
            }
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (keyboard != null) {
                    FilledTonalIconButton(onClick = { controller.control.value = !control; if (control) typing = false }) {
                        Icon(
                            Icons.Rounded.Mouse,
                            contentDescription = stringResource(if (control) R.string.screen_control_off else R.string.screen_control_on),
                            tint = if (control) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    if (control) {
                        FilledTonalIconButton(onClick = { controller.direct.value = !direct }) {
                            Icon(
                                if (direct) Icons.Rounded.TouchApp else Icons.Rounded.Mouse,
                                contentDescription = stringResource(if (direct) R.string.screen_control_use_pad else R.string.screen_control_use_touch),
                            )
                        }
                        FilledTonalIconButton(onClick = { typing = !typing }) {
                            Icon(Icons.Rounded.Keyboard, contentDescription = stringResource(R.string.screen_control_keyboard))
                        }
                    }
                }
                FilledTonalIconButton(onClick = { controller.landscape.value = !landscape }) {
                    Icon(Icons.Rounded.ScreenRotation, contentDescription = stringResource(R.string.screen_rotate))
                }
                FilledTonalIconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.screen_exit_fullscreen))
                }
            }
            if (control && typing && keyboard != null) {
                ControlKeyboard(keyboard, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Waiting() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.screen_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Unavailable(reason: ScreenViewState.Reason, onRetry: () -> Unit) {
    val (title, body) = when (reason) {
        ScreenViewState.Reason.NotAllowed ->
            R.string.screen_not_allowed_title to R.string.screen_not_allowed_body
        ScreenViewState.Reason.Unsupported ->
            R.string.screen_unsupported_title to R.string.screen_unsupported_body
        ScreenViewState.Reason.Failed ->
            R.string.screen_failed_title to R.string.screen_failed_body
        ScreenViewState.Reason.NoAnswer ->
            R.string.screen_no_answer_title to R.string.screen_no_answer_body
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier.padding(Spacing.lg),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.screen_try_again)) }
    }
}

/** Types into the PC from the full screen picture: the written text goes whole, with Enter and Backspace beside it. */
@Composable
private fun ControlKeyboard(keyboard: KeyboardSession, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier
            .fillMaxWidth()
            .imePadding()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (text.isNotEmpty()) keyboard.edit(0, text)
                text = ""
            }),
        )
        FilledTonalIconButton(onClick = { keyboard.backspace(1) }) {
            Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.screen_control_backspace))
        }
        FilledTonalIconButton(onClick = { keyboard.tapKey(0x28) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, contentDescription = stringResource(R.string.screen_control_enter))
        }
    }
}

/** Where a touch at [p] lies in a picture of this [aspect] shown fitted in [size], from 0 to 1 each way. */
private fun placeInPicture(size: IntSize, aspect: Float, p: Offset): Offset {
    val w = size.width.toFloat().coerceAtLeast(1f)
    val h = size.height.toFloat().coerceAtLeast(1f)
    val (iw, ih) = if (aspect > w / h) w to w / aspect else h * aspect to h
    val left = (w - iw) / 2f
    val top = (h - ih) / 2f
    return Offset(((p.x - left) / iw).coerceIn(0f, 1f), ((p.y - top) / ih).coerceIn(0f, 1f))
}

/**
 * Touch control over the picture. Direct: the pointer goes to where the finger is (and follows it), a tap clicks
 * there. Pad: one finger moves the pointer by how far it moves. Either way a long press right-clicks and two
 * fingers scroll. The pointer itself is the one in the picture.
 */
private suspend fun PointerInputScope.controlGestures(direct: Boolean, aspect: Float, send: (InputEvent) -> Unit) {
    val slop = 8.dp.toPx()
    val notch = 36.dp.toPx()
    val speed = 1.8f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var travelled = 0f
        var scrolled = false
        var carry = 0f
        if (direct) placeInPicture(size, aspect, down.position).let { send(InputEvent.MouseTo(it.x, it.y)) }
        do {
            val event = awaitPointerEvent()
            val held = event.changes.filter { it.pressed }
            if (held.size >= 2) {
                scrolled = true
                carry += held.map { it.positionChange().y }.average().toFloat()
                while (carry >= notch) { send(InputEvent.Scroll(1f)); carry -= notch }
                while (carry <= -notch) { send(InputEvent.Scroll(-1f)); carry += notch }
            } else if (!scrolled && held.size == 1) {
                val d = held[0].positionChange()
                travelled += kotlin.math.abs(d.x) + kotlin.math.abs(d.y)
                if (d != Offset.Zero) {
                    if (direct) {
                        placeInPicture(size, aspect, held[0].position).let { send(InputEvent.MouseTo(it.x, it.y)) }
                    } else {
                        send(InputEvent.MouseMove(d.x * speed / density, d.y * speed / density))
                    }
                }
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        val heldFor = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
        if (!scrolled && travelled < slop) {
            send(if (heldFor >= 500) InputEvent.RightClick else InputEvent.Click)
        }
    }
}
