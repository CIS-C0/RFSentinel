package com.rfsentinel.app.util

import android.app.Application
import android.media.AudioAttributes
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration

/**
 * The speech engine can drop an utterance without a word (Google's speech service updated or killed in
 * the background): no start, no done, no error. Spoken alerts must carry on, not wait for it forever
 * while the Test button (which clears the wait) still works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoiceRecoveryTest {

    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The engine Voice just created, once it reports ready. */
    private fun engineUp(): TextToSpeech {
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        return tts
    }

    @Before
    fun setUp() {
        ShadowTextToSpeech.reset()
        Voice.shutdown()
        idle()
    }

    @After
    fun tearDown() {
        Voice.shutdown()
        idle()
    }

    @Test
    fun anUtteranceTheEngineDroppedDoesNotSilenceTheAlertsAfterIt() {
        Voice.say(ctx, "Flock camera", VoiceQueue.STRONG, attrs)
        idle()
        val first = engineUp()
        idle(500)
        assertEquals(listOf("Flock camera"), shadowOf(first).spokenTextList)

        // The speech service goes away: what's sent to it is never started or finished.
        first.setOnUtteranceProgressListener(null)
        Voice.say(ctx, "Axon body camera", VoiceQueue.STRONG, attrs)
        idle(500)
        Voice.say(ctx, "Drone", VoiceQueue.STRONG, attrs)
        idle(500)
        assertEquals("sent, then nothing", listOf("Flock camera", "Axon body camera"), shadowOf(first).spokenTextList)

        // The engine is started afresh and the dropped alert said again, then the one waiting.
        idle(5_000)
        val second = engineUp()
        assertNotSame(first, second)
        idle(1_000)
        assertEquals(listOf("Axon body camera", "Drone"), shadowOf(second).spokenTextList)
    }

    @Test
    fun anUtteranceThatStartedButNeverFinishedIsLetGo() {
        Voice.say(ctx, "Body cam", VoiceQueue.STRONG, attrs)
        idle()
        val tts = engineUp()
        idle(500)
        // Started, then the done callback is lost.
        val listener = shadowOf(tts).utteranceProgressListener
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = listener.onStart(id)
            override fun onDone(id: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {}
        })
        Voice.say(ctx, "Police car", VoiceQueue.STRONG, attrs)
        idle(500)
        Voice.say(ctx, "Speed camera", VoiceQueue.STRONG, attrs)
        idle(500)
        assertEquals(listOf("Body cam", "Police car"), shadowOf(tts).spokenTextList)
        idle(8_000)
        assertEquals("the same engine carries on", listOf("Body cam", "Police car", "Speed camera"), shadowOf(tts).spokenTextList)
    }
}
