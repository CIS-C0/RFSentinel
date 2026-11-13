package com.rfsentinel.app.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Listening from the waterfall: the channel [offsetHz] from the tuned centre, mixed down, filtered and
 * demodulated to mono audio at [audioRate] (the input rate / 64, 32 kHz at 2.048 MS/s). Wide FM for
 * broadcast radio, AM for the airband and CB, narrow FM with a noise squelch for everything else.
 * The chain follows RFAnalyzer's Demodulator (Dennis Mantz, GPL-2.0-or-later): channel filter, then
 * FM as the phase step between samples and AM as the envelope. Receive-only, pure, unit-tested.
 */
class AudioDemod(val mode: Mode, offsetHz: Long, inputRate: Int, private val out: (ShortArray, Int) -> Unit) {

    enum class Mode(val label: String) { WFM("wide FM"), NFM("narrow FM"), AM("AM") }

    /** Input / 8: the rate wide FM is demodulated at. */
    private val midRate = inputRate / D1
    val audioRate = midRate / D2

    // Mixer: a phasor turning at -offset.
    private val stepR: Double; private val stepI: Double
    init { val w = -2 * PI * offsetHz / inputRate; stepR = cos(w); stepI = sin(w) }
    private var pr = 1.0; private var pi = 0.0

    // First decimation, by D1: triangular weights 1..D1..1 (two boxcars), the rising half for this block and the
    // falling half for the last one; each output comes a block late, once both halves are in.
    private var pos = 0
    private var riseR = 0.0; private var riseI = 0.0; private var lastRiseR = 0.0; private var lastRiseI = 0.0
    private var fallR = 0.0; private var fallI = 0.0

    // Second decimation, by D2: a windowed-sinc low-pass over the last TAPS samples at [midRate].
    private val taps = lowPass(when (mode) { Mode.WFM -> 15_000.0; Mode.NFM -> 8_000.0; Mode.AM -> 5_000.0 } / midRate)
    private val histR = DoubleArray(TAPS); private val histI = DoubleArray(TAPS)
    private var head = 0; private var phase2 = 0

    // Demodulators.
    private var lastR = 1.0; private var lastI = 0.0
    private val fmGain = when (mode) {
        Mode.WFM -> midRate / (2 * PI * 75_000)
        else -> audioRate / (2 * PI * 5_000)
    }
    /** Audio low-pass: 75 µs de-emphasis for broadcast FM, about 3 kHz for voice. */
    private val lpA = exp(-1.0 / (audioRate * if (mode == Mode.WFM) 75e-6 else 1 / (2 * PI * 3_000)))
    private var lp = 0.0
    private var carrier = 0.0
    private var amN = 0L
    private var lastAudio = 0.0
    /** Narrow FM: smoothed mean jump between audio samples, large on noise, small on a carrier. */
    private var noise = NOISE_CLOSE
    var open = mode != Mode.NFM; private set
    /** Each audio sample before the squelch (about ±1), for data bursts sent as the radio keys up ([Mdc1200]). */
    var tap: ((Double) -> Unit)? = null

    private val pcm = ShortArray(4096)
    private var pcmN = 0
    private var blockNoise = 0.0; private var blockN = 0

    /** Unsigned 8-bit I/Q as the dongle delivers it: [count] complex samples from [buf]. */
    fun feed(buf: ByteArray, count: Int) {
        var k = 0
        for (s in 0 until count) {
            val i = (buf[k++].toInt() and 0xff) - 127.5
            val q = (buf[k++].toInt() and 0xff) - 127.5
            val mr = i * pr - q * pi
            val mi = i * pi + q * pr
            val nr = pr * stepR - pi * stepI
            pi = pr * stepI + pi * stepR; pr = nr
            val up = (pos + 1).toDouble(); val down = (D1 - 1 - pos).toDouble()
            riseR += up * mr; riseI += up * mi
            fallR += down * mr; fallI += down * mi
            if (++pos == D1) {
                mid((lastRiseR + fallR) / (D1 * D1 * 127.5), (lastRiseI + fallI) / (D1 * D1 * 127.5))
                lastRiseR = riseR; lastRiseI = riseI
                riseR = 0.0; riseI = 0.0; fallR = 0.0; fallI = 0.0; pos = 0
                val m = 1.5 - 0.5 * (pr * pr + pi * pi); pr *= m; pi *= m // keeps the phasor on the unit circle
            }
        }
        flush()
    }

    /** One sample at [midRate], about ±1 at full scale. */
    private fun mid(re: Double, im: Double) {
        if (mode == Mode.WFM) {
            // Wide FM is demodulated here, before the cut to audio rate: its ±75 kHz needs the room.
            val v = fm(re, im)
            histR[head] = v; head = (head + 1) % TAPS
            if (++phase2 == D2) { phase2 = 0; audio(filter(histR)) }
        } else {
            histR[head] = re; histI[head] = im; head = (head + 1) % TAPS
            if (++phase2 == D2) {
                phase2 = 0
                val r = filter(histR); val q = filter(histI)
                audio(if (mode == Mode.NFM) fm(r, q) else am(r, q))
            }
        }
    }

    private fun filter(h: DoubleArray): Double {
        var acc = 0.0
        var k = head
        for (t in 0 until TAPS) { acc += taps[t] * h[k]; if (++k == TAPS) k = 0 }
        return acc
    }

    /** The phase step since the last sample, scaled so full deviation is ±1. */
    private fun fm(re: Double, im: Double): Double {
        val v = atan2(im * lastR - re * lastI, re * lastR + im * lastI) * fmGain
        lastR = re; lastI = im
        return v
    }

    /** The envelope less the carrier, over the carrier: the modulation, the same loud for a weak station or a strong one. */
    private fun am(re: Double, im: Double): Double {
        val env = sqrt(re * re + im * im)
        // A plain average over the first quarter second, then a moving one: right from the start, then following fades.
        carrier += (env - carrier) * maxOf(CARRIER_ALPHA, 1.0 / ++amN)
        return if (carrier > 1e-6) (env - carrier) / carrier else 0.0
    }

    private fun audio(v: Double) {
        lp = lp * lpA + v * (1 - lpA)
        tap?.invoke(lp)
        if (mode == Mode.NFM) {
            blockNoise += kotlin.math.abs(v - lastAudio); blockN++
            lastAudio = v
        }
        val s = if (open) (lp * VOLUME).coerceIn(-1.0, 1.0) else 0.0
        pcm[pcmN++] = (s * 32767).toInt().toShort()
        if (pcmN == pcm.size) flush()
    }

    private fun flush() {
        if (mode == Mode.NFM && blockN > 0) {
            noise += (blockNoise / blockN - noise) * 0.5
            blockNoise = 0.0; blockN = 0
            // Hysteresis: a fading signal doesn't chop on and off.
            if (open && noise > NOISE_CLOSE) open = false else if (!open && noise < NOISE_OPEN) open = true
        }
        if (pcmN > 0) { out(pcm, pcmN); pcmN = 0 }
    }

    companion object {
        private const val D1 = 8
        private const val D2 = 8
        private const val TAPS = 96
        private const val VOLUME = 0.6
        /** About a quarter-second time constant at 32 kHz. */
        private const val CARRIER_ALPHA = 1.0 / 8_000
        /** Mean jump between narrow-FM audio samples: around 0.1 on speech, above 1.5 on bare noise. */
        internal const val NOISE_OPEN = 0.45
        internal const val NOISE_CLOSE = 0.7

        /** How [hz] is usually sent: broadcast FM, AM airband and CB, narrow FM for the rest. */
        fun modeFor(hz: Long): Mode = when (hz) {
            in 87_500_000L..108_000_000L -> Mode.WFM
            in 108_000_001L..137_000_000L, in 26_960_000L..27_410_000L -> Mode.AM
            else -> Mode.NFM
        }

        /** Blackman-windowed sinc, cut-off [fc] as a fraction of the sample rate, unity gain at 0 Hz. */
        private fun lowPass(fc: Double): DoubleArray {
            val m = TAPS - 1
            val h = DoubleArray(TAPS) { n ->
                val x = n - m / 2.0
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                sinc * (0.42 - 0.5 * cos(2 * PI * n / m) + 0.08 * cos(4 * PI * n / m))
            }
            val sum = h.sum()
            for (n in h.indices) h[n] /= sum
            return h
        }
    }
}
