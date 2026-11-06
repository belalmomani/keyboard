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
import com.omsingh.beckon.R
import com.omsingh.beckon.core.screen.ScreenPicture
import com.omsingh.beckon.core.screen.ScreenViewState
import com.omsingh.beckon.ui.theme.Spacing

/**
 * The PC's screen on the phone: view only. It asks for pictures while this tab is on screen and the app is in
 * front, and stops (telling the PC) the moment either stops being true.
 */
@Composable
fun ScreenTab(controller: ScreenController?, connected: Boolean, modifier: Modifier = Modifier) {
    if (controller == null) return
    val state by controller.state.collectAsState()
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // Keyed on `connected`: a reconnect resets the client, so the request must be sent again.
    DisposableEffect(controller, connected) {
        if (connected) controller.start()
        onDispose { controller.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { controller.start() }
    if (fullscreen) FullscreenPicture(state, controller, onClose = { fullscreen = false })

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
                is ScreenViewState.Showing -> PictureView(current.picture, onFullscreen = { fullscreen = true })
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
private fun PictureView(picture: ScreenPicture, onFullscreen: () -> Unit) {
    val bitmap = remember(picture) {
        Bitmap.createBitmap(picture.pixels, picture.width, picture.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.screen_picture_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clickable(onClick = onFullscreen),
        )
        FilledTonalIconButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm)) {
            Icon(Icons.Rounded.Fullscreen, contentDescription = stringResource(R.string.screen_fullscreen))
        }
    }
}

/**
 * The picture over the whole screen: sharper (the PC is asked for a bigger one), turned sideways if wanted, with
 * pinch to zoom, drag to move and a double tap to go back to the whole picture. View only, as ever.
 */
@Composable
private fun FullscreenPicture(state: ScreenViewState, controller: ScreenController, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var landscape by rememberSaveable { mutableStateOf(false) }

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
                },
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
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                    )
                }
                else -> CircularProgressIndicator(color = Color.White)
            }
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                FilledTonalIconButton(onClick = { landscape = !landscape }) {
                    Icon(Icons.Rounded.ScreenRotation, contentDescription = stringResource(R.string.screen_rotate))
                }
                FilledTonalIconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.screen_exit_fullscreen))
                }
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
