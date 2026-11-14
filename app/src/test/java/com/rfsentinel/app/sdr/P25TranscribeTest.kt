package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class P25TranscribeTest {
    @Test fun upsampleDoublesTheRateAndInterpolates() {
        val b = ByteBuffer.wrap(P25Transcribe.upsample(shortArrayOf(0, 100, -100))).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ShortArray(b.remaining()).also { b.get(it) }
        assertEquals(6 + 8_000, out.size) // 2x, then half a second of silence at 16 kHz
        assertEquals(listOf<Short>(0, 50, 100, 0, -100, -100), out.take(6))
        assertEquals(0, out.drop(6).count { it != 0.toShort() })
    }

    @Test fun to8kAveragesDownToEightKilohertz() {
        // 32 kHz (the sweep's analog audio): every 4 samples become their mean.
        assertEquals(listOf<Short>(25, 200), P25Transcribe.to8k(shortArrayOf(0, 0, 50, 50, 100, 200, 300, 200, 7), 32_000).toList())
        // A rate that doesn't divide evenly still gives the right length.
        assertEquals(8_000, P25Transcribe.to8k(ShortArray(44_100) { 1000 }, 44_100).let { o -> assertEquals(0, o.count { it != 1000.toShort() }); o.size })
        // 8 kHz P25 speech goes through as it is.
        val p25 = shortArrayOf(1, 2, 3)
        assertEquals(p25, P25Transcribe.to8k(p25, 8_000))
    }
}
