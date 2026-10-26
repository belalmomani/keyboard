package com.omsingh.beckon.ui.screens.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omsingh.beckon.R
import com.omsingh.beckon.connection.TextSendResult
import com.omsingh.beckon.core.commands.BoardError
import com.omsingh.beckon.core.commands.Command
import com.omsingh.beckon.core.commands.CommandBoard
import com.omsingh.beckon.core.commands.ShortcutKeys
import com.omsingh.beckon.core.commands.WheelSlot
import com.omsingh.beckon.core.input.HidModifierMask
import com.omsingh.beckon.core.text.TextBlockResult
import com.omsingh.beckon.ui.theme.Spacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Quick control: the command wheel, leader mode, rhythm taps and the window radar. */
@Composable
fun QuickTab(quick: QuickController?, modifier: Modifier = Modifier) {
    if (quick == null) return
    var section by rememberSaveable { mutableStateOf(0) }
    val labels = listOf(R.string.quick_wheel, R.string.quick_leader, R.string.quick_rhythm, R.string.quick_windows)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = section == index,
                    onClick = { section = index },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    icon = {},
                ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                0 -> WheelSection(quick)
                1 -> LeaderSection(quick)
                2 -> RhythmSection(quick)
                else -> WindowsSection(quick)
            }
        }
        StatusLine(quick)
    }
    Confirm(quick)
}

// ── Names ────────────────────────────────────────────────────────────

/** A command's name: the person's own, or the app's name for a built-in one. */
@Composable
fun commandLabel(command: Command): String = command.label ?: builtInName("cmd_${command.id}", command.id)

@Composable
fun groupLabel(name: String): String = builtInName("group_$name", name)

@Composable
private fun builtInName(resource: String, fallback: String): String {
    val context = LocalContext.current
    val id = remember(resource) { context.resources.getIdentifier(resource, "string", context.packageName) }
    return if (id != 0) stringResource(id) else fallback
}

@Composable
internal fun boardErrorText(error: BoardError): String = stringResource(
    when (error) {
        BoardError.NAME_EMPTY -> R.string.quick_err_name_empty
        BoardError.NAME_TOO_LONG -> R.string.quick_err_name_long
        BoardError.TEXT_EMPTY -> R.string.lib_err_empty
        BoardError.TEXT_TOO_LONG -> R.string.lib_err_long
        BoardError.WHEEL_FULL -> R.string.quick_err_wheel_full
        BoardError.GROUP_EMPTY -> R.string.quick_err_group_empty
        BoardError.BAD_KEYS -> R.string.quick_err_bad_keys
        BoardError.KEYS_TAKEN, BoardError.KEYS_CONFLICT -> R.string.quick_err_keys_taken
        BoardError.BAD_PATTERN -> R.string.quick_err_bad_pattern
        BoardError.PATTERN_TAKEN -> R.string.quick_err_pattern_taken
        BoardError.TOO_MANY_CUSTOM -> R.string.quick_err_too_many
        BoardError.UNKNOWN_KEY, BoardError.UNKNOWN_COMMAND -> R.string.lib_err_other
    },
)

// ── Wheel ────────────────────────────────────────────────────────────

@Composable
private fun WheelSection(quick: QuickController) {
    val board by quick.board.board.collectAsState()
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf(false) }
    val openGroup = open?.let { board.wheel.getOrNull(it) as? WheelSlot.Group }
    if (open != null && openGroup == null) open = null

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val entries: List<Pair<String, () -> Unit>> = if (openGroup != null) {
                openGroup.commandIds.mapNotNull { id -> quick.board.command(id) }.map { c ->
                    commandLabel(c) to { quick.run(c); open = null }
                }
            } else {
                board.wheel.mapIndexedNotNull { index, slot ->
                    when (slot) {
                        is WheelSlot.Item -> quick.board.command(slot.commandId)?.let { c -> commandLabel(c) to { quick.run(c) } }
                        is WheelSlot.Group -> groupLabel(slot.name) + " …" to { open = index }
                    }
                }
            }
            val groupNames = board.wheel.map { (it as? WheelSlot.Group)?.name }
            val centre = openGroup?.let { groupLabel(it.name) } ?: stringResource(R.string.quick_wheel)
            // The labels of groups need composable lookups, so they are resolved above for both wheels.
            Wheel(
                entries = entries,
                centre = centre,
                onCentre = if (openGroup != null) ({ open = null }) else null,
            )
            if (groupNames.isEmpty() && board.wheel.isEmpty()) {
                Text(stringResource(R.string.quick_wheel_empty), textAlign = TextAlign.Center)
            }
        }
        OutlinedButton(onClick = { editing = true }) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
            Text(stringResource(R.string.quick_customize))
        }
    }
    if (editing) WheelEditor(quick, onDismiss = { editing = false })
}

/** The commands in a ring around a centre button. Tapping a command runs it. */
@Composable
private fun Wheel(entries: List<Pair<String, () -> Unit>>, centre: String, onCentre: (() -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(Spacing.sm), contentAlignment = Alignment.Center) {
        val item = 72.dp
        val side = minOf(maxWidth, if (maxHeight != Dp.Infinity) maxHeight else maxWidth, 340.dp)
        val radius = (side - item) / 2f
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            entries.forEachIndexed { i, (label, action) ->
                val angle = (-PI / 2 + 2 * PI * i / entries.size).toFloat()
                Surface(
                    onClick = action,
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .size(item)
                        .offset(x = radius * cos(angle), y = radius * sin(angle)),
                ) {
                    Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Surface(
                onClick = { onCentre?.invoke() },
                enabled = onCentre != null,
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (onCentre != null) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.quick_back))
                    } else {
                        Text(centre, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelEditor(quick: QuickController, onDismiss: () -> Unit) {
    val board by quick.board.board.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<BoardError?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_customize)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                board.wheel.forEachIndexed { index, slot ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val name = when (slot) {
                            is WheelSlot.Item -> quick.board.command(slot.commandId)?.let { commandLabel(it) } ?: slot.commandId
                            is WheelSlot.Group -> groupLabel(slot.name) + " (" + slot.commandIds.size + ")"
                        }
                        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { quick.board.moveInWheel(index, -1) }) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.quick_move_up))
                        }
                        IconButton(onClick = { quick.board.moveInWheel(index, 1) }) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.quick_move_down))
                        }
                        IconButton(onClick = { quick.board.removeFromWheel(index) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.lib_delete))
                        }
                    }
                }
                error?.let { Text(boardErrorText(it), color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.quick_add_command)) }
                    OutlinedButton(onClick = { grouping = true }) { Text(stringResource(R.string.quick_add_group)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { mine = true }) { Text(stringResource(R.string.quick_my_commands)) }
                    TextButton(onClick = { quick.board.resetToDefaults(); error = null }) { Text(stringResource(R.string.quick_reset)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_dismiss)) } },
    )
    if (picking) {
        CommandPicker(quick.board, onDismiss = { picking = false }) { error = quick.board.addToWheel(it.id); picking = false }
    }
    if (grouping) GroupDialog(quick.board, onDismiss = { grouping = false }) { name, ids ->
        quick.board.addGroup(name, ids).also { if (it == null) { grouping = false; error = null } }
    }
    if (mine) MyCommandsDialog(quick.board, onDismiss = { mine = false })
}

/** Chooses one command, from the built-in ones and the person's own. */
@Composable
fun CommandPicker(board: CommandBoard, onDismiss: () -> Unit, onPick: (Command) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_pick_command)) },
        text = {
            LazyColumn {
                items(board.allCommands(), key = { it.id }) { command ->
                    TextButton(onClick = { onPick(command) }, Modifier.fillMaxWidth()) {
                        Text(commandLabel(command), Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

@Composable
private fun GroupDialog(board: CommandBoard, onDismiss: () -> Unit, onSave: (String, List<String>) -> BoardError?) {
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<BoardError?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_add_group)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.lib_folder_name)) })
                error?.let { Text(boardErrorText(it), color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(board.allCommands(), key = { it.id }) { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = c.id in chosen,
                                onCheckedChange = { on -> chosen = if (on) chosen + c.id else chosen - c.id },
                            )
                            Text(commandLabel(c))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(name, board.allCommands().map { it.id }.filter { it in chosen }) }) { Text(stringResource(R.string.lib_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

/** The shortcuts and texts the person made: list, delete, and make a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyCommandsDialog(board: CommandBoard, onDismiss: () -> Unit) {
    val state by board.board.collectAsState()
    var label by remember { mutableStateOf("") }
    var keyIndex by remember { mutableStateOf(0) }
    var mods by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    var asText by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<BoardError?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_my_commands)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for (c in state.custom) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(commandLabel(c), Modifier.weight(1f))
                        IconButton(onClick = { board.deleteCustom(c.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.lib_delete))
                        }
                    }
                }
                OutlinedTextField(label, { label = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.lib_title)) })
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(!asText, { asText = false }, { Text(stringResource(R.string.quick_kind_shortcut)) })
                    FilterChip(asText, { asText = true }, { Text(stringResource(R.string.quick_kind_text)) })
                }
                if (asText) {
                    OutlinedTextField(text, { text = it; error = null }, minLines = 2, label = { Text(stringResource(R.string.lib_text)) })
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        for ((name, bit) in listOf(
                            "Ctrl" to HidModifierMask.LEFT_CTRL, "Shift" to HidModifierMask.LEFT_SHIFT,
                            "Alt" to HidModifierMask.LEFT_ALT, "Win" to HidModifierMask.LEFT_META,
                        )) {
                            FilterChip(mods and bit != 0, { mods = mods xor bit }, { Text(name) })
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text(ShortcutKeys.all[keyIndex].first) }
                        DropdownMenu(menu, { menu = false }) {
                            ShortcutKeys.all.forEachIndexed { i, (name, _) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { keyIndex = i; menu = false })
                            }
                        }
                    }
                }
                error?.let { Text(boardErrorText(it), color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    error = if (asText) board.addPhrase(label, text) else board.addShortcut(label, ShortcutKeys.all[keyIndex].second, mods)
                    if (error == null) { label = ""; text = "" }
                }) { Text(stringResource(R.string.lib_save)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_dismiss)) } },
    )
}

// ── Status and confirmation ──────────────────────────────────────────

@Composable
internal fun StatusLine(quick: QuickController) {
    val status by quick.status.collectAsState()
    val text: String? = when (val s = status) {
        QuickStatus.Idle -> null
        is QuickStatus.Sent -> stringResource(R.string.quick_sent, commandLabel(s.command))
        is QuickStatus.Ran -> stringResource(
            when (s.outcome) {
                com.omsingh.beckon.core.commands.RunOutcome.Done -> R.string.quick_ran_done
                com.omsingh.beckon.core.commands.RunOutcome.Failed -> R.string.quick_ran_failed
                com.omsingh.beckon.core.commands.RunOutcome.Rejected -> R.string.quick_ran_rejected
                com.omsingh.beckon.core.commands.RunOutcome.NoAnswer -> R.string.quick_ran_noanswer
            },
            commandLabel(s.command),
        )
        QuickStatus.NotConnected -> stringResource(R.string.lib_not_connected)
        QuickStatus.WindowsUnavailable -> stringResource(R.string.quick_windows_unavailable)
        is QuickStatus.Window -> stringResource(
            when (s.result) {
                com.omsingh.beckon.core.windows.FocusResult.Done -> R.string.quick_focus_done
                com.omsingh.beckon.core.windows.FocusResult.Failed -> R.string.quick_focus_failed
                com.omsingh.beckon.core.windows.FocusResult.Gone -> R.string.quick_focus_gone
                com.omsingh.beckon.core.windows.FocusResult.NoAnswer -> R.string.quick_focus_noanswer
            },
            s.window.program,
        )
        is QuickStatus.Text -> when (val r = s.result) {
            is TextSendResult.Confirmed -> when (r.result.outcome) {
                TextBlockResult.Outcome.DONE -> stringResource(R.string.lib_done, r.result.typed)
                TextBlockResult.Outcome.FAILED -> stringResource(R.string.lib_failed, r.result.typed, r.result.length)
                TextBlockResult.Outcome.CANCELLED -> stringResource(R.string.lib_cancelled, r.result.typed)
                TextBlockResult.Outcome.REJECTED -> stringResource(R.string.lib_rejected)
                TextBlockResult.Outcome.TIMEOUT -> stringResource(R.string.lib_timeout)
                TextBlockResult.Outcome.TOO_LONG -> stringResource(R.string.lib_too_long)
            }
            TextSendResult.Unconfirmed -> stringResource(R.string.lib_unconfirmed)
            TextSendResult.NotConnected -> stringResource(R.string.lib_not_connected)
            TextSendResult.NeedsWifi -> stringResource(R.string.lib_needs_wifi)
            TextSendResult.PcTooOld -> stringResource(R.string.lib_pc_too_old)
        }
    }
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        )
    }
}

@Composable
internal fun Confirm(quick: QuickController) {
    val command by quick.confirming.collectAsState()
    command?.let { c ->
        AlertDialog(
            onDismissRequest = quick::cancelConfirm,
            title = { Text(stringResource(R.string.quick_confirm_title, commandLabel(c))) },
            text = { Text(stringResource(R.string.quick_confirm_body)) },
            confirmButton = { TextButton(onClick = quick::confirm) { Text(stringResource(R.string.lib_confirm_send)) } },
            dismissButton = { TextButton(onClick = quick::cancelConfirm) { Text(stringResource(R.string.lib_cancel)) } },
        )
    }
}
