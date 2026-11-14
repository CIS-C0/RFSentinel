package com.rfsentinel.app.sdr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class P25WhisperTest {
    @Test
    fun upsamplesTo16kFloats() {
        val out = P25Whisper.samples16k(shortArrayOf(0, 16384, -32768))
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.5f, -0.25f, -1f, -1f), out, 1e-6f)
    }
}
