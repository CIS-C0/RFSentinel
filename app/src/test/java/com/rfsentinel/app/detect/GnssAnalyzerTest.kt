package com.rfsentinel.app.detect

import com.rfsentinel.app.detect.GnssAnalyzer.Sat
import com.rfsentinel.app.detect.GnssAnalyzer.Snapshot
import com.rfsentinel.app.detect.GnssAnalyzer.System
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssAnalyzerTest {

    /** A normal sky: strength rises with elevation, four systems. */
    private fun sky(shift: Float = 0f, systems: Set<System> = setOf(System.GPS, System.GALILEO, System.GLONASS, System.BEIDOU)): List<Sat> =
        systems.flatMap { sys -> (1..4).map { i -> val el = i * 20f; Sat(sys, i, 25f + el / 3 + shift, el, true) } }

    private fun feed(a: GnssAnalyzer, from: Long, seconds: Int, sats: List<Sat>, agc: Double?) =
        (0 until seconds).flatMap { a.onSnapshot(Snapshot(from + it * 1000L, sats, agc)) }

    @Test
    fun normalSkyIsQuiet() {
        val a = GnssAnalyzer()
        assertTrue(feed(a, 0, 60, sky(), 30.0).isEmpty())
        assertEquals("No jamming or spoofing signs", a.verdict)
    }

    @Test
    fun noiseRiseWithWeakerSatellitesIsJamming() {
        val a = GnssAnalyzer()
        feed(a, 0, 60, sky(), 30.0)
        val out = feed(a, 60_000, 10, sky(shift = -12f), 18.0)
        assertEquals("jam", out.single().key)
        assertEquals(75, out.single().confidence)
    }

    @Test
    fun aTunnelIsNotJamming() {
        val a = GnssAnalyzer()
        feed(a, 0, 60, sky(), 30.0)
        assertTrue(feed(a, 60_000, 20, sky(shift = -15f), 30.0).isEmpty()) // weaker, but no noise rise
    }

    @Test
    fun totalLossWithoutAgcIsOnlyAWeakSign() {
        val a = GnssAnalyzer()
        feed(a, 0, 60, sky(), null)
        val out = feed(a, 60_000, 10, emptyList(), null)
        assertEquals("loss", out.single().key)
        assertTrue(out.single().confidence < 50)
    }

    @Test
    fun equallyStrongSatellitesLookSpoofed() {
        val a = GnssAnalyzer()
        feed(a, 0, 30, sky(), 30.0)
        val fake = (1..8).map { Sat(System.GPS, it, 45f + (it % 2) * 0.5f, (it * 10).toFloat(), true) }
        assertTrue(feed(a, 30_000, 10, fake, 30.0).none { it.key == "uniform" }) // a moment isn't enough
        assertTrue(feed(a, 40_000, 10, fake, 30.0).any { it.key == "uniform" })
    }

    @Test
    fun otherSystemsVanishingWhileGpsStaysStrong() {
        val a = GnssAnalyzer()
        feed(a, 0, 60, sky(shift = 10f), 30.0)
        val out = feed(a, 60_000, 20, sky(shift = 10f, systems = setOf(System.GPS)), 30.0)
        assertTrue(out.any { it.key == "gpsonly" })
    }

    @Test
    fun clockSkewAndImpossibleJumps() {
        val a = GnssAnalyzer()
        fun fix(t: Long, lat: Double, skewMs: Long = 0) = a.onFix(GnssAnalyzer.Fix(t + skewMs, t, lat, -20.0, 5f))
        assertTrue(fix(1_000_000, 10.0).isEmpty())
        // ~111 km in 2 s, and it stays there: a jump once the following fixes agree.
        assertTrue(fix(1_002_000, 11.0).isEmpty())
        assertTrue(fix(1_003_000, 11.0).isEmpty())
        assertTrue(fix(1_004_000, 11.0).isEmpty())
        assertEquals("jump", fix(1_005_000, 11.0).single().key)
        // The clock an hour off, fix after fix.
        assertTrue(fix(1_006_000, 11.0, 3_600_000).isEmpty())
        assertTrue(fix(1_007_000, 11.0, 3_600_000).isEmpty())
        assertTrue(fix(1_008_000, 11.0, 3_600_000).any { it.key == "time" })
    }

    @Test
    fun oneBadFixOrAHandSetClockIsNoSign() {
        val a = GnssAnalyzer()
        fun fix(t: Long, lat: Double, skewMs: Long = 0, networkClock: Boolean = true, acc: Float = 5f) =
            a.onFix(GnssAnalyzer.Fix(t + skewMs, t, lat, -20.0, acc, networkClock))
        // Out and straight back: one bad fix.
        assertTrue((0..6).flatMap { fix(1_000_000L + it * 1000, if (it == 2) 11.0 else 10.0) }.isEmpty())
        // A rough fix far away isn't even compared.
        assertTrue(fix(1_008_000, 12.0, acc = 400f).isEmpty())
        // The phone's clock set by hand: its gap to GNSS time means nothing.
        assertTrue((0..5).flatMap { fix(1_010_000L + it * 1000, 10.0, 3_600_000, networkClock = false) }.isEmpty())
        // A leap-second gap (18 s) after a cold start isn't spoofing.
        assertTrue((0..5).flatMap { fix(1_020_000L + it * 1000, 10.0, 18_000) }.isEmpty())
    }

    @Test
    fun equallyStrongSatellitesAllAtOneHeightAreNoSign() {
        val a = GnssAnalyzer()
        feed(a, 0, 30, sky(), 30.0)
        val same = (1..8).map { Sat(System.GPS, it, 45f, 60f + it, true) }
        assertTrue(feed(a, 30_000, 30, same, 30.0).none { it.key == "uniform" })
    }
}
