package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Listening from the waterfall: a 1 kHz tone sent each way comes out as a 1 kHz tone. */
class AudioDemodTest {

    private val rate = 2_048_000
    private val offset = 250_000L

    /** [seconds] of 8-bit I/Q with a carrier [offset] above the centre, its phase and amplitude per sample from [mod]. */
    private fun iq(seconds: Double, noise: Double = 0.0, mod: (t: Double) -> Pair<Double, Double>): ByteArray {
        val n = (seconds * rate).toInt()
        val r = java.util.Random(7)
        val b = ByteArray(2 * n)
        for (s in 0 until n) {
            val t = s.toDouble() / rate
            val (phase, amp) = mod(t)
            val a = 2 * PI * offset * t + phase
            b[2 * s] = (127.5 + amp * cos(a) + noise * r.nextGaussian()).roundToInt().coerceIn(0, 255).toByte()
            b[2 * s + 1] = (127.5 + amp * sin(a) + noise * r.nextGaussian()).roundToInt().coerceIn(0, 255).toByte()
        }
        return b
    }

    /** FM by a 1 kHz tone at [dev] Hz deviation. */
    private fun fmTone(dev: Double): (Double) -> Pair<Double, Double> = { t -> dev / 1000.0 * sin(2 * PI * 1000 * t) to 60.0 }

    private fun play(mode: AudioDemod.Mode, input: ByteArray): Pair<AudioDemod, ShortArray> {
        val got = ArrayList<Short>()
        val d = AudioDemod(mode, offset, rate) { pcm, n -> for (k in 0 until n) got += pcm[k] }
        // In reads of the dongle's size, as the receiver feeds it.
        var at = 0
        while (at < input.size) { val n = minOf(32_768, input.size - at); d.feed(input.copyOfRange(at, at + n), n / 2); at += n }
        return d to got.toShortArray()
    }

    /** Level of [hz] in [pcm] (after the first [skip] samples) against everything else: 1 is a pure tone. */
    private fun purity(pcm: ShortArray, hz: Double, sampleRate: Int, skip: Int = 3_200): Double {
        var re = 0.0; var im = 0.0; var total = 0.0
        for (k in skip until pcm.size) {
            val v = pcm[k].toDouble()
            re += v * cos(2 * PI * hz * k / sampleRate); im += v * sin(2 * PI * hz * k / sampleRate)
            total += v * v
        }
        val n = pcm.size - skip
        return if (total == 0.0) 0.0 else 2 * (re * re + im * im) / n / total
    }

    @Test fun narrowFmToneComesThroughAndTheSquelchOpens() {
        val (d, pcm) = play(AudioDemod.Mode.NFM, iq(0.5, mod = fmTone(3_000.0)))
        assertEquals(32_000, d.audioRate)
        assertEquals(16_000.0, pcm.size.toDouble(), 20.0)
        assertTrue(d.open)
        assertTrue("tone purity ${purity(pcm, 1000.0, 32_000)}", purity(pcm, 1000.0, 32_000) > 0.8)
    }

    @Test fun narrowFmNoiseStaysSilent() {
        val (d, pcm) = play(AudioDemod.Mode.NFM, iq(0.5, noise = 6.0) { 0.0 to 0.0 })
        assertFalse(d.open)
        assertTrue(pcm.all { it.toInt() == 0 })
    }

    @Test fun theSquelchClosesWhenTheSignalStops() {
        val on = iq(0.3, noise = 1.0, mod = fmTone(3_000.0))
        val off = iq(0.3, noise = 6.0) { 0.0 to 0.0 }
        val (d, pcm) = play(AudioDemod.Mode.NFM, on + off)
        assertFalse(d.open)
        // Quiet within a read or two (16 ms each at most) of the signal going.
        assertTrue(pcm.copyOfRange(9_600 + 1_600, pcm.size).all { it.toInt() == 0 })
    }

    @Test fun wideFmTone() {
        val (_, pcm) = play(AudioDemod.Mode.WFM, iq(0.5, mod = fmTone(50_000.0)))
        assertTrue("tone purity ${purity(pcm, 1000.0, 32_000)}", purity(pcm, 1000.0, 32_000) > 0.8)
    }

    @Test fun amToneAtAnyLevel() {
        for (level in doubleArrayOf(8.0, 80.0)) {
            // With the receiver noise every real dongle has (without it an 8-level tone quantizes into harmonics).
            val (_, pcm) = play(AudioDemod.Mode.AM, iq(1.0, noise = 1.5) { t -> 0.0 to level * (1 + 0.5 * sin(2 * PI * 1000 * t)) })
            assertTrue("tone purity at $level: ${purity(pcm, 1000.0, 32_000)}", purity(pcm, 1000.0, 32_000) > 0.8)
            // The same loudness weak or strong: half the RMS of a 0.5 modulation at the set volume.
            val rms = kotlin.math.sqrt(pcm.drop(16_000).sumOf { it.toDouble() * it } / (pcm.size - 16_000)) / 32767
            assertEquals(0.5 * 0.6 / kotlin.math.sqrt(2.0), rms, 0.05)
        }
    }

    @Test fun aStationNextDoorIsFilteredOut() {
        // An unmodulated carrier on the channel plus a loud 1 kHz FM station 50 kHz away: nearly silent.
        val near = iq(0.5) { 0.0 to 30.0 }
        val other = iq(0.5) { t -> 2 * PI * 50_000 * t + 3.0 * sin(2 * PI * 1000 * t) to 60.0 }
        val sum = ByteArray(near.size) { ((near[it].toInt() and 0xff) + (other[it].toInt() and 0xff) - 127).coerceIn(0, 255).toByte() }
        val (_, pcm) = play(AudioDemod.Mode.NFM, sum)
        val rms = kotlin.math.sqrt(pcm.drop(3_200).sumOf { it.toDouble() * it } / (pcm.size - 3_200)) / 32767
        assertTrue("rms $rms", rms < 0.05)
    }

    @Test fun picksTheUsualModulation() {
        assertEquals(AudioDemod.Mode.WFM, AudioDemod.modeFor(98_100_000L))
        assertEquals(AudioDemod.Mode.AM, AudioDemod.modeFor(119_100_000L))
        assertEquals(AudioDemod.Mode.AM, AudioDemod.modeFor(27_185_000L))
        assertEquals(AudioDemod.Mode.NFM, AudioDemod.modeFor(155_475_000L))
        assertEquals(AudioDemod.Mode.NFM, AudioDemod.modeFor(462_600_000L))
    }
}
