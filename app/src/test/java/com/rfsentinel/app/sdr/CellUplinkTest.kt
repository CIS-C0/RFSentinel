package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The optional cellular-uplink watch: wideband energy in an LTE uplink slice, nothing decoded. A
 * transmitter lifts the whole slice above its learned quiet level; towers (downlink) are never looked at.
 */
class CellUplinkTest {
    private val rate = 2_048_000
    private val n = 1024
    private val binHz = rate.toDouble() / n
    private val band = RadioWatch.LTE_UPLINK_BANDS.first { 830_000_000L in it.startHz..it.endHz } // 850 MHz uplink, 814-849 MHz
    private val center = 830_000_000L                 // a slice well inside it
    private val cfg = RadioWatch.Config(bands = listOf(band))

    /** A slice at [level] dB across the usable window, optionally with uplink energy [rise] dB higher. */
    private fun slice(level: Float, rise: Int = 0): FloatArray {
        val p = FloatArray(n) { -90f }
        val usable = (RadioWatch.USABLE_HZ / binHz).toInt()
        for (i in n / 2 - usable..n / 2 + usable) if (i in 0 until n) p[i] = level + if (rise > 0) rise else 0
        return p
    }

    private fun learn(w: RadioWatch, sweeps: Int): Long {
        var t = 0L
        repeat(sweeps) { w.analyze(center, slice(-70f), rate, t); w.sweepDone(); t += 2_000 }
        return t
    }

    @Test
    fun aTransmitterInTheSliceIsReportedAfterLearning() {
        val w = RadioWatch(); w.config = cfg
        val t = learn(w, 8)
        val ev = w.analyze(center, slice(-70f, rise = 12), rate, t)
        assertEquals(1, ev.size)
        assertEquals(center, ev[0].freqHz)
        assertEquals(RadioWatch.Kind.CELLULAR_UPLINK, ev[0].band.kind)
        assertTrue("confidence is modest: a cellular signal alone is weak", ev[0].confidence in 40..55)
    }

    @Test
    fun aQuietSliceIsNeverReported() {
        val w = RadioWatch(); w.config = cfg
        val t = learn(w, 8)
        assertTrue(w.analyze(center, slice(-70f), rate, t).isEmpty())
        // small drift (a few dB) is not a transmitter
        assertTrue(w.analyze(center, slice(-66f), rate, t + 2_000).isEmpty())
    }

    @Test
    fun nothingIsReportedWhileStillLearning() {
        val w = RadioWatch(); w.config = cfg
        assertTrue(w.analyze(center, slice(-70f, rise = 15), rate, 0L).isEmpty())
    }

    @Test
    fun theSameSliceIsARepeatNotANewAlert() {
        val w = RadioWatch(); w.config = cfg
        val t = learn(w, 8)
        assertFalse(w.analyze(center, slice(-70f, rise = 12), rate, t).single().repeat)
        assertTrue(w.analyze(center, slice(-70f, rise = 12), rate, t + 2_000).single().repeat)
    }

    @Test
    fun aSlowlyRisingNoiseFloorIsLearnedNotAlerted() {
        // The ambient level creeps up over many sweeps (moving between areas): the baseline follows it.
        val w = RadioWatch(); w.config = cfg
        var t = 0L; var level = -80f
        repeat(40) { w.analyze(center, slice(level), rate, t); w.sweepDone(); t += 2_000; level += 0.5f }
        assertTrue("a gradual drift is not a transmitter", w.analyze(center, slice(level), rate, t).isEmpty())
    }

    @Test
    fun theUplinkBandsStayInTheDonglesRange() {
        for (b in RadioWatch.LTE_UPLINK_BANDS) {
            assertTrue("${b.label} starts in range", b.startHz in 24_000_000L..1_766_000_000L)
            assertTrue("${b.label} ends in range", b.endHz in 24_000_000L..1_766_000_000L)
            assertEquals(RadioWatch.Kind.CELLULAR_UPLINK, b.kind)
        }
        // The sweep plan tiles the uplink band with chunk centres inside it.
        val plan = RadioWatch.plan(cfg)
        assertTrue(plan.any { it in band.startHz..band.endHz })
    }

    @Test
    fun aTowerOnTheDownlinkIsNeverWatched() {
        // Downlinks (towers) are never watched: 850 MHz 869-894, Band 13 746-756, Band 14 758-768.
        for (downlink in listOf(881_000_000L, 751_000_000L, 763_000_000L))
            assertTrue(RadioWatch.LTE_UPLINK_BANDS.none { downlink in it.startHz..it.endHz })
        // Band 14's uplink (788-798 MHz, the public-safety broadband block) is.
        assertTrue(RadioWatch.LTE_UPLINK_BANDS.any { 793_000_000L in it.startHz..it.endHz })
    }
}
