package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.rfsentinel.app.sdr.SdrWaterfall
import java.util.Locale
import kotlin.math.abs

/**
 * The radio waterfall: the latest spectrum as a line on top, and below it every row so far, newest at
 * the top, from dark (noise) to bright (a strong signal). The colour scale follows the noise floor, so
 * it reads the same with any antenna. Tap to pick a frequency ([onTap]).
 */
class WaterfallView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** A frequency tapped on the waterfall or the spectrum. */
    var onTap: ((Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val textColor = TypedValue().let { tv ->
        context.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = resources.getDimension(com.rfsentinel.app.R.dimen.text_caption)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; strokeWidth = 1.5f * density; style = Paint.Style.STROKE }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; alpha = 90; strokeWidth = 1f * density; style = Paint.Style.STROKE }
    private val gapPaint = Paint().apply { color = textColor; alpha = 140; strokeWidth = 1f * density }
    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val blackPaint = Paint().apply { color = Color.BLACK }

    // Where each pixel column comes from in the current rows' layout.
    private var layoutKey = ""
    private var colSeg = IntArray(0); private var colFrom = IntArray(0); private var colTo = IntArray(0)
    private var colHz = LongArray(0)
    /** Columns where one band stops and another starts (not side by side in frequency). */
    private var gaps = IntArray(0)
    private var tuned = false

    private var bitmap: Bitmap? = null
    /** Bitmap row holding the newest line; the ones after it are older. */
    private var newest = 0
    private var rows = 0
    private var pixels = IntArray(0)
    private var spectrum: FloatArray? = null
    private var peak: FloatArray? = null
    /** Noise floor (dB), followed slowly so the colours stay steady. */
    private var floor = Float.NaN

    private val spectrumH get() = (height * 0.28f).coerceAtLeast(64 * density)
    private val labelH get() = textPaint.textSize + 6 * density
    private val waterTop get() = spectrumH + labelH

    /** Forgets every row (the view switched between the sweep and a single frequency). */
    fun clear() {
        layoutKey = ""; rows = 0; spectrum = null; peak = null; floor = Float.NaN
        bitmap?.eraseColor(Color.BLACK)
        invalidate()
    }

    /** Adds the newest row; call on the main thread, then [invalidate]. */
    fun add(row: SdrWaterfall.Row) {
        val w = width; if (w <= 0 || height <= waterTop + 8 * density) return
        val key = row.tuned.toString() + w + row.segments.joinToString { "${it.startHz}-${it.endHz}" }
        if (key != layoutKey) relayout(row, w, key)
        // One value per column: the strongest bin under it, so a narrow signal never falls between pixels.
        val values = FloatArray(w) { x ->
            val d = row.segments[colSeg[x]].db
            var m = -200f
            for (k in colFrom[x]..colTo[x]) if (d[k] > m) m = d[k]
            m
        }
        val median = values.copyOf().also { it.sort() }[w / 2]
        floor = if (floor.isNaN()) median else floor + (median - floor) * (if (row.tuned) 0.05f else 0.3f)
        spectrum = values
        peak = peak?.let { p -> FloatArray(w) { maxOf(values[it], p[it] - (if (row.tuned) 0.15f else 1.5f)) } } ?: values.copyOf()
        val bmp = bitmap ?: return
        for (x in 0 until w) pixels[x] = PALETTE[(((values[x] - floor + 3f) / RANGE_DB) * 255).toInt().coerceIn(0, 255)]
        newest = (newest - 1 + bmp.height) % bmp.height
        bmp.setPixels(pixels, 0, w, 0, newest, w, 1)
        rows = minOf(rows + 1, bmp.height)
    }

    private fun relayout(row: SdrWaterfall.Row, w: Int, key: String) {
        layoutKey = key; tuned = row.tuned
        val segs = row.segments
        val total = segs.sumOf { it.endHz - it.startHz }.coerceAtLeast(1)
        colSeg = IntArray(w); colFrom = IntArray(w); colTo = IntArray(w); colHz = LongArray(w)
        val gapList = ArrayList<Int>()
        var x0 = 0; var acc = 0L
        segs.forEachIndexed { i, s ->
            acc += s.endHz - s.startHz
            val x1 = if (i == segs.lastIndex) w else (acc * w / total).toInt()
            if (i > 0 && s.startHz > segs[i - 1].endHz + (s.endHz - s.startHz) / 20) gapList += x0
            val bins = s.db.size
            for (x in x0 until x1) {
                val f0 = (x - x0).toDouble() / (x1 - x0); val f1 = (x + 1 - x0).toDouble() / (x1 - x0)
                colSeg[x] = i
                colFrom[x] = (f0 * bins).toInt().coerceIn(0, bins - 1)
                colTo[x] = ((f1 * bins).toInt() - 1).coerceIn(colFrom[x], bins - 1)
                colHz[x] = s.startHz + ((s.endHz - s.startHz) * (f0 + f1) / 2).toLong()
            }
            x0 = x1
        }
        gaps = gapList.toIntArray()
        // The sweep gives a row every second or two: taller rows, so the history isn't a thin strip.
        val rowPx = if (row.tuned) 1f else 4 * density
        val h = ((height - waterTop) / rowPx).toInt().coerceAtLeast(1)
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        pixels = IntArray(w); newest = 0; rows = 0; spectrum = null; peak = null; floor = Float.NaN
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) { layoutKey = ""; bitmap?.recycle(); bitmap = null }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle(); bitmap = null; layoutKey = ""
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val top = waterTop
        canvas.drawRect(0f, top, w, height.toFloat(), blackPaint)
        val bmp = bitmap
        if (bmp != null && rows > 0) {
            // Newest row at the top: the bitmap from [newest] down, then its start.
            val scale = (height - top) / bmp.height
            val firstRows = minOf(rows, bmp.height - newest)
            canvas.drawBitmap(bmp, Rect(0, newest, bmp.width, newest + firstRows),
                android.graphics.RectF(0f, top, w, top + firstRows * scale), bitmapPaint)
            val rest = rows - firstRows
            if (rest > 0) canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, rest),
                android.graphics.RectF(0f, top + firstRows * scale, w, top + rows * scale), bitmapPaint)
        }
        for (g in gaps) canvas.drawLine(g.toFloat(), 0f, g.toFloat(), height.toFloat(), gapPaint)
        val s = spectrum
        if (s != null && !floor.isNaN()) {
            val h = spectrumH - 4 * density
            fun y(v: Float) = 2 * density + h * (1f - ((v - floor + 3f) / (RANGE_DB + 10f)).coerceIn(0f, 1f))
            peak?.let { p -> canvas.drawPath(Path().apply { p.forEachIndexed { x, v -> if (x == 0) moveTo(0f, y(v)) else lineTo(x.toFloat(), y(v)) } }, peakPaint) }
            canvas.drawPath(Path().apply { s.forEachIndexed { x, v -> if (x == 0) moveTo(0f, y(v)) else lineTo(x.toFloat(), y(v)) } }, linePaint)
        }
        drawLabels(canvas)
    }

    /** Frequencies under the spectrum: each band's start, and the right-hand end. */
    private fun drawLabels(canvas: Canvas) {
        if (colHz.isEmpty()) return
        val y = spectrumH + textPaint.textSize
        val marks = if (tuned) listOf(0, colHz.size / 2, colHz.size - 1) else (listOf(0) + gaps.toList() + (colHz.size - 1)).distinct()
        var lastEnd = -1f
        for ((i, x) in marks.withIndex()) {
            val t = mhz(colHz[x])
            val tw = textPaint.measureText(t)
            val left = when (i) { 0 -> 0f; marks.lastIndex -> width - tw; else -> (x - tw / 2).coerceIn(0f, width - tw) }
            if (left < lastEnd + 8 * density) continue
            canvas.drawText(t, left, y, textPaint)
            lastEnd = left + tw
        }
    }

    private fun mhz(hz: Long) = String.format(Locale.US, if (tuned) "%.3f" else "%.1f", hz / 1e6)

    private var downX = 0f; private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> if (abs(e.x - downX) < 16 * density && abs(e.y - downY) < 16 * density) {
                val x = e.x.toInt()
                if (x in colHz.indices) { onTap?.invoke(colHz[x]); performClick() }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        /** dB above the noise floor that reach the top of the colour scale. */
        private const val RANGE_DB = 40f

        /** Black, deep blue, cyan, yellow, red, white: the usual waterfall scale. */
        private val PALETTE = IntArray(256) { i ->
            val stops = intArrayOf(0xFF000000.toInt(), 0xFF0A1A6E.toInt(), 0xFF0096C8.toInt(), 0xFFF0E000.toInt(), 0xFFE02000.toInt(), 0xFFFFFFFF.toInt())
            val p = i / 255f * (stops.size - 1)
            val k = p.toInt().coerceAtMost(stops.size - 2); val f = p - k
            val a = stops[k]; val b = stops[k + 1]
            fun mix(sh: Int) = (((a shr sh) and 0xff) * (1 - f) + ((b shr sh) and 0xff) * f).toInt()
            Color.rgb(mix(16), mix(8), mix(0))
        }
    }
}
