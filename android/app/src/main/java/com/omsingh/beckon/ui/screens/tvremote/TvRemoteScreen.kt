package com.omsingh.beckon.ui.screens.tvremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omsingh.beckon.R
import com.omsingh.beckon.core.tvremote.FoundTv
import com.omsingh.beckon.core.tvremote.TvProtocol.Key
import com.omsingh.beckon.ui.theme.Spacing

/** Controlling a TV over Wi-Fi: pick it, type the code it shows the first time, then the remote. */
@Composable
fun TvRemoteRoute(viewModel: TvRemoteViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val leaving = state.phase != TvPhase.CHOOSE
    BackHandler(enabled = leaving) { viewModel.leave() }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.sm)) {
            IconButton(onClick = { if (leaving) viewModel.leave() else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.link_back))
            }
            Text(state.tv?.takeIf { leaving }?.name ?: stringResource(R.string.tv_remote_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            when (state.phase) {
                TvPhase.CHOOSE -> Choose(state, viewModel)
                TvPhase.WORKING -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.tv_remote_connecting), style = MaterialTheme.typography.bodyMedium)
                }
                TvPhase.CODE -> Code(viewModel)
                TvPhase.CONTROL -> Control(viewModel)
            }
        }
    }
}

@Composable
private fun Choose(state: TvRemoteState, viewModel: TvRemoteViewModel) {
    Text(stringResource(R.string.tv_remote_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.known.isNotEmpty()) {
        Text(stringResource(R.string.tv_remote_paired), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        state.known.forEach { tv ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = { viewModel.pick(tv) }, modifier = Modifier.weight(1f)) { Text(tv.name, maxLines = 1) }
                OutlinedButton(onClick = { viewModel.forget(tv) }) { Text(stringResource(R.string.tv_remote_forget)) }
            }
        }
    }
    Text(stringResource(R.string.tv_remote_found), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
    val others = state.found.filter { f -> state.known.none { it.host == f.host } }
    if (others.isEmpty()) {
        Text(stringResource(R.string.tv_remote_searching), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    others.forEach { tv -> FilledTonalButton(onClick = { viewModel.pick(tv) }, modifier = Modifier.fillMaxWidth()) { Text(tv.name, maxLines = 1) } }

    var address by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.trim() },
        label = { Text(stringResource(R.string.tv_remote_address)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (address.isNotEmpty()) viewModel.pick(FoundTv(address, address)) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { viewModel.pick(FoundTv(address, address)) }, enabled = address.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tv_remote_connect))
    }
}

@Composable
private fun Code(viewModel: TvRemoteViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.tv_remote_code_body), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter { c -> c.isLetterOrDigit() }.take(6).uppercase() },
        label = { Text(stringResource(R.string.tv_remote_code_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.length == 6) viewModel.submitCode(code) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { viewModel.submitCode(code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tv_remote_pair))
    }
}

@Composable
private fun Control(viewModel: TvRemoteViewModel) {
    // The arrows point where the TV's do, whatever the language reads towards.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
            KeyRow(viewModel, "▲" to Key.UP)
            KeyRow(viewModel, "◀" to Key.LEFT, stringResource(R.string.tv_key_ok) to Key.OK, "▶" to Key.RIGHT)
            KeyRow(viewModel, "▼" to Key.DOWN)
            KeyRow(viewModel, stringResource(R.string.tv_key_back) to Key.BACK, stringResource(R.string.tv_key_home) to Key.HOME, stringResource(R.string.tv_key_menu) to Key.MENU)
            KeyRow(viewModel, stringResource(R.string.tv_key_vol_down) to Key.VOLUME_DOWN, stringResource(R.string.tv_key_mute) to Key.MUTE, stringResource(R.string.tv_key_vol_up) to Key.VOLUME_UP)
            KeyRow(viewModel, stringResource(R.string.tv_key_play) to Key.PLAY_PAUSE, stringResource(R.string.tv_key_power) to Key.POWER)
            KeyRow(viewModel, stringResource(R.string.tv_key_ch_down) to Key.CHANNEL_DOWN, stringResource(R.string.tv_key_ch_up) to Key.CHANNEL_UP)
        }
    }
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.tv_remote_type)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (text.isNotEmpty()) { viewModel.type(text); text = "" } }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FilledTonalButton(onClick = { viewModel.press(Key.DELETE) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tv_key_delete)) }
        Button(onClick = { viewModel.type(text); text = "" }, enabled = text.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.tv_remote_send))
        }
    }
}

@Composable
private fun KeyRow(viewModel: TvRemoteViewModel, vararg keys: Pair<String, Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        keys.forEach { (label, key) ->
            FilledTonalButton(onClick = { viewModel.press(key) }, modifier = Modifier.height(56.dp).padding(0.dp)) { Text(label, maxLines = 1) }
        }
    }
}
