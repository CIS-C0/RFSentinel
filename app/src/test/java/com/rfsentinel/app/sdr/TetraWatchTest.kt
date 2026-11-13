package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The nonstop TETRA watch on its own dongle(s), its thresholds and the bar's readings. */
class TetraWatchTest {

    @Test
    fun theSensitivitySettingNeverMakesTetraDeaf() {
        assertEquals(20, RadioWatch.tetraMinDb(30, city = false))
        assertEquals(28, RadioWatch.tetraMinDb(30, city = true))
        assertEquals(20, RadioWatch.tetraMinDb(50, city = false)) // the cap
        assertEquals(5, RadioWatch.tetraMinDb(10, city = false))  // more sensitive stays more sensitive
        assertEquals("Far", RadioWatch.proximity(22))
        assertEquals("Closer", RadioWatch.proximity(30))
        assertEquals("Very close", RadioWatch.proximity(45))
    }

    @Test
    fun dongleCountSplitsTheBand() {
        assertEquals(emptyList<List<Long>>(), TetraWatch.split(0))
        assertEquals(listOf(listOf(TetraWatch.LOW, TetraWatch.HIGH)), TetraWatch.split(1))
        assertEquals(listOf(listOf(TetraWatch.LOW), listOf(TetraWatch.HIGH)), TetraWatch.split(2))
        assertEquals(emptyList<Long>(), TetraWatch.split(3)[2])
        // Both halves reach the band's edges.
        assertTrue(TetraWatch.LOW - TetraWatch.USABLE_HZ <= RadioWatch.TETRA_BAND.startHz)
        assertTrue(TetraWatch.HIGH + TetraWatch.USABLE_HZ >= RadioWatch.TETRA_BAND.endHz)
    }

    @Test
    fun theBarFollowsTheStrongestRadio() {
        val band = RadioWatch.TETRA_BAND
        fun ev(hz: Long, snr: Int) = RadioWatch.Event(hz, snr, band, 65, tdma = true)
        TetraWatch.heard(ev(381_000_000L, 25), 1_000L)
        TetraWatch.heard(ev(382_000_000L, 22), 1_500L)  // weaker, another radio: the bar stays on the first
        assertEquals(381_000_000L, TetraWatch.heard!!.hz)
        TetraWatch.heard(ev(381_000_000L, 31), 2_000L)
        assertEquals(1, TetraWatch.heard!!.trend)         // the same radio, stronger: getting closer
        TetraWatch.heard(RadioWatch.Event(381_000_000L, 50, band, 35), 2_500L) // no time slots: not TETRA's
        assertEquals(31, TetraWatch.heard!!.snr)
        assertEquals(0f, TetraWatch.level(15), 0f)
        assertEquals(1f, TetraWatch.level(60), 0f)
    }

    /** A dongle at [TetraWatch.RATE]: noise, and a TETRA handset's slot at [radio] while [on] and tuned near it. */
    private class FakeDongle(radio: Long, tunedTo: Long) : SdrDevice {
        override val label = "fake"
        var rate = 0
        override val sampleRate get() = rate
        override val ctlErrors = 0
        override val lost = false
        override val tunerWriteFails = 0
        override val settleReads = 1
        var tuned = 0L
        var on = false
        var tunes = 0
        var t = 0L // ms, 1/20 of a look per read
        private val count = 163_840
        private val noise = iq(null, 0L)
        private val burst = iq(radio, tunedTo)
        private var pos = 0
        private fun iq(radio: Long?, center: Long): ByteArray {
            val rnd = java.util.Random(7)
            val b = ByteArray(2 * count)
            for (i in 0 until count) {
                var re = rnd.nextGaussian() * 1.5; var im = rnd.nextGaussian() * 1.5
                val ms = i * 1000.0 / TetraWatch.RATE
                if (radio != null && (ms - 12.0 + 56.67) % 56.67 < 14.17) {
                    val ph = 2 * PI * (radio - center) * i / TetraWatch.RATE
                    re += 50 * cos(ph); im += 50 * sin(ph)
                }
                b[2 * i] = (127.5 + re).roundToInt().coerceIn(0, 255).toByte()
                b[2 * i + 1] = (127.5 + im).roundToInt().coerceIn(0, 255).toByte()
            }
            return b
        }
        private val radioCenter = tunedTo
        override fun open() = true
        override fun close() {}
        override fun setSampleRate(rate: Int) { this.rate = rate }
        override fun tune(hz: Long): Boolean { tuned = hz; tunes++; return true }
        override fun setGain(tenthsDb: Int) {}
        override fun resetBuffer() {}
        override fun read(buf: ByteArray, len: Int): Int {
            val src = if (on && tuned == radioCenter) burst else noise
            System.arraycopy(src, pos, buf, 0, len)
            pos = (pos + len) % src.size
            t += 5
            return len
        }
    }

    @Test
    fun oneDongleWatchesBothHalvesAndReportsOnlyBursts() {
        val radio = TetraWatch.LOW + 312_500L
        val fake = FakeDongle(radio, TetraWatch.LOW)
        val got = ArrayList<RadioWatch.Event>()
        var lastAt = 0L
        lateinit var w: TetraWatch
        w = TetraWatch(fake, { listOf(TetraWatch.LOW, TetraWatch.HIGH) }, "RX2", { RadioWatch.Config() },
            { if (w.looks == 250) fake.on = true; w.looks >= 500 }, { got += it; lastAt = fake.t }, clock = { fake.t })
        w.run()
        assertEquals(TetraWatch.RATE, fake.sampleRate)
        assertTrue("swaps halves every ${TetraWatch.SWAP_MS} ms", fake.tunes >= 20)
        assertTrue("reported", got.isNotEmpty())
        assertTrue(got.all { it.tdma && kotlin.math.abs(it.freqHz - radio) <= 12_500 })
        // A radio sending all the time is still reported at the end: not learned away as an always-busy channel.
        assertTrue(got.any { it.repeat })
        assertTrue("last report at $lastAt ms", lastAt >= 45_000L)
        assertTrue(got.size < 250 / 2) // a few updates a second, not one per look
    }
}
