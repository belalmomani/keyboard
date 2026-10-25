package com.omsingh.beckon.ui.screens.remote

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.omsingh.beckon.R
import com.omsingh.beckon.core.commands.CommandPlanner
import com.omsingh.beckon.core.commands.ShortcutKeys
import com.omsingh.beckon.core.input.HidKeyCodes
import com.omsingh.beckon.core.input.HidModifierMask
import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.input.KeyboardSession
import com.omsingh.beckon.core.interfaces.FractalNode
import com.omsingh.beckon.core.interfaces.GestureLayout
import com.omsingh.beckon.core.interfaces.PuppetMapper
import com.omsingh.beckon.core.interfaces.Swipe8
import com.omsingh.beckon.core.interfaces.SwipeClassifier
import com.omsingh.beckon.core.interfaces.SwipeKey
import com.omsingh.beckon.core.interfaces.UsageLevel
import com.omsingh.beckon.ui.theme.Haptics
import com.omsingh.beckon.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The other ways to use the phone: the cube, the cockpit, swipe keys, the mouse puppet and the fractal keyboard. */
@Composable
fun InterfacesTab(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    haptics: Haptics,
    quick: QuickController?,
    library: LibraryController?,
    interfaces: InterfacesController?,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(R.string.if_cube, R.string.if_cockpit, R.string.if_gestures, R.string.if_puppet, R.string.if_fractal)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            labels.forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { section = index }, label = { Text(stringResource(label), maxLines = 1) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                0 -> CubeSection(state, actions, keyboard, haptics, quick, library)
                1 -> if (quick != null && interfaces != null) CockpitSection(state, quick, interfaces)
                2 -> GestureSection(actions, haptics)
                3 -> PuppetSection(state, actions, haptics)
                else -> if (interfaces != null) FractalSection(interfaces, actions, haptics)
            }
        }
    }
}

// ── Cube ─────────────────────────────────────────────────────────────

/**
 * Four faces (typing, numbers, commands, saved phrases) that turn into one another. The turn is a real
 * rotation of the face; with the system's animations switched off, or when the person prefers, the faces
 * simply change, so the cube never gets in the way of typing.
 */
@Composable
private fun CubeSection(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    haptics: Haptics,
    quick: QuickController?,
    library: LibraryController?,
) {
    var face by rememberSaveable { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(face) }
    val context = LocalContext.current
    val animated = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Exception) {
            true
        }
    }
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(face) {
        if (animated && shown != face) {
            rotation.animateTo(90f, tween(TURN_MS))
            shown = face
            rotation.snapTo(-90f)
            rotation.animateTo(0f, tween(TURN_MS))
        } else {
            shown = face
        }
    }
    val faces = listOf(R.string.cube_type, R.string.cube_numbers, R.string.cube_commands, R.string.cube_phrases)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            faces.forEachIndexed { index, label ->
                FilterChip(selected = face == index, onClick = { face = index }, label = { Text(stringResource(label), maxLines = 1) })
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer {
                    rotationY = rotation.value
                    cameraDistance = 14f * density
                },
        ) {
            when (shown) {
                0 -> KeyboardTab(state, actions, keyboard, haptics)
                1 -> NumbersFace(actions, haptics)
                2 -> if (quick != null) CommandsFace(quick)
                else -> if (library != null) PhrasesFace(library)
            }
        }
    }
}

private const val TURN_MS = 150

@Composable
private fun NumbersFace(actions: RemoteActions, haptics: Haptics) {
    val rows = listOf(
        listOf("7", "8", "9", "+"),
        listOf("4", "5", "6", "-"),
        listOf("1", "2", "3", "*"),
        listOf(".", "0", ",", "/"),
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for (label in row) {
                    OutlinedButton(
                        onClick = { haptics.key(); actions.send(InputEvent.TextInput(label)) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    ) { Text(label, style = MaterialTheme.typography.headlineSmall) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = { haptics.key(); actions.send(InputEvent.TextInput("=")) },
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) { Text("=", style = MaterialTheme.typography.headlineSmall) }
            Button(
                onClick = { haptics.key(); pressKey(actions, SwipeKey(HidKeyCodes.BACKSPACE)) },
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) { Text("⌫", style = MaterialTheme.typography.headlineSmall) }
            Button(
                onClick = { haptics.key(); pressKey(actions, SwipeKey(HidKeyCodes.ENTER)) },
                modifier = Modifier.weight(2f).fillMaxSize(),
            ) { Text("⏎", style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

private fun pressKey(actions: RemoteActions, key: SwipeKey) {
    val mods = CommandPlanner.modifiersOf(key.modifiers)
    actions.send(InputEvent.KeyPress(key.keyCode, mods))
    actions.send(InputEvent.KeyRelease(key.keyCode, mods))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandsFace(quick: QuickController) {
    val board by quick.board.board.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // Re-read when the person's own commands change.
            for (command in remember(board) { quick.board.allCommands() }) {
                OutlinedButton(onClick = { quick.run(command) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(commandLabel(command), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        StatusLine(quick)
    }
    Confirm(quick)
}

@Composable
private fun PhrasesFace(library: LibraryController) {
    val data by library.phrases.collectAsState()
    val phrases = remember(data) { data.phrases.sortedWith(compareByDescending<com.omsingh.beckon.core.library.Phrase> { it.favorite }.thenBy { it.title }) }
    if (phrases.isEmpty()) {
        Text(stringResource(R.string.lib_empty_phrases), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(phrases, key = { it.id }) { phrase ->
                Surface(
                    onClick = { library.requestSend(phrase.text, phrase.id) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(phrase.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            phrase.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    SendStatus(library)
}

// ── Cockpit ──────────────────────────────────────────────────────────

/** The PC at a glance: who it is, what is in front, the controls used most, and the open windows. */
@Composable
private fun CockpitSection(state: RemoteUiState, quick: QuickController, interfaces: InterfacesController) {
    DisposableEffect(interfaces) {
        interfaces.watchFrontWindow(true)
        onDispose { interfaces.watchFrontWindow(false) }
    }
    LaunchedEffect(interfaces) {
        while (true) {
            interfaces.refreshWindows()
            delay(COCKPIT_REFRESH_MS)
        }
    }
    val front by interfaces.activeWindow.collectAsState()
    val windows by interfaces.windows.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Panel(stringResource(R.string.cockpit_pc)) {
            val connection = state.connection
            if (connection is com.omsingh.beckon.core.input.ConnectionState.Connected) {
                Text(connection.deviceName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        state.host.os.name.lowercase().replaceFirstChar { it.uppercase() },
                        connection.latencyMs?.let { "$it ms" },
                        connection.transport.name.lowercase().replaceFirstChar { it.uppercase() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.lib_not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Panel(stringResource(R.string.cockpit_front)) {
            val window = front
            when {
                !interfaces.canSeeFrontWindow -> Text(stringResource(R.string.cockpit_front_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                window == null -> Text(stringResource(R.string.cockpit_front_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(window.process.ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium)
                    if (window.title.isNotEmpty()) {
                        Text(window.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Panel(stringResource(R.string.cockpit_controls)) {
            CockpitButtons(quick, listOf("prev", "play_pause", "next"))
            CockpitButtons(quick, listOf("vol_down", "mute", "vol_up"))
            CockpitButtons(quick, listOf("show_desktop", "task_view", "screenshot", "lock"))
        }
        Panel(stringResource(R.string.cockpit_windows)) {
            val list = windows
            when {
                !interfaces.canSwitchWindows -> Text(stringResource(R.string.quick_windows_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.isNullOrEmpty() -> Text(stringResource(R.string.quick_windows_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> for (window in list.orEmpty().take(COCKPIT_WINDOWS)) {
                    TextButton(onClick = { quick.focus(window) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            window.program + if (window.foreground) "  ●" else "",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        StatusLine(quick)
    }
    Confirm(quick)
}

private const val COCKPIT_REFRESH_MS = 3_000L
private const val COCKPIT_WINDOWS = 6

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun CockpitButtons(quick: QuickController, ids: List<String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        for (id in ids) {
            val command = quick.board.command(id) ?: continue
            OutlinedButton(onClick = { quick.run(command) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(commandLabel(command), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Swipe keys ───────────────────────────────────────────────────────

private val SwipeArrows = mapOf(
    Swipe8.RIGHT to "→", Swipe8.DOWN_RIGHT to "↘", Swipe8.DOWN to "↓", Swipe8.DOWN_LEFT to "↙",
    Swipe8.LEFT to "←", Swipe8.UP_LEFT to "↖", Swipe8.UP to "↑", Swipe8.UP_RIGHT to "↗",
)

private fun keyName(key: SwipeKey): String {
    val mods = buildList {
        if (key.modifiers and HidModifierMask.LEFT_CTRL != 0) add("Ctrl")
        if (key.modifiers and HidModifierMask.LEFT_SHIFT != 0) add("Shift")
        if (key.modifiers and HidModifierMask.LEFT_ALT != 0) add("Alt")
        if (key.modifiers and HidModifierMask.LEFT_META != 0) add("Win")
    }
    return (mods + (ShortcutKeys.nameOf(key.keyCode) ?: "?")).joinToString("+")
}

/** A surface that turns swipes into keys: a way to move and edit with one thumb and no keyboard in the way. */
@Composable
private fun GestureSection(actions: RemoteActions, haptics: Haptics) {
    var layoutIndex by rememberSaveable { mutableIntStateOf(0) }
    val layout = GestureLayout.entries[layoutIndex]
    var last by remember { mutableStateOf<String?>(null) }
    val minPx = with(LocalDensity.current) { 28.dp.toPx() }
    val names = listOf(R.string.gest_arrows, R.string.gest_editing, R.string.gest_browser)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            names.forEachIndexed { index, label ->
                FilterChip(selected = layoutIndex == index, onClick = { layoutIndex = index }, label = { Text(stringResource(label), maxLines = 1) })
            }
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(layout) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            val swipe = SwipeClassifier.classify(up.position.x - down.position.x, up.position.y - down.position.y, minPx)
                            val key = layout.keyFor(swipe)
                            if (key != null) {
                                haptics.key()
                                pressKey(actions, key)
                                last = keyName(key)
                            }
                        }
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    last ?: stringResource(R.string.gest_hint),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        }
        Column {
            for (swipe in Swipe8.entries) {
                val key = layout.swipes[swipe] ?: continue
                Text("${SwipeArrows[swipe]}  ${keyName(key)}", style = MaterialTheme.typography.bodySmall)
            }
            layout.tap?.let { Text(stringResource(R.string.gest_tap, keyName(it)), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ── Mouse puppet ─────────────────────────────────────────────────────

/**
 * A handle to pull: the farther it is from the centre the faster the pointer moves, and letting go stops
 * it. Clicks are the buttons below, as on the pad.
 */
@Composable
private fun PuppetSection(state: RemoteUiState, actions: RemoteActions, haptics: Haptics) {
    val mapper = remember { PuppetMapper() }
    var pull by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    val sensitivity by rememberUpdatedState(state.preferences.sensitivity)
    val density = LocalDensity.current
    val travelPx = with(density) { PUPPET_TRAVEL.toPx() }

    LaunchedEffect(active) {
        while (active) {
            val (dx, dy) = mapper.step(pull.x, pull.y, sensitivity)
            if (dx != 0f || dy != 0f) actions.send(InputEvent.MouseMove(dx, dy))
            delay(PUPPET_TICK_MS)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.puppet_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .size(PUPPET_AREA)
                    .pointerInput(travelPx) {
                        awaitEachGesture {
                            awaitFirstDown()
                            active = true
                            haptics.tick()
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    val moved = change.position - centre
                                    val (x, y) = mapper.clamp(moved.x / travelPx, moved.y / travelPx)
                                    pull = Offset(x, y)
                                }
                            } while (event.changes.any { it.pressed })
                            active = false
                            pull = Offset.Zero
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(PUPPET_HANDLE)
                            .offset { IntOffset((pull.x * travelPx).roundToInt(), (pull.y * travelPx).roundToInt()) },
                    ) {}
                }
            }
        }
        ClickButtons(onEvent = actions::send, haptics = haptics)
    }
}

private val PUPPET_AREA = 280.dp
private val PUPPET_HANDLE = 64.dp
private val PUPPET_TRAVEL = 108.dp
private const val PUPPET_TICK_MS = 16L

// ── Fractal keyboard ─────────────────────────────────────────────────

/**
 * A keyboard with a few big keys. Tap a group, then a letter. The letters used most come to the first level
 * as the keyboard is used, so it grows with the person.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FractalSection(interfaces: InterfacesController, actions: RemoteActions, haptics: Haptics) {
    var arabic by rememberSaveable { mutableStateOf(false) }
    val keyboard = if (arabic) interfaces.arabic else interfaces.latin
    var stack by remember(arabic) { mutableStateOf(listOf(keyboard.top())) }
    var typed by remember(arabic) { mutableStateOf("") }
    val levelText = stringResource(
        when (keyboard.level) {
            UsageLevel.BEGINNER -> R.string.fractal_level_beginner
            UsageLevel.LEARNING -> R.string.fractal_level_learning
            UsageLevel.FAMILIAR -> R.string.fractal_level_familiar
        },
    )
    DisposableEffect(interfaces) { onDispose { interfaces.save() } }

    fun choose(node: FractalNode) {
        haptics.key()
        if (node.isLeaf) {
            actions.send(InputEvent.TextInput(node.char.toString()))
            interfaces.recordLetter(keyboard, node.char)
            typed = (typed + node.char).takeLast(24)
            stack = listOf(keyboard.top())
        } else {
            stack = stack + listOf(keyboard.open(node))
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(typed.ifEmpty { "…" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(levelText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                maxItemsInEachRow = 3,
            ) {
                for (node in stack.last()) {
                    Button(onClick = { choose(node) }, modifier = Modifier.width(104.dp).height(72.dp)) {
                        Text(node.label, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = { if (stack.size > 1) stack = stack.dropLast(1) },
                enabled = stack.size > 1,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.fractal_back), maxLines = 1) }
            OutlinedButton(
                onClick = { haptics.key(); actions.send(InputEvent.TextInput(" ")); typed = (typed + " ").takeLast(24) },
                modifier = Modifier.weight(1f),
            ) { Text("␣") }
            OutlinedButton(
                onClick = { haptics.key(); pressKey(actions, SwipeKey(HidKeyCodes.BACKSPACE)); typed = typed.dropLast(1) },
                modifier = Modifier.weight(1f),
            ) { Text("⌫") }
            OutlinedButton(
                onClick = { haptics.key(); pressKey(actions, SwipeKey(HidKeyCodes.ENTER)); typed = "" },
                modifier = Modifier.weight(1f),
            ) { Text("⏎") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = !arabic, onClick = { arabic = false }, label = { Text(stringResource(R.string.fractal_latin)) })
            FilterChip(selected = arabic, onClick = { arabic = true }, label = { Text(stringResource(R.string.fractal_arabic)) })
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(onClick = { interfaces.forgetUsage(); stack = listOf(keyboard.top()) }) {
                Text(stringResource(R.string.fractal_forget), maxLines = 1)
            }
        }
    }
}
