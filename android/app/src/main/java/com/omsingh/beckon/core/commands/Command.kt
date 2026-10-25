package com.omsingh.beckon.core.commands

import com.omsingh.beckon.core.host.Chord
import com.omsingh.beckon.core.host.HostProfile
import com.omsingh.beckon.core.host.ShortcutId
import com.omsingh.beckon.core.input.HidKeyCodes
import com.omsingh.beckon.core.input.HidModifierMask
import com.omsingh.beckon.core.input.InputEvent

/** What a command does on the PC. */
sealed interface CommandAction {
    /** A shortcut every desktop has, as that PC's operating system writes it (Ctrl+C, or Command+C). */
    data class Shortcut(val id: ShortcutId) : CommandAction

    /** One key with modifiers ([HidModifierMask] bits), exactly as given. */
    data class Keys(val keyCode: Int, val modifiers: Int = 0) : CommandAction
    data class Media(val action: InputEvent.MediaAction) : CommandAction
    data class Volume(val direction: InputEvent.VolumeDirection) : CommandAction
    data class System(val action: InputEvent.SystemAction) : CommandAction
    data object Lock : CommandAction

    /** Types a saved text. Long or multi-line texts go through the confirmed text path. */
    data class Phrase(val text: String) : CommandAction
}

/**
 * Something the person can run from the wheel, the leader mode or a rhythm.
 *
 * [label] is the person's own name for a command they made; a built-in command has none, and is named
 * by its [id] in the app's own strings.
 */
data class Command(val id: String, val label: String?, val action: CommandAction) {
    val builtIn: Boolean get() = label == null
    val dangerous: Boolean get() = Danger.isDangerous(action)
}

/** Which commands must be confirmed before they run, because they cannot be taken back or can do harm in the wrong window. */
object Danger {
    fun isDangerous(action: CommandAction): Boolean = when (action) {
        CommandAction.Lock -> true
        is CommandAction.Keys -> isDangerousKeys(action.keyCode, action.modifiers)
        // Every line break presses Enter in whatever is in front: in a terminal that runs the line.
        is CommandAction.Phrase -> action.text.any { it == '\n' || it == '\r' }
        is CommandAction.Shortcut, is CommandAction.Media, is CommandAction.Volume, is CommandAction.System -> false
    }

    private fun isDangerousKeys(keyCode: Int, modifiers: Int): Boolean {
        val alt = modifiers and (HidModifierMask.LEFT_ALT or HidModifierMask.RIGHT_ALT) != 0
        return keyCode == HidKeyCodes.DELETE ||
            (keyCode == HidKeyCodes.F4 && alt) ||
            // Closes a window or a program that may hold unsaved work.
            (keyCode == HidKeyCodes.Q && modifiers and (HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_META) != 0)
    }
}

/**
 * What running a command takes: key and media events, or typing a text (which the PC confirms).
 *
 * [Events.call] is the same command as the PC can run and report on (Wi-Fi to a PC that offers it); the
 * [Events.events] are for when it cannot, such as over Bluetooth.
 */
sealed interface CommandPlan {
    data class Events(val events: List<InputEvent>, val call: RemoteCall? = null) : CommandPlan
    data class TypeText(val text: String) : CommandPlan
}

object CommandPlanner {
    fun plan(command: Command, host: HostProfile): CommandPlan = when (val action = command.action) {
        is CommandAction.Shortcut -> chord(host.chord(action.id))
        is CommandAction.Keys -> chord(Chord(action.keyCode, action.modifiers))
        is CommandAction.Media -> CommandPlan.Events(
            listOf(InputEvent.MediaCommand(action.action)),
            RemoteCall(RemoteCall.MEDIA, 0, action.action.ordinal),
        )
        is CommandAction.Volume -> CommandPlan.Events(
            listOf(InputEvent.VolumeCommand(action.direction)),
            RemoteCall(RemoteCall.VOLUME, 0, action.direction.ordinal),
        )
        is CommandAction.System -> CommandPlan.Events(
            listOf(InputEvent.LaunchAction(action.action)),
            RemoteCall(RemoteCall.SYSTEM, 0, action.action.ordinal),
        )
        CommandAction.Lock -> CommandPlan.Events(listOf(InputEvent.LockScreen), RemoteCall(RemoteCall.LOCK, 0, 0))
        is CommandAction.Phrase -> CommandPlan.TypeText(action.text)
    }

    private fun chord(chord: Chord): CommandPlan {
        val mods = modifiersOf(chord.modifiers)
        return CommandPlan.Events(
            listOf(InputEvent.KeyPress(chord.keyCode, mods), InputEvent.KeyRelease(chord.keyCode, mods)),
            RemoteCall(RemoteCall.KEYS, chord.modifiers and 0xFF, chord.keyCode),
        )
    }

    fun modifiersOf(mask: Int) = InputEvent.Modifiers(
        leftCtrl = mask and HidModifierMask.LEFT_CTRL != 0,
        leftShift = mask and HidModifierMask.LEFT_SHIFT != 0,
        leftAlt = mask and HidModifierMask.LEFT_ALT != 0,
        leftMeta = mask and HidModifierMask.LEFT_META != 0,
        rightCtrl = mask and HidModifierMask.RIGHT_CTRL != 0,
        rightShift = mask and HidModifierMask.RIGHT_SHIFT != 0,
        rightAlt = mask and HidModifierMask.RIGHT_ALT != 0,
        rightMeta = mask and HidModifierMask.RIGHT_META != 0,
    )
}

/** The commands that come with the app. Their names are in the app's strings, as `cmd_<id>`. */
object CommandCatalog {
    private val ctrl = HidModifierMask.LEFT_CTRL
    private val shift = HidModifierMask.LEFT_SHIFT
    private val alt = HidModifierMask.LEFT_ALT
    private val meta = HidModifierMask.LEFT_META

    private fun c(id: String, action: CommandAction) = Command(id, null, action)

    val builtIn: List<Command> = listOf(
        c("copy", CommandAction.Shortcut(ShortcutId.COPY)),
        c("paste", CommandAction.Shortcut(ShortcutId.PASTE)),
        c("cut", CommandAction.Shortcut(ShortcutId.CUT)),
        c("undo", CommandAction.Shortcut(ShortcutId.UNDO)),
        c("redo", CommandAction.Shortcut(ShortcutId.REDO)),
        c("select_all", CommandAction.Shortcut(ShortcutId.SELECT_ALL)),
        c("find", CommandAction.Shortcut(ShortcutId.FIND)),
        c("save", CommandAction.Shortcut(ShortcutId.SAVE)),
        c("new_tab", CommandAction.Shortcut(ShortcutId.NEW_TAB)),
        c("close_tab", CommandAction.Shortcut(ShortcutId.CLOSE_TAB)),
        c("refresh", CommandAction.Shortcut(ShortcutId.REFRESH)),
        c("switch_app", CommandAction.Shortcut(ShortcutId.SWITCH_APP)),
        c("tab_next", CommandAction.Keys(HidKeyCodes.TAB, ctrl)),
        c("tab_prev", CommandAction.Keys(HidKeyCodes.TAB, ctrl or shift)),
        c("back", CommandAction.Keys(HidKeyCodes.LEFT, alt)),
        c("forward", CommandAction.Keys(HidKeyCodes.RIGHT, alt)),
        c("esc", CommandAction.Keys(HidKeyCodes.ESC)),
        c("enter", CommandAction.Keys(HidKeyCodes.ENTER)),
        c("task_view", CommandAction.System(InputEvent.SystemAction.TASK_VIEW)),
        c("show_desktop", CommandAction.System(InputEvent.SystemAction.SHOW_DESKTOP)),
        c("screenshot", CommandAction.System(InputEvent.SystemAction.SCREENSHOT)),
        c("task_manager", CommandAction.System(InputEvent.SystemAction.TASK_MANAGER)),
        c("file_manager", CommandAction.System(InputEvent.SystemAction.FILE_MANAGER)),
        c("desktop_prev", CommandAction.Keys(HidKeyCodes.LEFT, meta or ctrl)),
        c("desktop_next", CommandAction.Keys(HidKeyCodes.RIGHT, meta or ctrl)),
        c("close_window", CommandAction.Keys(HidKeyCodes.F4, alt)),
        c("lock", CommandAction.Lock),
        c("play_pause", CommandAction.Media(InputEvent.MediaAction.PLAY_PAUSE)),
        c("next", CommandAction.Media(InputEvent.MediaAction.NEXT)),
        c("prev", CommandAction.Media(InputEvent.MediaAction.PREV)),
        c("vol_up", CommandAction.Volume(InputEvent.VolumeDirection.UP)),
        c("vol_down", CommandAction.Volume(InputEvent.VolumeDirection.DOWN)),
        c("mute", CommandAction.Volume(InputEvent.VolumeDirection.MUTE)),
    )

    private val byId = builtIn.associateBy { it.id }

    fun find(id: String): Command? = byId[id]
}
