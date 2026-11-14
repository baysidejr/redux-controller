// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.TextView

/**
 * A mouse button on screen: held while a finger rests on it, released on lift or cancel.
 * Several hold buttons work at once, and they work alongside a finger on the trackpad.
 */
class HoldButton(context: Context, label: String, private val prominent: Boolean) : TextView(context) {
    var onHeld: ((Boolean) -> Unit)? = null
    private var held = false
    private val restColor = if (prominent) Color.rgb(42, 43, 51) else Color.rgb(31, 32, 38)
    private val heldColor = Color.rgb(255, 106, 80)
    private val shape = GradientDrawable().apply { setColor(restColor); cornerRadius = 12f * resources.displayMetrics.density }

    init {
        text = label; gravity = Gravity.CENTER; textSize = if (prominent) 16f else 13f
        typeface = if (prominent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (prominent) Color.rgb(244, 244, 242) else Color.rgb(163, 163, 171))
        background = shape; contentDescription = label; isClickable = true; isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { hold(true); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            MotionEvent.ACTION_UP -> { hold(false); performClick() }
            MotionEvent.ACTION_CANCEL -> hold(false)
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun release() = hold(false)

    private fun hold(value: Boolean) {
        if (held == value) return
        held = value
        shape.setColor(if (held) heldColor else restColor)
        setTextColor(if (held) Color.rgb(15, 21, 28) else if (prominent) Color.rgb(244, 244, 242) else Color.rgb(163, 163, 171))
        onHeld?.invoke(held)
    }
}
