package com.rfsentinel.app.sdr

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On a phone or emulator: the model downloads and checks out, and Whisper hears whisper.cpp's sample speech through our JNI. */
class WhisperDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext

    private fun model() {
        assertTrue("native library", WhisperNative.available)
        synchronized(WhisperDeviceTest::class.java) {
            if (!P25Whisper.ready(ctx)) {
                var err: String? = "timeout"
                val done = CountDownLatch(1)
                P25Whisper.download(ctx, {}) { err = it; done.countDown() }
                done.await(10, TimeUnit.MINUTES)
                assertNull(err)
            }
        }
        assertTrue(P25Whisper.ready(ctx))
    }

    /** jfk.wav: 16 kHz mono 16-bit. */
    private fun jfk16k(): ShortArray {
        val bytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        var at = 12
        while (String(bytes, at, 4) != "data") at += 8 + (java.nio.ByteBuffer.wrap(bytes, at + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        val pcm = java.nio.ByteBuffer.wrap(bytes, at + 8, bytes.size - at - 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(pcm.remaining()).also { pcm.get(it) }
    }

    @Test
    fun downloadsAndTranscribes() {
        model()
        // Every other sample gives 8 kHz, as saved P25 calls are.
        val pcm = jfk16k()
        val call = ShortArray(pcm.size / 2) { pcm[it * 2] }
        val text = P25Whisper.transcribe(ctx, call)
        android.util.Log.i("WhisperDeviceTest", "heard: $text")
        assertTrue("heard: $text", text?.lowercase()?.contains("country") == true)
    }

    /** A sweep transmission (analog audio at 32 kHz) through the queue: resampled, transcribed, its text kept beside its file. */
    @Test
    fun transcribesASweepTransmission() {
        model()
        val pcm = jfk16k()
        val tx = ShortArray(pcm.size * 2) { pcm[it / 2] }
        val wav = java.io.File(ctx.cacheDir, "test_tx.wav").apply { writeBytes(P25Record.speechWav(listOf(tx), 32_000)) }
        P25Transcribe.sidecar(wav).delete()
        P25Transcribe.enabled = true
        try {
            P25Transcribe.enqueue(ctx, wav, listOf(tx), 32_000)
            val until = System.currentTimeMillis() + 5 * 60_000
            while (P25Transcribe.textOf(wav) == null && System.currentTimeMillis() < until) Thread.sleep(1_000)
            val text = P25Transcribe.textOf(wav)
            android.util.Log.i("WhisperDeviceTest", "transmission heard: $text")
            assertTrue("heard: $text", text?.lowercase()?.contains("country") == true)
        } finally {
            P25Transcribe.enabled = false
            P25Transcribe.sidecar(wav).delete(); wav.delete()
        }
    }
}
