package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Frequency correction learned from a P25 control channel, and the tune that applies it. */
class FreqCorrectionTest {

    private val announced = 851_012_500L

    /** Where a dongle [errPpm] off, corrected by [appliedPpm], hears [hz] (as the app counts frequencies). */
    private fun heard(hz: Long, errPpm: Double, appliedPpm: Double): Long {
        val asked = SdrDevice.corrected(hz, appliedPpm)
        val lo = asked * (1 + errPpm / 1e6)
        return Math.round(hz + (hz - lo))
    }

    @Test fun learnsTheCrystalErrorAfterFiveAgreeingReadings() {
        val fix = FreqCorrection(0.0)
        repeat(FreqCorrection.NEEDED - 1) { assertNull(fix.add(heard(announced, 32.0, 0.0), announced)) }
        assertEquals(32.0, fix.add(heard(announced, 32.0, 0.0), announced)!!, 0.05)
    }

    @Test fun onTopOfTheCorrectionAlreadyApplied() {
        // Corrected by 30 for a 32 ppm crystal: 2 ppm left, so 32 in all.
        val fix = FreqCorrection(30.0)
        var r: Double? = null
        repeat(FreqCorrection.NEEDED) { r = fix.add(heard(announced, 32.0, 30.0), announced) }
        assertEquals(32.0, r!!, 0.05)
    }

    @Test fun afterTheCorrectionTheChannelIsHeardWhereItIs() {
        assertEquals(announced.toDouble(), heard(announced, -41.5, -41.5).toDouble(), 2.0)
    }

    @Test fun smallErrorsAreLeftAlone() {
        val fix = FreqCorrection(0.0)
        repeat(10) { assertNull(fix.add(heard(announced, 0.6, 0.0), announced)) }
    }

    @Test fun readingsThatDisagreeOrAreFarOffTeachNothing() {
        val fix = FreqCorrection(0.0)
        // A loop still settling: 20, 25, 20, 25, ... never within 1 ppm of each other.
        repeat(10) { assertNull(fix.add(heard(announced, if (it % 2 == 0) 20.0 else 25.0, 0.0), announced)) }
        // Another control channel 150 kHz away is no crystal error, and starts the count again.
        val other = FreqCorrection(0.0)
        repeat(4) { other.add(heard(announced, 10.0, 0.0), announced) }
        assertNull(other.add(announced + 150_000L, announced))
        assertNull(other.add(heard(announced, 10.0, 0.0), announced))
        // Nothing heard yet, or a channel whose table hasn't arrived.
        assertNull(other.add(0L, announced)); assertNull(other.add(announced, 0L))
    }

    @Test fun noCorrectionTunesExactly() {
        assertEquals(162_400_000L, SdrDevice.corrected(162_400_000L, 0.0))
        assertEquals(Math.round(162_400_000L / 1.00002), SdrDevice.corrected(162_400_000L, 20.0))
    }
}
