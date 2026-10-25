package com.omsingh.beckon.core.commands

import com.omsingh.beckon.core.host.HostOs
import com.omsingh.beckon.core.host.HostProfile
import com.omsingh.beckon.core.input.HidKeyCodes
import com.omsingh.beckon.core.input.HidModifierMask
import com.omsingh.beckon.core.input.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {

    private val windows = HostProfile(HostOs.WINDOWS)

    private fun board(initial: Board = Board()) = CommandBoard(initial, newId = run { var n = 0; { "id${++n}" } })

    // ── Danger ───────────────────────────────────────────────────────

    @Test
    fun `locking, closing and deleting need confirmation, copying does not`() {
        assertTrue(CommandCatalog.find("lock")!!.dangerous)
        assertTrue(CommandCatalog.find("close_window")!!.dangerous)
        assertTrue(Command("x", "Del", CommandAction.Keys(HidKeyCodes.DELETE)).dangerous)
        assertFalse(CommandCatalog.find("copy")!!.dangerous)
        assertFalse(CommandCatalog.find("play_pause")!!.dangerous)
    }

    @Test
    fun `a text with a line break needs confirmation because Enter runs a line in a terminal`() {
        assertTrue(Command("p", "P", CommandAction.Phrase("ls\nrm x")).dangerous)
        assertFalse(Command("p", "P", CommandAction.Phrase("hello")).dangerous)
    }

    // ── Planning ─────────────────────────────────────────────────────

    @Test
    fun `a shortcut is the PC's own key combination, pressed and released`() {
        val plan = CommandPlanner.plan(CommandCatalog.find("copy")!!, windows) as CommandPlan.Events
        val mods = InputEvent.Modifiers(leftCtrl = true)
        assertEquals(listOf(InputEvent.KeyPress(HidKeyCodes.C, mods), InputEvent.KeyRelease(HidKeyCodes.C, mods)), plan.events)

        val mac = CommandPlanner.plan(CommandCatalog.find("copy")!!, HostProfile(HostOs.MACOS)) as CommandPlan.Events
        assertEquals(InputEvent.KeyPress(HidKeyCodes.C, InputEvent.Modifiers(leftMeta = true)), mac.events.first())
    }

    @Test
    fun `media, system, lock and text commands plan what they say`() {
        assertEquals(
            CommandPlan.Events(
                listOf(InputEvent.MediaCommand(InputEvent.MediaAction.NEXT)),
                RemoteCall(RemoteCall.MEDIA, 0, 1),
            ),
            CommandPlanner.plan(CommandCatalog.find("next")!!, windows),
        )
        assertEquals(
            CommandPlan.Events(
                listOf(InputEvent.LaunchAction(InputEvent.SystemAction.SHOW_DESKTOP)),
                RemoteCall(RemoteCall.SYSTEM, 0, 0),
            ),
            CommandPlanner.plan(CommandCatalog.find("show_desktop")!!, windows),
        )
        assertEquals(
            CommandPlan.Events(listOf(InputEvent.LockScreen), RemoteCall(RemoteCall.LOCK, 0, 0)),
            CommandPlanner.plan(CommandCatalog.find("lock")!!, windows),
        )
        assertEquals(
            CommandPlan.TypeText("مرحبا"),
            CommandPlanner.plan(Command("p", "P", CommandAction.Phrase("مرحبا")), windows),
        )
    }

    @Test
    fun `every command that is not a text also has the form the PC can report on`() {
        for (c in CommandCatalog.builtIn) {
            val plan = CommandPlanner.plan(c, windows) as CommandPlan.Events
            assertNotNull(plan.call)
        }
    }

    @Test
    fun `a key command is asked of the PC with the same modifiers and key it would press`() {
        val plan = CommandPlanner.plan(CommandCatalog.find("tab_prev")!!, windows) as CommandPlan.Events
        assertEquals(
            RemoteCall(RemoteCall.KEYS, HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_SHIFT, HidKeyCodes.TAB),
            plan.call,
        )
        val volume = CommandPlanner.plan(CommandCatalog.find("mute")!!, windows) as CommandPlan.Events
        assertEquals(RemoteCall(RemoteCall.VOLUME, 0, 2), volume.call)
    }

    @Test
    fun `every built-in command has a distinct id and plans something`() {
        val ids = CommandCatalog.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (c in CommandCatalog.builtIn) assertNotNull(CommandPlanner.plan(c, windows))
    }

    @Test
    fun `the defaults only name commands that exist`() {
        val b = board()
        val b0 = b.board.value
        for (slot in b0.wheel) {
            val ids = when (slot) { is WheelSlot.Item -> listOf(slot.commandId); is WheelSlot.Group -> slot.commandIds }
            for (id in ids) assertNotNull(b.command(id))
        }
        for (l in b0.leader) assertNotNull(b.command(l.commandId))
        for (r in b0.rhythm) assertNotNull(b.command(r.commandId))
        assertTrue(b0.wheel.size <= CommandBoard.MAX_WHEEL_SLOTS)
    }

    // ── Board rules ──────────────────────────────────────────────────

    @Test
    fun `a custom shortcut is saved and ignores keys that are not allowed`() {
        val b = board()
        assertNull(b.addShortcut("  Zoom  ", HidKeyCodes.EQUAL.let { HidKeyCodes.NUM_1 }, HidModifierMask.LEFT_CTRL))
        val made = b.board.value.custom.single()
        assertEquals("Zoom", made.label)
        assertEquals(CommandAction.Keys(HidKeyCodes.NUM_1, HidModifierMask.LEFT_CTRL), made.action)
        assertEquals(BoardError.UNKNOWN_KEY, b.addShortcut("Bad", 0xFF, 0))
        assertEquals(BoardError.NAME_EMPTY, b.addShortcut("  ", HidKeyCodes.A, 0))
        assertEquals(BoardError.NAME_TOO_LONG, b.addShortcut("x".repeat(25), HidKeyCodes.A, 0))
        assertEquals(1, b.board.value.custom.size)
    }

    @Test
    fun `custom phrases keep Arabic text exactly`() {
        val b = board()
        val text = "السلام عليكم‏ ورحمة الله"
        assertNull(b.addPhrase("تحية", text))
        assertEquals(text, (b.board.value.custom.single().action as CommandAction.Phrase).text)
        assertEquals(BoardError.TEXT_EMPTY, b.addPhrase("x", "  "))
        assertEquals(BoardError.TEXT_TOO_LONG, b.addPhrase("x", "a".repeat(201)))
    }

    @Test
    fun `deleting a custom command removes it from the wheel, leader and rhythm`() {
        val b = board()
        b.removeFromWheel(0) // the default wheel is full
        b.removeFromWheel(0)
        b.addPhrase("Hi", "hi")
        val id = b.board.value.custom.single().id
        assertNull(b.addToWheel(id))
        assertNull(b.addGroup("g", listOf(id)))
        assertNull(b.addLeader("hh", id))
        assertNull(b.addRhythm("-.-.", id))
        b.deleteCustom(id)
        val now = b.board.value
        assertTrue(now.custom.isEmpty())
        assertTrue(now.wheel.none { it is WheelSlot.Item && it.commandId == id })
        assertTrue(now.wheel.none { it is WheelSlot.Group && it.name == "g" })
        assertTrue(now.leader.none { it.commandId == id })
        assertTrue(now.rhythm.none { it.commandId == id })
    }

    @Test
    fun `built-in commands cannot be deleted`() {
        val b = board()
        b.deleteCustom("copy")
        assertNotNull(b.command("copy"))
    }

    @Test
    fun `the wheel holds at most eight places and moves places about`() {
        val b = board(Board(wheel = emptyList()))
        for (id in listOf("copy", "paste", "cut", "undo", "redo", "save", "find", "refresh")) assertNull(b.addToWheel(id))
        assertEquals(BoardError.WHEEL_FULL, b.addToWheel("esc"))
        b.moveInWheel(0, 1)
        assertEquals(WheelSlot.Item("paste"), b.board.value.wheel[0])
        b.moveInWheel(0, -1) // nowhere to go
        assertEquals(WheelSlot.Item("paste"), b.board.value.wheel[0])
        b.removeFromWheel(0)
        assertEquals(7, b.board.value.wheel.size)
        assertEquals(BoardError.UNKNOWN_COMMAND, b.addToWheel("nope"))
    }

    @Test
    fun `a group needs commands that exist`() {
        val b = board(Board(wheel = emptyList()))
        assertEquals(BoardError.GROUP_EMPTY, b.addGroup("g", emptyList()))
        assertEquals(BoardError.UNKNOWN_COMMAND, b.addGroup("g", listOf("copy", "nope")))
        assertNull(b.addGroup("g", listOf("copy", "copy", "paste")))
        assertEquals(listOf("copy", "paste"), (b.board.value.wheel.single() as WheelSlot.Group).commandIds)
    }

    @Test
    fun `leader sequences are short, plain and never begin one another`() {
        val b = board(Board(leader = emptyList()))
        assertNull(b.addLeader("ab", "copy"))
        assertEquals(BoardError.KEYS_TAKEN, b.addLeader("ab", "paste"))
        assertEquals(BoardError.KEYS_CONFLICT, b.addLeader("a", "paste"))
        assertEquals(BoardError.KEYS_CONFLICT, b.addLeader("abc", "paste"))
        assertEquals(BoardError.BAD_KEYS, b.addLeader("", "paste"))
        assertEquals(BoardError.BAD_KEYS, b.addLeader("abcd", "paste"))
        assertEquals(BoardError.BAD_KEYS, b.addLeader("a!", "paste"))
        assertNull(b.addLeader("AC", "paste")) // case does not matter
        assertEquals("ac", b.board.value.leader.last().keys)
    }

    @Test
    fun `rhythm patterns are two to six taps of dots and dashes, each once`() {
        val b = board(Board(rhythm = emptyList()))
        assertNull(b.addRhythm("..-", "copy"))
        assertEquals(BoardError.PATTERN_TAKEN, b.addRhythm("..-", "paste"))
        assertEquals(BoardError.BAD_PATTERN, b.addRhythm(".", "paste"))
        assertEquals(BoardError.BAD_PATTERN, b.addRhythm(".......", "paste"))
        assertEquals(BoardError.BAD_PATTERN, b.addRhythm(".x", "paste"))
        assertNull(b.addRhythm("..", "paste")) // a shorter pattern is fine: a run is matched whole
    }

    @Test
    fun `reset restores the defaults and keeps what the person made`() {
        val b = board()
        b.addPhrase("Hi", "hi")
        b.removeFromWheel(0)
        b.resetToDefaults()
        assertEquals(DefaultBoard.wheel, b.board.value.wheel)
        assertEquals(1, b.board.value.custom.size)
    }

    // ── Saving ───────────────────────────────────────────────────────

    @Test
    fun `the setup survives being saved and read`() {
        val b = board()
        b.addPhrase("تحية", "مرحبا\nبكم")
        b.addShortcut("Zoom", HidKeyCodes.NUM_1, HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_ALT)
        b.addToWheel(b.board.value.custom[0].id)
        b.removeLeader("wd")
        b.removeRhythm("..")
        val read = CommandBoardJson.read(CommandBoardJson.write(b.board.value))
        assertEquals(b.board.value, read)
    }

    @Test
    fun `an emptied wheel stays empty, but a missing one gets the default`() {
        val emptied = CommandBoardJson.read("""{"version":1,"wheel":[]}""")!!
        assertTrue(emptied.wheel.isEmpty())
        assertEquals(DefaultBoard.wheel, emptied.let { CommandBoardJson.read("""{"version":1}""")!!.wheel })
    }

    @Test
    fun `damaged or unknown entries are dropped and the rest is kept`() {
        val text = """{"version":9,"future":1,"wheel":[{"type":"item","id":"copy"},{"type":"item","id":"gone"},
            {"type":"group","name":"g","ids":["gone"]},{"nonsense":true}],
            "leader":[{"keys":"ab","id":"copy"},{"keys":"a","id":"paste"},{"keys":"zz","id":"gone"}],
            "rhythm":[{"pattern":"..","id":"copy"},{"pattern":".","id":"copy"},{"pattern":"..","id":"paste"}]}"""
        val read = CommandBoardJson.read(text)!!
        assertEquals(listOf<WheelSlot>(WheelSlot.Item("copy")), read.wheel)
        assertEquals(listOf(LeaderBinding("ab", "copy")), read.leader)
        assertEquals(listOf(RhythmBinding("..", "copy")), read.rhythm)
    }

    @Test
    fun `text that is not a setup is not read`() {
        assertNull(CommandBoardJson.read("not json"))
        assertNull(CommandBoardJson.read("[1,2]"))
    }

    // ── Leader mode ──────────────────────────────────────────────────

    private class Clock(var t: Long = 0)

    private fun leader(clock: Clock, vararg b: Pair<String, String>) =
        LeaderSession({ b.map { LeaderBinding(it.first, it.second) } }, { clock.t }, timeoutMs = 1000)

    @Test
    fun `leader mode offers the next keys and runs the command at the end`() {
        val clock = Clock()
        val s = leader(clock, "wd" to "show_desktop", "wt" to "task_view", "mp" to "play_pause")
        val first = s.start()
        assertEquals(listOf('m', 'w'), first.next.map { it.key })
        assertTrue(first.next.all { it.commandId == null })
        val second = s.press('w') as LeaderStep.Pending
        assertEquals("w", second.typed)
        assertEquals(listOf(LeaderChoice('d', "show_desktop"), LeaderChoice('t', "task_view")), second.next)
        assertEquals(LeaderStep.Run("task_view"), s.press('t'))
    }

    @Test
    fun `a key that leads nowhere starts over and a pause forgets a half sequence`() {
        val clock = Clock()
        val s = leader(clock, "wd" to "show_desktop", "mp" to "play_pause")
        s.start()
        assertEquals(LeaderStep.NoMatch, s.press('x'))
        s.press('w')
        clock.t = 5_000
        // After the pause, 'd' alone is no sequence: the half sequence "w" was forgotten.
        assertEquals(LeaderStep.NoMatch, s.press('d'))
        assertEquals(LeaderStep.Run("play_pause"), s.also { s.press('m') }.press('p'))
    }

    @Test
    fun `leader keys do not depend on case`() {
        val s = leader(Clock(), "ab" to "copy")
        s.press('A')
        assertEquals(LeaderStep.Run("copy"), s.press('B'))
    }

    // ── Rhythm ───────────────────────────────────────────────────────

    private fun rhythm() = RhythmMatcher({ listOf(RhythmBinding("..", "play_pause"), RhythmBinding(".-", "prev"), RhythmBinding("...", "next")) })

    @Test
    fun `short and long taps make a pattern that ends after a pause`() {
        val r = rhythm()
        assertNull(r.tap(100, 1_000))
        assertNull(r.tap(100, 1_300))
        assertEquals("..", r.pattern)
        assertNull(r.poll(1_500)) // not yet: the person may still tap a third
        assertEquals(RhythmResult.Match("..", "play_pause"), r.poll(2_200))
        assertEquals("", r.pattern)
    }

    @Test
    fun `a long tap is a dash`() {
        val r = rhythm()
        r.tap(100, 1_000)
        r.tap(500, 1_400)
        assertEquals(RhythmResult.Match(".-", "prev"), r.poll(3_000))
    }

    @Test
    fun `a pattern that matches nothing says so, and a run is never read as part of another`() {
        val r = rhythm()
        r.tap(100, 1_000); r.tap(100, 1_200); r.tap(100, 1_400)
        assertEquals(RhythmResult.Match("...", "next"), r.poll(2_500))
        r.tap(500, 5_000); r.tap(500, 5_400)
        assertEquals(RhythmResult.NoMatch("--"), r.poll(6_500))
    }

    @Test
    fun `a tap long after the last starts a new run`() {
        val r = rhythm()
        r.tap(100, 1_000)
        r.tap(100, 9_000) // no poll in between
        assertEquals("." , r.pattern)
    }

    @Test
    fun `six taps end the run at once`() {
        val r = RhythmMatcher({ listOf(RhythmBinding("......", "copy")) })
        for (i in 0 until 5) assertNull(r.tap(50, 1_000L + i * 100))
        assertEquals(RhythmResult.Match("......", "copy"), r.tap(50, 1_600))
    }
}
