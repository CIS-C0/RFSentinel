package com.rfsentinel.app.sdr

import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** TETRA on 380-385 MHz: a handset's time-slot bursts are told apart from a carrier that stays on. */
class TetraTest {

    private val rate = 2_048_000
    private val count = 131_072                 // 64 ms, as SdrRadio reads a TETRA chunk
    private val center = 382_400_000L
    private val radio = 382_612_500L

    /** Noise, plus a tone at [freq] that is on during [on] (ms into the chunk). */
    private fun iq(freq: Long?, amp: Double = 50.0, seed: Int = 1, on: (Double) -> Boolean = { true }): ByteArray {
        val rnd = java.util.Random(seed.toLong())
        val b = ByteArray(2 * count)
        for (i in 0 until count) {
            var re = rnd.nextGaussian() * 1.5; var im = rnd.nextGaussian() * 1.5
            if (freq != null && on(i * 1000.0 / rate)) {
                val ph = 2 * PI * (freq - center) * i / rate
                re += amp * cos(ph); im += amp * sin(ph)
            }
            b[2 * i] = (127.5 + re).roundToInt().coerceIn(0, 255).toByte()
            b[2 * i + 1] = (127.5 + im).roundToInt().coerceIn(0, 255).toByte()
        }
        return b
    }

    /** One TETRA slot (14.17 ms) per 56.67 ms frame, starting [start] ms in. */
    private fun slot(start: Double) = { t: Double -> ((t - start) % 56.67 + 56.67) % 56.67 < 14.17 }

    private fun watch(): Pair<RadioWatch, Long> {
        val w = RadioWatch()
        w.config = RadioWatch.Config(bands = listOf(RadioWatch.TETRA_BAND))
        var t = 0L
        repeat(8) { s ->
            val b = RadioWatch.burstSpectrum(iq(null, seed = s), count, rate)
            w.analyze(center, b.psd, rate, t, b); w.sweepDone(); t += 2_500
        }
        return w to t
    }

    @Test
    fun burstSpectrumKeepsTwoMillisecondSteps() {
        val b = RadioWatch.burstSpectrum(iq(radio, on = slot(10.0)), count, rate)
        assertEquals(2.0, b.groupMs, 0.01)
        assertEquals(32, b.groups.size)
    }

    @Test
    fun aHandsetsSlotIsTdmaAndAnAlert() {
        val (w, t) = watch()
        for (start in listOf(0.0, 20.0, 47.0)) {
            val b = RadioWatch.burstSpectrum(iq(radio, on = slot(start)), count, rate)
            val ev = w.analyze(center, b.psd, rate, t + (start * 1000).toLong(), b).single()
            assertTrue("slot at $start ms", ev.tdma)
            assertEquals(RadioWatch.Kind.TETRA, ev.band.kind)
            assertTrue(kotlin.math.abs(ev.freqHz - radio) <= 12_500)
        }
        val b = RadioWatch.burstSpectrum(iq(radio, on = slot(5.0)), count, rate)
        assertTrue(w.analyze(center, b.psd, rate, t + 500_000, b).single().confidence >= 65)
    }

    @Test
    fun aCarrierThatStaysOnIsNotTdma() {
        val (w, t) = watch()
        val b = RadioWatch.burstSpectrum(iq(radio), count, rate)
        val ev = w.analyze(center, b.psd, rate, t, b)
        assertTrue(ev.none { it.tdma })
        assertTrue(ev.all { it.confidence <= 55 })
    }

    @Test
    fun noiseIsNotTdma() {
        val levels = FloatArray(32) { if (it % 3 == 0) 7f else 3f }
        assertFalse(RadioWatch.isTdma(levels, 2.0))
        // A short chunk can't show a whole frame.
        assertFalse(RadioWatch.isTdma(FloatArray(16) { if (it < 7) 40f else 0f }, 2.0))
        assertTrue(RadioWatch.isTdma(FloatArray(32) { if (it in 5..11) 40f else 0f }, 2.0))
    }

    @Test
    fun theBandIsOnlyTheHandsetSide() {
        val b = RadioWatch.TETRA_BAND
        assertEquals(380_000_000L, b.startHz)
        assertEquals(385_000_000L, b.endHz)
        assertEquals(4, RadioWatch.plan(RadioWatch.Config(bands = listOf(b))).size)
        assertTrue(RadioWatch.covers(b, 384_000_000L))
        assertFalse(RadioWatch.covers(b, 392_000_000L))   // base stations: never swept
    }

    @Test
    fun onByDefaultInEurope() {
        assertTrue(Prefs.tetraRegion("de"))
        assertTrue(Prefs.tetraRegion("GB"))
        assertFalse(Prefs.tetraRegion("US"))
        assertFalse(Prefs.tetraRegion(""))
    }

    @Test
    fun hackRfIsAnSdr() {
        assertTrue(HackRf.isHackRf(0x1d50, 0x6089))
        assertFalse(HackRf.isHackRf(0x0bda, 0x2838))
        assertFalse(RtlSdr.isRtlSdr(0x1d50, 0x6089))
    }
}
