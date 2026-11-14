package com.rfsentinel.app.sdr

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On a phone or emulator: the model downloads and checks out, and Whisper hears whisper.cpp's sample speech through our JNI. */
class WhisperDeviceTest {
    @Test
    fun downloadsAndTranscribes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        assertTrue("native library", WhisperNative.available)
        if (!P25Whisper.ready(ctx)) {
            var err: String? = "timeout"
            val done = CountDownLatch(1)
            P25Whisper.download(ctx, {}) { err = it; done.countDown() }
            done.await(10, TimeUnit.MINUTES)
            assertNull(err)
        }
        assertTrue(P25Whisper.ready(ctx))
        // jfk.wav: 16 kHz mono 16-bit; every other sample gives 8 kHz, as saved calls are.
        val bytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        var at = 12
        while (String(bytes, at, 4) != "data") at += 8 + (java.nio.ByteBuffer.wrap(bytes, at + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        val pcm = java.nio.ByteBuffer.wrap(bytes, at + 8, bytes.size - at - 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val call = ShortArray(pcm.remaining() / 2) { pcm.get(it * 2) }
        val text = P25Whisper.transcribe(ctx, call)
        android.util.Log.i("WhisperDeviceTest", "heard: $text")
        assertTrue("heard: $text", text?.lowercase()?.contains("country") == true)
    }
}
