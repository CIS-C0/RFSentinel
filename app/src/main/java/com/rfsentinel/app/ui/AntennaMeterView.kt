package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.rfsentinel.app.usb.AntennaCheck
import kotlin.math.abs

/**
 * The antenna check's meters (after Wardrive Go's): one bar per antenna with its signal, the
 * same-frame difference between them in the middle pointing at the stronger one, and a history
 * line of the last 30 s under each. One bar when the adapter reports a single signal per frame.
 */
class AntennaMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accent = ChipStyle.accent(context).first
    private val ink = ChipStyle.onSurface(context)
    private val weak = 0xFFE0A33A.toInt()

    private var dual = true
    private var a: Double? = null
    private var b: Double? = null
    private var delta: Double? = null
    private var zeroed = false
    private var histA = FloatArray(0)
    private var histB = FloatArray(0)
    /** Bar heights as drawn (0..1), eased toward the readings. */
    private var shownA = 0f
    private var shownB = 0f

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (ink and 0x00FFFFFF) or 0x1A000000 }
    private val trackEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = (ink and 0x00FFFFFF) or 0x40000000
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    /** Two antennas: [a] and [b] are chain 0 and chain 1 (dBm), [delta] their zeroed difference. */
    fun setDual(a: Double?, b: Double?, delta: Double?, zeroed: Boolean, h: AntennaCheck.History) {
        dual = true; this.a = a; this.b = b; this.delta = delta; this.zeroed = zeroed
        histA = h.a; histB = h.b
        invalidate()
    }

    /** One signal per frame. */
    fun setSingle(rssi: Double?, h: AntennaCheck.History) {
        dual = false; a = rssi; b = null; delta = null; zeroed = false
        histA = h.rssi; histB = FloatArray(0)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize((300 * density).toInt(), heightMeasureSpec))
    }

    private fun level(dbm: Double?): Float = dbm?.let { ((it + 100) / 70).toFloat().coerceIn(0.03f, 1f) } ?: 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val pad = 4 * density
        val labelY = 16 * density
        val barTop = 28 * density
        val barBottom = height - 82 * density
        val numY = barBottom + 38 * density
        val sparkTop = numY + 12 * density
        val sparkBottom = height - 6 * density

        // Ease toward the readings so the bars move instead of jumping every refresh.
        val ta = level(a); val tb = level(b)
        shownA += (ta - shownA) * 0.25f; shownB += (tb - shownB) * 0.25f
        if (abs(ta - shownA) > 0.002f || abs(tb - shownB) > 0.002f) postInvalidateOnAnimation()

        val d = delta
        val strongA = d == null || d >= -1.0
        val strongB = d == null || d <= 1.0
        if (!dual) {
            val bw = w * 0.42f
            val left = (w - bw) / 2
            column(canvas, "SIGNAL", left, left + bw, labelY, barTop, barBottom, numY, sparkTop, sparkBottom, shownA, a, histA, accent)
            return
        }
        val bw = w * 0.32f
        column(canvas, "CHAIN 0", pad, pad + bw, labelY, barTop, barBottom, numY, sparkTop, sparkBottom, shownA, a, histA,
            if (strongA) accent else weak)
        column(canvas, "CHAIN 1", w - pad - bw, w - pad, labelY, barTop, barBottom, numY, sparkTop, sparkBottom, shownB, b, histB,
            if (strongB) accent else weak)

        // The difference, pointing at the stronger antenna.
        val cx = w / 2
        val cy = (barTop + barBottom) / 2
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = (ink and 0x00FFFFFF) or 0xAA000000.toInt()
        text.textSize = 14 * density
        canvas.drawText("Δ", cx, cy - 26 * density, text)
        text.color = ink
        text.textSize = 30 * density
        val value = d?.let { "%.1f".format(abs(it)) } ?: "–"
        canvas.drawText(value, cx, cy + 10 * density, text)
        if (d != null && abs(d) > 1.0) {
            val half = text.measureText(value) / 2
            text.textSize = 22 * density
            text.color = accent
            if (d > 0) canvas.drawText("‹", cx - half - 10 * density, cy + 8 * density, text)
            else canvas.drawText("›", cx + half + 10 * density, cy + 8 * density, text)
        }
        text.typeface = Typeface.DEFAULT
        text.color = (ink and 0x00FFFFFF) or 0xAA000000.toInt()
        text.textSize = 12 * density
        canvas.drawText(if (zeroed) "dB · zeroed" else "dB", cx, cy + 28 * density, text)
    }

    private fun column(
        canvas: Canvas, label: String, left: Float, right: Float, labelY: Float, barTop: Float, barBottom: Float,
        numY: Float, sparkTop: Float, sparkBottom: Float, shown: Float, dbm: Double?, hist: FloatArray, color: Int
    ) {
        val cx = (left + right) / 2
        val r = 14 * density
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 13 * density
        text.color = color
        canvas.drawText(label, cx, labelY, text)

        rect.set(left, barTop, right, barBottom)
        canvas.drawRoundRect(rect, r, r, track)
        canvas.drawRoundRect(rect, r, r, trackEdge)
        if (shown > 0f) {
            val inset = 4 * density
            val top = barBottom - inset - (barBottom - barTop - 2 * inset) * shown
            rect.set(left + inset, top, right - inset, barBottom - inset)
            fill.color = color
            canvas.drawRoundRect(rect, r - inset, r - inset, fill)
        }

        // The reading, big, with its unit.
        val num = dbm?.let { "%.0f".format(it) } ?: "–"
        text.color = color
        text.textSize = 12 * density
        val uw = text.measureText(" dBm")
        // Shrink the number on narrow screens so it and its unit fit the column.
        text.textSize = 34 * density
        val fit = (right - left) - uw
        if (text.measureText(num) > fit) text.textSize *= fit / text.measureText(num)
        val big = text.textSize
        val nw = text.measureText(num)
        val x0 = cx - (nw + uw) / 2
        text.textAlign = Paint.Align.LEFT
        text.textSize = big
        canvas.drawText(num, x0, numY, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 12 * density
        text.color = (ink and 0x00FFFFFF) or 0xAA000000.toInt()
        canvas.drawText(" dBm", x0 + nw, numY, text)

        spark(canvas, hist, left, right, sparkTop, sparkBottom, color)
    }

    /** The last 30 s, scaled to its own range (at least 10 dB) so a flapping antenna shows up as a jumpy line. */
    private fun spark(canvas: Canvas, h: FloatArray, left: Float, right: Float, top: Float, bottom: Float, color: Int) {
        val vals = h.filter { !it.isNaN() }
        line.color = color
        if (vals.size < 2) {
            line.alpha = 0x60
            canvas.drawLine(left, bottom, right, bottom, line)
            return
        }
        var lo = vals.min(); var hi = vals.max()
        if (hi - lo < 10f) { val mid = (hi + lo) / 2; lo = mid - 5f; hi = mid + 5f }
        path.reset()
        var started = false
        h.forEachIndexed { i, v ->
            if (v.isNaN()) return@forEachIndexed
            val x = left + (right - left) * i / (h.size - 1).coerceAtLeast(1)
            val y = bottom - (bottom - top) * (v - lo) / (hi - lo)
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        line.alpha = 0xFF
        canvas.drawPath(path, line)
    }
}
