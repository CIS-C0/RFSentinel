package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DmrTest {

    @Test
    fun blockCodesMatchTheirParityChecks() {
        for ((h, code, dmin) in listOf(Triple(Dmr.HAMMING_H, Dmr.HAMMING, 4), Triple(Dmr.QR_H, Dmr.QR, 6))) {
            val rows = h.size / 16
            for (cw in code) for (r in 0 until rows) {
                var p = 0
                for (c in 0 until 16) p = p xor ((cw shr (15 - c)) and h[r * 16 + c])
                assertEquals(0, p)
            }
            var min = 16
            for (i in code.indices) for (j in 0 until i) min = minOf(min, Integer.bitCount(code[i] xor code[j]))
            assertEquals(dmin, min)
        }
        assertEquals(0x55, Dmr.decode(Dmr.QR[0x55] xor 0b1000_0000_0100_0000, Dmr.QR, 2))
        assertEquals(-1, Dmr.decode(Dmr.QR[0x55] xor 0b1010_0000_0100_0000, Dmr.QR, 2))
        assertEquals(0x3A5, Dmr.decode(Dmr.HAMMING[0x3A5] xor 0x0100, Dmr.HAMMING, 1))
    }

    // ---- a synthetic DMR call ----

    /** A 72-bit AMBE frame (mbelib rows) holding the 49 data bits [d]: the inverse of [AmbeDecoder.data]. */
    private fun ambeFrame(d: IntArray): Array<IntArray> {
        val fr = arrayOf(IntArray(24), IntArray(23), IntArray(11), IntArray(14))
        val c0 = Nxdn.value(d, 0, 12); val c1 = Nxdn.value(d, 12, 12)
        val w0 = c0 shl 11 or P25Voice.golayParity(c0)
        val w1 = c1 shl 11 or P25Voice.golayParity(c1)
        for (j in 0 until 23) fr[0][j + 1] = w0 shr j and 1
        var pr = 16 * c0
        for (j in 22 downTo 0) { pr = (173 * pr + 13849) and 0xffff; fr[1][j] = (w1 shr j and 1) xor (pr shr 15) }
        for (i in 0 until 11) fr[2][10 - i] = d[24 + i]
        for (i in 0 until 14) fr[3][13 - i] = d[35 + i]
        return fr
    }

    /** A frame's 36 dibits as sent: [AmbeDecoder.frame] run backwards (where each dibit's two bits land). */
    private fun ambeDibits(fr: Array<IntArray>) = IntArray(36) { i ->
        fun pos(x: Int): Pair<Int, Int> { val f = AmbeDecoder.frame { if (it == i) x else 0 }; val r = f.indexOfFirst { row -> row.any { it == 1 } }; return r to f[r].indexOf(1) }
        val (hr, hc) = pos(2); val (lr, lc) = pos(1)
        fr[hr][hc] shl 1 or fr[lr][lc]
    }

    private fun bitsOf(v: Long, len: Int) = IntArray(len) { ((v shr (len - 1 - it)) and 1).toInt() }

    /** The 128 bits of a link control (group call [dst] from [src], service options [options]) as bursts B-E send them. */
    private fun embeddedLc(options: Int, dst: Int, src: Int): IntArray {
        val data = bitsOf(0L, 16) + bitsOf(options.toLong(), 8) + bitsOf(dst.toLong(), 24) + bitsOf(src.toLong(), 24)
        var sum = 0; for (i in 0 until 9) sum += Nxdn.value(data, i * 8, 8)
        val crc = sum % 31
        val m = Array(8) { IntArray(16) }
        var k = 0
        for (r in 0 until 7) {
            val info = IntArray(11)
            val n = if (r < 2) 11 else 10
            for (c in 0 until n) info[c] = data[k++]
            if (r >= 2) info[10] = (crc shr (6 - r)) and 1
            val cw = Dmr.HAMMING[Nxdn.value(info, 0, 11)]
            for (c in 0 until 16) m[r][c] = (cw shr (15 - c)) and 1
        }
        for (c in 0 until 16) m[7][c] = (0 until 7).sumOf { m[it][c] } % 2
        return IntArray(128) { m[it % 8][it / 8] }
    }

    /** Six bursts (A-F) of a superframe as dibits: voice sync [pat] on A, EMB with [pi] and the link control on B-E. */
    private fun superframe(speech: IntArray, pi: Int, lc: IntArray, pat: Int = 1): List<IntArray> {
        val voice = ambeDibits(ambeFrame(speech))
        return List(6) { vc ->
            val d = IntArray(Dmr.BURST)
            for (f in 0 until 3) for (i in 0 until 36) d[Dmr.voiceDibit(f, i)] = voice[i]
            val mid = if (vc == 0) Dmr.VOICE[pat].map { if (it == 3) 1 else 3 }.toIntArray() else {
                val lcss = when (vc) { 1 -> 1; 4 -> 2; 5 -> 0; else -> 3 }
                val emb = Dmr.QR[1 shl 3 or (pi shl 2) or lcss]
                val frag = if (vc in 1..4) lc.copyOfRange((vc - 1) * 32, vc * 32) else IntArray(32)
                val bits = bitsOf(emb.toLong() shr 8, 8) + frag + bitsOf(emb.toLong() and 0xff, 8)
                IntArray(24) { bits[2 * it] shl 1 or bits[2 * it + 1] }
            }
            mid.copyInto(d, 54)
            d
        }
    }

    /** A mobile's bursts on the air at [rate]: each in its 30 ms slot, nothing in the other; ±3 = ±0.6 · [sign] plus [dc]. */
    private fun air(bursts: List<IntArray>, rate: Int, dc: Double, sign: Int): DoubleArray {
        val sym = ArrayList<Int>()
        repeat(200) { sym += 0 }
        for (b in bursts) {
            for (x in b) sym += when (x) { 0 -> 1; 1 -> 3; 2 -> -1; else -> -3 }
            repeat(Dmr.FRAME - Dmr.BURST) { sym += 0 }
        }
        repeat(400) { sym += 0 }
        val sps = rate.toDouble() / Dmr.BAUD
        return DoubleArray((sym.size * sps).toInt()) { sign * 0.2 * sym[(it / sps).toInt()] + dc }
    }

    private val speech = IntArray(49).also { it[2] = 1; it[39] = 1; it[10] = 1; it[20] = 1 }

    @Test
    fun clearCallIsDecodedWithItsIds() {
        val lc = embeddedLc(0, 1234, 567)
        val frames = superframe(speech, 0, lc) + superframe(speech, 0, lc)
        var samples = 0
        val dmr = Dmr(32_000, 32_000) { samples += it.size }
        for (v in air(frames, 32_000, 0.05, 1)) dmr.sample(v)
        assertEquals(12, dmr.bursts)
        assertEquals(0, dmr.cipher)
        assertEquals(2, dmr.linkControls)
        assertEquals(1234, dmr.destination)
        assertEquals(567, dmr.source)
        assertEquals(3, dmr.heldFrames) // burst A, before the first EMB said clear
        assertEquals(33, dmr.voiceFrames)
        assertEquals(0, dmr.ambeErrors)
        assertEquals(33 * 160 * 4, samples)
    }

    @Test
    fun theOtherWayUpIsFoundFromTheSyncRhythm() {
        val lc = embeddedLc(0, 1, 2)
        val frames = (0 until 5).flatMap { superframe(speech, 0, lc) }
        val dmr = Dmr(32_000) { }
        for (v in air(frames, 32_000, 0.0, -1)) dmr.sample(v)
        assertEquals(1, dmr.polarityFlips)
        assertEquals(0, dmr.cipher)
        assertTrue(dmr.voiceFrames >= 6 * 3)
    }

    @Test
    fun encryptedCallsAreNotPlayed() {
        // The EMB's PI flag: known from burst B on.
        val pi = Dmr(32_000) { }
        for (v in air(superframe(speech, 1, embeddedLc(0, 1, 2)), 32_000, 0.0, 1)) pi.sample(v)
        assertEquals(1, pi.cipher)
        assertEquals("encrypted", Dmr.cipherLabel(pi.cipher))
        assertEquals(0, pi.voiceFrames)
        // Only the link control's privacy bit (no PI): B-D play, burst E completes it and nothing plays after.
        val so = Dmr(32_000) { }
        val lc = embeddedLc(0x40, 1, 2)
        for (v in air(superframe(speech, 0, lc) + superframe(speech, 0, lc), 32_000, 0.0, 1)) so.sample(v)
        assertEquals(1, so.cipher)
        assertEquals(9, so.voiceFrames)
        assertEquals(2 * 3 + 6 * 3, so.encryptedFrames)
    }

    @Test
    fun noiseIsNotDmr() {
        val r = java.util.Random(11)
        val dmr = Dmr(32_000) { }
        repeat(32_000 * 5) { dmr.sample(r.nextGaussian() * 0.3) }
        assertEquals(0, dmr.bursts)
        assertTrue(!dmr.active)
    }
}
