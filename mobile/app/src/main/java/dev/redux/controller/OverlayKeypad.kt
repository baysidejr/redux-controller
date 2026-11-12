// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The twelve-key play surface. Draws the active plate (overlay artwork or a drawn keypad),
 * tracks every touch pointer to the key under it, releases keys on slide-off and cancel, and
 * reports the held keys as a 12-bit mask. Several keys may be held at once.
 */
class OverlayKeypad(context: Context) : View(context) {
    var onKeysChanged: ((Int) -> Unit)? = null
    var onKeyPressed: ((Int) -> Unit)? = null
    private var plate: Plate? = null
    private var template = KeypadGeometry.RENDER
    private var bitmap: Bitmap? = null
    private val pointers = SparseIntArray()
    private var mask = 0
    private val drawRect = RectF()
    private val pill = RectF()
    private val plateColor = Color.rgb(22, 24, 30)
    private val pillColor = Color.rgb(48, 58, 72)
    private val inkColor = Color.rgb(239, 243, 247)
    private val accent = Color.rgb(255, 106, 80)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val image = Paint(Paint.FILTER_BITMAP_FLAG)

    fun show(plate: Plate) {
        releaseAll()
        this.plate = plate
        template = KeypadGeometry.of(plate.template)
        bitmap?.recycle(); bitmap = null
        if (plate.image != null) {
            bitmap = runCatching { context.assets.open(plate.image).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        contentDescription = "${plate.name} keypad"
        layout()
        invalidate()
    }

    fun releaseAll() {
        pointers.clear()
        update()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layout() }

    private fun layout() {
        val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: template.aspect
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val scale = minOf(w / aspect, h)
        val dw = scale * aspect; val dh = scale
        drawRect.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val plate = plate ?: return
        val art = bitmap
        if (art != null) canvas.drawBitmap(art, null, drawRect, image)
        else drawPlate(canvas, plate)
        for (key in 0 until 12) if (mask and (1 shl key) != 0) {
            pillOf(key)
            fill.color = Color.argb(150, 255, 106, 80)
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill)
            stroke.color = accent; stroke.strokeWidth = pill.height() * 0.08f
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, stroke)
        }
    }

    private fun drawPlate(canvas: Canvas, plate: Plate) {
        fill.color = plateColor
        val corner = drawRect.height() * 0.06f
        canvas.drawRoundRect(drawRect, corner, corner, fill)
        text.textSize = drawRect.height() * 0.055f; text.color = Color.rgb(153, 170, 188)
        canvas.drawText("A T A R I   J A G U A R", drawRect.centerX(), drawRect.top + drawRect.height() * 0.06f, text)
        for (key in 0 until 12) {
            pillOf(key)
            fill.color = pillColor
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill)
            text.textSize = pill.height() * 0.62f; text.color = inkColor
            canvas.drawText(plate.labels[key], pill.centerX(), pill.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun pillOf(key: Int) {
        val cx = drawRect.left + template.columns[key % 3] * drawRect.width()
        val cy = drawRect.top + template.rows[key / 3] * drawRect.height()
        val hw = template.pillWidth * drawRect.width() / 2; val hh = template.pillHeight * drawRect.height() / 2
        pill.set(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    private fun keyAt(x: Float, y: Float): Int {
        if (drawRect.isEmpty) return -1
        return KeypadGeometry.hit(template, (x - drawRect.left) / drawRect.width(), (y - drawRect.top) / drawRect.height())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                pointers.put(event.getPointerId(index), keyAt(event.getX(index), event.getY(index)))
            }
            MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount) {
                val id = event.getPointerId(index)
                if (pointers.indexOfKey(id) >= 0) pointers.put(id, keyAt(event.getX(index), event.getY(index)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointers.delete(event.getPointerId(event.actionIndex))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> pointers.clear()
            else -> return super.onTouchEvent(event)
        }
        update()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun update() {
        var next = 0
        for (i in 0 until pointers.size()) { val key = pointers.valueAt(i); if (key >= 0) next = next or (1 shl key) }
        if (next == mask) return
        val pressed = next and mask.inv()
        mask = next
        onKeysChanged?.invoke(mask)
        if (pressed != 0) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onKeyPressed?.invoke(Integer.numberOfTrailingZeros(pressed))
        }
        invalidate()
    }
}
