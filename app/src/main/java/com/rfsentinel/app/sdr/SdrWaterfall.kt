package com.rfsentinel.app.sdr

/**
 * Live rows for the radio waterfall screen, handed over from the SDR thread, and built only while
 * that screen listens. Sweeping, a row is one sweep of every watched band (the usable middle of each
 * tuned chunk, side by side); zoomed in ([SdrRadio.tuneHz]), a row is a few milliseconds of the
 * ~2 MHz around one frequency.
 */
object SdrWaterfall {

    /** A stretch of spectrum: [db] evenly spaced from [startHz] to [endHz], in the band named [band]. */
    class Segment(val startHz: Long, val endHz: Long, val db: FloatArray, val band: String?)

    /** One line of the waterfall, segments in frequency order. [tuned]: the fast single-frequency view. */
    class Row(val segments: List<Segment>, val tuned: Boolean, val at: Long)

    /** Called on the SDR thread with each new row; null while no screen shows it. */
    @Volatile var listener: ((Row) -> Unit)? = null
    val listening: Boolean get() = listener != null

    /**
     * The middle [halfSpanHz] either side of [centerHz] from one chunk's spectrum [psd] (FFT-shifted,
     * DC at n/2), with the receiver's own spike at the centre levelled to the noise beside it.
     */
    fun segment(centerHz: Long, psd: FloatArray, sampleRate: Int, halfSpanHz: Double, band: String?): Segment {
        val n = psd.size
        val binHz = sampleRate.toDouble() / n
        val half = (halfSpanHz / binHz).toInt().coerceIn(1, n / 2 - 1)
        val out = psd.copyOfRange(n / 2 - half, n / 2 + half + 1)
        val guard = maxOf(1, (RadioWatch.DC_GUARD_HZ / binHz).toInt()).coerceAtMost(half - 1)
        val level = minOf(out[half - guard - 1], out[half + guard + 1])
        for (k in half - guard..half + guard) out[k] = level
        val edge = (half * binHz).toLong()
        return Segment(centerHz - edge, centerHz + edge, out, band)
    }
}
