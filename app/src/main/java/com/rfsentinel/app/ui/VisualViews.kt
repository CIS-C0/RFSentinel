package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.rfsentinel.app.detect.GnssAnalyzer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Shared bits of the visual-style views. */
private val View.dp get() = resources.displayMetrics.density
private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

/** Four signal bars, filled up to the level of [dbm], for list rows. */
class SignalBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var level = 0f
    private var color = ChipStyle.accent(context).first
    private val ink = ChipStyle.onSurface(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(dbm: Int?, color: Int? = null) {
        level = VisualMode.dbmLevel(dbm)
        this.color = color ?: VisualMode.levelColor(level)
        contentDescription = dbm?.let { "$it dBm" } ?: "no signal"
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(resolveSize((26 * dp).toInt(), w), resolveSize((20 * dp).toInt(), h))

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val gap = 2.5f * dp
        val bw = (width - gap * (n - 1)) / n
        val lit = if (level <= 0f) 0 else (level * n + 0.5f).toInt().coerceIn(1, n)
        for (i in 0 until n) {
            val h = height * (i + 1f) / n
            rect.set(i * (bw + gap), height - h, i * (bw + gap) + bw, height.toFloat())
            paint.color = if (i < lit) color else alpha(ink, 0x30)
            canvas.drawRoundRect(rect, 1.5f * dp, 1.5f * dp, paint)
        }
    }
}

/** A thin horizontal bar filled to [fraction], for confidence on list rows. */
class LevelBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var fraction = 0f
    private var color = ChipStyle.accent(context).first
    private val ink = ChipStyle.onSurface(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(fraction: Float, color: Int) { this.fraction = fraction.coerceIn(0f, 1f); this.color = color; invalidate() }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize((4 * dp).toInt(), h))

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = alpha(ink, 0x22); canvas.drawRoundRect(rect, r, r, paint)
        if (fraction > 0f) {
            rect.right = width * fraction
            paint.color = color; canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/** A ring filled to [percent] with the number in the middle and a caption under it. */
class ConfidenceRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var percent = 0
    private var caption = ""
    private var color = ChipStyle.accent(context).first
    private var center = ""
    private val ink = ChipStyle.onSurface(context)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()
    private var shown = 0f

    /** [centerText] replaces "NN%" in the middle (a countdown "42 s"). */
    fun set(percent: Int, color: Int, caption: String, centerText: String? = null) {
        this.percent = percent.coerceIn(0, 100); this.color = color; this.caption = caption
        center = centerText ?: "${this.percent}%"
        contentDescription = "$center $caption"
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = resolveSize((112 * dp).toInt(), w)
        setMeasuredDimension(s, resolveSize(s, h))
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = min(width, height) * 0.09f
        ring.strokeWidth = stroke
        val r = min(width, height) / 2f - stroke
        val cx = width / 2f; val cy = height / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        ring.color = alpha(ink, 0x22)
        canvas.drawArc(oval, 0f, 360f, false, ring)
        shown += (percent - shown) * 0.2f
        if (abs(percent - shown) > 0.3f) postInvalidateOnAnimation() else shown = percent.toFloat()
        ring.color = color
        if (shown > 0f) canvas.drawArc(oval, -90f, 360f * shown / 100f, false, ring)
        text.color = color
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = r * 0.55f
        if (text.measureText(center) > r * 1.6f) text.textSize *= r * 1.6f / text.measureText(center)
        canvas.drawText(center, cx, cy + text.textSize * 0.2f, text)
        text.typeface = Typeface.DEFAULT
        text.color = alpha(ink, 0xAA)
        text.textSize = r * 0.24f
        if (text.measureText(caption) > r * 1.5f) text.textSize *= r * 1.5f / text.measureText(caption)
        canvas.drawText(caption, cx, cy + r * 0.48f, text)
    }
}

/**
 * The device's signal as a half-dial (−100 to −30 dBm) that warms from blue to red as it gets
 * stronger, with the reading in the middle and whether you're getting closer: a hot / cold finder.
 */
class SignalGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var dbm: Int? = null
    private var best: Int? = null
    private var trend = 0
    private val ink = ChipStyle.onSurface(context)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()
    private var shown = 0f

    /** [trend]: 1 getting closer, -1 moving away, 0 steady. */
    fun set(dbm: Int?, best: Int?, trend: Int) {
        this.dbm = dbm; this.best = best; this.trend = trend
        contentDescription = dbm?.let { "$it dBm, " + trendText() } ?: "not heard"
        invalidate()
    }

    private fun trendText() = when (trend) { 1 -> "getting closer"; -1 -> "moving away"; else -> "steady" }

    /** Cold blue at the weakest, warm red at the strongest. */
    private fun heat(level: Float): Int {
        val stops = intArrayOf(0xFF3D7BD9.toInt(), 0xFF2EA6A0.toInt(), 0xFFE0A33A.toInt(), 0xFFD0503C.toInt())
        val x = level.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        return android.animation.ArgbEvaluator().evaluate(x - i, stops[i], stops[i + 1]) as Int
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, resolveSize((width * 0.62f).toInt().coerceIn((150 * dp).toInt(), (230 * dp).toInt()), h))
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = 16 * dp
        val r = min(width / 2f - stroke, height - stroke - 30 * dp)
        val cx = width / 2f; val cy = stroke / 2 + r + 4 * dp
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arc.strokeWidth = stroke
        // The track, in ticks of colour so the scale reads at a glance.
        val steps = 28
        for (i in 0 until steps) {
            arc.color = alpha(heat(i / (steps - 1f)), 0x40)
            canvas.drawArc(oval, 180f + i * 180f / steps + 1f, 180f / steps - 2f, false, arc)
        }
        val target = VisualMode.dbmLevel(dbm)
        shown += (target - shown) * 0.2f
        if (abs(target - shown) > 0.003f) postInvalidateOnAnimation() else shown = target
        val lit = (shown * steps).toInt()
        for (i in 0 until lit) {
            arc.color = heat(i / (steps - 1f))
            canvas.drawArc(oval, 180f + i * 180f / steps + 1f, 180f / steps - 2f, false, arc)
        }
        // Best reading so far: a small notch.
        best?.let {
            val a = Math.toRadians(180.0 + 180.0 * VisualMode.dbmLevel(it))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 2.5f * dp }
            canvas.drawLine(cx + ((r - stroke) * cos(a)).toFloat(), cy + ((r - stroke) * sin(a)).toFloat(),
                cx + ((r + stroke) * cos(a)).toFloat(), cy + ((r + stroke) * sin(a)).toFloat(), p)
        }
        val color = if (dbm == null) alpha(ink, 0x88) else heat(target)
        text.color = color
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = r * 0.42f
        canvas.drawText(dbm?.toString() ?: "--", cx, cy - r * 0.12f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 12 * dp
        text.color = alpha(ink, 0xAA)
        canvas.drawText("dBm", cx, cy + 8 * dp, text)
        if (dbm != null) {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = 15 * dp
            text.color = when (trend) { 1 -> 0xFFD0503C.toInt(); -1 -> 0xFF3D7BD9.toInt(); else -> alpha(ink, 0xCC) }
            val label = when (trend) { 1 -> "▲ Getting closer"; -1 -> "▼ Moving away"; else -> "● Steady" }
            canvas.drawText(label, cx, cy + 28 * dp, text)
        }
        text.typeface = Typeface.DEFAULT
        text.textSize = 10 * dp
        text.color = alpha(ink, 0x88)
        canvas.drawText("−100", cx - r, cy + 18 * dp, text)
        canvas.drawText("−30", cx + r, cy + 18 * dp, text)
    }
}

/** A line of recent values (NaN = gap), scaled to its own range, filled underneath. */
class SparklineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var values = FloatArray(0)
    private var color = ChipStyle.accent(context).first
    private var minRange = 1f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()

    /** [minRange]: the smallest span the height covers, so a steady value stays a flat line. */
    fun set(values: FloatArray, color: Int, minRange: Float = 1f) {
        this.values = values; this.color = color; this.minRange = minRange; invalidate()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize((36 * dp).toInt(), h))

    override fun onDraw(canvas: Canvas) {
        line.strokeWidth = 1.8f * dp
        line.color = color
        val vals = values.filter { !it.isNaN() }
        val bottom = height - line.strokeWidth
        if (vals.size < 2) {
            line.alpha = 0x50
            canvas.drawLine(0f, bottom, width.toFloat(), bottom, line)
            return
        }
        var lo = vals.min(); var hi = vals.max()
        if (hi - lo < minRange) { val mid = (hi + lo) / 2; lo = mid - minRange / 2; hi = mid + minRange / 2 }
        val top = line.strokeWidth
        path.reset(); area.reset()
        var started = false; var lastX = 0f
        values.forEachIndexed { i, v ->
            if (v.isNaN()) return@forEachIndexed
            val x = width * i / (values.size - 1).coerceAtLeast(1).toFloat()
            val y = bottom - (bottom - top) * (v - lo) / (hi - lo)
            if (!started) { path.moveTo(x, y); area.moveTo(x, height.toFloat()); area.lineTo(x, y); started = true }
            else { path.lineTo(x, y); area.lineTo(x, y) }
            lastX = x
        }
        area.lineTo(lastX, height.toFloat()); area.close()
        fill.color = alpha(color, 0x30)
        canvas.drawPath(area, fill)
        line.alpha = 0xFF
        canvas.drawPath(path, line)
    }
}

/** Satellite colours by system, shared by the sky plot and the signal bars. */
object GnssColors {
    fun of(s: GnssAnalyzer.System): Int = when (s) {
        GnssAnalyzer.System.GPS -> 0xFF3D7BD9.toInt()
        GnssAnalyzer.System.GLONASS -> 0xFFD0503C.toInt()
        GnssAnalyzer.System.GALILEO -> 0xFFE0A33A.toInt()
        GnssAnalyzer.System.BEIDOU -> 0xFF9C5BD0.toInt()
        GnssAnalyzer.System.QZSS -> 0xFF2EA6A0.toInt()
        GnssAnalyzer.System.NAVIC -> 0xFF2E9E5B.toInt()
        else -> 0xFF8A8A8A.toInt()
    }
}

/**
 * Where each satellite sits in the sky: the outer ring is the horizon, the centre straight up,
 * north at the top. Filled dots are used for the position; size follows the signal.
 */
class SkyPlotView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var sats: List<GnssAnalyzer.Sat> = emptyList()
    private val ink = ChipStyle.onSurface(context)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun set(list: List<GnssAnalyzer.Sat>) {
        sats = list
        contentDescription = "${list.count { it.cn0 > 0f }} satellites in the sky plot"
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w).coerceAtMost((360 * dp).toInt())
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize(s, h))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 16 * dp
        grid.strokeWidth = 1.2f * dp
        grid.color = alpha(ink, 0x40)
        for (el in intArrayOf(0, 30, 60)) canvas.drawCircle(cx, cy, r * (90 - el) / 90f, grid)
        grid.color = alpha(ink, 0x25)
        canvas.drawLine(cx - r, cy, cx + r, cy, grid)
        canvas.drawLine(cx, cy - r, cx, cy + r, grid)
        text.textSize = 11 * dp
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = alpha(ink, 0xAA)
        canvas.drawText("N", cx, cy - r - 4 * dp, text)
        canvas.drawText("S", cx, cy + r + 13 * dp, text)
        canvas.drawText("E", cx + r + 9 * dp, cy + 4 * dp, text)
        canvas.drawText("W", cx - r - 9 * dp, cy + 4 * dp, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 9 * dp
        text.color = alpha(ink, 0x77)
        canvas.drawText("30°", cx + 3 * dp, cy - r * 60 / 90f - 2 * dp, text)
        canvas.drawText("60°", cx + 3 * dp, cy - r * 30 / 90f - 2 * dp, text)
        // Weakest first so strong ones sit on top.
        for (s in sats.filter { it.cn0 > 0f && it.elevation >= 0f }.sortedBy { it.cn0 }) {
            val rr = r * (90f - s.elevation.coerceIn(0f, 90f)) / 90f
            val a = Math.toRadians(s.azimuth.toDouble() - 90.0)
            val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
            val size = (3.5f + (s.cn0 - 15f).coerceIn(0f, 35f) / 35f * 5f) * dp
            val c = GnssColors.of(s.system)
            if (s.usedInFix) {
                dot.style = Paint.Style.FILL; dot.color = c
            } else {
                dot.style = Paint.Style.STROKE; dot.strokeWidth = 1.8f * dp; dot.color = alpha(c, 0xCC)
            }
            canvas.drawCircle(x, y, size, dot)
            text.color = alpha(ink, 0xCC)
            text.textSize = 8.5f * dp
            canvas.drawText("${s.svid}", x, y - size - 2 * dp, text)
        }
    }
}

/** One bar per satellite heard, its signal (dB-Hz) as the height, coloured by system; outlined when not used. */
class SatBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var sats: List<GnssAnalyzer.Sat> = emptyList()
    private val ink = ChipStyle.onSurface(context)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun set(list: List<GnssAnalyzer.Sat>) {
        sats = list.filter { it.cn0 > 0f }.sortedWith(compareBy<GnssAnalyzer.Sat> { it.system.ordinal }.thenByDescending { it.cn0 })
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize((150 * dp).toInt(), h))

    override fun onDraw(canvas: Canvas) {
        val labelH = 12 * dp
        val top = 14 * dp; val bottom = height - labelH
        text.textSize = 8.5f * dp
        text.color = alpha(ink, 0x77)
        val grid = Paint().apply { color = alpha(ink, 0x20); strokeWidth = dp }
        for (v in intArrayOf(20, 30, 40, 50)) {
            val y = bottom - (bottom - top) * (v - 10) / 45f
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
        }
        if (sats.isEmpty()) return
        // Only as many as fit at a readable width; the strongest of each system stay.
        val maxBars = (width / (9 * dp)).toInt().coerceAtLeast(1)
        val shown = if (sats.size <= maxBars) sats else sats.sortedByDescending { it.cn0 }.take(maxBars)
            .sortedWith(compareBy<GnssAnalyzer.Sat> { it.system.ordinal }.thenByDescending { it.cn0 })
        val slot = width.toFloat() / shown.size
        val bw = (slot * 0.7f).coerceAtMost(22 * dp)
        shown.forEachIndexed { i, s ->
            val c = GnssColors.of(s.system)
            val x = slot * i + (slot - bw) / 2
            val y = bottom - (bottom - top) * ((s.cn0 - 10f) / 45f).coerceIn(0.03f, 1f)
            rect.set(x, y, x + bw, bottom)
            if (s.usedInFix) { bar.style = Paint.Style.FILL; bar.color = c }
            else { bar.style = Paint.Style.STROKE; bar.strokeWidth = 1.5f * dp; bar.color = c; rect.inset(0.75f * dp, 0.75f * dp) }
            canvas.drawRoundRect(rect, 2 * dp, 2 * dp, bar)
            if (slot >= 14 * dp) {
                text.color = alpha(ink, 0xAA)
                canvas.drawText("${s.svid}", x + bw / 2, height - 2 * dp, text)
                canvas.drawText("%.0f".format(s.cn0), x + bw / 2, y - 3 * dp, text)
            }
        }
    }
}

/**
 * The aircraft around you seen from above: you in the middle, north up, rings at a quarter, half
 * and the whole of [rangeKm]. Each one points where it's flying, with its callsign and altitude;
 * law-enforcement ones in red.
 */
class AircraftRadarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    class Blip(val bearingDeg: Double, val distanceKm: Double, val trackDeg: Double?, val label: String, val altitude: String?, val police: Boolean)

    private var blips: List<Blip> = emptyList()
    private var rangeKm = 50.0
    private val ink = ChipStyle.onSurface(context)
    private val accent = ChipStyle.accent(context).first
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val plane = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun set(list: List<Blip>, rangeKm: Double) {
        blips = list; this.rangeKm = rangeKm
        contentDescription = "${list.size} aircraft within ${rangeKm.toInt()} km"
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w).coerceAtMost((420 * dp).toInt())
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize(s, h))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 14 * dp
        grid.strokeWidth = 1.2f * dp
        text.textAlign = Paint.Align.LEFT
        text.textSize = 9 * dp
        text.typeface = Typeface.DEFAULT
        for (k in 1..4) {
            grid.color = alpha(accent, if (k == 4) 0x77 else 0x35)
            canvas.drawCircle(cx, cy, r * k / 4f, grid)
            if (k % 2 == 0) {
                text.color = alpha(ink, 0x88)
                canvas.drawText("${(rangeKm * k / 4).toInt()} km", cx + 3 * dp, cy - r * k / 4f + 11 * dp, text)
            }
        }
        grid.color = alpha(accent, 0x25)
        canvas.drawLine(cx - r, cy, cx + r, cy, grid)
        canvas.drawLine(cx, cy - r, cx, cy + r, grid)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 11 * dp
        text.color = alpha(ink, 0xAA)
        canvas.drawText("N", cx, cy - r - 3 * dp, text)
        plane.color = accent
        canvas.drawCircle(cx, cy, 4 * dp, plane)

        for (b in blips.sortedBy { it.police }) {
            val f = (b.distanceKm / rangeKm).coerceAtMost(1.0)
            val a = Math.toRadians(b.bearingDeg - 90)
            val x = cx + (r * f * cos(a)).toFloat(); val y = cy + (r * f * sin(a)).toFloat()
            val c = if (b.police) 0xFFD0503C.toInt() else ink
            plane.color = c
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate((b.trackDeg ?: 0.0).toFloat())
            path.reset()
            val s = (if (b.police) 9f else 7f) * dp
            if (b.trackDeg != null) {
                path.moveTo(0f, -s); path.lineTo(s * 0.7f, s * 0.8f); path.lineTo(0f, s * 0.4f); path.lineTo(-s * 0.7f, s * 0.8f); path.close()
                canvas.drawPath(path, plane)
            } else canvas.drawCircle(0f, 0f, s * 0.5f, plane)
            canvas.restore()
            text.textAlign = Paint.Align.LEFT
            text.typeface = if (b.police) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = 10 * dp
            text.color = c
            val lx = if (x > cx + r * 0.5f) x - text.measureText(b.label) - s - 2 * dp else x + s + 2 * dp
            canvas.drawText(b.label, lx, y, text)
            b.altitude?.let {
                text.textSize = 9 * dp
                text.color = alpha(c, 0xAA)
                canvas.drawText(it, lx, y + 11 * dp, text)
            }
        }
    }
}

/** A trace's route as an outline (north up, kept to its own shape), with its flagged devices as dots. */
class RouteOutlineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var points: List<Pair<Double, Double>> = emptyList()
    private var flagged: List<Pair<Double, Double>> = emptyList()
    private var color = ChipStyle.accent(context).first
    private val ink = ChipStyle.onSurface(context)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun set(points: List<Pair<Double, Double>>, flagged: List<Pair<Double, Double>>, color: Int) {
        this.points = points; this.flagged = flagged; this.color = color
        contentDescription = if (points.size < 2) "No route" else "Route, ${flagged.size} flagged along it"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 6 * dp
        if (points.size < 2) {
            line.color = alpha(ink, 0x40); line.strokeWidth = 2 * dp
            canvas.drawLine(pad, height / 2f, width - pad, height / 2f, line)
            return
        }
        val lat0 = points.sumOf { it.first } / points.size
        val k = cos(Math.toRadians(lat0))
        val minX = points.minOf { it.second } * k; val maxX = points.maxOf { it.second } * k
        val minY = points.minOf { it.first }; val maxY = points.maxOf { it.first }
        val span = maxOf(maxX - minX, maxY - minY, 1e-6)
        val scale = min((width - 2 * pad) / (maxX - minX).coerceAtLeast(span * 0.05), (height - 2 * pad) / (maxY - minY).coerceAtLeast(span * 0.05))
        val ox = pad + ((width - 2 * pad) - (maxX - minX) * scale) / 2
        val oy = pad + ((height - 2 * pad) - (maxY - minY) * scale) / 2
        fun x(lon: Double) = (ox + (lon * k - minX) * scale).toFloat()
        fun y(lat: Double) = (oy + (maxY - lat) * scale).toFloat()
        path.reset()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.second), y(p.first)) else path.lineTo(x(p.second), y(p.first)) }
        line.color = color; line.strokeWidth = 2.5f * dp
        canvas.drawPath(path, line)
        dot.color = color
        canvas.drawCircle(x(points.first().second), y(points.first().first), 3.5f * dp, dot)
        dot.color = ink
        canvas.drawCircle(x(points.last().second), y(points.last().first), 3.5f * dp, dot)
        dot.color = 0xFFD0503C.toInt()
        for (f in flagged) canvas.drawCircle(x(f.second), y(f.first), 3f * dp, dot)
    }
}

/** Small bars, one per entry, each with its own colour (a timeline of checks); a stub where a value is 0. */
class MiniBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var values = FloatArray(0)
    private var colors = IntArray(0)
    private var slots = 20
    private val ink = ChipStyle.onSurface(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** [slots]: how many places the width is cut into (the newest bar sits at the right). */
    fun set(values: FloatArray, colors: IntArray, slots: Int) {
        this.values = values; this.colors = colors; this.slots = slots.coerceAtLeast(values.size).coerceAtLeast(1); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize((40 * dp).toInt(), h))

    override fun onDraw(canvas: Canvas) {
        val slot = width.toFloat() / slots
        val bw = slot * 0.65f
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        paint.color = alpha(ink, 0x25)
        canvas.drawRect(0f, height - dp, width.toFloat(), height.toFloat(), paint)
        val first = slots - values.size
        values.forEachIndexed { i, v ->
            val x = slot * (first + i) + (slot - bw) / 2
            val h = (height * v / max).coerceAtLeast(3 * dp)
            rect.set(x, height - h, x + bw, height.toFloat())
            paint.color = colors.getOrElse(i) { ink }
            canvas.drawRoundRect(rect, 2 * dp, 2 * dp, paint)
        }
    }
}
