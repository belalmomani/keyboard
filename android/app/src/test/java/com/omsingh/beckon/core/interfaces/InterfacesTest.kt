package com.omsingh.beckon.core.interfaces

import com.omsingh.beckon.core.input.HidKeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfacesTest {

    // ── Swipes ───────────────────────────────────────────────────────

    @Test
    fun `swipes are read in eight directions with the screen's y going down`() {
        val m = 20f
        assertEquals(Swipe8.RIGHT, SwipeClassifier.classify(100f, 5f, m))
        assertEquals(Swipe8.LEFT, SwipeClassifier.classify(-100f, -5f, m))
        assertEquals(Swipe8.DOWN, SwipeClassifier.classify(3f, 100f, m))
        assertEquals(Swipe8.UP, SwipeClassifier.classify(-3f, -100f, m))
        assertEquals(Swipe8.DOWN_RIGHT, SwipeClassifier.classify(80f, 80f, m))
        assertEquals(Swipe8.UP_LEFT, SwipeClassifier.classify(-80f, -80f, m))
        assertEquals(Swipe8.UP_RIGHT, SwipeClassifier.classify(80f, -80f, m))
        assertEquals(Swipe8.DOWN_LEFT, SwipeClassifier.classify(-80f, 80f, m))
    }

    @Test
    fun `a short drag is a tap, not a swipe`() {
        assertNull(SwipeClassifier.classify(5f, 5f, 20f))
        assertNull(SwipeClassifier.classify(0f, 0f, 20f))
    }

    @Test
    fun `every layout answers every swipe, and the arrows layout's tap is Enter`() {
        for (layout in GestureLayout.entries) {
            for (swipe in Swipe8.entries) assertTrue(layout.keyFor(swipe) != null)
        }
        assertEquals(SwipeKey(HidKeyCodes.ENTER), GestureLayout.ARROWS.keyFor(null))
        assertNull(GestureLayout.EDITING.keyFor(null))
        assertEquals(SwipeKey(HidKeyCodes.LEFT), GestureLayout.ARROWS.keyFor(Swipe8.LEFT))
    }

    // ── Puppet ───────────────────────────────────────────────────────

    @Test
    fun `a resting handle moves nothing, and a pulled one moves the pointer that way`() {
        val p = PuppetMapper()
        assertEquals(0f to 0f, p.step(0f, 0f, 1.6f))
        assertEquals(0f to 0f, p.step(0.1f, -0.05f, 1.6f))
        val (dx, dy) = p.step(0.8f, 0f, 1.6f)
        assertTrue(dx > 0f)
        assertEquals(0f, dy, 0.0001f)
        val (ux, uy) = p.step(0f, -0.8f, 1.6f)
        assertEquals(0f, ux, 0.0001f)
        assertTrue(uy < 0f)
    }

    @Test
    fun `farther is faster and the handle cannot pass the edge`() {
        val p = PuppetMapper()
        val near = p.step(0.4f, 0f, 1f).first
        val far = p.step(0.9f, 0f, 1f).first
        assertTrue(far > near * 2)
        assertEquals(p.step(1f, 0f, 1f), p.step(5f, 0f, 1f))
        val (cx, cy) = p.clamp(3f, 4f)
        assertEquals(0.6f, cx, 0.0001f)
        assertEquals(0.8f, cy, 0.0001f)
    }

    @Test
    fun `sensitivity scales the speed`() {
        val p = PuppetMapper()
        assertTrue(p.step(0.8f, 0f, 3f).first > p.step(0.8f, 0f, 1f).first * 2.5f)
    }

    // ── Fractal keyboard ─────────────────────────────────────────────

    @Test
    fun `a new keyboard shows a few groups that together hold every letter once`() {
        val k = FractalKeyboard(FractalLayouts.LATIN)
        val top = k.top()
        assertTrue(top.size <= 6)
        assertEquals(FractalLayouts.LATIN, top.flatMap { it.chars })
        assertEquals(UsageLevel.BEGINNER, k.level)
    }

    @Test
    fun `a letter is two taps away at first, and a group of six or fewer opens into letters`() {
        val k = FractalKeyboard(FractalLayouts.LATIN)
        val group = k.top().first()
        val letters = k.open(group)
        assertTrue(letters.all { it.isLeaf })
        assertEquals(group.chars, letters.map { it.char })
        assertEquals('a', letters.first().char)
    }

    @Test
    fun `Arabic letters work the same way`() {
        val k = FractalKeyboard(FractalLayouts.ARABIC)
        val top = k.top()
        assertTrue(top.size <= 6)
        assertEquals(FractalLayouts.ARABIC, top.flatMap { it.chars })
        for (node in top) assertTrue(k.open(node).all { it.isLeaf || it.chars.size > 1 })
    }

    @Test
    fun `with use the most typed letters climb to the first level`() {
        val k = FractalKeyboard(FractalLayouts.LATIN)
        repeat(20) { k.record('e') }
        repeat(10) { k.record('t') }
        repeat(5) { k.record('a') }
        assertEquals(UsageLevel.LEARNING, k.level)
        val top = k.top()
        assertEquals(listOf('e', 't', 'a'), top.take(3).map { it.char })
        // A letter shown on its own is not also inside a group.
        val inGroups = top.drop(3).flatMap { it.chars }
        assertTrue('e' !in inGroups && 't' !in inGroups && 'a' !in inGroups)
        assertEquals(FractalLayouts.LATIN.size, 3 + inGroups.size)
    }

    @Test
    fun `levels follow how much the keyboard was used`() {
        assertEquals(UsageLevel.BEGINNER, UsageLevel.of(0))
        assertEquals(UsageLevel.BEGINNER, UsageLevel.of(29))
        assertEquals(UsageLevel.LEARNING, UsageLevel.of(30))
        assertEquals(UsageLevel.FAMILIAR, UsageLevel.of(200))
        assertEquals(6, UsageLevel.FAMILIAR.shortcuts)
    }

    @Test
    fun `letters that are not on the keyboard are not counted`() {
        val k = FractalKeyboard(FractalLayouts.LATIN)
        k.record('7')
        k.record('ب')
        assertEquals(0, k.total)
        k.record('q')
        assertEquals(mapOf('q' to 1), k.snapshot())
    }

    @Test
    fun `saved counts are used when the keyboard is made again`() {
        val first = FractalKeyboard(FractalLayouts.LATIN)
        repeat(40) { first.record('s') }
        val again = FractalKeyboard(FractalLayouts.LATIN, first.snapshot().toMutableMap())
        assertEquals(UsageLevel.LEARNING, again.level)
        assertEquals('s', again.top().first().char)
    }

    @Test
    fun `usage counts survive being saved, Arabic letters included, and junk lines are skipped`() {
        val file = java.io.File.createTempFile("fractal", ".txt")
        try {
            val store = FractalUsageStore(file)
            assertEquals(emptyMap<Char, Int>(), store.load())
            assertTrue(store.save(mapOf('e' to 12, 'ب' to 3)))
            assertEquals(mapOf('e' to 12, 'ب' to 3), store.load())
            file.writeText("101=5\nnonsense\n102=-4\n103=x\n98=2")
            assertEquals(mapOf('e' to 5, 'b' to 2), store.load())
        } finally {
            file.delete()
        }
    }
}
