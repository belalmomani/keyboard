package com.omsingh.beckon.core.interfaces

import kotlin.math.hypot

/**
 * The mouse puppet is a joystick: the farther the handle is pulled from the centre, the faster the pointer
 * moves, and letting go stops it. This turns the handle's position into pointer movement per tick.
 */
class PuppetMapper(
    /** The share of the radius around the centre where nothing moves, so a resting thumb does not drift the pointer. */
    private val deadZone: Float = 0.12f,
    /** Pixels per tick at the edge of the radius, before sensitivity. */
    private val maxSpeed: Float = 22f,
) {
    /** The handle's offset ([x], [y]) from the centre as a share of the radius, kept inside the circle. */
    fun clamp(x: Float, y: Float): Pair<Float, Float> {
        val length = hypot(x, y)
        return if (length <= 1f || length == 0f) x to y else (x / length) to (y / length)
    }

    /** Pointer movement for one tick: ([x], [y]) is the offset from [clamp], [sensitivity] the app's setting. */
    fun step(x: Float, y: Float, sensitivity: Float): Pair<Float, Float> {
        val (cx, cy) = clamp(x, y)
        val length = hypot(cx, cy)
        if (length <= deadZone) return 0f to 0f
        // Slow near the centre for fine aiming, fast at the edge to cross the screen.
        val t = (length - deadZone) / (1f - deadZone)
        val speed = maxSpeed * sensitivity.coerceIn(0.2f, 5f) * t * t
        return (cx / length) * speed to (cy / length) * speed
    }
}
