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
import com.rfsentinel.app.sdr.AmbeTables.W0
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The AMBE+2 half-rate vocoder (AMBE 3600x2450) used by NXDN and DMR: a 72-bit frame to 20 ms of speech,
 * 160 samples at 8 kHz. A port of mbelib (ISC licence): its error correction (two Golay words, the second one
 * whitened by a sequence seeded from the first), its parameter decoding and tables ([AmbeTables]); synthesis
 * is shared with IMBE ([MbeSynth]). One decoder per call: each frame's spectrum is coded against the last.
 */
class AmbeDecoder(rnd: Random = Random(1)) : MbeSynth(rnd) {

    /** Bits the last frame's error correction fixed. */
    var errors = 0; private set

    /** 20 ms of speech from one frame in mbelib's layout: [fr] rows of 24, 23, 11 and 14 bits (bit 0 last sent). */
    fun decode(fr: Array<IntArray>): ShortArray {
        val d = data(fr)
        return if (parms(d) == SPEECH) speak(errors <= 3) else silence()
    }

    /** The 49 data bits of a frame, its two Golay words corrected; sets [errors]. */
    internal fun data(fr: Array<IntArray>): IntArray {
        val d = IntArray(49)
        var w0 = 0
        for (j in 0 until 23) w0 = w0 or (fr[0][j + 1] shl j)
        val c0 = P25Voice.golay(w0).first
        var e = Integer.bitCount(c0 xor (w0 ushr 11))
        for (i in 0 until 12) d[i] = c0 shr (11 - i) and 1
        // The second word is whitened by a sequence seeded from the first one's data.
        var pr = 16 * c0
        var w1 = 0
        for (j in 22 downTo 0) {
            pr = (173 * pr + 13849) and 0xffff
            w1 = w1 or ((fr[1][j] xor (pr shr 15)) shl j)
        }
        val c1 = P25Voice.golay(w1).first
        e += Integer.bitCount(c1 xor (w1 ushr 11))
        for (i in 0 until 12) d[12 + i] = c1 shr (11 - i) and 1
        for (i in 0 until 11) d[24 + i] = fr[2][10 - i]
        for (i in 0 until 14) d[35 + i] = fr[3][13 - i]
        errors = e
        return d
    }

    /** The data bits to [cur]: [SPEECH] (silence frames included), or an erasure or tone frame. */
    private fun parms(d: IntArray): Int {
        cur.repeat = prev.repeat
        fun bits(vararg i: Int): Int { var v = 0; for (x in i) v = v shl 1 or d[x]; return v }
        val b0 = bits(0, 1, 2, 3, 37, 38, 39)
        if (b0 in 120..123) return ERASURE
        if (b0 >= 126) return TONE
        val silent = b0 >= 124
        val f0 = if (silent) 1.0 / 32 else W0[b0].toDouble()
        val L = if (silent) 14 else AmbeTables.L[b0]
        cur.w0 = 2 * PI * f0; cur.L = L
        val unvc = 0.2046 / sqrt(cur.w0)

        // Voicing: one of 32 patterns over 8 bands.
        val b1 = bits(4, 5, 6, 7, 35)
        for (l in 1..L) cur.Vl[l] = if (silent) 0 else VUV[b1 * 8 + minOf(7, (l * 16.0 * f0).toInt())]

        // Gain, coded against the last frame's.
        cur.gamma = DG[bits(8, 9, 10, 11, 36)] + 0.5 * prev.gamma

        // The prediction residual: its 8 block averages (PRBA), then each block's higher order coefficients (HOC).
        val gm = DoubleArray(9)
        val b3 = bits(12, 13, 14, 15, 16, 17, 18, 19, 40)
        for (i in 0..2) gm[2 + i] = PRBA24[b3 * 3 + i].toDouble()
        val b4 = bits(20, 21, 22, 23, 41, 42, 43)
        for (i in 0..3) gm[5 + i] = PRBA58[b4 * 4 + i].toDouble()
        val ri = DoubleArray(9)
        for (i in 1..8) for (m in 1..8) ri[i] += (if (m == 1) 1 else 2) * gm[m] * cos(PI * (m - 1) * (i - 0.5) / 8)
        val cik = Array(5) { DoubleArray(18) }
        val rc = 1 / (2 * sqrt(2.0))
        for (i in 1..4) { cik[i][1] = 0.5 * (ri[2 * i - 1] + ri[2 * i]); cik[i][2] = rc * (ri[2 * i - 1] - ri[2 * i]) }
        val hoc = arrayOf(HOC5, HOC6, HOC7, HOC8)
        val hb = intArrayOf(bits(24, 25, 26, 27, 44), bits(28, 29, 30, 45), bits(31, 32, 33, 46), bits(34, 47, 48))
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
            val f = prev.L.toDouble() / L * x
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
        return SPEECH
    }

    companion object {
        private const val SPEECH = 0
        private const val ERASURE = 2
        private const val TONE = 3

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
