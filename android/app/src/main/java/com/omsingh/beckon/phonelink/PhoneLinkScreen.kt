package com.omsingh.beckon.phonelink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.omsingh.beckon.R
import com.omsingh.beckon.core.phonelink.ClientState
import com.omsingh.beckon.core.phonelink.CloseReason
import com.omsingh.beckon.core.phonelink.FailReason
import com.omsingh.beckon.core.phonelink.HostState
import com.omsingh.beckon.core.phonelink.LinkInvite
import com.omsingh.beckon.core.phonelink.ReceivedFile
import com.omsingh.beckon.core.screen.ScreenViewState
import com.omsingh.beckon.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Linking this phone with another one: this phone shows a code and receives, the other phone scans it and
 * sends its screen and files. Both run Beckon.
 */
@Composable
fun PhoneLinkRoute(
    viewModel: PhoneLinkViewModel,
    onScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()

    BackHandler(enabled = mode != LinkMode.MENU) { viewModel.leave() }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.sm)) {
            IconButton(onClick = {
                if (mode == LinkMode.MENU) onBack() else viewModel.leave()
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.link_back))
            }
            Text(stringResource(R.string.link_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            when (mode) {
                LinkMode.MENU -> Menu(problem, onReceive = viewModel::receive, onSend = { viewModel.send(); onScan() })
                LinkMode.RECEIVE -> ReceivePane(viewModel)
                LinkMode.SEND -> SendPane(viewModel, onScan)
            }
        }
    }
}

@Composable
private fun Menu(problem: PhoneLinkViewModel.Problem?, onReceive: () -> Unit, onSend: () -> Unit) {
    Text(stringResource(R.string.link_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    problem?.let {
        Text(
            stringResource(if (it == PhoneLinkViewModel.Problem.NO_NETWORK) R.string.link_problem_network else R.string.link_problem_listen),
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = onReceive, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_receive_here)) }
    Text(stringResource(R.string.link_receive_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onSend, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_send_from_here)) }
    Text(stringResource(R.string.link_send_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── This phone receives ──────────────────────────────────────────────

@Composable
private fun ReceivePane(viewModel: PhoneLinkViewModel) {
    val host = viewModel.hub.host
    val state by host.state.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()

    when (val current = state) {
        is HostState.Idle -> Text(stringResource(R.string.link_waiting))
        is HostState.Waiting -> {
            Text(stringResource(R.string.link_show_code_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.link_show_code_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            invite?.let { QrCode(it, Modifier.fillMaxWidth()) }
            Text(stringResource(R.string.link_same_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = viewModel::leave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_cancel)) }
        }
        is HostState.Asking -> {
            Text(stringResource(R.string.link_asking_title, current.deviceName), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.link_asking_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { viewModel.answer(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_allow)) }
            OutlinedButton(onClick = { viewModel.answer(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_refuse)) }
        }
        is HostState.Connected -> Received(viewModel, current.deviceName)
        is HostState.Closed -> {
            Text(stringResource(closedTitle(current.reason)), style = MaterialTheme.typography.titleMedium)
            Button(onClick = viewModel::newCode, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_new_code)) }
            val files by host.received.collectAsStateWithLifecycle()
            if (files.isNotEmpty()) FilesList(files)
        }
    }
}

private fun closedTitle(reason: CloseReason): Int = when (reason) {
    CloseReason.FINISHED -> R.string.link_closed_finished
    CloseReason.REJECTED -> R.string.link_closed_rejected
    CloseReason.EXPIRED -> R.string.link_closed_expired
    CloseReason.TOO_MANY_TRIES -> R.string.link_closed_tries
    CloseReason.LOST, CloseReason.FAILED -> R.string.link_closed_lost
}

@Composable
private fun Received(viewModel: PhoneLinkViewModel, deviceName: String) {
    val host = viewModel.hub.host
    val picture by host.screen.collectAsStateWithLifecycle()
    val incoming by host.incoming.collectAsStateWithLifecycle()
    val files by host.received.collectAsStateWithLifecycle()

    Text(stringResource(R.string.link_connected_to, deviceName), style = MaterialTheme.typography.titleMedium)

    val shown = picture
    if (shown != null) {
        val bitmap by produceState<Bitmap?>(null, shown.counter) {
            value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(shown.jpeg, 0, shown.jpeg.size) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(shown.width.toFloat() / shown.height.toFloat())
                .background(Color.Black, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.link_screen_description),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(stringResource(R.string.link_view_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(stringResource(R.string.link_no_screen), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    incoming?.let { (name, fraction) ->
        Text(stringResource(R.string.link_receiving, name))
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }

    if (files.isNotEmpty()) FilesList(files)
    OutlinedButton(onClick = viewModel::leave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_disconnect)) }
}

@Composable
private fun FilesList(files: List<ReceivedFile>) {
    val context = LocalContext.current
    Text(stringResource(R.string.link_received_title), style = MaterialTheme.typography.titleMedium)
    for (file in files.take(MAX_LISTED)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open(context, file) }
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (file.isImage) {
                val thumb by produceState<Bitmap?>(null, file.path) { value = withContext(Dispatchers.IO) { thumbnail(file.path) } }
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(sizeText(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { open(context, file) }) { Text(stringResource(R.string.link_open)) }
        }
        HorizontalDivider()
    }
}

private const val MAX_LISTED = 50

private fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun thumbnail(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Opens a received file with whatever app on this phone can show it. */
internal fun open(context: Context, file: ReceivedFile) {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(file.path))
        val type = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No app to open it with: the file is still kept.
    }
}

/** The invitation as a QR code, black on white whatever the theme. */
@Composable
private fun QrCode(invite: LinkInvite, modifier: Modifier = Modifier) {
    val bitmap = remember(invite) { qrBitmap(invite.toUri()) }
    Box(modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.link_code_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
    }
}

internal fun qrBitmapFor(text: String): Bitmap = qrBitmap(text)

private fun qrBitmap(text: String): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    // Scaled up in whole steps, so the modules stay sharp.
    val small = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    val step = (QR_PIXELS / matrix.width).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(small, matrix.width * step, matrix.height * step, false)
}

private const val QR_PIXELS = 720

// ── This phone sends ─────────────────────────────────────────────────

@Composable
private fun SendPane(viewModel: PhoneLinkViewModel, onScan: () -> Unit) {
    val state by viewModel.hub.client.state.collectAsStateWithLifecycle()
    val sharing by viewModel.hub.sharingScreen.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) ScreenShareService.start(context, result.resultCode, data)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.sendFiles(uris)
    }

    when (val current = state) {
        ClientState.Idle, ClientState.Closed -> {
            Text(stringResource(R.string.link_scan_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.link_scan_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_scan_button)) }
        }
        ClientState.Connecting -> Waiting(R.string.link_connecting)
        ClientState.WaitingForApproval -> Waiting(R.string.link_waiting_approval)
        is ClientState.Failed -> {
            Text(stringResource(failTitle(current.reason)), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_scan_again)) }
        }
        is ClientState.Connected -> {
            Text(stringResource(R.string.link_connected_to, current.hostName.ifBlank { stringResource(R.string.link_other_phone) }), style = MaterialTheme.typography.titleMedium)
            val relay by viewModel.hub.pcRelay.state.collectAsStateWithLifecycle()
            val relaying = relay != RelayState.Off && relay != RelayState.NoPc
            if (relaying) {
                Text(
                    stringResource(
                        when (val r = relay) {
                            RelayState.Sending -> R.string.link_pc_sending
                            is RelayState.Problem -> if (r.reason == ScreenViewState.Reason.NotAllowed) R.string.link_pc_not_allowed else R.string.link_pc_problem
                            else -> R.string.link_pc_waiting
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { viewModel.hub.pcRelay.stop() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_pc_stop)) }
            } else if (sharing) {
                Text(stringResource(R.string.link_sharing_now), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { ScreenShareService.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_stop_sharing)) }
            } else {
                Button(
                    onClick = {
                        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        capture.launch(manager.createScreenCaptureIntent())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.link_share_screen)) }
                Text(stringResource(R.string.link_share_screen_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!relaying && !sharing) {
                OutlinedButton(onClick = { viewModel.hub.pcRelay.start() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_pc_show)) }
                Text(
                    stringResource(if (relay == RelayState.NoPc) R.string.link_pc_none else R.string.link_pc_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (relay == RelayState.NoPc) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_send_files)) }
            progress?.let { SendProgressView(it, onDone = viewModel::clearProgress) }
            TextButton(onClick = viewModel::leave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_disconnect)) }
        }
    }
}

private fun failTitle(reason: FailReason): Int = when (reason) {
    FailReason.UNREACHABLE -> R.string.link_failed_unreachable
    FailReason.NOT_THE_CODE -> R.string.link_failed_code
    FailReason.REJECTED -> R.string.link_failed_rejected
    FailReason.LOST -> R.string.link_failed_lost
    FailReason.NO_ANSWER -> R.string.link_failed_no_answer
}

@Composable
private fun Waiting(text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(stringResource(text))
    }
}

@Composable
private fun SendProgressView(progress: SendProgress, onDone: () -> Unit) {
    if (progress.finished) {
        Text(stringResource(R.string.link_sent_summary, progress.done, progress.total))
        if (progress.failed > 0) Text(stringResource(R.string.link_sent_failed, progress.failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onDone) { Text(stringResource(R.string.link_ok)) }
    } else {
        Text(stringResource(R.string.link_sending, progress.current, progress.done + 1, progress.total))
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    }
}
