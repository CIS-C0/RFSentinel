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
        val out = feed(a, 30_000, 10, fake, 30.0)
        assertTrue(out.any { it.key == "uniform" })
    }

    @Test
    fun otherSystemsVanishingWhileGpsStaysStrong() {
        val a = GnssAnalyzer()
        feed(a, 0, 60, sky(shift = 10f), 30.0)
        val out = feed(a, 60_000, 10, sky(shift = 10f, systems = setOf(System.GPS)), 30.0)
        assertTrue(out.any { it.key == "gpsonly" })
    }

    @Test
    fun clockSkewAndImpossibleJumps() {
        val a = GnssAnalyzer()
        assertTrue(a.onFix(GnssAnalyzer.Fix(1_000_000, 1_000_000, 10.0, -20.0, 5f)).isEmpty())
        val jump = a.onFix(GnssAnalyzer.Fix(1_002_000, 1_002_000, 11.0, -20.0, 5f)) // ~111 km in 2 s
        assertEquals("jump", jump.single().key)
        val skew = a.onFix(GnssAnalyzer.Fix(1_004_000 + 3_600_000, 1_004_000, 11.0, -20.0, 5f))
        assertTrue(skew.any { it.key == "time" })
    }
}
