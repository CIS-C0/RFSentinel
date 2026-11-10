package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.rfsentinel.app.sdr.SdrWaterfall
import java.util.Locale
import kotlin.math.abs

/**
 * The radio waterfall: the latest spectrum as a line on top, and below it every row so far, newest at
 * the top, from dark (noise) to bright (a strong signal). The colour scale follows the noise floor, so
 * it reads the same with any antenna. Tap to pick a frequency ([onTap]); pinch to zoom the picture, drag to move it.
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
    private var layoutW = 0; private var layoutH = 0
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

    /** Pinch zoom of the picture (1 = everything) and the first column shown, in columns. */
    private var zoom = 1f
    private var panX = 0f
    private fun colAt(sx: Float) = (panX + sx / zoom).toInt().coerceIn(0, (colHz.size - 1).coerceAtLeast(0))
    private fun screenX(col: Float) = (col - panX) * zoom
    private fun clampPan() { panX = panX.coerceIn(0f, (width - width / zoom).coerceAtLeast(0f)) }
    private var focusX = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { focusX = d.focusX; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Like a map: the frequency under the fingers stays under them, and moving both fingers moves the picture.
            val at = panX + focusX / zoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
            panX = at - d.focusX / zoom
            focusX = d.focusX
            clampPan(); invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false } // a double tap would also be two taps that tune

    init {
        // Screen readers: double tap zooms in on the strongest signal shown, as tapping on it would.
        ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            "zoom in on the strongest signal") { _, _ -> strongest()?.let { onTap?.invoke(it); true } ?: false }
    }

    /** The frequency of the strongest signal on screen, if any. */
    private fun strongest(): Long? {
        val s = spectrum ?: return null
        if (colHz.size != s.size) return null
        val x = (colAt(0f)..colAt(width - 1f)).maxByOrNull { s[it] } ?: return null
        return if (s[x] > SdrWaterfall.NO_DATA) colHz[x] else null
    }

    private val spectrumH get() = (height * 0.28f).coerceAtLeast(64 * density)
    private val labelH get() = textPaint.textSize + 6 * density
    private val waterTop get() = spectrumH + labelH

    /** Forgets every row (the view switched between the sweep and a single frequency). */
    fun clear() {
        layoutKey = ""; rows = 0; spectrum = null; peak = null; floor = Float.NaN; zoom = 1f; panX = 0f
        bitmap?.eraseColor(Color.BLACK)
        invalidate()
    }

    /** Adds the newest row; call on the main thread, then [invalidate]. */
    fun add(row: SdrWaterfall.Row) {
        val w = width; if (w <= 0 || height <= waterTop + 8 * density) return
        val key = row.tuned.toString() + row.segments.joinToString { "${it.startHz}-${it.endHz}" }
        if (key != layoutKey || w != layoutW || height != layoutH) relayout(row, w, key)
        // The sweep: each chunk against its own noise floor. The dongle hears more noise on some bands (VHF most),
        // and one scale for all made those bands glow and the others look dead.
        val dbs = row.segments.map { s ->
            if (row.tuned) s.db else {
                val heard = s.db.filter { it > SdrWaterfall.NO_DATA }.sorted()
                val noise = if (heard.isEmpty()) 0f else heard[heard.size / 2]
                FloatArray(s.db.size) { if (s.db[it] <= SdrWaterfall.NO_DATA) SdrWaterfall.NO_DATA else s.db[it] - noise }
            }
        }
        // One value per column: the strongest bin under it, so a narrow signal never falls between pixels.
        val values = FloatArray(w) { x ->
            val d = dbs[colSeg[x]]
            var m = -200f
            for (k in colFrom[x]..colTo[x]) if (d[k] > m) m = d[k]
            m
        }
        val heard = values.filter { it > SdrWaterfall.NO_DATA }.sorted()
        if (heard.isNotEmpty()) {
            val median = heard[heard.size / 2]
            floor = if (floor.isNaN()) median else floor + (median - floor) * (if (row.tuned) 0.05f else 0.3f)
        }
        spectrum = values
        peak = peak?.let { p -> FloatArray(w) { maxOf(values[it], p[it] - (if (row.tuned) 0.15f else 1.5f)) } } ?: values.copyOf()
        val bmp = bitmap ?: return
        if (floor.isNaN()) return
        for (x in 0 until w) pixels[x] = if (values[x] <= SdrWaterfall.NO_DATA) PALETTE[0] else PALETTE[(((values[x] - floor + 3f) / RANGE_DB) * 255).toInt().coerceIn(0, 255)]
        newest = (newest - 1 + bmp.height) % bmp.height
        bmp.setPixels(pixels, 0, w, 0, newest, w, 1)
        rows = minOf(rows + 1, bmp.height)
    }

    private fun relayout(row: SdrWaterfall.Row, w: Int, key: String) {
        // Only the size changed (the phone turned): the rows so far are kept, stretched to the new width.
        val old = bitmap?.takeIf { key == layoutKey && rows > 0 && !it.isRecycled }
        val oldRows = rows; val oldNewest = newest
        layoutKey = key; layoutW = w; layoutH = height; tuned = row.tuned
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
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        pixels = IntArray(w); newest = 0; rows = 0; spectrum = null; peak = null
        if (old != null) {
            // Newest first from row 0, as the screen shows them; the next row goes in above (ring buffer).
            val c = Canvas(fresh)
            val keep = minOf(oldRows, h)
            val first = minOf(keep, old.height - oldNewest)
            c.drawBitmap(old, Rect(0, oldNewest, old.width, oldNewest + first), Rect(0, 0, w, first), bitmapPaint)
            if (keep > first) c.drawBitmap(old, Rect(0, 0, old.width, keep - first), Rect(0, first, w, keep), bitmapPaint)
            rows = keep
        } else {
            floor = Float.NaN
            // Zoomed in on a frequency and moved to another: keep the zoom, centred on it. New sweep bands: show them all.
            if (row.tuned) panX = (w - w / zoom) / 2 else { zoom = 1f; panX = 0f }
        }
        clampPan()
        bitmap?.recycle()
        bitmap = fresh
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // The next row lays it out again at the new size, keeping the rows so far.
        if (w != oldw || h != oldh) invalidate()
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
        // Pinch zoom: columns stretched by [zoom] from [panX]; lines are drawn in screen pixels so they stay thin.
        val m = Matrix().apply { setScale(zoom, 1f); postTranslate(-panX * zoom, 0f) }
        if (bmp != null && rows > 0) {
            canvas.save(); canvas.concat(m)
            // Newest row at the top: the bitmap from [newest] down, then its start.
            val scale = (height - top) / bmp.height
            val firstRows = minOf(rows, bmp.height - newest)
            canvas.drawBitmap(bmp, Rect(0, newest, bmp.width, newest + firstRows),
                android.graphics.RectF(0f, top, w, top + firstRows * scale), bitmapPaint)
            val rest = rows - firstRows
            if (rest > 0) canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, rest),
                android.graphics.RectF(0f, top + firstRows * scale, w, top + rows * scale), bitmapPaint)
            canvas.restore()
        }
        for (g in gaps) screenX(g.toFloat()).let { canvas.drawLine(it, 0f, it, height.toFloat(), gapPaint) }
        val s = spectrum
        if (s != null && !floor.isNaN()) {
            val h = spectrumH - 4 * density
            fun y(v: Float) = 2 * density + h * (1f - ((v - floor + 3f) / (RANGE_DB + 10f)).coerceIn(0f, 1f))
            // A chunk that gave no reading leaves a gap, not a flat line along the bottom that looks like a signal.
            fun line(a: FloatArray) = Path().apply {
                var pen = false
                a.forEachIndexed { x, v ->
                    if (v <= SdrWaterfall.NO_DATA || s[x] <= SdrWaterfall.NO_DATA) pen = false
                    else if (pen) lineTo(x.toFloat(), y(v)) else { moveTo(x.toFloat(), y(v)); pen = true }
                }
                transform(m)
            }
            peak?.let { canvas.drawPath(line(it), peakPaint) }
            canvas.drawPath(line(s), linePaint)
        }
        drawLabels(canvas)
        // How far in, and how to get back out: shown only while zoomed.
        if (zoom > 1.05f) {
            val t = String.format(Locale.US, "%.0f× · pinch out to see all", zoom)
            canvas.drawText(t, width - textPaint.measureText(t) - 2 * density, textPaint.textSize, textPaint)
        }
    }

    /** Frequencies under the spectrum: each band's start, and the right-hand end. */
    private fun drawLabels(canvas: Canvas) {
        if (colHz.isEmpty()) return
        val y = spectrumH + textPaint.textSize
        // The first, last and band-start frequencies of what is on screen (all of it unless zoomed).
        val first = colAt(0f); val last = colAt(width - 1f)
        val marks = if (tuned) listOf(first, colAt(width / 2f), last) else (listOf(first) + gaps.filter { it in first + 1..last } + last).distinct()
        var lastEnd = Float.NEGATIVE_INFINITY // -1 hid the first label: 0 is closer than 8 dp to it
        for ((i, x) in marks.withIndex()) {
            val t = mhz(colHz[x])
            val tw = textPaint.measureText(t)
            val left = when (i) { 0 -> 0f; marks.lastIndex -> width - tw; else -> (screenX(x.toFloat()) - tw / 2).coerceIn(0f, width - tw) }
            if (left < lastEnd + 8 * density) continue
            canvas.drawText(t, left, y, textPaint)
            lastEnd = left + tw
        }
    }

    private fun mhz(hz: Long) = String.format(Locale.US, if (tuned || zoom > 2f) "%.3f" else "%.1f", hz / 1e6)

    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    /** This touch pinched or dragged, so lifting the finger is not a tap. */
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; moved = false }
            MotionEvent.ACTION_POINTER_DOWN -> { moved = true; parent?.requestDisallowInterceptTouchEvent(true) }
            // One finger left of a pinch: drag on from where it is now, without a jump.
            MotionEvent.ACTION_POINTER_UP -> lastX = e.getX(if (e.actionIndex == 0) 1 else 0)
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                if (abs(e.x - downX) >= 16 * density || abs(e.y - downY) >= 16 * density) moved = true
                if (moved && zoom > 1f) { panX -= (e.x - lastX) / zoom; clampPan(); invalidate() }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> if (!moved && colHz.isNotEmpty()) { onTap?.invoke(colHz[colAt(e.x)]); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Keyboard and D-pad: left / right move the zoomed-in view by [KEY_STEP_HZ]. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val step = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -KEY_STEP_HZ
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_STEP_HZ
            else -> return super.onKeyDown(keyCode, event)
        }
        if (!tuned || colHz.isEmpty()) return super.onKeyDown(keyCode, event)
        onTap?.invoke(colHz[colHz.size / 2] + step)
        return true
    }

    companion object {
        /** dB above the noise floor that reach the top of the colour scale. */
        private const val RANGE_DB = 40f
        private const val KEY_STEP_HZ = 100_000L
        private const val MAX_ZOOM = 8f

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
