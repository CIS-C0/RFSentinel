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

    @Test
    fun theSweepsRawCaptureSaysItsRealRateInTheWav() {
        val b = P25Record.Baseband(250_000, 2_048_000)
        assertEquals(48_761, b.rate) // 2.048 MS/s isn't a multiple of 48 kHz
        b.feed(ByteArray(2 * 42 * 10) { 127 }, 42 * 10)
        assertEquals(10, b.samples)
        val h = ByteBuffer.wrap(b.wav()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48_761, h.getInt(24))
        assertEquals(44 + 10 * 4, b.wav().size)
    }

    @Test
    fun aPlayedCallIsSavedAsAn8kMonoWavAndReadBackFromItsName() {
        val wav = P25Record.speechWav(listOf(ShortArray(160) { 1000 }, ShortArray(160) { -1000 }))
        val h = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 2 * 320, wav.size)
        assertEquals(1, h.getShort(22).toInt())
        assertEquals(ImbeDecoder.RATE, h.getInt(24))
        assertEquals(640, h.getInt(40))
        assertEquals(-1000, h.getShort(44 + 2 * 160).toInt())

        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val f = java.io.File(dir, "20261112_114720_tg54221.wav").apply { writeBytes(wav) }
            val c = P25Record.saved(f)!!
            assertEquals(54221, c.tg)
            assertEquals(0.04, c.seconds, 1e-9)
            val private = P25Record.saved(java.io.File(dir, "20261112_114720_to1500020.wav").apply { writeBytes(wav) })!!
            assertEquals(1500020L, private.target)
            assertEquals(null, private.tg)
            assertEquals(null, P25Record.saved(java.io.File(dir, "other.wav")))
            // A walkie-talkie the sweep played: its frequency, and its length at the analog audio's rate.
            val radio = P25Record.saved(java.io.File(dir, "20261112_114720_462562500hz.wav")
                .apply { writeBytes(P25Record.speechWav(listOf(ShortArray(32_000)), 32_000)) })!!
            assertEquals(462_562_500L, radio.hz)
            assertEquals(1.0, radio.seconds, 1e-9)
            assertEquals(null, radio.who)
            // Who keyed up: an MDC-1200 unit ID, a P25 radio.
            val mdc = P25Record.saved(java.io.File(dir, "20261112_114720_154430000hz_u1A2B.wav").apply { writeBytes(wav) })!!
            assertEquals(154_430_000L, mdc.hz); assertEquals("unit 1A2B", mdc.who)
            assertEquals("radio 1500020", P25Record.saved(java.io.File(dir, "20261112_114720_tg54221_r1500020.wav").apply { writeBytes(wav) })!!.who)
        } finally { dir.deleteRecursively() }
    }
}
