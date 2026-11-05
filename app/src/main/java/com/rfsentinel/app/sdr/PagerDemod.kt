package com.rfsentinel.app.sdr

import kotlin.math.PI
import kotlin.math.atan2

/**
 * Pager messages, first stage: the SDR's raw I/Q to pages. Picks the channel [offsetHz] from the tuned centre (mixed and
 * cut to 48 kS/s as for P25), FM-demodulates it and hands it to POCSAG at its three speeds and to FLEX at once: a
 * channel doesn't say which it is. Pure (no Android): unit-tested on a synthetic signal.
 */
class PagerDemod(offsetHz: Long, onPage: (PagerLog.Page) -> Unit) {

    private class PocsagRx(baud: Int, onPage: (PagerLog.Page) -> Unit) {
        val decoder = Pocsag(baud, onPage)
        private val clock = SymbolClock(RATE, baud)
        private var sum = 0.0
        fun sample(x: Double) {
            if (clock.mid) sum += x
            clock.crossed(x, CLOCK_RATE)
            if (clock.advance()) { decoder.bit(sum > 0); sum = 0.0 }
        }
    }

    private val pocsag = listOf(512, 1200, 2400).map { PocsagRx(it, onPage) }
    private val flex = Flex(RATE, onPage)
    private val baseband = P25Record.Baseband(offsetHz, onSample = ::channelSample)

    /** POCSAG batches and FLEX frames that checked out: the channel really carries pages. */
    val frames get() = flex.frames + pocsag.sumOf { it.decoder.batches }

    private val firR = DoubleArray(TAPS.size); private val firI = DoubleArray(TAPS.size); private var firAt = 0
    private var lastR = 0.0; private var lastI = 0.0
    private val box = DoubleArray(BOX); private var boxAt = 0; private var boxSum = 0.0
    private var dc = 0.0

    /** Unsigned 8-bit I/Q as the dongle delivers it: [count] complex samples from [buf]. */
    fun feed(buf: ByteArray, count: Int) = baseband.feed(buf, count)

    private fun channelSample(r: Double, i: Double) {
        firR[firAt] = r; firI[firAt] = i
        var yr = 0.0; var yi = 0.0; var j = firAt
        for (t in TAPS) { yr += t * firR[j]; yi += t * firI[j]; if (--j < 0) j = TAPS.size - 1 }
        if (++firAt == TAPS.size) firAt = 0
        val hz = atan2(yi * lastR - yr * lastI, yr * lastR + yi * lastI) * RATE / (2 * PI)
        lastR = yr; lastI = yi
        // Smoothed, or noise on the discriminator makes false zero crossings for the symbol clocks.
        boxSum += hz - box[boxAt]; box[boxAt] = hz; if (++boxAt == BOX) boxAt = 0
        val x = boxSum / BOX
        // The carrier is only about on the channel's centre (crystal error): the slow average is where zero is.
        dc += (x - dc) / DC_SAMPLES
        for (p in pocsag) p.sample(x - dc)
        flex.sample(x)
    }

    companion object {
        private const val RATE = P25Demod.CHANNEL_RATE
        private const val CLOCK_RATE = 0.05
        private const val BOX = 6
        private const val DC_SAMPLES = 2_400.0    // 50 ms
        /** Up to ±4.8 kHz deviation, plus the signal's own width. */
        private val TAPS = P25Demod.lowPass(8_000.0)
    }
}
