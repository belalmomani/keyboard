package com.omsingh.beckon.phonelink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.omsingh.beckon.core.phonelink.ReceivedFile
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omsingh.beckon.R
import com.omsingh.beckon.core.phonelink.CloseReason
import com.omsingh.beckon.core.phonelink.HostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Beckon on a television: it shows a code, a phone scans it and then shows its screen (or the PC's screen) here,
 * full screen. View only. Everything is done with the remote's arrows and OK; no touch and no camera are needed.
 */
@Composable
fun TvRoute(viewModel: PhoneLinkViewModel, modifier: Modifier = Modifier) {
    val host = viewModel.hub.host
    val state by host.state.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()

    // A code is shown as soon as the app opens, and again whenever the last one is over.
    // Without a network yet (a TV that starts before Wi-Fi is up), it tries again every few seconds.
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    LaunchedEffect(state is HostState.Idle || state is HostState.Closed, problem) {
        if (state is HostState.Idle || state is HostState.Closed) {
            if (problem != null) kotlinx.coroutines.delay(5_000)
            viewModel.receive()
        }
    }
    // Back ends a link (and shows a new code); on the code itself it leaves the app as usual.
    BackHandler(enabled = state is HostState.Connected || state is HostState.Asking) {
        viewModel.leave()
        viewModel.receive()
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (val current = state) {
            is HostState.Connected -> TvPicture(viewModel, current.deviceName)
            is HostState.Asking -> TvAsking(current.deviceName, onAnswer = viewModel::answer)
            else -> TvCode(viewModel, invite, closed = (current as? HostState.Closed)?.reason)
        }
    }
}

@Composable
private fun TvCode(viewModel: PhoneLinkViewModel, invite: com.omsingh.beckon.core.phonelink.LinkInvite?, closed: CloseReason?) {
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxSize().padding(64.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(360.dp), contentAlignment = Alignment.Center) {
            if (invite != null) TvQr(invite) else Text(stringResource(R.string.tv_no_code), color = Color.White)
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.tv_title), style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(stringResource(R.string.tv_steps), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.8f))
            Text(stringResource(R.string.link_same_wifi), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.6f))
            if (closed != null && closed != CloseReason.FINISHED) {
                Text(stringResource(R.string.tv_link_ended), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.6f))
            }
            problem?.let {
                Text(
                    stringResource(if (it == PhoneLinkViewModel.Problem.NO_NETWORK) R.string.link_problem_network else R.string.link_problem_listen),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = viewModel::receive) { Text(stringResource(R.string.link_new_code)) }
        }
    }
}

@Composable
private fun TvQr(invite: com.omsingh.beckon.core.phonelink.LinkInvite) {
    val bitmap = remember(invite) { qrBitmapFor(invite.toUri()) }
    Box(Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.link_code_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TvAsking(name: String, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.link_asking_title, name), style = MaterialTheme.typography.displaySmall, color = Color.White)
        Text(stringResource(R.string.tv_asking_body), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.8f))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // The refuse button is the one that has focus first: nothing is allowed by a stray press of OK.
            OutlinedButton(onClick = { onAnswer(false) }, Modifier.focusRequester(focus)) { Text(stringResource(R.string.link_refuse)) }
            Button(onClick = { onAnswer(true) }) { Text(stringResource(R.string.link_allow)) }
        }
    }
}

@Composable
private fun TvPicture(viewModel: PhoneLinkViewModel, name: String) {
    val host = viewModel.hub.host
    val picture by host.screen.collectAsStateWithLifecycle()
    val files by host.received.collectAsStateWithLifecycle()
    val shown = picture
    var viewing by remember { mutableStateOf<ReceivedFile?>(null) }
    val viewed = viewing
    if (viewed != null && shown == null) {
        TvFileViewer(viewed, onClose = { viewing = null })
    } else if (shown != null) {
        val bitmap by produceState<Bitmap?>(null, shown.counter) {
            value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(shown.jpeg, 0, shown.jpeg.size) }
        }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.link_screen_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.link_connected_to, name), style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(stringResource(R.string.tv_waiting_screen), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.8f))
            if (files.isNotEmpty()) {
                Text(stringResource(R.string.tv_files_received, files.size), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
                TvFiles(files, onView = { viewing = it })
            }
        }
    }
}

/** The files that came from the phone, newest first: OK on one shows a picture full screen, or opens it with another app. */
@Composable
private fun TvFiles(files: List<ReceivedFile>, onView: (ReceivedFile) -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(files.firstOrNull()?.path) { runCatching { focus.requestFocus() } }
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        files.take(6).forEachIndexed { index, file ->
            Button(
                onClick = { if (file.isImage) onView(file) else open(context, file) },
                modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
            ) { Text(file.name, maxLines = 1) }
        }
    }
}

/** A received picture over the whole screen; Back returns to the list. */
@Composable
private fun TvFileViewer(file: ReceivedFile, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val bitmap by produceState<Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.Default) { decodeForScreen(file.path) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A picture file, no bigger than a TV screen needs. */
private fun decodeForScreen(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
