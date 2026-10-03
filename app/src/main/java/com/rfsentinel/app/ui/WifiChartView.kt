package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R

/**
 * WiFi channel views for one band, from ordinary scan results:
 *  - [Mode.CHANNELS]: networks per channel (bar) and the strongest signal on it;
 *  - [Mode.SPECTRUM]: each network as a curve across the channels it occupies
 *    (centre frequency and width), so overlapping networks are visible.
 */
class WifiChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { CHANNELS, SPECTRUM }
    enum class Band(val label: String) { B24("2.4 GHz"), B5("5 GHz"), B6("6 GHz") }

    /** One access point as the chart needs it. */
    data class Ap(val ssid: String, val bssid: String, val freq: Int, val centerFreq: Int, val widthMhz: Int, val rssi: Int)

    var mode = Mode.CHANNELS
        set(v) { field = v; invalidate() }
    var band = Band.B24
        set(v) { field = v; invalidate() }
    private var aps: List<Ap> = emptyList()

    fun setAps(list: List<Ap>) { aps = list; invalidate() }

    private val d = resources.displayMetrics.density
    private val textColor = MaterialColors.getColor(context, android.R.attr.textColorSecondary, 0xFF888888.toInt())
    private val accent = MaterialColors.getColor(context, R.attr.rfTraceColor, 0xFFE0622D.toInt())
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (textColor and 0x00FFFFFF) or 0x33000000; strokeWidth = d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textSize = 10 * d }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val curveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val curveLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; textAlign = Paint.Align.CENTER }

    private val palette = intArrayOf(
        0xFFE0622D.toInt(), 0xFF1F8FBF.toInt(), 0xFF2E9E5B.toInt(), 0xFF8E44AD.toInt(), 0xFFD4A017.toInt(),
        0xFFC2185B.toInt(), 0xFF00897B.toInt(), 0xFF5D6D7E.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt()
    )

    /** Channels drawn on the x axis for the band, with their centre frequency. */
    private fun axis(): List<Pair<Int, Int>> = when (band) {
        Band.B24 -> (1..13).map { it to 2407 + 5 * it }
        Band.B5 -> listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165, 169, 173, 177)
            .map { it to 5000 + 5 * it }
        Band.B6 -> (1..233 step 4).map { it to 5950 + 5 * it }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inBand = aps.filter { bandOf(it.freq) == band }
        val left = 30 * d; val right = width - 6 * d; val top = 14 * d; val bottom = height - 20 * d
        val chans = axis()
        val fLo = chans.first().second - if (band == Band.B24) 12 else 15
        val fHi = chans.last().second + if (band == Band.B24) 12 else 15
        fun xOf(f: Double) = (left + (f - fLo) / (fHi - fLo) * (right - left)).toFloat()
        fun yOf(rssi: Int) = bottom - (rssi.coerceIn(-100, -20) + 100) / 80f * (bottom - top)

        // dBm grid
        text.textAlign = Paint.Align.RIGHT
        for (db in -90..-30 step 20) {
            val y = yOf(db)
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText("$db", left - 3 * d, y + 3 * d, text)
        }
        // channel labels (thinned so they never collide)
        text.textAlign = Paint.Align.CENTER
        val minGap = 22 * d
        var lastX = -1e9f
        for ((ch, f) in chans) {
            val x = xOf(f.toDouble())
            if (x - lastX < minGap) continue
            canvas.drawText("$ch", x, height - 6 * d, text)
            lastX = x
        }
        if (inBand.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("No ${band.label} networks heard", (left + right) / 2, (top + bottom) / 2, text)
            return
        }
        when (mode) {
            Mode.CHANNELS -> drawChannels(canvas, inBand, chans, ::xOf, ::yOf, bottom)
            Mode.SPECTRUM -> drawSpectrum(canvas, inBand, ::xOf, ::yOf, bottom)
        }
    }

    private fun drawChannels(
        canvas: Canvas, list: List<Ap>, chans: List<Pair<Int, Int>>,
        xOf: (Double) -> Float, yOf: (Int) -> Float, bottom: Float
    ) {
        val byCh = list.groupBy { channelOf(it.freq) }
        val maxCount = byCh.values.maxOf { it.size }.coerceAtLeast(1)
        val slot = if (chans.size > 1) xOf(chans[1].second.toDouble()) - xOf(chans[0].second.toDouble()) else 20 * d
        val w = (slot * 0.7f).coerceIn(4 * d, 18 * d)
        label.color = textColor
        for ((ch, aps) in byCh) {
            val x = xOf(freqOf(ch, band).toDouble())
            val strongest = aps.maxOf { it.rssi }
            // Bar height = strongest signal; the count is printed on top.
            val y = yOf(strongest)
            bar.alpha = (110 + 145 * aps.size / maxCount).coerceAtMost(255)
            canvas.drawRoundRect(x - w / 2, y, x + w / 2, bottom, 2 * d, 2 * d, bar)
            canvas.drawText("${aps.size}", x, y - 3 * d, label)
        }
    }

    private fun drawSpectrum(canvas: Canvas, list: List<Ap>, xOf: (Double) -> Float, yOf: (Int) -> Float, bottom: Float) {
        val sorted = list.sortedBy { it.rssi } // strongest drawn last, on top
        for ((i, ap) in sorted.withIndex()) {
            val color = palette[(ap.bssid.hashCode() and 0x7FFFFFFF) % palette.size]
            val c = (if (ap.centerFreq > 0) ap.centerFreq else ap.freq).toDouble()
            val half = ap.widthMhz / 2.0
            val x0 = xOf(c - half); val x1 = xOf(c + half)
            val ramp = (x1 - x0) * 0.18f
            val y = yOf(ap.rssi)
            val path = Path().apply {
                moveTo(x0, bottom)
                cubicTo(x0 + ramp * 0.6f, bottom, x0 + ramp * 0.4f, y, x0 + ramp, y)
                lineTo(x1 - ramp, y)
                cubicTo(x1 - ramp * 0.4f, y, x1 - ramp * 0.6f, bottom, x1, bottom)
            }
            curveFill.color = (color and 0x00FFFFFF) or 0x2E000000
            canvas.drawPath(path, curveFill)
            curveLine.color = color
            canvas.drawPath(path, curveLine)
            // Label the strongest few so the chart stays readable.
            if (i >= sorted.size - 8) {
                label.color = color
                canvas.drawText(ap.ssid.take(16), (x0 + x1) / 2, y - 3 * d, label)
            }
        }
    }

    companion object {
        fun bandOf(freq: Int): Band? = when {
            freq in 2400..2500 -> Band.B24
            freq in 4900..5899 -> Band.B5
            freq >= 5925 -> Band.B6
            else -> null
        }

        fun channelOf(freq: Int): Int = when {
            freq == 2484 -> 14
            freq in 2400..2500 -> (freq - 2407) / 5
            freq in 4900..5899 -> (freq - 5000) / 5
            freq >= 5925 -> (freq - 5950) / 5
            else -> 0
        }

        fun freqOf(ch: Int, band: Band): Int = when (band) {
            Band.B24 -> if (ch == 14) 2484 else 2407 + 5 * ch
            Band.B5 -> 5000 + 5 * ch
            Band.B6 -> 5950 + 5 * ch
        }
    }
}
