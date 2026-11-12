package com.rfsentinel.app.sdr

import com.rfsentinel.app.sdr.P25TestSignal.block
import com.rfsentinel.app.sdr.P25TestSignal.frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** LSM demodulation, soft decoding, secondary control channels, inbound messages, Phase 2 marking, try order. */
class P25ImprovementsTest {

    /** Sync + [payload] as an LSM (CQPSK) simulcast transmitter sends it: phase steps per dibit, RRC shaped, 8-bit I/Q. */
    private fun lsmSignal(payload: IntArray, carrierHz: Double, frames: Int): ByteArray {
        val dibits = IntArray(40) { (it * 7 + 3) % 4 } + (0 until frames).flatMap { (P25TestSignal.SYNC + payload).toList() } + IntArray(40) { it % 4 }
        val sps = P25Demod.SPS
        // Impulses at the symbol instants, then the transmitter's RRC.
        val impR = DoubleArray(dibits.size * sps); val impI = DoubleArray(dibits.size * sps)
        var phase = 0.0
        for ((k, d) in dibits.withIndex()) {
            phase += P25TestSignal.hz(d) / 1800.0 * 3 * PI / 4 // +1800 Hz ↔ +135°
            impR[k * sps] = cos(phase); impI[k * sps] = sin(phase)
        }
        val rrc = P25Demod.rootRaisedCosine()
        val bbR = DoubleArray(impR.size); val bbI = DoubleArray(impI.size)
        for (n in impR.indices) for (t in rrc.indices) {
            val m = n - t; if (m < 0) break
            bbR[n] += rrc[t] * impR[m]; bbI[n] += rrc[t] * impI[m]
        }
        val peak = bbR.indices.maxOf { kotlin.math.hypot(bbR[it], bbI[it]) }
        val hold = P25Demod.DECIM
        val out = ByteArray(bbR.size * hold * 2)
        val rnd = java.util.Random(11)
        var k = 0; var c = 0.0
        for (n in bbR.indices) repeat(hold) {
            c += 2 * PI * carrierHz / P25Demod.SAMPLE_RATE
            val r = bbR[n] / peak * 50; val i = bbI[n] / peak * 50
            out[k++] = (127.5 + r * cos(c) - i * sin(c) + rnd.nextGaussian() * 2).roundToInt().coerceIn(0, 255).toByte()
            out[k++] = (127.5 + r * sin(c) + i * cos(c) + rnd.nextGaussian() * 2).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    @Test
    fun lsmFramesAreFound() {
        val rnd = java.util.Random(5)
        val payload = IntArray(P25Demod.FRAME_DIBITS) { rnd.nextInt(4) }
        val frames = ArrayList<IntArray>(); val lsm = ArrayList<Boolean>()
        lateinit var demod: P25Demod
        demod = P25Demod(200_000.0, mod = P25Demod.Mod.LSM) { frames += it.copyOf(); lsm += demod.lsmFrame }
        val iq = lsmSignal(payload, 200_000.0, 3)
        var at = 0
        while (at < iq.size) { val len = minOf(16_384, iq.size - at); demod.feed(iq.copyOfRange(at, at + len), len / 2); at += len }
        assertEquals(3, frames.size)
        for (f in frames) assertArrayEquals(payload, f)
        assertTrue(lsm.all { it })
    }

    private val grant = byteArrayOf(0x00, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08)
    private val last = byteArrayOf(0x80.toByte(), 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09)
    private val level = doubleArrayOf(1.0 / 3, 1.0, -1.0 / 3, -1.0)

    @Test
    fun softDecisionsDecodeMoreBlocks() {
        val clean = frame(0x293, P25Frame.DUID_TSDU, block(grant), block(last))
        val rnd = java.util.Random(2)
        var hard = 0; var soft = 0
        repeat(200) {
            val s = DoubleArray(clean.size) { level[clean[it]] + rnd.nextGaussian() * 0.2 }
            val d = IntArray(s.size) { P25Demod.slice(s[it]) }
            hard += P25Frame.decode(d).tsbks.size
            soft += P25Frame.decode(d, s).tsbks.size
        }
        assertTrue("soft $soft, hard $hard", soft > hard)
        // A clean frame decodes the same both ways; soft values of the wrong length are ignored.
        assertEquals(2, P25Frame.decode(clean, DoubleArray(clean.size) { level[clean[it]] }).tsbks.size)
        assertEquals(2, P25Frame.decode(clean, DoubleArray(5)).tsbks.size)
    }

    private fun tsbk(opcode: Int, args: Long, mfid: Int = 0, protected: Boolean = false) =
        P25Frame.Tsbk(ByteArray(10) { i -> when (i) {
            0 -> (0x80 or (if (protected) 0x40 else 0) or opcode).toByte(); 1 -> mfid.toByte(); else -> (args ushr (8 * (9 - i))).toByte() } })

    @Test
    fun secondaryControlChannels() {
        // RFSS 1, site 7, channels 0x1010 and 0x1020.
        val m = P25Tsbk.parse(tsbk(0x39, (1L shl 56) or (7L shl 48) or (0x1010L shl 32) or (0x1020L shl 8)))
        assertEquals(P25Tsbk.Secondary(1, 7, listOf(0x1010, 0x1020)), m)
        // An empty second slot (0 or FFFF) is left out.
        assertEquals(listOf(0x1010), (P25Tsbk.parse(tsbk(0x39, (0x1010L shl 32) or (0xffffL shl 8))) as P25Tsbk.Secondary).channels)
    }

    @Test
    fun inboundMessagesNameTheRadio() {
        assertEquals(P25Tsbk.Inbound(0x123456, "registration"), P25Tsbk.parseInbound(tsbk(0x2C, 0xABCD_0012_3456L)))
        assertEquals("talkgroup affiliation", P25Tsbk.parseInbound(tsbk(0x28, 0x0102_0003_0405L))?.kind)
        assertNull(P25Tsbk.parseInbound(tsbk(0x2C, 0x0012_3456L, protected = true)))
        assertNull(P25Tsbk.parseInbound(tsbk(0x2C, 0x0012_3456L, mfid = 0x90)))
        assertNull(P25Tsbk.parseInbound(tsbk(0x2C, 0L)))
        assertNull(P25Tsbk.parseInbound(tsbk(0x2C, 0xfffff5L)))
        assertNull(P25Tsbk.parseInbound(tsbk(0x3F, 0x0012_3456L)))
    }

    @Test
    fun alternatesPhase2AndNearbyRadios() {
        val log = P25Log()
        log.add(P25Tsbk.Iden(1, 851_000_000L, 6_250, 1), 0)
        log.add(P25Tsbk.Iden(2, 770_000_000L, 12_500, 2), 0) // a TDMA table
        log.add(P25Tsbk.Site(0xBEE00, 0x3A1, 1, 7, 0x1004), 0)
        log.add(P25Tsbk.Neighbor(1, 8, 0x1010), 0)
        log.add(P25Tsbk.Secondary(1, 7, listOf(0x1008, 0x9999)), 0) // 0x9999: no table, left out
        assertEquals(listOf(851_025_000L, 851_050_000L, 851_100_000L), log.alternates())

        log.add(P25Tsbk.Grant(0x2001, 300, null, 1001, false, false), 1_000)
        log.add(P25Tsbk.Grant(0x1002, 301, null, 1002, false, false), 1_000)
        val rows = log.rows().associateBy { it.tg }
        assertTrue(rows.getValue(300).phase2)
        assertTrue(!rows.getValue(301).phase2)
        assertTrue(log.takeCalls().first { it.tg == 300 }.phase2)

        // A radio's own message: its last talkgroup (affiliation or a call it started) is marked near.
        log.add(P25Tsbk.Affiliation(1003, 301), 2_000)
        assertEquals(301, log.heardRadio(1003, 33, 3_000))
        assertEquals(300, log.heardRadio(1001, 20, 3_000))
        assertNull(log.heardRadio(4444, 20, 3_000))
        assertEquals(3_000L to 33, log.rows().first { it.tg == 301 }.let { it.inputAt to it.inputSnr })
        log.clear(4_000)
        assertNull(log.heardRadio(1003, 33, 5_000))
        assertEquals(emptyList<Long>(), log.alternates())
    }

    @Test
    fun rankPutsRejectedAndWeakLast() {
        val a = P25Hunt.Candidate(1L, 25f); val b = P25Hunt.Candidate(2L, 30f); val weak = P25Hunt.Candidate(3L, 8f); val c = P25Hunt.Candidate(4L, 22f)
        val order = P25Hunt.rank(listOf(a, b, weak, c), rejected = { it == 2L }, steady = { if (it.hz == 4L) 3 else 0 })
        // c: 22 + 3 × 3 beats a's 25; b was rejected, so it goes after even the weak one.
        assertEquals(listOf(4L, 1L, 3L, 2L), order.map { it.hz })
    }

    @Test
    fun bandsFallBackToTheDefaults() {
        assertEquals(P25Hunt.DEFAULT_BANDS, P25Hunt.bandsFor(emptySet()))
        assertEquals(listOf("vhf"), P25Hunt.bandsFor(setOf("vhf", "nonsense")).map { it.key })
    }
}
