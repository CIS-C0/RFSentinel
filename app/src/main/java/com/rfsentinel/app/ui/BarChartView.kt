package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R

/** A compact bar chart with a label under some bars (hour of day, weekday). */
class BarChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var values: IntArray = IntArray(0)
    private var labels: List<String?> = emptyList()
    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, R.attr.rfTraceColor, 0xFFE0622D.toInt())
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (MaterialColors.getColor(context, R.attr.rfScopeColor, 0xFF0B5C63.toInt()) and 0x00FFFFFF) or 0x30000000
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9 * d
        textAlign = Paint.Align.CENTER
        color = MaterialColors.getColor(context, android.R.attr.textColorSecondary, 0xFF888888.toInt())
    }

    fun set(values: IntArray, labels: List<String?>) {
        this.values = values; this.labels = labels
        contentDescription = values.joinToString()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val labelH = text.textSize + 3 * d
        val top = 2 * d; val bottom = height - labelH
        val slot = width.toFloat() / values.size
        val gap = (slot * 0.18f).coerceAtLeast(1f)
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        values.forEachIndexed { i, v ->
            val x0 = i * slot + gap / 2; val x1 = (i + 1) * slot - gap / 2
            canvas.drawRoundRect(x0, top, x1, bottom, 2 * d, 2 * d, empty)
            if (v > 0) {
                val y = bottom - (bottom - top) * v / max
                canvas.drawRoundRect(x0, y, x1, bottom, 2 * d, 2 * d, bar)
            }
            labels.getOrNull(i)?.let { canvas.drawText(it, (x0 + x1) / 2, height - 2 * d, text) }
        }
    }
}
