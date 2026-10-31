package com.rfsentinel.app.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * P25 Phase 1 control channel, first stage: the RTL-SDR's raw I/Q to C4FM dibits.
 *
 * Picks the 12.5 kHz channel [offsetHz] from the tuned centre (mix, integrate-and-dump to 48 kS/s,
 * low-pass), FM-demodulates it and finds each frame sync (0x5575F5FF77FF, 24 symbols of ±3). The sync
 * gives the symbol timing, the level of ±3 and the DC offset, and the [FRAME_DIBITS] dibits after it are
 * sliced and handed to [onFrame] (status symbols still in: the frame decoder takes them out).
 * A slow frequency loop keeps the channel centred while the dongle's crystal drifts.
 *
 * Signalling only: it never demodulates voice. Pure (no Android): unit-tested on a synthetic signal.
 */
class P25Demod(offsetHz: Double, private val onFrame: (IntArray) -> Unit) {

    /** Where the channel is, from the tuned centre; the frequency loop moves it while it tracks. */
    var offsetHz = offsetHz; private set
    /** Frame syncs found so far. */
    var syncs = 0; private set

    // Mixer: a phasor turned each input sample.
    private var pr = 1.0; private var pi = 0.0
    private var stepR = 1.0; private var stepI = 0.0
    private fun setStep() { val w = -2 * PI * offsetHz / SAMPLE_RATE; stepR = cos(w); stepI = sin(w) }
    init { setStep() }

    // Integrate-and-dump to 48 kS/s.
    private var accR = 0.0; private var accI = 0.0; private var accN = 0

    // Channel low-pass at 48 kS/s.
    private val firR = DoubleArray(TAPS.size); private val firI = DoubleArray(TAPS.size); private var firAt = 0

    // Discriminator.
    private var lastR = 0.0; private var lastI = 0.0
    private var afcSum = 0.0; private var afcN = 0

    // Symbol filter (a symbol-long moving average) and the history the sync search looks back over.
    private val box = DoubleArray(SPS); private var boxAt = 0; private var boxSum = 0.0
    private val hist = DoubleArray(HIST); private var n = 0L

    private class Collect(val start: Long, val dc: Double, val level: Double) {
        val dibits = IntArray(FRAME_DIBITS); var got = 0
    }
    private val collecting = ArrayList<Collect>()
    private var lastSync = Long.MIN_VALUE / 2
    private var best = 0.0; private var bestAt = -1L

    /** Unsigned 8-bit I/Q as the dongle delivers it: [count] complex samples from [buf]. */
    fun feed(buf: ByteArray, count: Int) {
        var k = 0
        for (s in 0 until count) {
            val i = (buf[k++].toInt() and 0xff) - 127.5
            val q = (buf[k++].toInt() and 0xff) - 127.5
            accR += i * pr - q * pi
            accI += i * pi + q * pr
            val nr = pr * stepR - pi * stepI
            pi = pr * stepI + pi * stepR; pr = nr
            if (++accN == DECIM) {
                accN = 0
                channelSample(accR, accI)
                accR = 0.0; accI = 0.0
                // Keeps the phasor on the unit circle (rounding drifts it).
                val m = 1.5 - 0.5 * (pr * pr + pi * pi); pr *= m; pi *= m
            }
        }
    }

    private fun channelSample(r: Double, i: Double) {
        firR[firAt] = r; firI[firAt] = i
        var yr = 0.0; var yi = 0.0; var j = firAt
        for (t in TAPS) { yr += t * firR[j]; yi += t * firI[j]; if (--j < 0) j = TAPS.size - 1 }
        if (++firAt == TAPS.size) firAt = 0
        // FM discriminator: the phase step between samples, in Hz.
        val hz = atan2(yi * lastR - yr * lastI, yr * lastR + yi * lastI) * CHANNEL_RATE / (2 * PI)
        lastR = yr; lastI = yi
        afc(hz)
        boxSum += hz - box[boxAt]; box[boxAt] = hz; if (++boxAt == SPS) boxAt = 0
        symbolSample(boxSum / SPS)
    }

    /** C4FM averages to zero over random data: what's left is the channel being off centre. */
    private fun afc(hz: Double) {
        afcSum += hz.coerceIn(-3000.0, 3000.0)
        if (++afcN < AFC_SAMPLES) return
        offsetHz += AFC_GAIN * afcSum / afcN
        afcSum = 0.0; afcN = 0
        setStep()
    }

    private fun at(back: Int) = hist[((n - back) % HIST).toInt().let { if (it < 0) it + HIST else it }]

    private fun symbolSample(v: Double) {
        hist[(n % HIST).toInt()] = v
        // Dibits for the frames being collected: one every SPS samples after their sync.
        if (collecting.isNotEmpty()) {
            val it = collecting.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if ((n - c.start) % SPS != 0L) continue
                c.dibits[c.got++] = slice((v - c.dc) / c.level)
                if (c.got == FRAME_DIBITS) { it.remove(); onFrame(c.dibits) }
            }
        }
        // Sync search: the last 24 symbols against the pattern, normalized.
        if (n >= SYNC.size * SPS) {
            var dot = 0.0; var energy = 0.0
            for (s in SYNC.indices) {
                val x = at((SYNC.size - 1 - s) * SPS)
                dot += x * SYNC[s]; energy += x * x
            }
            val corr = if (energy > 0) dot / sqrt(energy * SYNC_ENERGY) else 0.0
            if (corr > SYNC_CORR && corr > best) { best = corr; bestAt = n }
            // The peak is past: that sample is the sync's last symbol.
            if (bestAt >= 0 && n - bestAt >= SPS / 2) {
                if (bestAt - lastSync > SYNC.size * SPS) found(bestAt)
                best = 0.0; bestAt = -1
            }
        }
        n++
    }

    private fun found(end: Long) {
        lastSync = end
        var plus = 0.0; var minus = 0.0; var np = 0; var nm = 0
        for (s in SYNC.indices) {
            val x = at((n - end).toInt() + (SYNC.size - 1 - s) * SPS)
            if (SYNC[s] > 0) { plus += x; np++ } else { minus += x; nm++ }
        }
        val hi = plus / np; val lo = minus / nm
        val level = (hi - lo) / 2
        if (level <= 0) return
        syncs++
        val c = Collect(end, (hi + lo) / 2, level)
        // Samples already past since the sync's end: catch up those dibits from the history.
        var t = end + SPS
        while (t <= n && c.got < FRAME_DIBITS) { c.dibits[c.got++] = slice((at((n - t).toInt()) - c.dc) / c.level); t += SPS }
        collecting += c
    }

    companion object {
        /** RTL-SDR sample rate for P25: 40 × 48 kS/s, so 10 samples a symbol after decimation. */
        const val SAMPLE_RATE = 1_920_000
        const val DECIM = 40
        const val CHANNEL_RATE = SAMPLE_RATE / DECIM
        const val SYMBOL_RATE = 4_800
        const val SPS = CHANNEL_RATE / SYMBOL_RATE
        /** Dibits kept after a sync: NID and up to three trellis blocks, status symbols included. */
        const val FRAME_DIBITS = 340
        private const val HIST = 1024
        private const val SYNC_CORR = 0.85
        private const val AFC_SAMPLES = 4_800      // 0.1 s
        private const val AFC_GAIN = 0.3

        /** Frame sync 0x5575F5FF77FF as symbols: dibit 01 is +3, 11 is -3. */
        val SYNC: IntArray = run {
            val bits = 0x5575F5FF77FFL
            IntArray(24) { k -> if (((bits shr (46 - 2 * k)) and 3L) == 1L) 3 else -3 }
        }
        private val SYNC_ENERGY = SYNC.sumOf { it * it }.toDouble()

        /** Symbol (scaled so ±3 is ±1) to dibit: +3 → 01, +1 → 00, -1 → 10, -3 → 11. */
        fun slice(x: Double): Int = when {
            x >= 2.0 / 3 -> 1
            x >= 0 -> 0
            x >= -2.0 / 3 -> 2
            else -> 3
        }

        /** Channel low-pass at 48 kS/s: windowed sinc, cut at 6.25 kHz. */
        private val TAPS: DoubleArray = run {
            val len = 31; val fc = 6_250.0 / CHANNEL_RATE
            val t = DoubleArray(len) { k ->
                val x = k - (len - 1) / 2.0
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                sinc * (0.54 - 0.46 * cos(2 * PI * k / (len - 1)))
            }
            val sum = t.sum(); DoubleArray(len) { t[it] / sum }
        }
    }
}
