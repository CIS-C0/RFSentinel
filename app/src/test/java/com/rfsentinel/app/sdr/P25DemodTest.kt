package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class P25DemodTest {

    /** Sync + [payload] as dongle I/Q: FM on [carrierHz] from the centre, 8-bit, with a little noise. */
    private fun signal(payload: IntArray, carrierHz: Double, frames: Int): ByteArray {
        val dibits = IntArray(40) { (it * 7 + 3) % 4 } + (0 until frames).flatMap { (P25TestSignal.SYNC + payload).toList() } + IntArray(40) { it % 4 }
        val perSymbol = P25Demod.SAMPLE_RATE / P25Demod.SYMBOL_RATE
        val out = ByteArray(dibits.size * perSymbol * 2)
        val rnd = java.util.Random(7)
        var phase = 0.0; var k = 0
        for (d in dibits) repeat(perSymbol) {
            phase += 2 * PI * (carrierHz + P25TestSignal.hz(d)) / P25Demod.SAMPLE_RATE
            out[k++] = (127.5 + 50 * cos(phase) + rnd.nextGaussian() * 4).roundToInt().coerceIn(0, 255).toByte()
            out[k++] = (127.5 + 50 * sin(phase) + rnd.nextGaussian() * 4).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    @Test
    fun recoversDibitsAfterEachSync() {
        val rnd = java.util.Random(1)
        val payload = IntArray(P25Demod.FRAME_DIBITS) { rnd.nextInt(4) }
        val frames = ArrayList<IntArray>()
        // The channel 200 kHz off the centre, the dongle's crystal another 900 Hz off.
        val demod = P25Demod(200_000.0) { frames += it.copyOf() }
        val iq = signal(payload, 200_900.0, 3)
        // Fed in USB-sized pieces.
        var at = 0
        while (at < iq.size) { val len = minOf(16_384, iq.size - at); demod.feed(iq.copyOfRange(at, at + len), len / 2); at += len }
        assertEquals(3, demod.syncs)
        assertEquals(3, frames.size)
        for (f in frames) assertArrayEquals(payload, f)
        assertTrue("frequency loop followed the drift: ${demod.offsetHz}", demod.offsetHz > 200_200.0)
    }

    @Test
    fun syncPattern() {
        assertEquals(listOf(3, 3, 3, 3, 3, -3), P25Demod.SYNC.take(6))
        assertEquals(1, P25Demod.slice(1.1)); assertEquals(0, P25Demod.slice(0.3))
        assertEquals(2, P25Demod.slice(-0.3)); assertEquals(3, P25Demod.slice(-1.0))
    }
}
