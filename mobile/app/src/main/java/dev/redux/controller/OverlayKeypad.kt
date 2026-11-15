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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * The twelve-key play surface. By default it draws twelve big keys in the plate colour that fill
 * the whole view, each with the plate pictogram and a short label; the classic mode draws the
 * original overlay artwork instead. Either way it tracks every touch pointer to the key under it,
 * releases keys on slide-off and cancel, and reports the held keys as a 12-bit mask. Several keys
 * may be held at once.
 */
class OverlayKeypad(context: Context) : View(context) {
    var onKeysChanged: ((Int) -> Unit)? = null
    var onKeyPressed: ((Int) -> Unit)? = null
    /** Big themed keys (true) or the original overlay artwork fitted into the view (false). */
    var bigKeys = true
    var keyColor = Profile.DEFAULT_COLOR
    private var plate: Plate? = null
    private var template = KeypadGeometry.RENDER
    private var bitmap: Bitmap? = null
    private val pointers = SparseIntArray()
    private var mask = 0
    private val drawRect = RectF()
    private val pill = RectF()
    private val keys = Array(12) { RectF() }
    private val scratch = RectF()
    private var gap = 0f
    private val plateColor = Color.rgb(22, 24, 30)
    private val pillColor = Color.rgb(48, 58, 72)
    private val blankColor = Color.rgb(38, 42, 49)
    private val inkColor = Color.rgb(239, 243, 247)
    private val accent = Color.rgb(255, 106, 80)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT }
    private val image = Paint(Paint.FILTER_BITMAP_FLAG)
    private val starGlyph = if (text.hasGlyph("✱")) "✱" else "*"

    fun show(plate: Plate) {
        releaseAll()
        this.plate = plate
        template = KeypadGeometry.of(plate.template)
        bitmap?.recycle(); bitmap = null
        if (!bigKeys && plate.image != null) {
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
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        if (bigKeys) {
            // Four rows always span the full height; three columns take the width up to a comfortable key shape.
            gap = h * 0.028f
            val keyHeight = (h - 3 * gap) / 4
            val keyWidth = min((w - 2 * gap) / 3, keyHeight * 3.4f)
            val gridWidth = keyWidth * 3 + gap * 2
            val left = (w - gridWidth) / 2
            drawRect.set(left, 0f, left + gridWidth, h)
            for (key in 0 until 12) {
                val x = left + (key % 3) * (keyWidth + gap); val y = (key / 3) * (keyHeight + gap)
                keys[key].set(x, y, x + keyWidth, y + keyHeight)
            }
            return
        }
        val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: template.aspect
        val scale = minOf(w / aspect, h)
        val dw = scale * aspect; val dh = scale
        drawRect.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val plate = plate ?: return
        if (bigKeys) { drawBigKeys(canvas, plate); return }
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

    private fun drawBigKeys(canvas: Canvas, plate: Plate) {
        for (key in 0 until 12) {
            val rect = keys[key]
            val label = plate.labels[key]; val pictogram = plate.icons[key]
            val held = mask and (1 shl key) != 0
            val blank = label.isEmpty() && pictogram.isEmpty()
            val corner = rect.height() * 0.22f
            fill.color = when { blank && held -> lighten(blankColor, 0.25f); blank -> blankColor; held -> lighten(keyColor, 0.45f); else -> keyColor }
            canvas.drawRoundRect(rect, corner, corner, fill)
            // A soft highlight over the upper half gives the key some relief.
            fill.color = Color.argb(if (blank) 14 else 36, 255, 255, 255)
            scratch.set(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.5f)
            canvas.drawRoundRect(scratch, corner, corner, fill)
            stroke.strokeWidth = rect.height() * 0.025f
            stroke.color = if (held) Color.argb(230, 255, 255, 255) else Color.argb(40, 0, 0, 0)
            canvas.drawRoundRect(rect, corner, corner, stroke)
            if (held) {
                stroke.strokeWidth = rect.height() * 0.09f; stroke.color = Color.argb(70, 255, 255, 255)
                canvas.drawRoundRect(rect, corner, corner, stroke)
            }
            if (blank) continue
            val numeral = KEY_NAMES[key]
            // The star and hash keys carry their symbol inline ("✱ RESET"); digit keys get a small numeral hint.
            val inline = (key == 9 || key == 11) && label != numeral && pictogram.isEmpty()
            if (label != numeral && !inline) {
                hint.color = Color.argb(120, 255, 255, 255); hint.textSize = rect.height() * 0.17f
                canvas.drawText(numeral, rect.left + rect.height() * 0.14f, rect.top + rect.height() * 0.26f, hint)
            }
            drawKeyFace(canvas, rect, if (inline) "${if (key == 9) starGlyph else "#"} ${label.uppercase()}" else label, pictogram)
        }
    }

    /** The pictogram above a small caption, or a label alone sized to the key. */
    private fun drawKeyFace(canvas: Canvas, rect: RectF, label: String, pictogram: String) {
        val h = rect.height(); val maxWidth = rect.width() * 0.88f
        val symbol = when (label) { "*" -> starGlyph; "#" -> "#"; else -> null }
        text.color = inkColor
        text.setShadowLayer(h * 0.04f, 0f, h * 0.02f, Color.argb(150, 0, 0, 0))
        if (symbol != null) {
            text.textSize = h * 0.5f
            canvas.drawText(symbol, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        } else if (pictogram.isNotEmpty()) {
            icon.textSize = h * 0.40f
            val caption = label.uppercase()
            text.textSize = fitSize(caption, h * 0.19f, maxWidth)
            val iconHeight = icon.descent() - icon.ascent()
            val captionHeight = if (caption.isEmpty()) 0f else (text.descent() - text.ascent()) * 0.95f
            var y = rect.centerY() - (iconHeight + captionHeight) / 2 - icon.ascent()
            canvas.drawText(pictogram, rect.centerX(), y, icon)
            if (caption.isNotEmpty()) {
                y += icon.descent() - text.ascent() * 0.95f
                canvas.drawText(caption, rect.centerX(), y, text)
            }
        } else {
            val caption = if (label.length == 1) label else label.uppercase()
            val single = fitSize(caption, if (label.length == 1) h * 0.5f else h * 0.26f, maxWidth)
            val lines = if (single >= h * 0.2f) listOf(caption) else splitLines(caption)
            text.textSize = if (lines.size == 1) single else fitSize(lines.maxBy { text.measureText(it) }, h * 0.22f, maxWidth)
            val lineHeight = (text.descent() - text.ascent()) * 0.95f
            var y = rect.centerY() - lineHeight * lines.size / 2 - text.ascent() * 0.95f
            for (line in lines) { canvas.drawText(line, rect.centerX(), y, text); y += lineHeight }
        }
        text.clearShadowLayer()
    }

    /** The largest text size up to [preferred] at which [value] fits [maxWidth], never below 55 % of it. */
    private fun fitSize(value: String, preferred: Float, maxWidth: Float): Float {
        text.textSize = preferred
        val measured = text.measureText(value)
        return if (measured <= maxWidth || measured == 0f) preferred else maxOf(preferred * 0.55f, preferred * maxWidth / measured)
    }

    /** Break a label into two lines at the space nearest its middle, never right after a lone symbol. */
    private fun splitLines(value: String): List<String> {
        val cut = value.indices.filter { it > 1 && value[it] == ' ' }.minByOrNull { abs(it - value.length / 2) } ?: return listOf(value)
        return listOf(value.substring(0, cut), value.substring(cut + 1))
    }

    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt())

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
        if (bigKeys) {
            // Each key owns its cell plus half the gap around it, so a finger between keys still counts.
            val cellWidth = keys[0].width() + gap; val cellHeight = keys[0].height() + gap
            val fx = (x - drawRect.left + gap / 2) / cellWidth; val fy = (y - drawRect.top + gap / 2) / cellHeight
            if (fx < 0 || fy < 0 || fx >= 3 || fy >= 4) return -1
            return floor(fy).toInt() * 3 + floor(fx).toInt()
        }
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

    companion object {
        /** The Jaguar key under each index: rows of three, 1–9 then *, 0, #. */
        val KEY_NAMES = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    }
}
