// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Trackpad arithmetic with no Android dependency: finger travel in dp becomes mouse counts
 * with a speed setting and light acceleration, and scroll travel becomes wheel detents.
 * Fractions carry over between events so slow, precise movement is not lost to rounding.
 */
class MouseMotion(speed: Int = DEFAULT_SPEED) {
    var speed = speed.coerceIn(1, SPEEDS)
        set(value) { field = value.coerceIn(1, SPEEDS) }
    private var xRemainder = 0f
    private var yRemainder = 0f
    private var scrollRemainder = 0f

    /** Counts to send for one movement event of (dxDp, dyDp). */
    fun counts(dxDp: Float, dyDp: Float): Pair<Int, Int> {
        val gain = GAINS[speed - 1]
        val distance = sqrt(dxDp * dxDp + dyDp * dyDp)
        // Faster swipes travel further; slow ones stay precise.
        val boost = 1f + 0.6f * min(distance / 24f, 1f)
        xRemainder += dxDp * gain * boost; yRemainder += dyDp * gain * boost
        val x = xRemainder.roundToInt(); val y = yRemainder.roundToInt()
        xRemainder -= x; yRemainder -= y
        return x to y
    }

    /** Wheel detents for a scroll movement; fingers moving down scroll the page down (negative wheel), as a Windows touchpad does. */
    fun detents(dyDp: Float): Int {
        scrollRemainder += dyDp / DETENT_DP
        val whole = scrollRemainder.toInt()
        scrollRemainder -= whole
        return -whole
    }

    /** A new gesture starts from zero. */
    fun reset() { xRemainder = 0f; yRemainder = 0f; scrollRemainder = 0f }

    companion object {
        const val SPEEDS = 5
        const val DEFAULT_SPEED = 3
        const val DETENT_DP = 28f
        private val GAINS = floatArrayOf(0.8f, 1.2f, 1.7f, 2.4f, 3.2f)
        fun label(speed: Int) = "Speed ${speed.coerceIn(1, SPEEDS)}"
    }
}
