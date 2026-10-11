package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** The map's scale: a bar of a round distance (50 m, 200 m, 1 km...) no wider than [maxWidthDp]. */
class ScaleBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        /** The roundest distance (1, 2 or 5 x 10^n metres) that fits in [maxMeters]. */
        internal fun niceMeters(maxMeters: Double): Double {
            if (maxMeters <= 0.0) return 0.0
            val p = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(maxMeters)))
            return listOf(5.0, 2.0, 1.0).map { it * p }.first { it <= maxMeters }
        }

        internal fun label(meters: Double): String =
            if (meters >= 1000) "${(meters / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }} km"
            else "${meters.toInt()} m"
    }

    private val dp = resources.displayMetrics.density
    private val maxWidthDp = 100f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); strokeWidth = 2 * dp; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); strokeWidth = 5 * dp; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = 11 * dp; isFakeBoldText = true }
    private val textHalo = Paint(text).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * dp }
    private var barPx = 0f
    private var caption = ""

    /** Metres per screen pixel at the map's centre. */
    fun setMetersPerPixel(mpp: Double) {
        val meters = niceMeters(mpp * maxWidthDp * dp)
        val px = if (mpp > 0) (meters / mpp).toFloat() else 0f
        val cap = label(meters)
        if (px == barPx && cap == caption) return
        barPx = px; caption = cap
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(((maxWidthDp + 12) * dp).toInt(), (28 * dp).toInt())

    override fun onDraw(canvas: Canvas) {
        if (barPx <= 0f) return
        val x0 = 4 * dp; val y = height - 6 * dp
        for (p in listOf(halo, line)) {
            canvas.drawLine(x0, y, x0 + barPx, y, p)
            canvas.drawLine(x0, y, x0, y - 6 * dp, p)
            canvas.drawLine(x0 + barPx, y, x0 + barPx, y - 6 * dp, p)
        }
        canvas.drawText(caption, x0 + 3 * dp, y - 8 * dp, textHalo)
        canvas.drawText(caption, x0 + 3 * dp, y - 8 * dp, text)
    }
}
