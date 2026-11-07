package com.omsingh.beckon.ui.screens.remote

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omsingh.beckon.R
import com.omsingh.beckon.connection.Notice
import com.omsingh.beckon.core.host.HostOs
import com.omsingh.beckon.core.input.ConnectionState
import com.omsingh.beckon.core.input.KeyboardSession
import com.omsingh.beckon.ui.components.HeroIllustration
import com.omsingh.beckon.ui.components.Scene
import com.omsingh.beckon.ui.components.StatusDot
import com.omsingh.beckon.ui.components.failureBody
import com.omsingh.beckon.ui.components.failureTitle
import com.omsingh.beckon.ui.theme.Spacing
import com.omsingh.beckon.ui.theme.BeckonTheme
import com.omsingh.beckon.ui.theme.rememberHaptics

enum class RemoteMode(val icon: ImageVector, val label: Int) {
    PAD(Icons.Rounded.Mouse, R.string.remote_mode_pad),
    KEYS(Icons.Rounded.Keyboard, R.string.remote_mode_keys),
    MEDIA(Icons.Rounded.PlayCircle, R.string.remote_mode_media),
    QUICK(Icons.Rounded.Bolt, R.string.remote_mode_quick),
    LIBRARY(Icons.Rounded.Bookmarks, R.string.remote_mode_library),
    BOARDS(Icons.Rounded.Dashboard, R.string.remote_mode_boards),
    SCREEN(Icons.Rounded.Computer, R.string.remote_mode_screen),
    ;

    /** The two used most stay on the bar; the rest wait in the tools sheet. */
    val onBar: Boolean get() = this == PAD || this == KEYS

    companion object {
        /** What the tools sheet offers, in order. */
        val tools: List<RemoteMode> = values().filterNot { it.onBar }
    }
}

/** The remote control wired to its view model, with the brief messages it shows. */
@Composable
fun RemoteRoute(
    viewModel: RemoteViewModel,
    onGoToDevices: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkPhone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val sent = stringResource(R.string.notice_clipboard_sent)
    val empty = stringResource(R.string.notice_clipboard_empty)
    val disabled = stringResource(R.string.notice_clipboard_disabled)
    val fromPc = stringResource(R.string.notice_clipboard_from_pc)
    val resources = androidx.compose.ui.platform.LocalContext.current.resources

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val text = when (notice) {
                Notice.ClipboardSent -> sent
                Notice.ClipboardEmpty -> empty
                Notice.ClipboardDisabled -> disabled
                Notice.ClipboardFromPc -> fromPc
                is Notice.UntypeableText -> resources.getQuantityString(R.plurals.notice_untypeable, notice.count, notice.count)
            }
            snackbar.showSnackbar(text)
        }
    }

    RemoteScreen(
        state = state,
        actions = viewModel,
        keyboard = viewModel.keyboard,
        library = viewModel.library,
        quick = viewModel.quick,
        interfaces = viewModel.interfaces,
        screen = viewModel.screen,
        snackbar = snackbar,
        onGoToDevices = onGoToDevices,
        onLinkPhone = onLinkPhone,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    onGoToDevices: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkPhone: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    library: LibraryController? = null,
    quick: QuickController? = null,
    interfaces: InterfacesController? = null,
    screen: ScreenController? = null,
) {
    var mode by rememberSaveable { mutableStateOf(RemoteMode.PAD) }
    val keyboardUi = rememberKeyboardUiState()
    var details by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var cardOpen by rememberSaveable { mutableStateOf(true) }
    var gestures by remember { mutableStateOf(false) }
    val haptics = rememberHaptics(state.preferences.hapticFeedback)

    val usable = state.connection is ConnectionState.Connected || state.connection is ConnectionState.Reconnecting

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Without a connection there is nothing to control: show why, and the way to fix it,
        // instead of the chrome of a remote that cannot be used.
        if (!usable) {
            Box(Modifier.padding(padding).fillMaxSize().statusBarsPadding()) {
                NotConnected(state.connection, onGoToDevices)
            }
            return@Scaffold
        }
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            val landscape = maxWidth > maxHeight
            // Turning the phone changes which keyboard fits it, so a choice made before the turn is forgotten.
            // This is here, not in the Keys tab, which is rebuilt every time it is opened.
            LaunchedEffect(landscape) { keyboardUi.chosenPcLayout = null }
            val content: @Composable (Modifier) -> Unit = { contentModifier ->
                RemoteContent(
                    state = state,
                    actions = actions,
                    keyboard = keyboard,
                    keyboardUi = keyboardUi,
                    mode = mode,
                    library = library,
                    quick = quick,
                    interfaces = interfaces,
                    screen = screen,
                    haptics = haptics,
                    modifier = contentModifier,
                )
            }

            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .padding(vertical = Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        ConnectionChip(state.connection, compact = true, onClick = { details = true })
                        ModeRail(mode, onSelect = { mode = it }, onMore = { toolsOpen = true })
                    }
                    content(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(end = Spacing.screen, top = Spacing.md, bottom = Spacing.md),
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.screen),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConnectionBar(
                            state.connection,
                            open = cardOpen,
                            onToggle = { cardOpen = !cardOpen },
                            onDetails = { details = true },
                            modifier = Modifier.weight(1f),
                        )
                        if (mode == RemoteMode.PAD && state.connected) {
                            IconButton(onClick = { gestures = true }) {
                                Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = stringResource(R.string.pad_gestures_title))
                            }
                        }
                    }
                    ModeSwitcher(mode, onSelect = { mode = it }, onMore = { toolsOpen = true })
                    content(Modifier.weight(1f).fillMaxWidth())
                    ToolsHandle(onOpen = { toolsOpen = true })
                }
            }
        }
    }

    if (toolsOpen) {
        ToolsSheet(
            current = mode,
            onPick = {
                mode = it
                toolsOpen = false
            },
            onLinkPhone = {
                toolsOpen = false
                onLinkPhone()
            },
            onDismiss = { toolsOpen = false },
        )
    }
    if (details) ConnectionDetailsSheet(state, onDisconnect = { details = false; actions.disconnect() }, onSwitch = { details = false; onGoToDevices() }, onDismiss = { details = false })
    if (gestures) GestureHelpSheet(onDismiss = { gestures = false })
}

@Composable
private fun RemoteContent(
    state: RemoteUiState,
    actions: RemoteActions,
    keyboard: KeyboardSession,
    keyboardUi: KeyboardUiState,
    mode: RemoteMode,
    library: LibraryController?,
    quick: QuickController?,
    interfaces: InterfacesController?,
    screen: ScreenController?,
    haptics: com.omsingh.beckon.ui.theme.Haptics,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // The Keys tab shows its modifiers itself. Anywhere else, one that is still on has to be
            // seen, and it takes its own room rather than covering a control.
            if (mode != RemoteMode.KEYS) {
                ModifierHud(state.host, keyboard, Modifier.align(Alignment.CenterHorizontally).padding(bottom = Spacing.sm))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "remoteMode",
                ) { current ->
                    when (current) {
                        RemoteMode.PAD -> TouchpadTab(state, actions, haptics)
                        RemoteMode.KEYS -> KeyboardTab(state, actions, keyboard, haptics, ui = keyboardUi)
                        RemoteMode.MEDIA -> MediaTab(state, actions, haptics)
                        RemoteMode.QUICK -> QuickTab(quick)
                        RemoteMode.BOARDS -> InterfacesTab(state, actions, keyboard, haptics, quick, library, interfaces)
                        RemoteMode.LIBRARY -> LibraryTab(library, onCopyToPhone = { library?.copyToPhone(it) })
                        RemoteMode.SCREEN -> ScreenTab(screen, connected = state.connection is ConnectionState.Connected, actions = actions, keyboard = keyboard)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.connection is ConnectionState.Reconnecting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            ReconnectingOverlay()
        }
    }
}

/** Covers the controls while the link is rebuilt, and swallows touches so nothing is sent into the void. */
@Composable
private fun ReconnectingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), MaterialTheme.shapes.extraLarge)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.remote_reconnecting), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun NotConnected(connection: ConnectionState, onGoToDevices: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        HeroIllustration(Scene.PHONE_AND_PC, Modifier.padding(horizontal = Spacing.xl))
        when (connection) {
            is ConnectionState.Failed -> {
                Text(failureTitle(connection.reason, connection.deviceName), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    failureBody(connection.reason, connection.deviceName, connection.hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            is ConnectionState.Connecting -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.devices_connecting_to, connection.deviceName), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            else -> {
                Text(stringResource(R.string.remote_not_connected_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.remote_not_connected_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Button(onClick = onGoToDevices) { Text(stringResource(R.string.remote_go_to_devices)) }
    }
}

// ── Chrome ───────────────────────────────────────────────────────────

/**
 * The bar at the top: Pad and Keys, which are used most, and a third place that shows the tool in use
 * (or "Tools") and opens the sheet with the rest.
 */
@Composable
private fun ModeSwitcher(mode: RemoteMode, onSelect: (RemoteMode) -> Unit, onMore: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            for (item in RemoteMode.values().filter { it.onBar }) {
                BarItem(item.icon, stringResource(item.label), mode == item, { onSelect(item) }, Modifier.weight(1f))
            }
            val tool = mode.takeUnless { it.onBar }
            BarItem(
                icon = tool?.icon ?: Icons.Rounded.Apps,
                label = stringResource(tool?.label ?: R.string.remote_tools),
                selected = tool != null,
                onClick = onMore,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { role = Role.Tab; this.selected = selected },
    ) {
        Column(
            Modifier.padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** The vertical equivalent for landscape, where height is precious: Pad, Keys, the tool in use, and More. */
@Composable
private fun ModeRail(mode: RemoteMode, onSelect: (RemoteMode) -> Unit, onMore: () -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (item in RemoteMode.values().filter { it.onBar }) RailItem(item.icon, stringResource(item.label), mode == item) { onSelect(item) }
        val tool = mode.takeUnless { it.onBar }
        RailItem(tool?.icon ?: Icons.Rounded.Apps, stringResource(tool?.label ?: R.string.remote_tools), tool != null, onMore)
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .width(72.dp)
            .semantics { role = Role.Tab; this.selected = selected },
    ) {
        Column(
            Modifier.padding(vertical = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** A thin strip at the bottom with a small arrow: tap it, or swipe up, for the other tools. */
@Composable
private fun ToolsHandle(onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { },
                onDragStopped = { velocity -> if (velocity < -SWIPE_UP_VELOCITY) onOpen() },
            ),
    ) {
        Row(
            Modifier.padding(vertical = Spacing.xs),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.remote_tools), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private const val SWIPE_UP_VELOCITY = 300f

/** Every other tool as a big button. Picking one closes the sheet; the arrow at the top does too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsSheet(current: RemoteMode, onPick: (RemoteMode) -> Unit, onLinkPhone: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_tools), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.remote_tools_hide))
                }
            }
            val items = RemoteMode.tools.map { Triple(it.icon, it.label, it) }
            for (row in items.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    for ((icon, label, target) in row) {
                        ToolTile(icon, stringResource(label), current == target, { onPick(target) }, Modifier.weight(1f))
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ToolTile(Icons.Rounded.PhoneAndroid, stringResource(R.string.remote_tools_link), false, onLinkPhone, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolTile(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = Spacing.lg, horizontal = Spacing.xs).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * The PC's card, which can be folded to a thin strip (with a small arrow) to give the controls more room.
 * Tapping the card opens the connection's details.
 */
@Composable
private fun ConnectionBar(
    connection: ConnectionState,
    open: Boolean,
    onToggle: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (open) {
            ConnectionCard(connection, onClick = onDetails, modifier = Modifier.weight(1f))
        } else {
            ConnectionChip(connection, compact = false, onClick = onDetails, modifier = Modifier.weight(1f))
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (open) R.string.remote_connection_fold else R.string.remote_connection_unfold),
            )
        }
    }
}

/** The PC this phone is controlling, always in view at the top: its name, how the link is, and a way into the details. */
@Composable
private fun ConnectionCard(connection: ConnectionState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extended = BeckonTheme.extended
    val (color, name, status) = when (connection) {
        is ConnectionState.Connected -> Triple(
            if (connection.unstable) extended.warning else extended.success,
            connection.deviceName,
            listOfNotNull(
                stringResource(R.string.devices_connected_short),
                connection.latencyMs?.let { stringResource(R.string.devices_latency, it) },
            ).joinToString(" · "),
        )
        is ConnectionState.Reconnecting -> Triple(extended.warning, connection.deviceName, stringResource(R.string.remote_reconnecting))
        is ConnectionState.Connecting -> Triple(extended.warning, connection.deviceName, stringResource(R.string.remote_status_connecting))
        is ConnectionState.Failed -> Triple(MaterialTheme.colorScheme.error, connection.deviceName.orEmpty(), "")
        ConnectionState.Disconnected -> Triple(MaterialTheme.colorScheme.outline, stringResource(R.string.remote_not_connected_title), "")
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), androidx.compose.ui.graphics.Color.Transparent),
                    ),
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .border(BorderStroke(2.dp, color), CircleShape)
                    .padding(Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (status.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color)
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Who the phone is connected to, always in view, and tappable for the details. */
@Composable
private fun ConnectionChip(
    connection: ConnectionState,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extended = BeckonTheme.extended
    val (color, name, detail) = when (connection) {
        is ConnectionState.Connected -> Triple(
            if (connection.unstable) extended.warning else extended.success,
            connection.deviceName,
            connection.latencyMs?.let { stringResource(R.string.devices_latency, it) },
        )
        is ConnectionState.Reconnecting -> Triple(extended.warning, connection.deviceName, null)
        is ConnectionState.Connecting -> Triple(extended.warning, connection.deviceName, null)
        is ConnectionState.Failed -> Triple(MaterialTheme.colorScheme.error, connection.deviceName.orEmpty(), null)
        ConnectionState.Disconnected -> Triple(MaterialTheme.colorScheme.outline, stringResource(R.string.remote_not_connected_title), null)
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color)
            if (!compact) {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(Icons.Rounded.Wifi, contentDescription = name, modifier = Modifier.width(18.dp))
            }
        }
    }
}

// ── Sheets ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDetailsSheet(
    state: RemoteUiState,
    onDisconnect: () -> Unit,
    onSwitch: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val connection = state.connection
            val name = when (connection) {
                is ConnectionState.Connected -> connection.deviceName
                is ConnectionState.Reconnecting -> connection.deviceName
                is ConnectionState.Connecting -> connection.deviceName
                else -> stringResource(R.string.generic_your_pc)
            }
            Text(name, style = MaterialTheme.typography.headlineSmall)
            val os = when (state.host.os) {
                HostOs.WINDOWS -> stringResource(R.string.remote_os_windows)
                HostOs.MACOS -> stringResource(R.string.remote_os_macos)
                HostOs.LINUX -> stringResource(R.string.remote_os_linux)
                HostOs.UNKNOWN -> stringResource(R.string.remote_os_unknown)
            }
            val via = when (state.transport) {
                ConnectionState.Transport.BLUETOOTH -> stringResource(R.string.devices_via_bluetooth)
                else -> stringResource(R.string.devices_via_wifi)
            }
            val latency = (connection as? ConnectionState.Connected)?.latencyMs?.let { stringResource(R.string.devices_latency, it) }
            Text(
                listOfNotNull(via, latency, stringResource(R.string.remote_connection_os, os)).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedButton(onClick = onSwitch, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.remote_switch_device)) }
                Button(onClick = onDisconnect, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.devices_disconnect)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.pad_gestures_title), style = MaterialTheme.typography.headlineSmall)
            for (line in listOf(
                R.string.pad_gesture_move,
                R.string.pad_gesture_click,
                R.string.pad_gesture_drag,
                R.string.pad_gesture_scroll,
                R.string.pad_gesture_right,
                R.string.pad_gesture_middle,
                R.string.pad_gesture_buttons,
            )) {
                Text(stringResource(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
