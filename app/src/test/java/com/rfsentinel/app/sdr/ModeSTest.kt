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

    /** Magnitudes for a preamble and [msg] in pulse-position code, with noise-free levels. */
    private fun signal(msg: ByteArray, lead: Int = 40): IntArray {
        val m = IntArray(lead + ModeS.OVERLAP + 100) { 2 }
        for (p in intArrayOf(0, 2, 7, 9)) m[lead + p] = 120
        for (i in 0 until msg.size * 8) {
            val one = (msg[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1
            m[lead + 16 + 2 * i + (if (one) 0 else 1)] = 120
        }
        return m
    }

    @Test
    fun demodulatesAndFixesOneBadBit() {
        val msg = hex("8D4840D6202CC371C32CE0576098")
        val out = ArrayList<ModeS.Frame>()
        val d = ModeS.Demod()
        d.scan(signal(msg), signal(msg).size, out)
        assertEquals(1, out.size)
        assertEquals("KLM1023", ModeS.decode(out[0]).callsign)

        val bad = msg.copyOf().also { it[6] = (it[6].toInt() xor 0x10).toByte() }
        out.clear()
        d.scan(signal(bad), signal(bad).size, out)
        assertEquals("KLM1023", ModeS.decode(out.single()).callsign)
        assertEquals(1L, d.fixed)
    }

    @Test
    fun noiseIsNotAMessage() {
        val r = java.util.Random(1)
        val m = IntArray(200_000) { r.nextInt(60) }
        val out = ArrayList<ModeS.Frame>()
        ModeS.Demod().scan(m, m.size, out)
        assertTrue(out.size <= 1)
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
