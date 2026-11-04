package com.omsingh.beckon.ui.screens.remote

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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

    // Keyed on `connected`: a reconnect resets the client, so the request must be sent again.
    DisposableEffect(controller, connected) {
        if (connected) controller.start()
        onDispose { controller.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { controller.start() }

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
                is ScreenViewState.Showing -> PictureView(current.picture)
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
private fun PictureView(picture: ScreenPicture) {
    val bitmap = remember(picture) {
        Bitmap.createBitmap(picture.pixels, picture.width, picture.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.screen_picture_description),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
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
