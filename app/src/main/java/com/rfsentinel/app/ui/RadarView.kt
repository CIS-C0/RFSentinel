package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar-style view of nearby devices. Distance from the centre = signal
 * strength (a stronger signal plots closer). The ANGLE is a stable position
 * derived from the address so blips don't jump around - a single phone
 * antenna cannot measure direction, and the view says so.
 */
class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Blip(val mac: String, val rssi: Int, val color: Int, val flagged: Boolean, val label: String?)

    var onBlipClick: ((String) -> Unit)? = null

    private var blips: List<Blip> = emptyList()
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
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val youPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = you }
    private val ordinaryColor = scope
    private val sweepColor = alpha(scope, 0x55)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val (cx, cy) = center()
        sweepPaint.shader = SweepGradient(
            cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, sweepColor), floatArrayOf(0f, 0.75f, 1f)
        )
    }

    fun ordinaryColor() = ordinaryColor

    fun setBlips(list: List<Blip>) {
        blips = list
        invalidate()
    }

    private fun center() = Pair(width / 2f, (height - 28 * density) / 2f)
    private fun radius() = min(width, (height - 28 * density).toInt()) / 2f - 8 * density

    /** Maps RSSI (-30 strong ... -100 weak) to 0.08..0.96 of the radius. */
    private fun rssiToFraction(rssi: Int): Float =
        (0.08f + (((-30 - rssi).coerceIn(0, 70)) / 70f) * 0.88f)

    private fun angleOf(mac: String): Double {
        val h = mac.hashCode()
        return ((h and 0x7fffffff) % 3600) / 3600.0 * 2 * Math.PI
    }

    private fun position(b: Blip): Pair<Float, Float> {
        val (cx, cy) = center()
        val r = radius() * rssiToFraction(b.rssi)
        val a = angleOf(b.mac)
        return Pair(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (cx, cy) = center()
        val rad = radius()
        if (rad <= 0) return

        // Signal bands: Near (>= -60), Nearby (>= -80), Far.
        val near = rad * rssiToFraction(-60)
        val nearby = rad * rssiToFraction(-80)
        canvas.drawCircle(cx, cy, near, ringPaint)
        canvas.drawCircle(cx, cy, nearby, ringPaint)
        canvas.drawCircle(cx, cy, rad, ringPaint)
        canvas.drawLine(cx - rad, cy, cx + rad, cy, ringPaint)
        canvas.drawLine(cx, cy - rad, cx, cy + rad, ringPaint)
        canvas.drawText("Near", cx + 4 * density, cy - near + 12 * density, textPaint)
        canvas.drawText("Nearby", cx + 4 * density, cy - nearby + 12 * density, textPaint)
        canvas.drawText("Far", cx + 4 * density, cy - rad + 12 * density, textPaint)

        // Sweep.
        canvas.save()
        canvas.rotate(sweep, cx, cy)
        canvas.drawCircle(cx, cy, rad, sweepPaint)
        canvas.restore()

        val t = (System.currentTimeMillis() % 1000L) / 1000f
        // Ordinary devices first so flagged ones draw on top.
        for (b in blips.sortedBy { it.flagged }) {
            val (x, y) = position(b)
            blipPaint.color = b.color
            if (b.flagged) {
                pulsePaint.color = (b.color and 0x00FFFFFF) or (((1f - t) * 200).toInt() shl 24)
                canvas.drawCircle(x, y, (7 + 14 * t) * density, pulsePaint)
                canvas.drawCircle(x, y, 7 * density, blipPaint)
                b.label?.let {
                    labelPaint.color = b.color
                    canvas.drawText(it.take(22), x + 10 * density, y + 4 * density, labelPaint)
                }
            } else {
                canvas.drawCircle(x, y, 4 * density, blipPaint)
            }
        }
        canvas.drawCircle(cx, cy, 5 * density, youPaint)

        canvas.drawText(
            "Closer to centre = stronger signal · angle is not direction",
            8 * density, height - 8 * density, textPaint
        )

        sweep = (sweep + 2.4f) % 360f
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val hit = blips.minByOrNull { b ->
                val (x, y) = position(b)
                hypot(x - event.x, y - event.y)
            }
            if (hit != null) {
                val (x, y) = position(hit)
                if (hypot(x - event.x, y - event.y) <= 28 * density) {
                    performClick()
                    onBlipClick?.invoke(hit.mac)
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
