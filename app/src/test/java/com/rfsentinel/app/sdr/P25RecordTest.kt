package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class P25RecordTest {

    @Test
    fun theVoiceChannelIsMixedDownTo48kAsAWav() {
        // 0.1 s of a carrier 1 kHz above the channel, which sits 250 kHz above the tuned centre.
        val rate = P25Demod.SAMPLE_RATE
        val count = rate / 10
        val iq = ByteArray(2 * count)
        val tone = 251_000.0
        for (i in 0 until count) {
            val ph = 2 * PI * tone * i / rate
            iq[2 * i] = (127.5 + 60 * cos(ph)).roundToInt().toByte()
            iq[2 * i + 1] = (127.5 + 60 * sin(ph)).roundToInt().toByte()
        }
        val rec = P25Record.Baseband(250_000L)
        rec.feed(iq, count)
        assertEquals(P25Record.RATE / 10, rec.samples)

        val wav = ByteBuffer.wrap(rec.wav()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(rec.wav(), 0, 4))
        assertEquals(P25Record.RATE, wav.getInt(24))
        assertEquals(rec.samples * 4, wav.getInt(40))
        // What's left after mixing turns 1 kHz: the phase steps 2π · 1000 / 48000 each sample.
        var step = 0.0
        for (k in 100 until 200) {
            val a = atan2(wav.getShort(44 + 4 * k + 2).toDouble(), wav.getShort(44 + 4 * k).toDouble())
            val b = atan2(wav.getShort(44 + 4 * (k + 1) + 2).toDouble(), wav.getShort(44 + 4 * (k + 1)).toDouble())
            step += ((b - a + 3 * PI) % (2 * PI)) - PI
        }
        assertEquals(2 * PI * 1000 / P25Record.RATE, step / 100, 0.01)
        assertTrue(wav.getShort(44 + 400).toInt() != 0)
    }
}
