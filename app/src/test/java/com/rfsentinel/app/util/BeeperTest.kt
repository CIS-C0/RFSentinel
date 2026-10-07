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
}
