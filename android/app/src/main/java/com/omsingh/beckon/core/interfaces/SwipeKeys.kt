package com.omsingh.beckon.core.interfaces

import com.omsingh.beckon.core.input.HidKeyCodes
import com.omsingh.beckon.core.input.HidModifierMask
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** The eight directions of a swipe, as seen on the screen (down is down). */
enum class Swipe8 { RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT, LEFT, UP_LEFT, UP, UP_RIGHT }

object SwipeClassifier {
    /**
     * Which of the eight directions a drag of ([dx], [dy]) screen pixels points to, or null when it is
     * shorter than [minDistance] and so is a tap, not a swipe. Screen y grows downwards.
     */
    fun classify(dx: Float, dy: Float, minDistance: Float): Swipe8? {
        if (hypot(dx, dy) < minDistance) return null
        // atan2 with y down gives degrees clockwise from "right"; 45 degrees to a direction.
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val sector = Math.floorMod(Math.round(degrees / 45.0).toInt(), 8)
        return Swipe8.entries[sector]
    }
}

/** One key press with modifiers ([HidModifierMask] bits). */
data class SwipeKey(val keyCode: Int, val modifiers: Int = 0)

/** Which key each swipe presses, and what a tap does. */
enum class GestureLayout(val swipes: Map<Swipe8, SwipeKey>, val tap: SwipeKey?) {
    /** Arrow keys on the four directions, and Home, End and the page keys on the diagonals. */
    ARROWS(
        mapOf(
            Swipe8.UP to SwipeKey(HidKeyCodes.UP),
            Swipe8.DOWN to SwipeKey(HidKeyCodes.DOWN),
            Swipe8.LEFT to SwipeKey(HidKeyCodes.LEFT),
            Swipe8.RIGHT to SwipeKey(HidKeyCodes.RIGHT),
            Swipe8.UP_LEFT to SwipeKey(HidKeyCodes.HOME),
            Swipe8.UP_RIGHT to SwipeKey(HidKeyCodes.PAGE_UP),
            Swipe8.DOWN_LEFT to SwipeKey(HidKeyCodes.END),
            Swipe8.DOWN_RIGHT to SwipeKey(HidKeyCodes.PAGE_DOWN),
        ),
        tap = SwipeKey(HidKeyCodes.ENTER),
    ),

    /** Space, Backspace, Tab, Enter and Escape: what typing needs besides letters. */
    EDITING(
        mapOf(
            Swipe8.RIGHT to SwipeKey(HidKeyCodes.SPACE),
            Swipe8.LEFT to SwipeKey(HidKeyCodes.BACKSPACE),
            Swipe8.DOWN to SwipeKey(HidKeyCodes.ENTER),
            Swipe8.UP to SwipeKey(HidKeyCodes.TAB),
            Swipe8.UP_LEFT to SwipeKey(HidKeyCodes.ESC),
            Swipe8.UP_RIGHT to SwipeKey(HidKeyCodes.DELETE),
            Swipe8.DOWN_LEFT to SwipeKey(HidKeyCodes.BACKSPACE, HidModifierMask.LEFT_CTRL),
            Swipe8.DOWN_RIGHT to SwipeKey(HidKeyCodes.DELETE, HidModifierMask.LEFT_CTRL),
        ),
        tap = null,
    ),

    /** Moving about in a web page or a document. */
    BROWSER(
        mapOf(
            Swipe8.UP to SwipeKey(HidKeyCodes.PAGE_UP),
            Swipe8.DOWN to SwipeKey(HidKeyCodes.PAGE_DOWN),
            Swipe8.LEFT to SwipeKey(HidKeyCodes.LEFT, HidModifierMask.LEFT_ALT),
            Swipe8.RIGHT to SwipeKey(HidKeyCodes.RIGHT, HidModifierMask.LEFT_ALT),
            Swipe8.UP_LEFT to SwipeKey(HidKeyCodes.TAB, HidModifierMask.LEFT_CTRL or HidModifierMask.LEFT_SHIFT),
            Swipe8.UP_RIGHT to SwipeKey(HidKeyCodes.TAB, HidModifierMask.LEFT_CTRL),
            Swipe8.DOWN_LEFT to SwipeKey(HidKeyCodes.HOME, HidModifierMask.LEFT_CTRL),
            Swipe8.DOWN_RIGHT to SwipeKey(HidKeyCodes.END, HidModifierMask.LEFT_CTRL),
        ),
        tap = null,
    ),
    ;

    fun keyFor(swipe: Swipe8?): SwipeKey? = if (swipe == null) tap else swipes[swipe]
}

/** Whether a swipe is mostly horizontal; used to tell a deliberate swipe from a wobble while tapping. */
fun isMostlyHorizontal(dx: Float, dy: Float): Boolean = abs(dx) >= abs(dy)
