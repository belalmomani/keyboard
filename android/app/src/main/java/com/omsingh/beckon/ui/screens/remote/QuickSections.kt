package com.omsingh.beckon.ui.screens.remote

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omsingh.beckon.R
import com.omsingh.beckon.core.commands.BoardError
import com.omsingh.beckon.core.commands.Command
import com.omsingh.beckon.core.commands.LeaderStep
import com.omsingh.beckon.core.commands.RhythmResult
import com.omsingh.beckon.ui.theme.Spacing
import kotlinx.coroutines.delay

// ── Leader mode ──────────────────────────────────────────────────────

/**
 * Press "Leader", then the keys that lead to a command: the keys that can come next are shown as buttons, so
 * nothing has to be remembered. A pause of a few seconds starts over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LeaderSection(quick: QuickController) {
    val board by quick.board.board.collectAsState()
    var step by remember { mutableStateOf<LeaderStep.Pending?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var keys by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<BoardError?>(null) }
    val noMatch = stringResource(R.string.quick_leader_nomatch)

    // A half-finished sequence is dropped after a pause, on screen too.
    LaunchedEffect(step) {
        if (step != null) {
            delay(LEADER_IDLE_MS)
            quick.leader.reset()
            step = null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val current = step
        if (current == null) {
            Button(
                onClick = { message = null; step = quick.leader.start() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.quick_leader_start)) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Text(
                stringResource(R.string.quick_leader_keys, current.typed.ifEmpty { "…" }),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for (choice in current.next) {
                    val label = choice.commandId?.let { id -> quick.board.command(id)?.let { commandLabel(it) } }
                        ?: stringResource(R.string.quick_leader_more)
                    OutlinedButton(
                        onClick = {
                            when (val result = quick.leader.press(choice.key)) {
                                is LeaderStep.Pending -> step = result
                                is LeaderStep.Run -> { step = null; quick.runById(result.commandId) }
                                LeaderStep.NoMatch -> { step = null; message = noMatch }
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("${choice.key.uppercaseChar()}  $label", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            OutlinedButton(onClick = { quick.leader.reset(); step = null }) { Text(stringResource(R.string.lib_cancel)) }
        }

        Text(stringResource(R.string.quick_leader_list), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.md))
        for (binding in board.leader.sortedBy { it.keys }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(binding.keys.uppercase(), Modifier.padding(end = Spacing.md), style = MaterialTheme.typography.titleSmall)
                Text(
                    quick.board.command(binding.commandId)?.let { commandLabel(it) } ?: binding.commandId,
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { quick.board.removeLeader(binding.keys) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.lib_delete))
                }
            }
        }
        OutlinedTextField(
            keys, { keys = it.take(3); error = null }, singleLine = true,
            label = { Text(stringResource(R.string.quick_leader_new_keys)) },
            isError = error != null,
            supportingText = error?.let { { Text(boardErrorText(it)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { picking = keys }) { Text(stringResource(R.string.quick_pick_command)) }
    }
    picking?.let { chosenKeys ->
        CommandPicker(quick.board, onDismiss = { picking = null }) { command ->
            error = quick.board.addLeader(chosenKeys, command.id)
            if (error == null) keys = ""
            picking = null
        }
    }
}

private const val LEADER_IDLE_MS = 4_000L

// ── Rhythm ───────────────────────────────────────────────────────────

/**
 * A pad to tap. Short and long taps make a pattern; when the taps stop, the pattern is matched against the
 * person's rhythms and the command runs. Taps on the pad do nothing else.
 */
@Composable
internal fun RhythmSection(quick: QuickController) {
    val board by quick.board.board.collectAsState()
    var pattern by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var newPattern by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<BoardError?>(null) }
    val noMatch = stringResource(R.string.quick_rhythm_nomatch)

    // The run ends when the taps pause; checking a few times a second is enough for a pause of most of a second.
    LaunchedEffect(Unit) {
        while (true) {
            delay(RHYTHM_POLL_MS)
            when (val result = quick.rhythm.poll(SystemClock.elapsedRealtime())) {
                is RhythmResult.Match -> { pattern = ""; message = result.pattern; quick.runById(result.commandId) }
                is RhythmResult.NoMatch -> { pattern = ""; message = noMatch }
                null -> Unit
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val down = SystemClock.elapsedRealtime()
                        waitForUpOrCancellation()
                        val up = SystemClock.elapsedRealtime()
                        val result = quick.rhythm.tap(up - down, up)
                        pattern = quick.rhythm.pattern
                        when (result) {
                            is RhythmResult.Match -> { pattern = ""; message = result.pattern; quick.runById(result.commandId) }
                            is RhythmResult.NoMatch -> { pattern = ""; message = noMatch }
                            null -> Unit
                        }
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    pattern.ifEmpty { stringResource(R.string.quick_rhythm_hint) },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        Text(stringResource(R.string.quick_rhythm_list), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.md))
        for (binding in board.rhythm) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(binding.pattern, Modifier.padding(end = Spacing.md), style = MaterialTheme.typography.titleMedium)
                Text(
                    quick.board.command(binding.commandId)?.let { commandLabel(it) } ?: binding.commandId,
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { quick.board.removeRhythm(binding.pattern) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.lib_delete))
                }
            }
        }
        OutlinedTextField(
            newPattern, { newPattern = it.filter { c -> c == '.' || c == '-' }.take(6); error = null }, singleLine = true,
            label = { Text(stringResource(R.string.quick_rhythm_new)) },
            isError = error != null,
            supportingText = error?.let { { Text(boardErrorText(it)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { picking = newPattern }) { Text(stringResource(R.string.quick_pick_command)) }
    }
    picking?.let { chosen ->
        CommandPicker(quick.board, onDismiss = { picking = null }) { command: Command ->
            error = quick.board.addRhythm(chosen, command.id)
            if (error == null) newPattern = ""
            picking = null
        }
    }
}

private const val RHYTHM_POLL_MS = 100L

// ── Windows ──────────────────────────────────────────────────────────

/** The windows open on the PC. Tapping one brings it to the front, and the PC says whether it did. */
@Composable
internal fun WindowsSection(quick: QuickController) {
    val windows by quick.windows.collectAsState()
    // The list is asked for again every few seconds while this is on screen, and not at all otherwise.
    LaunchedEffect(Unit) {
        while (true) {
            quick.refreshWindows()
            delay(WINDOWS_REFRESH_MS)
        }
    }
    val list = windows
    when {
        !quick.canSwitchWindows -> Text(stringResource(R.string.quick_windows_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        list == null -> Text(stringResource(R.string.quick_windows_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.isEmpty() -> Text(stringResource(R.string.quick_windows_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(list, key = { it.handle }) { window ->
                Surface(
                    onClick = { quick.focus(window) },
                    shape = MaterialTheme.shapes.large,
                    color = if (window.foreground) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(window.program, style = MaterialTheme.typography.titleSmall)
                        if (window.title.isNotEmpty()) {
                            Text(
                                window.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val WINDOWS_REFRESH_MS = 3_000L
