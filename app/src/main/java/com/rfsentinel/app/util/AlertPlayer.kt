package com.rfsentinel.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.rfsentinel.app.car.CarState
import com.rfsentinel.app.detect.Tier
import java.util.Locale

/**
 * Plays match alerts directly (default notification sound, a vibration pattern
 * that encodes the confidence tier, optional spoken announcement) instead of
 * relying on the notification, so alerts still work when notifications are
 * denied or muted. Honors the ringer mode and Do Not Disturb like any
 * notification sound.
 *
 * Vibration: one pulse = weak, two = probable, three = strong, long-short-long
 * = something is following you.
 */
object AlertPlayer {

    private val phoneAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * On Android Auto, alerts use the navigation-guidance usage: the car plays
     * it through its speakers and ducks the music, exactly like a turn prompt.
     * (Plain notification sounds can be quiet or suppressed on car audio.)
     */
    private val carToneAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val carSpeechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val phoneSpeechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null

    fun play(context: Context, tier: Tier, spoken: String?, following: Boolean = false) {
        val inCar = CarState.connected
        val muted = Prefs.alertsMuted(context)
        val willSpeak = !muted && spoken != null && (Prefs.voiceEnabled(context) || (inCar && Prefs.carVoice(context)))
        if (!muted && (Prefs.soundEnabled(context) || willSpeak)) {
            if (inCar) duckOthers(context, if (willSpeak) 6_000L else 2_500L)
        }
        if (!muted && Prefs.soundEnabled(context)) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(context, uri)
                    ?.apply { audioAttributes = if (inCar) carToneAttributes else phoneAttributes }
                    ?.play()
            } catch (e: Exception) {
                // No default sound configured / audio unavailable - vibration still runs.
            }
        }
        if (Prefs.vibrateEnabled(context)) {
            val pattern = when {
                following -> longArrayOf(0, 600, 150, 150, 150, 600)
                tier == Tier.STRONG -> longArrayOf(0, 220, 120, 220, 120, 220)
                tier == Tier.MEDIUM -> longArrayOf(0, 250, 150, 250)
                else -> longArrayOf(0, 300)
            }
            vibrate(context, pattern)
        }
        if (willSpeak) speak(context.applicationContext, spoken!!, if (inCar) carSpeechAttributes else phoneSpeechAttributes)
    }

    /** Short "may duck" focus so music dips under the alert, then comes back. */
    @Synchronized
    private fun duckOthers(context: Context, forMs: Long) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(carSpeechAttributes)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
        main.postDelayed({
            synchronized(this) {
                if (focusRequest === req) {
                    am.abandonAudioFocusRequest(req)
                    focusRequest = null
                }
            }
        }, forMs)
    }

    fun vibrate(context: Context, pattern: LongArray) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() != true) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    @Synchronized
    private fun speak(context: Context, text: String, attrs: AudioAttributes) {
        val engine = tts
        if (engine != null && ttsReady) {
            engine.setAudioAttributes(attrs)
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "rf-${System.nanoTime()}")
            return
        }
        if (engine == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    tts?.setAudioAttributes(attrs)
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "rf-first")
                }
            }
        }
    }

    @Synchronized
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
