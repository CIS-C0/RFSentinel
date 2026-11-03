package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ImbeDecoderTest {

    /** A codeword with fundamental code [b0], every band voiced, gain [gain], the other coefficients at 0. */
    private fun codeword(b0: Int, gain: Int): ByteArray {
        val d = ByteArray(88)
        for (i in 0..5) d[i] = (b0 shr (7 - i) and 1).toByte()
        d[85] = (b0 shr 1 and 1).toByte(); d[86] = (b0 and 1).toByte()
        val l = (0.9254 * (PI / (4 * PI / (b0 + 39.5)) + 0.25).toInt()).toInt()
        if (b0 <= 207) for (i in 6 until 85) {
            val p = (l - 9) * 79 + i - 6
            d[i] = when (ImbeDecoder.BO_M[p] - '0') {
                1 -> 1
                2 -> (gain shr (ImbeDecoder.BO_B[p] - '0') and 1)
                else -> 0
            }.toByte()
        }
        return d
    }

    @Test
    fun voicedSpeechHasItsPitch() {
        // b0 100: w0 = 4π / 139.5, a period of 69.75 samples (115 Hz).
        val imbe = ImbeDecoder()
        val out = (0 until 10).flatMap { imbe.decode(codeword(100, 40), 0).toList() }.drop(5 * ImbeDecoder.N).map { it.toDouble() }
        assertEquals(5 * ImbeDecoder.N, out.size)
        val energy = out.sumOf { it * it } / out.size
        assertTrue("energy $energy", energy > 1e4)
        fun corr(lag: Int) = (0 until out.size - lag).sumOf { out[it] * out[it + lag] }
        val best = (40..120).maxBy { corr(it) }
        assertTrue("strongest repeat at $best samples", best in 69..70)
    }

    @Test
    fun aBadCodewordRepeatsTheLastThenGoesSilent() {
        val imbe = ImbeDecoder()
        repeat(3) { imbe.decode(codeword(100, 40), 0) }
        // Damaged (too many errors) or silence: the last good one goes on, three times.
        repeat(3) { assertTrue(imbe.decode(codeword(100, 40), 9).any { it.toInt() != 0 }) }
        assertTrue(imbe.decode(codeword(216, 0), 0).all { it.toInt() == 0 })
        assertTrue(imbe.decode(codeword(216, 0), 0).all { it.toInt() == 0 })
        // Then speech again.
        repeat(2) { imbe.decode(codeword(100, 40), 0) }
        assertTrue(imbe.decode(codeword(100, 40), 0).any { it.toInt() != 0 })
    }
}
