package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class RadioWatchTest {

    private val rate = 2_048_000
    private val n = 1024
    private val binHz = rate.toDouble() / n

    /** A flat -60 dB spectrum with a signal [snr] dB up at each frequency of [signals]. */
    private fun psd(center: Long, vararg signals: Pair<Long, Int>): FloatArray {
        val p = FloatArray(n) { -60f }
        for ((f, snr) in signals) {
            val mid = n / 2 + ((f - center) / binHz).roundToInt()
            for (k in mid - 2..mid + 2) if (k in 0 until n) p[k] = -60f + snr
        }
        return p
    }

    private val center = 807_000_000L         // a chunk in the 800 MHz public-safety band
    private val tower = 806_500_000L          // always on
    private val radio = 807_312_500L          // a handheld keying up

    private fun learn(w: RadioWatch, sweeps: Int, t0: Long = 0L): Long {
        var t = t0
        repeat(sweeps) { w.analyze(center, psd(center, tower to 40), rate, t); w.sweepDone(); t += 2_500 }
        return t
    }

    @Test
    fun alwaysOnChannelsAreLearnedAndIgnored() {
        val w = RadioWatch()
        val t = learn(w, 10)
        assertTrue(w.busyChannels >= 1)
        assertTrue(w.analyze(center, psd(center, tower to 45), rate, t).isEmpty())
    }

    @Test
    fun aStrongBurstOnAQuietChannelIsReported() {
        val w = RadioWatch()
        val t = learn(w, 8)
        val ev = w.analyze(center, psd(center, tower to 40, radio to 42), rate, t)
        assertEquals(1, ev.size)
        assertEquals(radio, ev[0].freqHz)
        assertEquals(75, ev[0].confidence)
        assertEquals(RadioWatch.Kind.PUBLIC_SAFETY_MOBILE, ev[0].band.kind)
        // the same channel isn't reported again right away
        assertTrue(w.analyze(center, psd(center, radio to 42), rate, t + 2_500).isEmpty())
    }

    @Test
    fun nothingIsReportedWhileLearning() {
        val w = RadioWatch()
        learn(w, 2)
        assertTrue(w.analyze(center, psd(center, radio to 45), rate, 10_000).isEmpty())
    }

    @Test
    fun weakSignalsAndSplatterAreNotReportedTwice() {
        val w = RadioWatch()
        val t = learn(w, 8)
        assertTrue(w.analyze(center, psd(center, radio to 20), rate, t).isEmpty()) // too weak for an alert
        val ev = w.analyze(center, psd(center, radio to 42, radio + 12_500 to 35), rate, t + 200_000)
        assertEquals(1, ev.size)
    }

    @Test
    fun sharedBandsNeedAStrongerSignalAndSkipConsumerRadios() {
        val c = 462_600_000L
        val w = RadioWatch()
        var t = 0L
        repeat(8) { w.analyze(c, psd(c), rate, t); w.sweepDone(); t += 2_500 }
        assertTrue(RadioWatch.excluded(462_625_000L))  // FRS / GMRS
        assertTrue(w.analyze(c, psd(c, 462_625_000L to 50), rate, t).isEmpty())
        val ev = w.analyze(c, psd(c, 462_900_000L to 50), rate, t + 1)
        assertEquals(1, ev.size)
        assertEquals(55, ev[0].confidence)
        assertTrue(w.analyze(c, psd(c, 463_100_000L to 36), rate, t + 2).single().confidence == 35)
    }

    @Test
    fun planCoversEveryBandWithoutLteUplink() {
        val plan = RadioWatch.plan()
        assertEquals(37, plan.size)
        for (b in RadioWatch.BANDS) assertTrue(plan.any { it - RadioWatch.USABLE_HZ <= b.startHz })
        assertFalse(RadioWatch.BANDS.any { it.endHz > 814_000_000L && it.startHz < 849_000_000L && it.startHz > 806_000_000L })
    }

    @Test
    fun powerSpectrumPutsAToneInItsBin() {
        val count = 8192
        val iq = ByteArray(2 * count)
        val tone = 200_000.0
        for (i in 0 until count) {
            val ph = 2 * PI * tone * i / rate
            iq[2 * i] = (127.5 + 60 * cos(ph)).roundToInt().toByte()
            iq[2 * i + 1] = (127.5 + 60 * sin(ph)).roundToInt().toByte()
        }
        val p = RadioWatch.powerSpectrum(iq, count, n)
        val peak = p.indices.maxByOrNull { p[it] }!!
        assertEquals(n / 2 + (tone / binHz).roundToInt(), peak)
    }
}
