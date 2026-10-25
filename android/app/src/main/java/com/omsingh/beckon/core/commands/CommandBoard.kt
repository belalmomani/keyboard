package com.omsingh.beckon.core.commands

import com.omsingh.beckon.core.input.HidKeyCodes
import com.omsingh.beckon.core.input.HidModifierMask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One place on the command wheel: a command, or a group that opens a wheel of its own (one level deep). */
sealed interface WheelSlot {
    data class Item(val commandId: String) : WheelSlot
    data class Group(val name: String, val commandIds: List<String>) : WheelSlot
}

/** After the leader key, these keys in order run [commandId]. */
data class LeaderBinding(val keys: String, val commandId: String)

/** This pattern of short (`.`) and long (`-`) taps runs [commandId]. */
data class RhythmBinding(val pattern: String, val commandId: String)

/** Everything the person set up for quick control. */
data class Board(
    val custom: List<Command> = emptyList(),
    val wheel: List<WheelSlot> = DefaultBoard.wheel,
    val leader: List<LeaderBinding> = DefaultBoard.leader,
    val rhythm: List<RhythmBinding> = DefaultBoard.rhythm,
)

/** What the app starts with. */
object DefaultBoard {
    val wheel: List<WheelSlot> = listOf(
        WheelSlot.Item("copy"),
        WheelSlot.Item("paste"),
        WheelSlot.Item("undo"),
        WheelSlot.Item("save"),
        WheelSlot.Item("screenshot"),
        WheelSlot.Item("switch_app"),
        WheelSlot.Group("media", listOf("play_pause", "prev", "next", "mute", "vol_down", "vol_up")),
        WheelSlot.Group("windows", listOf("show_desktop", "task_view", "desktop_prev", "desktop_next", "close_window")),
    )
    val leader: List<LeaderBinding> = listOf(
        LeaderBinding("wd", "show_desktop"),
        LeaderBinding("wt", "task_view"),
        LeaderBinding("wc", "close_window"),
        LeaderBinding("mp", "play_pause"),
        LeaderBinding("mn", "next"),
        LeaderBinding("mb", "prev"),
        LeaderBinding("mm", "mute"),
        LeaderBinding("ss", "screenshot"),
        LeaderBinding("sl", "lock"),
        LeaderBinding("tn", "new_tab"),
        LeaderBinding("tc", "close_tab"),
    )
    val rhythm: List<RhythmBinding> = listOf(
        RhythmBinding("..", "play_pause"),
        RhythmBinding("...", "next"),
        RhythmBinding(".-", "prev"),
        RhythmBinding("--", "task_view"),
        RhythmBinding("-.-", "show_desktop"),
    )
}

/** Why a change was refused. Nothing is changed when one is returned. */
enum class BoardError {
    NAME_EMPTY,
    NAME_TOO_LONG,
    TEXT_EMPTY,
    TEXT_TOO_LONG,
    TOO_MANY_CUSTOM,
    UNKNOWN_KEY,
    UNKNOWN_COMMAND,
    WHEEL_FULL,
    GROUP_EMPTY,
    BAD_KEYS,
    KEYS_TAKEN,
    KEYS_CONFLICT,
    BAD_PATTERN,
    PATTERN_TAKEN,
}

/** The keys a custom shortcut may use: letters, digits, function keys, arrows and the usual editing keys. */
object ShortcutKeys {
    /** Display name to HID usage, in the order they are offered. */
    val all: List<Pair<String, Int>> = buildList {
        for (i in 0 until 26) add(('A' + i).toString() to HidKeyCodes.A + i)
        for (i in 1..9) add(i.toString() to HidKeyCodes.NUM_1 + i - 1)
        add("0" to HidKeyCodes.NUM_0)
        for (i in 1..12) add("F$i" to HidKeyCodes.F1 + i - 1)
        add("Enter" to HidKeyCodes.ENTER)
        add("Esc" to HidKeyCodes.ESC)
        add("Tab" to HidKeyCodes.TAB)
        add("Space" to HidKeyCodes.SPACE)
        add("Backspace" to HidKeyCodes.BACKSPACE)
        add("Delete" to HidKeyCodes.DELETE)
        add("Home" to HidKeyCodes.HOME)
        add("End" to HidKeyCodes.END)
        add("PgUp" to HidKeyCodes.PAGE_UP)
        add("PgDn" to HidKeyCodes.PAGE_DOWN)
        add("←" to HidKeyCodes.LEFT)
        add("→" to HidKeyCodes.RIGHT)
        add("↑" to HidKeyCodes.UP)
        add("↓" to HidKeyCodes.DOWN)
    }
    private val codes = all.map { it.second }.toSet()
    fun isAllowed(code: Int) = code in codes
    fun nameOf(code: Int): String? = all.firstOrNull { it.second == code }?.first
}

/**
 * The person's quick-control setup and the rules for changing it. Ids come in as a function so every rule
 * is a plain test. Not thread-safe: use from one thread (the view model's).
 */
class CommandBoard(
    initial: Board = Board(),
    private val newId: () -> String = { "c-" + java.util.UUID.randomUUID().toString() },
) {
    private val _board = MutableStateFlow(initial)
    val board: StateFlow<Board> = _board.asStateFlow()

    /** The command named [id]: a built-in one or one the person made. */
    fun command(id: String): Command? = CommandCatalog.find(id) ?: _board.value.custom.firstOrNull { it.id == id }

    /** Every command that can be put on the wheel, in a leader sequence or on a rhythm. */
    fun allCommands(): List<Command> = CommandCatalog.builtIn + _board.value.custom

    // ── Commands the person makes ────────────────────────────────────

    fun addShortcut(label: String, keyCode: Int, modifiers: Int): BoardError? {
        checkLabel(label)?.let { return it }
        if (!ShortcutKeys.isAllowed(keyCode)) return BoardError.UNKNOWN_KEY
        return addCustom(Command(newId(), label.trim(), CommandAction.Keys(keyCode, modifiers and ALLOWED_MODIFIERS)))
    }

    fun addPhrase(label: String, text: String): BoardError? {
        checkLabel(label)?.let { return it }
        if (text.isBlank()) return BoardError.TEXT_EMPTY
        if (text.length > MAX_TEXT_CHARS) return BoardError.TEXT_TOO_LONG
        return addCustom(Command(newId(), label.trim(), CommandAction.Phrase(text)))
    }

    private fun checkLabel(label: String): BoardError? = when {
        label.isBlank() -> BoardError.NAME_EMPTY
        label.trim().length > MAX_LABEL_CHARS -> BoardError.NAME_TOO_LONG
        else -> null
    }

    private fun addCustom(command: Command): BoardError? {
        val board = _board.value
        if (board.custom.size >= MAX_CUSTOM) return BoardError.TOO_MANY_CUSTOM
        _board.value = board.copy(custom = board.custom + command)
        return null
    }

    /** Deletes a command the person made, and everywhere it was used. Built-in commands stay. */
    fun deleteCustom(id: String) {
        val board = _board.value
        if (board.custom.none { it.id == id }) return
        _board.value = board.copy(
            custom = board.custom.filterNot { it.id == id },
            wheel = board.wheel.mapNotNull { slot -> dropFrom(slot, id) },
            leader = board.leader.filterNot { it.commandId == id },
            rhythm = board.rhythm.filterNot { it.commandId == id },
        )
    }

    private fun dropFrom(slot: WheelSlot, id: String): WheelSlot? = when (slot) {
        is WheelSlot.Item -> slot.takeIf { it.commandId != id }
        is WheelSlot.Group -> slot.copy(commandIds = slot.commandIds.filterNot { it == id }).takeIf { it.commandIds.isNotEmpty() }
    }

    // ── Wheel ────────────────────────────────────────────────────────

    fun addToWheel(commandId: String): BoardError? {
        if (command(commandId) == null) return BoardError.UNKNOWN_COMMAND
        val board = _board.value
        if (board.wheel.size >= MAX_WHEEL_SLOTS) return BoardError.WHEEL_FULL
        _board.value = board.copy(wheel = board.wheel + WheelSlot.Item(commandId))
        return null
    }

    fun addGroup(name: String, commandIds: List<String>): BoardError? {
        checkLabel(name)?.let { return it }
        val ids = commandIds.distinct()
        if (ids.isEmpty()) return BoardError.GROUP_EMPTY
        if (ids.size > MAX_WHEEL_SLOTS) return BoardError.WHEEL_FULL
        if (ids.any { command(it) == null }) return BoardError.UNKNOWN_COMMAND
        val board = _board.value
        if (board.wheel.size >= MAX_WHEEL_SLOTS) return BoardError.WHEEL_FULL
        _board.value = board.copy(wheel = board.wheel + WheelSlot.Group(name.trim(), ids))
        return null
    }

    fun removeFromWheel(index: Int) {
        val board = _board.value
        if (index !in board.wheel.indices) return
        _board.value = board.copy(wheel = board.wheel.filterIndexed { i, _ -> i != index })
    }

    fun moveInWheel(index: Int, by: Int) {
        val board = _board.value
        val target = index + by
        if (index !in board.wheel.indices || target !in board.wheel.indices) return
        val list = board.wheel.toMutableList()
        list.add(target, list.removeAt(index))
        _board.value = board.copy(wheel = list)
    }

    // ── Leader mode ──────────────────────────────────────────────────

    /** Binds [keys] (one to three of a to z and 0 to 9). No sequence may begin another, or the shorter would hide the longer. */
    fun addLeader(keys: String, commandId: String): BoardError? {
        val k = keys.trim().lowercase()
        if (k.isEmpty() || k.length > MAX_LEADER_KEYS || !k.all { it in 'a'..'z' || it in '0'..'9' }) return BoardError.BAD_KEYS
        if (command(commandId) == null) return BoardError.UNKNOWN_COMMAND
        val board = _board.value
        if (board.leader.any { it.keys == k }) return BoardError.KEYS_TAKEN
        if (board.leader.any { it.keys.startsWith(k) || k.startsWith(it.keys) }) return BoardError.KEYS_CONFLICT
        _board.value = board.copy(leader = board.leader + LeaderBinding(k, commandId))
        return null
    }

    fun removeLeader(keys: String) {
        val board = _board.value
        _board.value = board.copy(leader = board.leader.filterNot { it.keys == keys })
    }

    // ── Rhythm ───────────────────────────────────────────────────────

    /** Binds a pattern of two to six taps, `.` for a short one and `-` for a long one. */
    fun addRhythm(pattern: String, commandId: String): BoardError? {
        val p = pattern.trim()
        if (p.length !in MIN_RHYTHM..MAX_RHYTHM || !p.all { it == '.' || it == '-' }) return BoardError.BAD_PATTERN
        if (command(commandId) == null) return BoardError.UNKNOWN_COMMAND
        val board = _board.value
        if (board.rhythm.any { it.pattern == p }) return BoardError.PATTERN_TAKEN
        _board.value = board.copy(rhythm = board.rhythm + RhythmBinding(p, commandId))
        return null
    }

    fun removeRhythm(pattern: String) {
        val board = _board.value
        _board.value = board.copy(rhythm = board.rhythm.filterNot { it.pattern == pattern })
    }

    /** Back to what the app starts with. Commands the person made are kept. */
    fun resetToDefaults() {
        _board.value = Board(custom = _board.value.custom)
    }

    companion object {
        const val MAX_WHEEL_SLOTS = 8
        const val MAX_CUSTOM = 30
        const val MAX_LABEL_CHARS = 24
        const val MAX_TEXT_CHARS = 200
        const val MAX_LEADER_KEYS = 3
        const val MIN_RHYTHM = 2
        const val MAX_RHYTHM = 6
        private const val ALLOWED_MODIFIERS = HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_SHIFT or
            HidModifierMask.LEFT_ALT or HidModifierMask.LEFT_META
    }
}
