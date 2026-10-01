package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeeperTest {

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
