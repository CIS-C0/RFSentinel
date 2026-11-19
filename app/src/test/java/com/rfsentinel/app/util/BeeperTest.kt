package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeperTest {

    @Test
    fun radarTicksSpeedUpAsTheSignalGetsStronger() {
        assertEquals(1300L, Beeper.tickIntervalMs(-100))
        assertEquals(60L, Beeper.tickIntervalMs(-30))
        assertTrue(Beeper.tickIntervalMs(-55) < 200 && Beeper.tickIntervalMs(-75) > 350)
        assertTrue(Beeper.tickIntervalMs(-60) < Beeper.tickIntervalMs(-80))
    }

    @Test
    fun radarBeepsAreVerySlowFarAwayAndCappedUpClose() {
        val slow = Beeper.RADAR_SLOWEST_MS
        assertEquals(4000L, Beeper.tickIntervalMs(-110, slow)) // faint and far: a tick every 4 s
        assertEquals(60L, Beeper.tickIntervalMs(-20, slow))    // as close as it gets: never faster than 60 ms
        assertTrue((-95..-45).zipWithNext().all { (a, b) -> Beeper.tickIntervalMs(b, slow) <= Beeper.tickIntervalMs(a, slow) })
        // The ticks stop when the device does: 0.3 s after a fast advertiser's last packet, 2.5 s at the very most.
        assertEquals(300L, Beeper.tickFreshMs(100))
        assertEquals(1500L, Beeper.tickFreshMs(1000))
        assertEquals(2500L, Beeper.tickFreshMs(2000))                 // a USB WiFi adapter's 2 s reports: no break between them
        assertEquals(2500L, Beeper.tickFreshMs(30_000))               // the phone's WiFi scan: one short run per scan
        assertEquals(2500L, Beeper.tickFreshMs(Long.MAX_VALUE))       // first packet: no gap known
        // One packet from far away is one tick: its run is over before the next one is due.
        assertTrue(Beeper.tickFreshMs(Long.MAX_VALUE) < Beeper.tickIntervalMs(-95, slow))
    }

    @Test
    fun detectorTonesGetFasterAndHigherWithStrength() {
        val ka = Beeper.detectorAlert(3); val k = Beeper.detectorAlert(2); val x = Beeper.detectorAlert(1)
        assertTrue(ka.size > k.size && k.size > x.size)
        assertTrue(ka.maxOf { it.toHz } > k.maxOf { it.fromHz } && k.maxOf { it.fromHz } > x.maxOf { it.fromHz })
        assertTrue(Beeper.toneDurationMs(ka) < 1_000 && Beeper.toneDurationMs(Beeper.detectorStartup) < 1_000)
        val pcm = Beeper.tonePcm(Beeper.detectorStartup)
        assertEquals(Beeper.toneDurationMs(Beeper.detectorStartup) * 22_050 / 1000, pcm.size)
        assertTrue(pcm.maxOf { it.toInt() } > 20_000)
    }

    @Test
    fun patternMatchesTierAndFollowing() {
        assertEquals(1, Beeper.pattern(1, following = false).size)
        assertEquals(3, Beeper.pattern(3, following = false).size)
        assertEquals(0, Beeper.pattern(3, following = false).last().second)
        val f = Beeper.pattern(1, following = true)
        assertTrue(f.first().first > f[1].first) // long-short-long
    }

    @Test
    fun pcmIsAudibleWithSilentGaps() {
        val p = Beeper.pattern(2, following = false)
        val pcm = Beeper.pcm(p)
        assertEquals(Beeper.durationMs(p) * 22_050 / 1000, pcm.size)
        assertTrue(pcm.maxOf { it.toInt() } > 20_000)
        // Middle of the gap between the two beeps is silent.
        val gapMid = (140 + 55) * 22_050 / 1000
        assertEquals(0, pcm[gapMid].toInt())
    }

    @Test
    fun locateTapsGrowWithTheSignalAndFitBetweenTicks() {
        assertEquals(12L to 70, Beeper.vibeTap(-100))
        assertEquals(30L to 255, Beeper.vibeTap(-30))
        val mid = Beeper.vibeTap(-70)
        assertTrue(mid.first in 13L..29L && mid.second in 71..254)
        for (rssi in -100..-30) assertTrue(Beeper.vibeTap(rssi).first * 2 <= Beeper.tickIntervalMs(rssi))
    }
}
