package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NxdnTest {

    private fun bits(s: String) = IntArray(s.length) { s[it] - '0' }

    @Test
    fun crcsMatchARealRadio() {
        // SACCH quarters and a FACCH1 VCALL as a Kenwood NEXEDGE sent them.
        assertEquals(0b010110, Nxdn.crc(bits("00001011000000000000000000"), 26, 6))
        assertEquals(0b100001, Nxdn.crc(bits("11001011000000010000000000"), 26, 6))
        val vcall = bits("00000001000000000010000000000000000000010000000000000000000000000000000000000000111001101111")
        assertEquals(Nxdn.value(vcall, 80, 12), Nxdn.crc(vcall, 80, 12))
    }

    @Test
    fun viterbiUndoesTheCodeThroughPuncturingAndErrors() {
        val r = java.util.Random(3)
        val data = IntArray(32) { r.nextInt(2) }
        val code = Nxdn.encode(data) // 72 bits
        val p = intArrayOf(1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0)
        val sent = code.filterIndexed { i, _ -> p[i % 12] == 1 }.toIntArray()
        sent[7] = sent[7] xor 1; sent[40] = sent[40] xor 1
        val got = Nxdn.viterbi(Nxdn.depuncture(sent, p, 72))
        assertArrayEquals(data, got.copyOf(32))
        // Interleaving: column order back to the order sent.
        val x = IntArray(60) { it }
        val sentOrder = IntArray(60).also { s -> for (i in 0 until 60) s[i] = x[(i % 5) * 12 + i / 5] }
        assertArrayEquals(x, Nxdn.deinterleave(sentOrder, 5))
    }

    // ---- a synthetic NXDN48 transmission ----

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

    @Test
    fun ambeFrameCorrectionFixesBitErrors() {
        val r = java.util.Random(5)
        val d = IntArray(49) { r.nextInt(2) }
        val fr = ambeFrame(d)
        fr[0][5] = fr[0][5] xor 1; fr[1][17] = fr[1][17] xor 1
        val dec = AmbeDecoder()
        assertArrayEquals(d, dec.data(fr))
        assertEquals(1, dec.errors) // C0's bit 5 is parity: only data bits count, as in mbelib
    }

    /** A voice frame (LICH 0x56: voice in both halves) with SACCH quarter [part] of [msg], as clear dibits. */
    private fun voiceFrame(part: Int, msg: IntArray, speech: IntArray): IntArray {
        val d = IntArray(Nxdn.FRAME)
        val lich = 0x56 shl 1 or ((0x56 shr 6 xor (0x56 shr 5) xor (0x56 shr 4) xor (0x56 shr 3)) and 1)
        for (k in 0 until 8) d[k] = (lich shr (7 - k) and 1) shl 1 or 1
        // SACCH: structure, RAN, the quarter, CRC-6, coded, punctured, interleaved.
        val info = IntArray(32)
        val sf = 3 - part
        info[0] = sf shr 1; info[1] = sf and 1; info[7] = 1
        for (i in 0 until 18) info[8 + i] = msg[part * 18 + i]
        val crc = Nxdn.crc(info, 26, 6)
        for (i in 0 until 6) info[26 + i] = crc shr (5 - i) and 1
        val p = intArrayOf(1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0)
        val coded = Nxdn.encode(info).filterIndexed { i, _ -> p[i % 12] == 1 }
        val bits = IntArray(60) { coded[(it % 5) * 12 + it / 5] }
        for (i in 0 until 30) d[8 + i] = bits[2 * i] shl 1 or bits[2 * i + 1]
        // Four AMBE frames, through DSD's dibit map.
        val fr = ambeFrame(speech)
        val nw = intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2)
        val nx = intArrayOf(23, 10, 22, 9, 21, 8, 20, 7, 19, 6, 18, 5, 17, 4, 16, 3, 15, 2, 14, 1, 13, 0, 12, 10, 11, 9, 10, 8, 9, 7, 8, 6, 7, 5, 6, 4)
        val ny = intArrayOf(0, 2, 0, 2, 0, 2, 0, 2, 0, 3, 0, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3)
        val nz = intArrayOf(5, 3, 4, 2, 3, 1, 2, 0, 1, 13, 0, 12, 22, 11, 21, 10, 20, 9, 19, 8, 18, 7, 17, 6, 16, 5, 15, 4, 14, 3, 13, 2, 12, 1, 11, 0)
        for (b in 0 until 4) for (i in 0 until 36) d[38 + b * 36 + i] = fr[nw[i]][nx[i]] shl 1 or fr[ny[i]][nz[i]]
        return d
    }

    /** Dibits to an FM discriminator signal at [rate]: sync, scrambled frame, 2400 baud, ±3 = ±0.6 plus [dc]. */
    private fun air(frames: List<IntArray>, rate: Int, dc: Double, sign: Int): DoubleArray {
        val sym = ArrayList<Int>()
        repeat(20) { sym += if (it % 2 == 0) 3 else -3 } // preamble
        for (f in frames) {
            sym += Nxdn.SYNC.toList()
            for (j in f.indices) sym += when (f[j] xor (Nxdn.PN[j] shl 1)) { 0 -> 1; 1 -> 3; 2 -> -1; else -> -3 }
        }
        repeat(40) { sym += 0 }
        val sps = rate.toDouble() / Nxdn.BAUD
        return DoubleArray((sym.size * sps).toInt()) { sign * 0.2 * sym[(it / sps).toInt()] + dc }
    }

    private fun vcall(cipher: Int): IntArray {
        val m = IntArray(72)
        m[7] = 1                                   // VCALL
        m[56] = cipher shr 1; m[57] = cipher and 1
        m[39] = 1                                  // source unit 1
        return m
    }

    @Test
    fun clearCallIsDecodedAndSaysSo() {
        val r = java.util.Random(9)
        // Speech: a voiced frame, pitch index 40, loud enough.
        val speech = IntArray(49) { r.nextInt(2) }.also { for (i in intArrayOf(0, 1, 2, 3, 37, 38, 39)) it[i] = 0; it[2] = 1; it[39] = 1 }
        val msg = vcall(0)
        val frames = List(8) { voiceFrame(it % 4, msg, speech) }
        for (sign in intArrayOf(1, -1)) {
            var samples = 0
            val nx = Nxdn(32_000, 32_000) { samples += it.size }
            for (v in air(frames, 32_000, 0.05, sign)) nx.sample(v)
            assertEquals(8, nx.frames)
            assertEquals(0, nx.cipher)
            assertEquals(1, nx.source)
            assertEquals(32, nx.voiceFrames)
            assertEquals(0, nx.ambeErrors)
            assertEquals(32 * 160 * 4, samples)
        }
    }

    @Test
    fun encryptedCallIsFlaggedAndNotPlayed() {
        val speech = IntArray(49)
        val frames = List(8) { voiceFrame(it % 4, vcall(1), speech) }
        val nx = Nxdn(32_000) { }
        for (v in air(frames, 32_000, 0.0, 1)) nx.sample(v)
        assertEquals(1, nx.cipher)
        assertEquals("encrypted (scrambler)", Nxdn.cipherLabel(nx.cipher))
        // Until the superframe's last quarter the cipher isn't known: three frames play, the five after don't.
        assertEquals(20, nx.encryptedFrames)
    }

    @Test
    fun aCallStartsOnlyOnACheckedMessage() {
        val good = voiceFrame(0, vcall(0), IntArray(49))
        val noSacch = good.copyOf().also { for (i in 8 until 38) it[i] = 0 } // LICH fine, SACCH fails its CRC
        val nx = Nxdn(32_000) { }
        assertTrue(!nx.handle(noSacch))
        assertEquals(0, nx.voiceFrames)
        assertTrue(nx.handle(good))
        assertTrue(nx.handle(noSacch)) // within the call: still played
        assertEquals(8, nx.voiceFrames)
    }

    @Test
    fun noiseIsNotNxdn() {
        val r = java.util.Random(11)
        val nx = Nxdn(32_000) { }
        repeat(32_000 * 5) { nx.sample(r.nextGaussian() * 0.3) }
        assertEquals(0, nx.frames)
        assertTrue(!nx.active)
    }

    /** A recording saved by "Save played calls" (32 kHz WAV), given in NXDN_WAV: decoded and summed up. Off by default. */
    @Test
    fun recordingDecodes() {
        val path = System.getenv("NXDN_WAV")
        assumeTrue(path != null)
        val b = java.io.File(path!!).readBytes()
        val bb = java.nio.ByteBuffer.wrap(b, 44, b.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = java.io.ByteArrayOutputStream()
        val nx = Nxdn(32_000, 8_000) { pcm -> for (s in pcm) { out.write(s.toInt() and 0xff); out.write(s.toInt() shr 8 and 0xff) } }
        while (bb.remaining() >= 2) nx.sample(bb.short / 32767.0)
        println("frames ${nx.frames} bad ${nx.badFrames} voice ${nx.voiceFrames} encrypted ${nx.encryptedFrames} " +
            "cipher ${nx.cipher} AMBE errors ${nx.ambeErrors}")
        System.getenv("NXDN_OUT")?.let { java.io.File(it).writeBytes(P25Record.header(out.size(), 1, 8_000) + out.toByteArray()) }
        assertTrue(nx.voiceFrames > 0)
    }
}
