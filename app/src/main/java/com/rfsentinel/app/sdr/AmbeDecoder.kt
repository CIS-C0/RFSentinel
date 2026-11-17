package com.rfsentinel.app.sdr

import com.rfsentinel.app.sdr.AmbeTables.DG
import com.rfsentinel.app.sdr.AmbeTables.HOC5
import com.rfsentinel.app.sdr.AmbeTables.HOC6
import com.rfsentinel.app.sdr.AmbeTables.HOC7
import com.rfsentinel.app.sdr.AmbeTables.HOC8
import com.rfsentinel.app.sdr.AmbeTables.LMPRBL
import com.rfsentinel.app.sdr.AmbeTables.PRBA24
import com.rfsentinel.app.sdr.AmbeTables.PRBA58
import com.rfsentinel.app.sdr.AmbeTables.VUV
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The AMBE+2 half-rate vocoder (AMBE 3600x2450) used by NXDN and DMR: a 72-bit frame to 20 ms of speech,
 * 160 samples at 8 kHz. A port of mbelib (ISC licence): its error correction (two Golay words, the second one
 * whitened by a sequence seeded from the first), its parameter decoding and tables ([AmbeTables]); synthesis
 * is shared with IMBE ([MbeSynth]). Frame handling follows mbelib-neo's DVSI parity work (GPL-2.0-or-later):
 * DVSI's pitch law and field layout, error counts, silence frames, tones, lost frames, mutes and rounded output.
 * One decoder per call: each frame's spectrum is coded against the last.
 */
class AmbeDecoder(rnd: Random = Random(1)) : MbeSynth(rnd) {

    /** Bits the last frame's error correction fixed, parity bits included (TIA-102.BABA-1 5.5). */
    var errors = 0; private set
    /** Of those, C0's (its extended parity bit too): 4 is a detected, uncorrectable word. */
    internal var c0Errors = 0; private set

    private var errorRate = 0.0
    private var repeats = 0
    /** The next frame played rises from silence: a mute or a tone came, the model kept was not heard. */
    private var afresh = false
    /** The tone sounding (its two frequencies, Hz, and amplitude), or null; its oscillator phases. */
    private var tone: DoubleArray? = null
    private var ph1 = 0.0
    private var ph2 = 0.0

    /** 20 ms of speech from one frame in mbelib's layout: [fr] rows of 24, 23, 11 and 14 bits (bit 0 last sent). */
    fun decode(fr: Array<IntArray>): ShortArray {
        val d = data(fr)
        errorRate = 0.95 * errorRate + 0.001064 * errors
        val out = when (if (c0Errors >= 4) ERASURE else kind(d)) {
            VOICE -> valid(d, silent = false)
            SILENCE -> valid(d, silent = true)
            TONE -> tone(d)
            else -> lost()
        }
        return pcm(out, round = true)
    }

    /** The 49 data bits of a frame, its two Golay words corrected; sets [errors] and [c0Errors]. */
    internal fun data(fr: Array<IntArray>): IntArray {
        val d = IntArray(49)
        var w0 = 0
        for (j in 0 until 23) w0 = w0 or (fr[0][j + 1] shl j)
        val (c0, e0) = P25Voice.golay(w0)
        for (i in 0 until 12) d[i] = c0 shr (11 - i) and 1
        // Bit 0 is the codeword's parity: wrong after correcting 3 bits, that makes 4, a word beyond repair.
        val parity = Integer.bitCount(c0 shl 11 or P25Voice.golayParity(c0)) and 1
        c0Errors = e0 + if (fr[0][0] != parity) 1 else 0
        // The second word is whitened by a sequence seeded from the first one's data.
        var pr = 16 * c0
        var w1 = 0
        for (j in 22 downTo 0) {
            pr = (173 * pr + 13849) and 0xffff
            w1 = w1 or ((fr[1][j] xor (pr shr 15)) shl j)
        }
        val (c1, e1) = P25Voice.golay(w1)
        for (i in 0 until 12) d[12 + i] = c1 shr (11 - i) and 1
        for (i in 0 until 11) d[24 + i] = fr[2][10 - i]
        for (i in 0 until 14) d[35 + i] = fr[3][13 - i]
        errors = c0Errors + e1
        return d
    }

    private fun bits(d: IntArray, vararg i: Int): Int { var v = 0; for (x in i) v = v shl 1 or d[x]; return v }

    /** A tone frame (u0 starts with six ones) that can be played, or one that cannot (lost); else b0 says. */
    private fun kind(d: IntArray): Int {
        if ((0..5).all { d[it] == 1 }) {
            val consistent = (45..48).all { d[it] == 0 } || (0..3).all { d[12 + it] == d[20 + it] }
            return if (consistent && toneFreqs(toneId(d)) != null) TONE else ERASURE
        }
        return when (bits(d, 0, 1, 2, 3, 37, 38, 39)) {
            in 0..119 -> VOICE
            124, 125 -> SILENCE
            else -> ERASURE
        }
    }

    private fun toneId(d: IntArray) = bits(d, 12, 13, 14, 15, 16, 17, 18, 19)

    /** A voice or silence frame: decoded, played (unless the error rate mutes it), a tone left sounding fading out on top. */
    private fun valid(d: IntArray, silent: Boolean): DoubleArray {
        parms(d, silent)
        repeats = 0
        if (errorRate > MUTE_RATE) return mute(own = true, silent = silent)
        if (afresh) fromSilence()
        afresh = false
        val out = play(history = !silent)
        tail(out)
        return out
    }

    /** A lost frame (erasure, unplayable tone, C0 beyond repair): the last one again, up to 3 times, then a mute. */
    private fun lost(): DoubleArray {
        if (++repeats >= 4 || errorRate > MUTE_RATE) return mute(own = false, silent = false)
        if (tone != null) return renderTone()
        val out = replay(afresh)
        afresh = false
        return out
    }

    /**
     * A mute (5.7) as DVSI plays it: noise of about one 16-bit step; the first muted frame after a heard one
     * fades the last frame (or the tone) out over 10 ms. The model goes on underneath: a valid frame is still
     * synthesized, unheard, so the frame heard after the mute carries on from it.
     */
    private fun mute(own: Boolean, silent: Boolean): DoubleArray {
        val out = DoubleArray(N) { (rnd.nextDouble() * 2 - 1) * MUTE_NOISE / GAIN }
        if (tone != null) tail(out)
        else if (!afresh && hasPlayed()) {
            val last = replay(afresh = false)
            for (n in 0 until FADE) out[n] += last[n] * (1 - n.toDouble() / FADE)
        }
        if (own) play(history = !silent)
        afresh = true
        return out
    }

    /** A tone frame (7.3): the tone, fading in over 10 ms when it starts; the speech model stays as it was. */
    private fun tone(d: IntArray): DoubleArray {
        repeats = 0
        if (errorRate > MUTE_RATE) return mute(own = false, silent = false)
        val (f1, f2) = toneFreqs(toneId(d))!!
        val ad = bits(d, 6, 7, 8, 9, 10, 11, 44)
        val starts = tone == null
        // 7.2: +3.17 dBm0 at AD 127 down to -87.13 at 0; DVSI plays AD 127 at -2.52 dB per component.
        val db = -2.523 + (3.17 + 87.13) / 127 * (ad - 127)
        tone = doubleArrayOf(f1, f2, sqrt(2.0) * 32768 * 10.0.pow(db / 20) / GAIN)
        val out = renderTone()
        if (starts) for (n in 0 until FADE) out[n] *= n.toDouble() / FADE
        afresh = true
        return out
    }

    private fun renderTone(): DoubleArray {
        val t = tone!!
        val s1 = 2 * PI * t[0] / RATE
        val s2 = 2 * PI * t[1] / RATE
        return DoubleArray(N) {
            ph1 = (ph1 + s1) % (2 * PI); ph2 = (ph2 + s2) % (2 * PI)
            -t[2] * (cos(ph1) + if (t[1] != t[0]) cos(ph2) else 0.0)
        }
    }

    /** The tone that was sounding goes on over the first 10 ms of the frame after it, fading out, added to [out]. */
    private fun tail(out: DoubleArray) {
        if (tone == null) return
        val t = renderTone()
        for (n in 0 until FADE) out[n] += t[n] * (1 - n.toDouble() / FADE)
        tone = null
    }

    /** A voice or silence frame's bits to [cur], predicted from the last voice frame ([prev]). */
    private fun parms(d: IntArray, silent: Boolean) {
        val b0 = bits(d, 0, 1, 2, 3, 37, 38, 39)
        // DVSI's pitch law, every harmonic up to 0.9254 of Nyquist; silence: 15 bands at 1/32, all unvoiced.
        val f0 = if (silent) 1.0 / 32 else 2.0.pow(-4.322605 - 0.0220427 * b0)
        val L = if (silent) 15 else (0.9254 * 0.5 / f0).toInt().coerceIn(9, 56)
        cur.w0 = 2 * PI * f0; cur.L = L
        // A silence frame plays a further 13.8 dB down.
        val unvc = 0.2046 / sqrt(cur.w0) * if (silent) 0.2046 else 1.0

        // Voicing: one of 32 patterns over 8 bands; a voiced band also voices the half band below it.
        val b1 = bits(d, 4, 5, 6, 7, 35)
        for (l in 1..L) {
            val col = l * 16.0 * f0
            cur.Vl[l] = if (silent) 0 else VUV[b1 * 8 + minOf(7, col.toInt())] or VUV[b1 * 8 + minOf(7, (col + 0.5).toInt())]
        }

        // Gain, coded against the last frame's.
        cur.gamma = DG[bits(d, 8, 9, 10, 11, 36)] + 0.5 * prev.gamma

        // The prediction residual: its 8 block averages (PRBA), then each block's higher order coefficients (HOC).
        // In u3, b4's low bits come before b3's last one, as DVSI's codec lays them out.
        val gm = DoubleArray(9)
        val b3 = bits(d, 12, 13, 14, 15, 16, 17, 18, 19, 43)
        for (i in 0..2) gm[2 + i] = PRBA24[b3 * 3 + i].toDouble()
        val b4 = bits(d, 20, 21, 22, 23, 40, 41, 42)
        for (i in 0..3) gm[5 + i] = PRBA58[b4 * 4 + i].toDouble()
        val ri = DoubleArray(9)
        for (i in 1..8) for (m in 1..8) ri[i] += (if (m == 1) 1 else 2) * gm[m] * cos(PI * (m - 1) * (i - 0.5) / 8)
        val cik = Array(5) { DoubleArray(18) }
        val rc = 1 / (2 * sqrt(2.0))
        for (i in 1..4) { cik[i][1] = 0.5 * (ri[2 * i - 1] + ri[2 * i]); cik[i][2] = rc * (ri[2 * i - 1] - ri[2 * i]) }
        val hoc = arrayOf(HOC5, HOC6, HOC7, HOC8)
        val hb = intArrayOf(bits(d, 24, 25, 26, 27, 44), bits(d, 28, 29, 30, 45), bits(d, 31, 32, 33, 46), bits(d, 34, 47, 48))
        val ji = IntArray(5) { if (it == 0) 0 else LMPRBL[L * 4 + it - 1] }
        for (i in 1..4) for (k in 3..ji[i]) cik[i][k] = if (k > 6) 0.0 else hoc[i - 1][hb[i - 1] * 4 + k - 3].toDouble()
        val tl = DoubleArray(SLOTS)
        var l = 1
        for (i in 1..4) for (j in 1..ji[i]) {
            var s = 0.0
            for (k in 1..ji[i]) s += (if (k == 1) 1 else 2) * cik[i][k] * cos(PI * (k - 1) * (j - 0.5) / ji[i])
            tl[l++] = s
        }

        // The residuals add to the previous spectrum, resampled to L harmonics.
        if (L > prev.L) for (x in prev.L + 1..L) { prev.Ml[x] = prev.Ml[prev.L]; prev.log2Ml[x] = prev.log2Ml[prev.L] }
        prev.log2Ml[0] = prev.log2Ml[1]; prev.Ml[0] = prev.Ml[1]
        val kl = IntArray(L + 1); val dl = DoubleArray(L + 1)
        var sum43 = 0.0
        for (x in 1..L) {
            // In float, as the encoder picks the taps: a position on a whole number must land on the same side.
            val f = (prev.L.toFloat() / L * x).toDouble()
            kl[x] = f.toInt(); dl[x] = f - kl[x]
            sum43 += (1 - dl[x]) * prev.log2Ml[kl[x]] + dl[x] * prev.log2Ml[kl[x] + 1]
        }
        sum43 *= 0.65 / L
        var mean = 0.0
        for (x in 1..L) mean += tl[x]
        val bigGamma = cur.gamma - 0.5 * log2(L.toDouble()) - mean / L
        for (x in 1..L) {
            cur.log2Ml[x] = tl[x] + 0.65 * (1 - dl[x]) * prev.log2Ml[kl[x]] + 0.65 * dl[x] * prev.log2Ml[kl[x] + 1] - sum43 + bigGamma
            cur.Ml[x] = exp(0.693 * cur.log2Ml[x]) * if (cur.Vl[x] == 1) 1.0 else unvc
        }
    }

    companion object {
        private const val VOICE = 0
        private const val SILENCE = 1
        private const val ERASURE = 2
        private const val TONE = 3
        /** 5.7: the smoothed error rate above which a frame is muted. */
        private const val MUTE_RATE = 0.096
        /** Mute noise, uniform within ± this on the 16-bit scale: 0 or ±1 once rounded, as DVSI plays it. */
        private const val MUTE_NOISE = 0.75
        /** Samples a fade (a tone in or out, the last frame under a mute) lasts: 10 ms. */
        private const val FADE = 80

        /** Table 9: a tone index to its frequencies in Hz (equal for a single tone), or null if it plays none (255 too). */
        internal fun toneFreqs(id: Int): Pair<Double, Double>? = when (id) {
            5 -> 156.25 to 156.25
            6 -> 187.5 to 187.5
            in 7..122 -> 31.25 * id to 31.25 * id
            in 128..163 -> DUAL[2 * (id - 128)] to DUAL[2 * (id - 128) + 1]
            else -> null
        }

        // Dual tones 128..163: DTMF, KNOX and call progress (mbelib-neo's table).
        private val DUAL = doubleArrayOf(
            1336.0, 941.0, 1209.0, 697.0, 1336.0, 697.0, 1477.0, 697.0, 1209.0, 770.0, 1336.0, 770.0, 1477.0, 770.0,
            1209.0, 852.0, 1336.0, 852.0, 1477.0, 852.0, 1633.0, 697.0, 1633.0, 770.0, 1633.0, 852.0, 1633.0, 941.0,
            1209.0, 941.0, 1477.0, 941.0, 1162.0, 820.0, 1052.0, 606.0, 1162.0, 606.0, 1279.0, 606.0, 1052.0, 672.0,
            1162.0, 672.0, 1279.0, 672.0, 1052.0, 743.0, 1162.0, 743.0, 1279.0, 743.0, 1430.0, 606.0, 1430.0, 672.0,
            1430.0, 743.0, 1430.0, 820.0, 1052.0, 820.0, 1279.0, 820.0, 440.0, 350.0, 480.0, 440.0, 620.0, 480.0,
            490.0, 350.0,
        )

        /** One AMBE frame's 36 dibits as sent (NXDN, DMR; [dibit] of 0..35) to mbelib's rows: DSD's interleave map. */
        fun frame(dibit: (Int) -> Int): Array<IntArray> {
            val fr = Array(4) { IntArray(24) }
            for (i in 0 until 36) { val x = dibit(i); fr[NW[i]][NX[i]] = x shr 1; fr[NY[i]][NZ[i]] = x and 1 }
            return fr
        }

        // Dibit i's first bit to row NW bit NX, its second to NY / NZ.
        private val NW = intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2)
        private val NX = intArrayOf(23, 10, 22, 9, 21, 8, 20, 7, 19, 6, 18, 5, 17, 4, 16, 3, 15, 2, 14, 1, 13, 0, 12, 10, 11, 9, 10, 8, 9, 7, 8, 6, 7, 5, 6, 4)
        private val NY = intArrayOf(0, 2, 0, 2, 0, 2, 0, 2, 0, 3, 0, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3)
        private val NZ = intArrayOf(5, 3, 4, 2, 3, 1, 2, 0, 1, 13, 0, 12, 22, 11, 21, 10, 20, 9, 19, 8, 18, 7, 17, 6, 16, 5, 15, 4, 14, 3, 13, 2, 12, 1, 11, 0)
    }
}

/** 8 kHz speech to [outRate] (a whole multiple), by linear steps: the receiver's audio rate. */
class Upsampler(outRate: Int) {
    private val k = outRate / MbeSynth.RATE
    private var last = 0.0

    fun apply(pcm: ShortArray): ShortArray {
        if (k <= 1) return pcm
        val out = ShortArray(pcm.size * k)
        for (i in pcm.indices) {
            val x = pcm[i].toDouble()
            for (j in 1..k) out[i * k + j - 1] = (last + (x - last) * j / k).toInt().toShort()
            last = x
        }
        return out
    }
}
