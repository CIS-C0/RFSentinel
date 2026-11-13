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
}
