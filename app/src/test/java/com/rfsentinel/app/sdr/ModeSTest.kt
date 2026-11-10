package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Example messages from "The 1090 MHz Riddle" (Junzi Sun), with its worked answers. */
class ModeSTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun frame(s: String): ModeS.Frame {
        val b = hex(s)
        assertEquals("CRC", 0, ModeS.syndrome(b, b.size * 8))
        return ModeS.Frame(b, ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff), 100)
    }

    @Test
    fun identification() {
        val i = ModeS.decode(frame("8D4840D6202CC371C32CE0576098"))
        assertEquals(0x4840D6, i.icao)
        assertEquals("KLM1023", i.callsign)
    }

    @Test
    fun airbornePositionGlobalAndLocal() {
        val even = ModeS.decode(frame("8D40621D58C382D690C8AC2863A7"))
        val odd = ModeS.decode(frame("8D40621D58C386435CC412692AD6"))
        assertEquals(false, even.cprOdd); assertEquals(true, odd.cprOdd)
        assertEquals(38000, even.altitudeFt)
        val (lat, lon) = ModeS.globalPosition(even.cprLat, even.cprLon, odd.cprLat, odd.cprLon, oddNewer = false)!!
        assertEquals(52.2572, lat, 0.0001); assertEquals(3.91937, lon, 0.0001)
        val (la, lo) = ModeS.localPosition(even.cprLat, even.cprLon, false, 52.258, 3.918)
        assertEquals(52.2572, la, 0.0001); assertEquals(3.9194, lo, 0.0001)
    }

    @Test
    fun aFramePairOffTheGlobeIsDropped() {
        // Even 0, odd 0.66 of the CPR range: both latitudes come out near 120 degrees.
        assertNull(ModeS.globalPosition(0, (0.66 * 131072).toInt(), (0.66 * 131072).toInt(), 0, oddNewer = true))
    }

    @Test
    fun groundSpeedAndAirspeed() {
        val v = ModeS.decode(frame("8D485020994409940838175B284F"))
        assertEquals(159.2, v.speedKt!!, 0.1); assertEquals(182.88, v.trackDeg!!, 0.01)
        val a = ModeS.decode(frame("8DA05F219B06B6AF189400CBC33F"))
        assertEquals(375.0, a.speedKt!!, 0.1); assertEquals(243.98, a.trackDeg!!, 0.01)
    }

    @Test
    fun longitudeZones() {
        assertEquals(59, ModeS.nl(0.0)); assertEquals(36, ModeS.nl(52.2572)); assertEquals(1, ModeS.nl(88.0))
    }

    /**
     * Magnitudes at 2.4 MS/s for a preamble and [msg] in pulse-position code, starting [shift]
     * twelfths of a microsecond into sample [lead]: each sample is the share of it a pulse covers.
     */
    private fun signal(msg: ByteArray, lead: Int = 40, shift: Int = 0, level: Int = 120, floor: Int = 2): IntArray {
        // In sixtieths of a microsecond: a sample is 25, a half-microsecond symbol 30.
        val n = lead + 350 + ModeS.OVERLAP
        val high = BooleanArray(n * 25 + 60)
        fun pulse(symbol: Int) { val from = lead * 25 + shift * 5 + symbol * 30; for (t in from until from + 30) high[t] = true }
        for (p in intArrayOf(0, 2, 7, 9)) pulse(p)
        for (i in 0 until msg.size * 8) pulse(16 + 2 * i + (if ((msg[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1) 0 else 1))
        return IntArray(n) { k -> floor + level * (0 until 25).count { high[k * 25 + it] } / 25 }
    }

    private val klm = hex("8D4840D6202CC371C32CE0576098")

    /** An all-call reply (DF11) from [icao] with interrogator code [iid]. */
    private fun allCall(icao: Int, iid: Int): ByteArray {
        val m = byteArrayOf(0x5D, (icao shr 16).toByte(), (icao shr 8).toByte(), icao.toByte(), 0, 0, 0)
        val p = ModeS.parity(m, 56) xor iid
        m[4] = (p shr 16).toByte(); m[5] = (p shr 8).toByte(); m[6] = p.toByte()
        return m
    }

    @Test
    fun demodulatesAtEverySamplePhase() {
        for (shift in 0 until 12) {
            val out = ArrayList<ModeS.Frame>()
            val m = signal(klm, shift = shift)
            ModeS.Demod().scan(m, m.size, out)
            assertEquals("shift $shift", "KLM1023", ModeS.decode(out.single()).callsign)
            assertEquals(120.0, out[0].signal.toDouble(), 20.0)
        }
    }

    @Test
    fun fixesOneBadBit() {
        val bad = klm.copyOf().also { it[6] = (it[6].toInt() xor 0x10).toByte() }
        val out = ArrayList<ModeS.Frame>()
        val d = ModeS.Demod()
        val m = signal(bad, shift = 7)
        d.scan(m, m.size, out)
        assertEquals("KLM1023", ModeS.decode(out.single()).callsign)
        assertEquals(1L, d.fixed)
        // Two bad bits: dropped, not "fixed" into another message.
        val worse = bad.copyOf().also { it[9] = (it[9].toInt() xor 0x01).toByte() }
        out.clear()
        ModeS.Demod().scan(signal(worse), signal(worse).size, out)
        assertTrue(out.isEmpty())
    }

    @Test
    fun anAllCallReplyWithAnInterrogatorCodeNeedsAKnownAddress() {
        val d = ModeS.Demod()
        val out = ArrayList<ModeS.Frame>()
        fun hear(msg: ByteArray) { val m = signal(msg, shift = 3); d.scan(m, m.size, out) }
        hear(allCall(0x4840D6, 0x05))
        assertTrue("unknown address: too likely noise", out.isEmpty())
        hear(allCall(0xABCDEF, 0))
        assertEquals(0xABCDEF, out.single().icao)
        assertEquals(7, out[0].bytes.size)
        hear(klm)
        hear(allCall(0x4840D6, 0x05))
        assertEquals(listOf(0xABCDEF, 0x4840D6, 0x4840D6), out.map { it.icao })
    }

    @Test
    fun aMessageAcrossTwoReadsIsFoundOnceAndTheNoiseIsCounted() {
        // Three messages in one stream, read in pieces as the receiver does: each straddles two reads.
        val one = signal(klm, lead = 0, shift = 4)
        val stream = IntArray(5_000) { 2 }
        for (at in intArrayOf(1_100, 2_600, 3_900)) System.arraycopy(one, 0, stream, at, 330)
        val d = ModeS.Demod()
        val out = ArrayList<ModeS.Frame>()
        val quiet = IntArray(ModeS.LEVELS)
        val read = 1_500
        val mag = IntArray(read + 2 * ModeS.OVERLAP)
        var have = 0; var scanned = 0
        var at = 0
        while (at < stream.size) {
            val n = minOf(read, stream.size - at)
            System.arraycopy(stream, at, mag, have, n); at += n
            val total = have + n
            if (total < 2 * ModeS.OVERLAP) { have = total; continue }
            d.scan(mag, total, out, quiet)
            scanned += total - ModeS.OVERLAP
            System.arraycopy(mag, total - ModeS.OVERLAP, mag, 0, ModeS.OVERLAP); have = ModeS.OVERLAP
        }
        assertEquals(3, out.size)
        assertTrue(out.all { ModeS.decode(it).callsign == "KLM1023" })
        // Everything scanned but the messages (preamble and 112 bits: 288 samples each) is noise.
        assertEquals(scanned - 3 * 288, quiet.sum())
        assertTrue(quiet[2] >= quiet.sum() - 3 * 2) // the floor, but for a sample a pulse edge reaches
    }

    @Test
    fun noiseIsNotAMessage() {
        val r = java.util.Random(1)
        val m = IntArray(200_000) { r.nextInt(60) }
        val out = ArrayList<ModeS.Frame>()
        ModeS.Demod().scan(m, m.size, out)
        assertTrue(out.size <= 1)
        // Too short to scan at all.
        ModeS.Demod().scan(IntArray(ModeS.OVERLAP), ModeS.OVERLAP, out)
    }

    @Test
    fun altitudeNeedsTheQBit() {
        assertNull(ModeS.altitude(0))
        assertNotNull(ModeS.altitude(0x10))
    }

    @Test
    fun trackerPutsPositionTogether() {
        val t = AdsbTracker()
        t.add(frame("8D40621D58C386435CC412692AD6"), 1_000, null, null)
        assertTrue(t.located(1_000).isEmpty())
        t.add(frame("8D40621D58C382D690C8AC2863A7"), 2_000, null, null)
        val p = t.located(2_000).single()
        assertEquals("40621d", p.hex)
        assertEquals(52.2572, p.lat, 0.0001)
        assertEquals(38000, p.altitudeFt)
        // A decode far from you is dropped.
        val far = AdsbTracker()
        far.add(frame("8D40621D58C386435CC412692AD6"), 0, 10.5, -20.5)
        far.add(frame("8D40621D58C382D690C8AC2863A7"), 500, 10.5, -20.5)
        assertTrue(far.located(500).isEmpty())
    }
}
