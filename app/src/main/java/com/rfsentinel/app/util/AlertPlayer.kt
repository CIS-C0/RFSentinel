package com.rfsentinel.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.rfsentinel.app.car.CarState
import com.rfsentinel.app.detect.Tier
import java.util.Locale

/**
 * Plays match alerts directly (synthesized beeps, a vibration pattern
 * that encodes the confidence tier, optional spoken announcement) instead of
 * relying on the notification, so alerts still work when notifications are
 * denied or muted. On the phone, silent and vibrate ringer modes mute the
 * beeps; in the car they always play.
 *
 * Vibration: one pulse = weak, two = probable, three = strong, long-short-long
 * = something is following you.
 */
object AlertPlayer {

    /**
     * Phone beeps use the navigation-guidance usage too: it follows the media
     * volume (like Locate), which is usually up while driving, and dips music.
     */
    private val phoneToneAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
            duckOthers(context, if (willSpeak) 6_000L else 2_500L)
        }
        var beepMs = 0
        if (!muted && Prefs.soundEnabled(context) && (inCar || ringerAllowsSound(context))) {
            // Our own beeps (1 weak, 2 probable, 3 strong, long-short-long following) at
            // media volume, like Locate: the notification chime was too soft and too quiet.
            val beeps = when (tier) { Tier.STRONG -> 3; Tier.MEDIUM -> 2; else -> 1 }
            beepMs = Beeper.play(if (inCar) carToneAttributes else phoneToneAttributes, Beeper.pattern(beeps, following))
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
        if (willSpeak) {
            val app = context.applicationContext
            val attrs = if (inCar) carSpeechAttributes else phoneSpeechAttributes
            // Speak after the beeps, not over them.
            if (beepMs > 0) main.postDelayed({ speak(app, spoken!!, attrs) }, beepMs + 150L)
            else speak(app, spoken!!, attrs)
        }
    }

    /**
     * Silent and vibrate ringer modes, and Do Not Disturb, mute the beeps on the
     * phone (vibration still runs) - like the notification sound they replace.
     */
    private fun ringerAllowsSound(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.ringerMode
        if (mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE) return false
        val filter = context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
        return filter == null || filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL ||
            filter == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
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

    /** Announcements raised while the speech engine is still starting. */
    private val pending = mutableListOf<Pair<String, AudioAttributes>>()

    @Synchronized
    private fun speak(context: Context, text: String, attrs: AudioAttributes) {
        val engine = tts
        if (engine != null && ttsReady) {
            engine.setAudioAttributes(attrs)
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "rf-${System.nanoTime()}")
            return
        }
        // Still starting: queue it (bounded) so it's spoken once ready.
        if (pending.size < 5) pending += text to attrs
        if (engine == null) {
            tts = TextToSpeech(context) { status -> onTtsInit(status) }
        }
    }

    @Synchronized
    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            // Engine missing or still updating: drop it so the next alert tries again,
            // instead of staying silent until the app restarts.
            runCatching { engine.shutdown() }
            tts = null
            ttsReady = false
            pending.clear()
            return
        }
        ttsReady = true
        engine.language = Locale.getDefault()
        pending.forEach { (text, attrs) ->
            engine.setAudioAttributes(attrs)
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "rf-${System.nanoTime()}")
        }
        pending.clear()
    }

    @Synchronized
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        pending.clear()
    }
}
