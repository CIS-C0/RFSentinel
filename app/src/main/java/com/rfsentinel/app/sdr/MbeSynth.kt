package com.rfsentinel.app.sdr

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What the IMBE ([ImbeDecoder]) and AMBE ([AmbeDecoder]) vocoders share, from mbelib (ISC licence): the model
 * parameters of the last frames, spectral enhancement and speech synthesis. A subclass decodes a frame's bits to
 * [cur] and calls [speak]; a damaged frame repeats the last good one, up to three times, then it goes silent.
 */
abstract class MbeSynth(private val rnd: Random) {

    /** One codeword's model parameters: fundamental, harmonics, voicing, magnitudes, phases (index 1..L). */
    protected class Parms {
        var w0 = 0.0
        var L = 0
        var repeat = 0
        /** AMBE's frame gain, coded against the last frame's. */
        var gamma = 0.0
        val Vl = IntArray(SLOTS)
        val Ml = DoubleArray(SLOTS)
        val log2Ml = DoubleArray(SLOTS)
        val PHIl = DoubleArray(SLOTS)
        val PSIl = DoubleArray(SLOTS)

        fun copyFrom(o: Parms) {
            w0 = o.w0; L = o.L; repeat = o.repeat; gamma = o.gamma
            o.Vl.copyInto(Vl); o.Ml.copyInto(Ml); o.log2Ml.copyInto(log2Ml); o.PHIl.copyInto(PHIl); o.PSIl.copyInto(PSIl)
        }
    }

    protected val cur = Parms()
    protected val prev = Parms()
    protected val prevEnhanced = Parms()

    init { reset() }

    protected fun reset() {
        prev.w0 = 0.09378; prev.L = 30; prev.repeat = 0; prev.gamma = 0.0
        prev.Vl.fill(0); prev.Ml.fill(0.0); prev.log2Ml.fill(0.0); prev.PHIl.fill(0.0); prev.PSIl.fill(PI / 2)
        cur.copyFrom(prev); prevEnhanced.copyFrom(prev)
    }

    /** 20 ms of speech from [cur]; [ok] false: the frame was damaged, the last good one is said again. */
    protected fun speak(ok: Boolean): ShortArray {
        val out = DoubleArray(N)
        if (!ok) {
            cur.copyFrom(prev)
            cur.repeat++
        } else cur.repeat = 0
        if (cur.repeat <= 3) {
            prev.copyFrom(cur)
            enhance()
            synthesize(out)
            prevEnhanced.copyFrom(cur)
        } else reset()
        return ShortArray(N) { (out[it] * GAIN).coerceIn(-32760.0, 32760.0).toInt().toShort() }
    }

    /** 20 ms of silence, and the model starts over. */
    protected fun silence(): ShortArray { reset(); return ShortArray(N) }

    /** Spectral amplitude enhancement: sharpens formants, keeps the energy. */
    private fun enhance() {
        val L = cur.L; val w0 = cur.w0; val ml = cur.Ml
        var rm0 = 0.0; var rm1 = 0.0
        for (l in 1..L) { rm0 += ml[l] * ml[l]; rm1 += ml[l] * ml[l] * cos(w0 * l) }
        val r2m0 = rm0 * rm0; val r2m1 = rm1 * rm1
        for (l in 1..L) {
            if (ml[l] == 0.0 || 8 * l <= L) continue
            val w = sqrt(ml[l]) * (0.96 * PI * (r2m0 + r2m1 - 2 * rm0 * rm1 * cos(w0 * l)) / (w0 * rm0 * (r2m0 - r2m1))).pow(0.25)
            ml[l] *= when { w > 1.2 -> 1.2; w < 0.5 -> 0.5; else -> w }
        }
        var sum = 0.0
        for (l in 1..L) sum += ml[l] * ml[l]
        val gamma = if (sum == 0.0) 1.0 else sqrt(rm0 / sum)
        for (l in 1..L) ml[l] *= gamma
    }

    private fun randPhase() = rnd.nextDouble() * 2 * PI - PI

    /** 160 samples from [prevEnhanced] fading into [cur]: voiced harmonics as sines, unvoiced ones as a noisy sine mix. */
    private fun synthesize(out: DoubleArray) {
        val c = cur; val p = prevEnhanced
        val cw0 = c.w0; val pw0 = p.w0
        val numUv = (1..c.L).count { c.Vl[it] == 0 }
        val maxl = if (c.L > p.L) {
            for (l in p.L + 1..c.L) { p.Ml[l] = 0.0; p.Vl[l] = 1 }
            c.L
        } else {
            for (l in c.L + 1..p.L) { c.Ml[l] = 0.0; c.Vl[l] = 1 }
            p.L
        }
        for (l in 1..56) {
            c.PSIl[l] = p.PSIl[l] + (pw0 + cw0) * (l * N) / 2.0
            c.PHIl[l] = if (l <= c.L / 4) c.PSIl[l] else c.PSIl[l] + numUv * randPhase() / c.L
        }
        val rphase = DoubleArray(UV_QUALITY)
        // Unvoiced: UV_QUALITY sines spread around harmonic l, plus noise above 2.7 kHz.
        fun unvoiced(w0: Double, l: Int, n: Int): Double {
            var s = 0.0
            for (i in 0 until UV_QUALITY) {
                s += cos(w0 * n * (l + i * UV_STEP - UV_OFFSET) + rphase[i])
                if (w0 * l > UV_THRESHOLD) s += (w0 * l - UV_THRESHOLD) * UV_RAND * rnd.nextDouble()
            }
            return s * UV_SINE * Q_FACTOR
        }
        for (l in 1..maxl) {
            val cv = c.Vl[l] == 1; val pv = p.Vl[l] == 1
            when {
                !cv && pv -> {
                    for (i in rphase.indices) rphase[i] = randPhase()
                    for (n in 0 until N) out[n] += ws(n + N) * p.Ml[l] * cos(pw0 * l * n + p.PHIl[l]) +
                        unvoiced(cw0, l, n) * ws(n) * c.Ml[l]
                }
                cv && !pv -> {
                    for (i in rphase.indices) rphase[i] = randPhase()
                    for (n in 0 until N) out[n] += ws(n) * c.Ml[l] * cos(cw0 * l * (n - N) + c.PHIl[l]) +
                        unvoiced(pw0, l, n) * ws(n + N) * p.Ml[l]
                }
                cv || pv -> for (n in 0 until N) out[n] += ws(n + N) * p.Ml[l] * cos(pw0 * l * n + p.PHIl[l]) +
                    ws(n) * c.Ml[l] * cos(cw0 * l * (n - N) + c.PHIl[l])
                else -> {
                    for (i in rphase.indices) rphase[i] = randPhase()
                    val prevPart = DoubleArray(N) { n -> unvoiced(pw0, l, n) * ws(n + N) * p.Ml[l] }
                    for (i in rphase.indices) rphase[i] = randPhase()
                    for (n in 0 until N) out[n] += prevPart[n] + unvoiced(cw0, l, n) * ws(n) * c.Ml[l]
                }
            }
        }
    }

    companion object {
        /** Samples per frame: 20 ms at 8 kHz. */
        const val N = 160
        const val RATE = 8000
        internal const val SLOTS = 58
        private const val GAIN = 7.0
        private const val UV_QUALITY = 3
        private const val UV_STEP = 1.0 / UV_QUALITY
        private const val UV_OFFSET = UV_STEP * (UV_QUALITY - 1) / 2
        private val Q_FACTOR = ln(UV_QUALITY.toDouble()) / UV_QUALITY
        private const val UV_THRESHOLD = 2700 * PI / 4000
        private const val UV_SINE = 1.3591409 * E
        private const val UV_RAND = 2.0

        /** The synthesis window (mbelib's Ws): 0, a 50-sample ramp, flat around n = 160, back down. */
        private fun ws(n: Int) = (minOf(n - 55, 265 - n) / 50.0).coerceIn(0.0, 1.0)
    }
}
