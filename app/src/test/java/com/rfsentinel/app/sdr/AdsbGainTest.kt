package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** The ADS-B gain control against a receiver whose noise floor rises with the gain. */
class AdsbGainTest {

    /** Noise level at [tenths] of gain: 4 at 29.7 dB, ten times that 20 dB higher. */
    private fun noiseAt(tenths: Int, extra: Double = 1.0) = (4 * 10.0.pow((tenths - 297) / 200.0) * extra).roundToInt()

    private fun fits(level: Int) = 20 * log10(ModeS.FULL_SCALE / level.toDouble()) >= AdsbGain.TARGET_DB

    /** One second of noise at [level]. */
    private fun block(g: AdsbGain, level: Int): AdsbGain.Change? { g.quiet[level] += 100_000; return g.endOfBlock() }

    private fun run(g: AdsbGain, seconds: Int, extra: Double = 1.0) { repeat(seconds) { block(g, noiseAt(g.tenths, extra)) } }

    @Test fun comesDownToTheMostGainThatKeepsTheRange() {
        val g = AdsbGain()
        assertEquals(496, g.tenths)
        run(g, 600)
        val best = AdsbGain.STEPS.filter { fits(noiseAt(it)) }.max()
        assertEquals(best, g.tenths)
        assertTrue(g.rangeDb >= AdsbGain.TARGET_DB)
    }

    @Test fun aQuietReceiverKeepsTheMostGain() {
        val g = AdsbGain()
        repeat(60) { assertNull(block(g, 3)) }
        assertEquals(496, g.tenths)
    }

    @Test fun waitsForTheNoiseToSettleBetweenSteps() {
        val g = AdsbGain()
        val changes = (1..40).mapNotNull { s -> block(g, noiseAt(g.tenths))?.let { s } }
        assertEquals(listOf(11, 22, 33), changes)
    }

    @Test fun comesDownWhenTheNoiseRisesAndGoesBackUpOnceItFalls() {
        val g = AdsbGain()
        run(g, 600)
        val settled = g.tenths
        // Something nearby lifts the noise floor: lower.
        run(g, 300, extra = 2.0)
        assertTrue(g.tenths < settled)
        val lowered = g.tenths
        // It goes away: a few minutes later, back up to where it was.
        run(g, AdsbGain.SCAN_DELAY + 300)
        assertTrue(g.tenths > lowered)
        assertEquals(settled, g.tenths)
    }

    @Test fun noSamplesNoDecision() {
        val g = AdsbGain(297)
        assertEquals(297, g.tenths)
        repeat(50) { assertNull(g.endOfBlock()) }
        assertEquals(AdsbGain.STEPS.first(), AdsbGain(-50).tenths)
    }
}
