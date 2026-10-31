package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.location.Location
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R
import com.rfsentinel.app.detect.DeviceIntel
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar-style view of nearby devices. Distance from the centre = signal
 * strength (a stronger signal plots closer). The ANGLE is a stable position
 * derived from the address so blips don't jump around - a single phone
 * antenna cannot measure direction, and the view says so. An alert whose
 * position is known ([Blip.bearing]) plots in its real direction instead,
 * with a line from you and its distance. Heading-up from the phone's compass
 * (set right by the GPS course while driving, [HeadingFusion]), else north-up.
 */
class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** [bearing]: compass degrees from you to the device and [distanceM] how far, when its position is known.
     *  [ageMs]: since it was last heard (the blip fades). */
    data class Blip(val mac: String, val rssi: Int, val color: Int, val flagged: Boolean, val label: String?,
                    val bearing: Float? = null, val distanceM: Float? = null, val ageMs: Long = 0L)

    var onBlipClick: ((Blip) -> Unit)? = null
    /** Pinpoint is off in Settings: located alerts can't show, and the footer says how to turn it on. */
    var pinpointOff = false

    /** Drawing order (ordinary first, flagged on top) with each blip's label lines, built once per [setBlips]. */
    private class Item(val b: Blip, val title: String?, val detail: String?)
    private var items: List<Item> = emptyList()
    /** Flagged ones with a label, strongest first: the closest keeps its label when two would overlap. */
    private var labelled: List<Item> = emptyList()
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private val placed = ArrayList<RectF>()
    private var directional = false
    private var unplaced = false

    private val compass = Compass(context)
    private val fusion = HeadingFusion()
    private var fixTime = 0L
    /** Compass degrees at the top, null for north-up. */
    private var heading: Float? = null

    private var sweep = 0f
    private val density = resources.displayMetrics.density
    private val scope = MaterialColors.getColor(context, R.attr.rfScopeColor, 0xFF0B5C63.toInt())
    private val scopeText = MaterialColors.getColor(context, R.attr.rfScopeTextColor, 0xAA0B5C63.toInt())
    private val you = MaterialColors.getColor(context, R.attr.rfTraceColor, 0xFFE0622D.toInt())
    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = alpha(scope, 0x55)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = scopeText
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        isFakeBoldText = true
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        color = scopeText
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val youPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = you }
    private val ordinaryColor = scope
    private val sweepColor = alpha(scope, 0x55)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sweepPaint.shader = SweepGradient(
            cx(), cy(), intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, sweepColor), floatArrayOf(0f, 0.75f, 1f)
        )
    }

    // The compass runs only while the radar is on screen.
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) compass.start() else compass.stop()
    }

    override fun onDetachedFromWindow() {
        compass.stop()
        super.onDetachedFromWindow()
    }

    fun ordinaryColor() = ordinaryColor

    fun setBlips(list: List<Blip>) {
        items = list.sortedBy { it.flagged }.map { b ->
            val title = b.label?.take(22)
            Item(b, title, if (b.flagged && b.distanceM != null && b.bearing != null)
                DeviceIntel.formatDistance(b.distanceM.toDouble()) + " " + Sweep.compassPoint(b.bearing) else null)
        }
        labelled = items.filter { it.b.flagged && it.title != null }.sortedByDescending { it.b.rssi }
        if (xs.size < items.size) { xs = FloatArray(items.size); ys = FloatArray(items.size) }
        directional = list.any { it.bearing != null }
        unplaced = list.any { it.flagged && it.bearing == null }
        val alerts = list.count { it.flagged }
        contentDescription = "Radar: ${list.size} devices, $alerts alerts" + labelled.filter { it.detail != null }
            .joinToString("") { ", ${it.title} ${it.detail}" }
        invalidate()
    }

    /** The latest GPS fix: the course while driving and true north for the compass. */
    fun setFix(fix: Location?) {
        if (fix == null || fix.time == fixTime) return
        fixTime = fix.time
        compass.setLocation(fix.latitude, fix.longitude, fix.altitude)
        val course = fix.bearing.takeIf { fix.hasBearing() && (!fix.hasBearingAccuracy() || fix.bearingAccuracyDegrees <= 30f) }
        fusion.fix(System.currentTimeMillis(), course, if (fix.hasSpeed()) fix.speed else 0f)
    }

    private fun cx() = width / 2f
    private fun cy() = (height - 28 * density) / 2f
    private fun radius() = min(width, (height - 28 * density).toInt()) / 2f - 8 * density

    /** Maps RSSI (-30 strong ... -100 weak) to 0.08..0.96 of the radius. */
    private fun rssiToFraction(rssi: Int): Float =
        (0.08f + (((-30 - rssi).coerceIn(0, 70)) / 70f) * 0.88f)

    private fun angleOf(mac: String): Double {
        val h = mac.hashCode()
        return ((h and 0x7fffffff) % 3600) / 3600.0 * 2 * Math.PI
    }

    /** Screen angle (0 = right, clockwise) of a compass bearing, with up = [heading] or north. */
    private fun screenAngle(bearing: Float) = Math.toRadians((bearing - (heading ?: 0f) - 90f).toDouble())

    /** Where each blip goes now, into [xs] / [ys] (the heading turns the located ones). */
    private fun place() {
        val cx = cx(); val cy = cy(); val rad = radius()
        for ((i, it) in items.withIndex()) {
            val r = rad * rssiToFraction(it.b.rssi)
            val a = it.b.bearing?.let { screenAngle(it) } ?: angleOf(it.b.mac)
            xs[i] = cx + (r * cos(a)).toFloat()
            ys[i] = cy + (r * sin(a)).toFloat()
        }
    }

    /** Full until 5 s unheard, down to a third at a minute. */
    private fun fade(c: Int, ageMs: Long): Int {
        val f = 1f - 0.65f * ((ageMs - 5_000L).coerceIn(0L, 55_000L) / 55_000f)
        return alpha(c, ((c ushr 24) * f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = cx(); val cy = cy()
        val rad = radius()
        if (rad <= 0) return
        val now = System.currentTimeMillis()
        fusion.compass(compass.degrees)
        heading = fusion.heading(now)

        // Signal bands: Near (>= -60), Nearby (>= -80), Far.
        val near = rad * rssiToFraction(-60)
        val nearby = rad * rssiToFraction(-80)
        canvas.drawCircle(cx, cy, near, ringPaint)
        canvas.drawCircle(cx, cy, nearby, ringPaint)
        canvas.drawCircle(cx, cy, rad, ringPaint)
        canvas.drawLine(cx - rad, cy, cx + rad, cy, ringPaint)
        canvas.drawLine(cx, cy - rad, cx, cy + rad, ringPaint)
        canvas.drawText("Near", cx + 8 * density, cy - near + 12 * density, textPaint)
        canvas.drawText("Nearby", cx + 8 * density, cy - nearby + 12 * density, textPaint)
        canvas.drawText("Far", cx + 8 * density, cy - rad + 12 * density, textPaint)

        // Sweep.
        canvas.save()
        canvas.rotate(sweep, cx, cy)
        canvas.drawCircle(cx, cy, rad, sweepPaint)
        canvas.restore()

        // North on the outer ring (it turns with the heading), and which way the radar is turned.
        val n = screenAngle(0f)
        canvas.drawText("N", cx + ((rad - 12 * density) * cos(n)).toFloat(),
            cy + ((rad - 12 * density) * sin(n)).toFloat() + 4 * density, northPaint)
        canvas.drawText(if (heading != null) "Heading up" else "North up", 8 * density, 14 * density, textPaint)

        val t = (now % 1000L) / 1000f
        place()
        // Ordinary devices first so flagged ones draw on top.
        for ((i, it) in items.withIndex()) {
            val b = it.b
            val x = xs[i]; val y = ys[i]
            val color = fade(b.color, b.ageMs)
            blipPaint.color = color
            if (b.bearing != null) {
                pulsePaint.color = fade(alpha(b.color, 0x99), b.ageMs)
                canvas.drawLine(cx, cy, x, y, pulsePaint)
            }
            if (b.flagged) {
                pulsePaint.color = alpha(color, ((1f - t) * 200 * (color ushr 24) / 255).toInt())
                canvas.drawCircle(x, y, (7 + 14 * t) * density, pulsePaint)
                canvas.drawCircle(x, y, 7 * density, blipPaint)
            } else {
                canvas.drawCircle(x, y, 4 * density, blipPaint)
            }
        }
        canvas.drawCircle(cx, cy, 5 * density, youPaint)
        drawLabels(canvas)

        val footer = footer(width - 16 * density, now)
        canvas.drawText(footer, 8 * density, height - 8 * density, textPaint)

        sweep = (sweep + 2.4f) % 360f
        if (isShown) postInvalidateOnAnimation()
    }

    /** Alert names (and distance + direction when located) beside their blip, flipped left near the right edge;
     *  one that would overlap a label already drawn is left out (tap the blip for it). */
    private fun drawLabels(canvas: Canvas) {
        var used = 0
        val edge = 4 * density
        for (it in labelled) {
            val i = items.indexOf(it)
            val x = xs[i]; val y = ys[i]
            val title = it.title ?: continue
            val w = maxOf(labelPaint.measureText(title), it.detail?.let { d -> detailPaint.measureText(d) } ?: 0f)
            val gap = 10 * density
            var lx = if (x + gap + w > width - edge) x - gap - w else x + gap
            lx = lx.coerceIn(edge, (width - edge - w).coerceAtLeast(edge))
            if (used == placed.size) placed += RectF()
            val box = placed[used]
            box.set(lx, y - 9 * density, lx + w, y + (if (it.detail != null) 15 else 4) * density)
            if ((0 until used).any { j -> RectF.intersects(placed[j], box) }) continue
            used++
            val color = fade(it.b.color, it.b.ageMs)
            labelPaint.color = color
            canvas.drawText(title, lx, y + 4 * density, labelPaint)
            it.detail?.let { d ->
                detailPaint.color = color
                canvas.drawText(d, lx, y + 15 * density, detailPaint)
            }
        }
    }

    /** The hint under the radar, the long form when it fits the width, else the short one. */
    private fun footer(room: Float, now: Long): String {
        fun pick(long: String, short: String) = if (textPaint.measureText(long) <= room) long else short
        return when {
            directional && heading != null && compass.unreliable && !fusion.gpsSet(now) ->
                pick("Compass needs calibrating: wave the phone in a figure 8", "Calibrate: wave phone in a figure 8")
            pinpointOff && unplaced -> pick("Pinpoint is off: Settings › Location shows where alerts are", "Pinpoint off: Settings › Location")
            directional -> pick("Closer = stronger signal · lines point to located alerts", "Lines point to located alerts")
            else -> pick("Closer to centre = stronger signal · angle is not direction", "Closer = stronger · angle isn't direction")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            place()
            // Within reach, an alert beats an ordinary device next to it; then the nearest.
            val reach = 28 * density
            var hit = -1; var hitD = 0f
            for (i in items.indices) {
                val d = hypot(xs[i] - event.x, ys[i] - event.y)
                if (d > reach) continue
                val better = hit < 0 || (items[i].b.flagged && !items[hit].b.flagged) ||
                    (items[i].b.flagged == items[hit].b.flagged && d < hitD)
                if (better) { hit = i; hitD = d }
            }
            if (hit >= 0) {
                performClick()
                onBlipClick?.invoke(items[hit].b)
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
