// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The mouse-mode touch surface. One finger moves the cursor, a quick tap is a left click, a
 * two-finger tap is a right click and a two-finger drag scrolls. In scroll-only form (the
 * strip beside the trackpad) any vertical drag scrolls and taps do nothing. All geometry is
 * reported in dp so the same motion feels the same on every screen density.
 */
class TrackpadView(context: Context, private val scrollOnly: Boolean = false) : View(context) {
    var onMove: ((Float, Float) -> Unit)? = null
    var onScroll: ((Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onTwoFingerTap: (() -> Unit)? = null
    var onGestureStart: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val slop = 8f * density
    private var fingers = 0
    private var maxFingers = 0
    private var moved = false
    private var downAt = 0L
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var trackedId = -1
    private val touches = ArrayList<Pair<Float, Float>>(2)
    private val surface = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 24, 29) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(51, 52, 59); strokeWidth = 1.5f * density; pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f) }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(111, 112, 120); textAlign = Paint.Align.CENTER; textSize = 13f * density }
    private val hintBold = Paint(hint).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 15f * density; color = Color.rgb(153, 170, 188) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 106, 80) }

    init { contentDescription = if (scrollOnly) "Scroll strip" else "Trackpad" }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { surface.set(0f, 0f, w.toFloat(), h.toFloat()) }

    override fun onDraw(canvas: Canvas) {
        val radius = 14f * density
        canvas.drawRoundRect(surface, radius, radius, fill)
        if (!scrollOnly) canvas.drawRoundRect(surface, radius, radius, border)
        if (touches.isEmpty()) {
            if (scrollOnly) {
                canvas.save(); canvas.rotate(90f, surface.centerX(), surface.centerY())
                canvas.drawText("Scroll", surface.centerX(), surface.centerY() + hint.textSize / 3, hint)
                canvas.restore()
                canvas.drawText("▲", surface.centerX(), surface.top + 22f * density, hint)
                canvas.drawText("▼", surface.centerX(), surface.bottom - 12f * density, hint)
            } else {
                canvas.drawText("Drag to move the cursor", surface.centerX(), surface.centerY() - 4f * density, hintBold)
                canvas.drawText("Tap for left click · two-finger tap for right click · two-finger drag to scroll", surface.centerX(), surface.centerY() + 20f * density, hint)
            }
        } else for ((x, y) in touches) canvas.drawCircle(x, y, 18f * density, dot)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingers = 1; maxFingers = 1; moved = false; downAt = now
                trackedId = event.getPointerId(0)
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                onGestureStart?.invoke()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = event.pointerCount; maxFingers = maxOf(maxFingers, fingers)
                val (cx, cy) = centroid(event); lastX = cx; lastY = cy
            }
            MotionEvent.ACTION_MOVE -> {
                if (fingers >= 2 || scrollOnly) {
                    val (cx, cy) = centroid(event)
                    val dy = cy - lastY
                    if (abs(cy - downY) > slop || abs(cx - downX) > slop) moved = true
                    if (moved) onScroll?.invoke(dy / density)
                    lastX = cx; lastY = cy
                } else {
                    val index = event.findPointerIndex(trackedId).takeIf { it >= 0 } ?: 0
                    val x = event.getX(index); val y = event.getY(index)
                    if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) moved = true
                    if (moved) onMove?.invoke((x - lastX) / density, (y - lastY) / density)
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                fingers = event.pointerCount - 1
                if (!scrollOnly && maxFingers == 2 && !moved && now - downAt < TAP_MS) {
                    moved = true // one click per gesture
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTwoFingerTap?.invoke()
                }
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                if (remaining.isNotEmpty()) { lastX = remaining.map { event.getX(it) }.average().toFloat(); lastY = remaining.map { event.getY(it) }.average().toFloat() }
                if (remaining.size == 1) trackedId = event.getPointerId(remaining[0])
            }
            MotionEvent.ACTION_UP -> {
                if (!scrollOnly && maxFingers == 1 && !moved && now - downAt < TAP_MS) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTap?.invoke()
                }
                fingers = 0; maxFingers = 0; moved = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { fingers = 0; maxFingers = 0; moved = false }
            else -> return super.onTouchEvent(event)
        }
        touches.clear()
        if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
            for (i in 0 until event.pointerCount) if (!(event.actionMasked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex)) touches.add(event.getX(i) to event.getY(i))
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun centroid(event: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in 0 until event.pointerCount) { x += event.getX(i); y += event.getY(i) }
        return x / event.pointerCount to y / event.pointerCount
    }

    companion object { const val TAP_MS = 260L }
}
