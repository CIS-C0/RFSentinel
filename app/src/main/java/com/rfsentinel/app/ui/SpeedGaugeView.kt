package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The map's speedometer: your speed in a rounded card, and beside it the posted limit as a road sign (a red ring,
 * as in most of the world, or a white plate with "SPEED LIMIT" where distances are in miles). Your speed turns red
 * while you are over the limit. Sized from the screen, so it suits a small phone and a tablet alike; draws nothing
 * until it has a speed.
 */
class SpeedGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var speed: Int? = null
    private var limit: Int? = null
    private var miles = false
    private var dark = false
    private val d = resources.displayMetrics.density

    /** The card's height: about a sixth of the screen's short side, between 52 and 84 dp. */
    private fun unit(): Float {
        val shortSide = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        return (shortSide / 6.2f).coerceIn(52 * d, 84 * d)
    }

    /** [speedKmh] null = no speed yet (hidden); [limitKmh] null = no limit known (no sign). Both in km/h; shown in mph when [miles]. */
    fun set(speedKmh: Float?, limitKmh: Int?, miles: Boolean, dark: Boolean) {
        val s = speedKmh?.let { (if (miles) it / 1.609344f else it).roundToInt().coerceAtLeast(0) }
        val l = limitKmh?.let { com.rfsentinel.app.nav.SpeedLimits.shown(it, miles) }
        val want = if (s == null) GONE else VISIBLE
        if (visibility != want) visibility = want
        if (s == speed && l == limit && miles == this.miles && dark == this.dark) return
        speed = s; limit = l; this.miles = miles; this.dark = dark
        contentDescription = if (s == null) null else
            "Speed $s ${if (miles) "miles per hour" else "kilometres per hour"}" + (l?.let { ", limit $it" } ?: "")
        requestLayout(); invalidate()
    }

    /** Over the posted limit. */
    internal val over: Boolean get() = speed != null && limit != null && speed!! > limit!!

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val u = unit()
        val w = if (limit != null) u * 1.0f + u * 0.12f + u * 0.92f else u
        setMeasuredDimension(w.toInt() + 1, u.toInt() + 1)
    }

    override fun onDraw(c: Canvas) {
        val s = speed ?: return
        val u = unit()
        val red = 0xFFD32F2F.toInt()
        // Over the limit the whole card turns red with white writing, as in Google Maps.
        val ink = if (over || dark) 0xFFFFFFFF.toInt() else 0xFF1B1F27.toInt()
        val bg = if (over) red else if (dark) 0xF0222A3A.toInt() else 0xF2FFFFFF.toInt()

        // The speed: a rounded card, the number big and the unit small under it.
        box.set(0f, 0f, u, u)
        paint.style = Paint.Style.FILL; paint.color = bg
        c.drawRoundRect(box, u * 0.22f, u * 0.22f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = (if (over) 2.5f else 1.5f) * d
        paint.color = if (over) 0xFFFFFFFF.toInt() else if (dark) 0x55FFFFFF else 0x33000000
        c.drawRoundRect(box, u * 0.22f, u * 0.22f, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.color = ink
        paint.textSize = u * (if (s >= 100) 0.40f else 0.48f)
        c.drawText(s.toString(), u / 2, u * 0.56f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.color = ink; paint.alpha = 170
        paint.textSize = u * 0.17f
        c.drawText(if (miles) "mph" else "km/h", u / 2, u * 0.82f, paint)
        paint.alpha = 255

        val l = limit ?: return
        val x0 = u + u * 0.12f
        val sign = u * 0.92f
        val cx = x0 + sign / 2; val cy = u / 2
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        if (!miles) {
            // The round sign: white, a thick red ring, the number.
            paint.style = Paint.Style.FILL; paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(cx, cy, sign / 2, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = sign * 0.13f; paint.color = red
            c.drawCircle(cx, cy, sign / 2 - sign * 0.065f, paint)
            paint.style = Paint.Style.FILL; paint.color = 0xFF111111.toInt()
            paint.textSize = sign * (if (l >= 100) 0.36f else 0.44f)
            c.drawText(l.toString(), cx, cy + paint.textSize * 0.35f, paint)
        } else {
            // The plate: white, a black border, "SPEED LIMIT" over the number.
            val h = sign * 1.0f; val w = sign * 0.82f
            box.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            paint.style = Paint.Style.FILL; paint.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(box, sign * 0.1f, sign * 0.1f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = sign * 0.045f; paint.color = 0xFF111111.toInt()
            c.drawRoundRect(box, sign * 0.1f, sign * 0.1f, paint)
            paint.style = Paint.Style.FILL; paint.color = 0xFF111111.toInt()
            paint.textSize = sign * 0.14f
            c.drawText("SPEED", cx, box.top + h * 0.22f, paint)
            c.drawText("LIMIT", cx, box.top + h * 0.35f, paint)
            paint.textSize = sign * 0.46f
            c.drawText(l.toString(), cx, box.top + h * 0.80f, paint)
        }
    }
}
