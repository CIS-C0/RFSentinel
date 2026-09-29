package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R
import com.rfsentinel.app.service.DeviceRegistry

/** Signal strength over the last few minutes for one device. */
class SignalGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: List<DeviceRegistry.Sample> = emptyList()
    private val windowMs = 5 * 60 * 1000L
    private val density = resources.displayMetrics.density
    private val scope = MaterialColors.getColor(context, R.attr.rfScopeColor, 0xFF0B5C63.toInt())
    private val trace = MaterialColors.getColor(context, R.attr.rfTraceColor, 0xFFE0622D.toInt())

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density
        color = (scope and 0x00FFFFFF) or 0x33000000
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = 0xFF888888.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        color = trace
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (trace and 0x00FFFFFF) or 0x33000000 }
    private val line = Path()
    private val fill = Path()

    fun setSamples(list: List<DeviceRegistry.Sample>) {
        samples = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 34 * density
        val bottom = height - 16 * density
        val top = 6 * density
        val right = width - 6 * density
        fun y(rssi: Int) = top + (bottom - top) * ((-30 - rssi).coerceIn(0, 70) / 70f)

        for (level in intArrayOf(-40, -60, -80, -100)) {
            val yy = y(level)
            canvas.drawLine(left, yy, right, yy, gridPaint)
            canvas.drawText("$level", 2 * density, yy + 4 * density, textPaint)
        }
        canvas.drawText("5 min ago", left, height - 2 * density, textPaint)
        val nowLabel = "now"
        canvas.drawText(nowLabel, right - textPaint.measureText(nowLabel), height - 2 * density, textPaint)

        val now = System.currentTimeMillis()
        val visible = samples.filter { now - it.time <= windowMs }
        if (visible.size < 2) {
            canvas.drawText("Collecting signal samples...", left + 8 * density, (top + bottom) / 2, textPaint)
            return
        }
        fun x(time: Long) = left + (right - left) * (1f - (now - time).toFloat() / windowMs)
        line.reset()
        fill.reset()
        visible.forEachIndexed { i, s ->
            val px = x(s.time); val py = y(s.rssi)
            if (i == 0) { line.moveTo(px, py); fill.moveTo(px, bottom); fill.lineTo(px, py) }
            else { line.lineTo(px, py); fill.lineTo(px, py) }
        }
        fill.lineTo(x(visible.last().time), bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }
}
