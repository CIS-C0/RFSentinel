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
import com.rfsentinel.app.car.CarState
import com.rfsentinel.app.detect.Tier

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

    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null

    fun play(context: Context, tier: Tier, spoken: String?, following: Boolean = false) {
        val inCar = CarState.connected
        val muted = Prefs.alertsSilenced(context)
        val willSpeak = !muted && spoken != null && (Prefs.voiceEnabled(context) || (inCar && Prefs.carVoice(context)))
        var beepMs = 0
        if (!muted && Prefs.soundEnabled(context) && (inCar || ringerAllowsSound(context))) {
            // Speech holds its own audio focus for exactly as long as it talks; this covers the beeps.
            val detector = Prefs.detectorSound(context)
            duckOthers(context, if (detector) 4_500L else 2_500L)
            // Our own beeps (1 weak, 2 probable, 3 strong, long-short-long following) at
            // media volume, like Locate: the notification chime was too soft and too quiet.
            val beeps = when (tier) { Tier.STRONG -> 3; Tier.MEDIUM -> 2; else -> 1 }
            val attrs = if (inCar) carToneAttributes else phoneToneAttributes
            beepMs = if (detector) {
                val chosen = Prefs.detectorEffect(context)
                val clip = SoundClips.Clip.effect(when {
                    chosen != 0 -> chosen
                    following -> 4
                    beeps == 3 -> 1
                    beeps == 2 -> 2
                    else -> 3
                })
                SoundClips.play(context, attrs, clip).takeIf { it > 0 }
                    ?: Beeper.playTones(attrs, Beeper.detectorAlert(if (following) 3 else beeps))
            } else Beeper.play(attrs, Beeper.pattern(beeps, following))
            alertSoundUntil = System.currentTimeMillis() + beepMs
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
            val priority = when {
                following -> VoiceQueue.FOLLOWING
                tier == Tier.STRONG -> VoiceQueue.STRONG
                tier == Tier.MEDIUM -> VoiceQueue.PROBABLE
                else -> VoiceQueue.WEAK
            }
            // Speak after the beeps, not over them.
            if (beepMs > 0) main.postDelayed({ Voice.say(app, spoken!!, priority, attrs) }, beepMs + 150L)
            else Voice.say(app, spoken!!, priority, attrs)
        }
    }

    /**
     * One short radar-detector tick (no vibration, no voice). Same rules as the alert
     * beeps: off when alerts are muted or the sound is off, and on the phone also in
     * silent / vibrate / Do Not Disturb.
     */
    fun tick(context: Context) {
        val inCar = CarState.connected
        if (Prefs.alertsSilenced(context) || !Prefs.soundEnabled(context) || !(inCar || ringerAllowsSound(context))) return
        // Never over an alert sound that is still playing.
        if (System.currentTimeMillis() < alertSoundUntil) return
        val attrs = if (inCar) carToneAttributes else phoneToneAttributes
        if (Prefs.detectorSound(context)) detectorTick(context, attrs) else Beeper.play(attrs, listOf(70 to 0))
    }

    /** The recorded detector pulse, or the synthesized chirp until the clips are loaded. */
    private fun detectorTick(context: Context, attrs: AudioAttributes) {
        val tick = SoundClips.Clip.tick(Prefs.detectorEffect(context).takeIf { it != 0 } ?: 1)
        if (SoundClips.play(context, attrs, tick) == 0) Beeper.playTones(attrs, Beeper.detectorTick)
    }

    /** Settings preview of a detector effect (0 = the strong one), whatever the alert settings. */
    fun previewEffect(context: Context, effect: Int) {
        main.removeCallbacks(retryPreview)
        val clip = SoundClips.Clip.effect(effect.takeIf { it != 0 } ?: 1)
        if (SoundClips.playPreview(context, phoneToneAttributes, clip)) return
        // First use: the clips are still loading.
        retryPreview = Runnable { SoundClips.playPreview(context, phoneToneAttributes, clip) }
        main.postDelayed(retryPreview, 250L)
    }

    private var retryPreview = Runnable { }

    /** Stops a Settings preview (an effect or the intro test). */
    fun stopPreview(stopIntro: Boolean) {
        main.removeCallbacks(retryPreview)
        SoundClips.stopPreview()
        if (stopIntro) SoundClips.stopIntro()
    }

    /** The Settings "Test intro" button: plays the intro, or stops it if playing. True if it started. */
    fun testIntro(context: Context, onDone: () -> Unit): Boolean {
        stopPreview(false)
        if (SoundClips.introPlaying()) { SoundClips.stopIntro(); return false }
        SoundClips.playIntro(context, phoneToneAttributes) { main.post(onDone) }
        return true
    }

    /** Until when an alert sound plays (radar ticks wait for it). */
    @Volatile private var alertSoundUntil = 0L

    /** Loads the recorded sounds so the first alert can use them. */
    fun preload(context: Context) = SoundClips.preload(context, phoneToneAttributes)

    /**
     * One Locate tick (the device screen's proximity beeps) in the radar-detector sound style.
     * Started by the user, so it plays whatever the alert settings; false when the style is off.
     */
    fun locateTick(context: Context): Boolean {
        if (!Prefs.detectorSound(context)) return false
        detectorTick(context, phoneToneAttributes)
        return true
    }

    /**
     * When a scan starts: the optional intro sound, else the radar-detector power-on sweep
     * (in that sound style). Same rules as the alert sound.
     */
    fun startup(context: Context) {
        val inCar = CarState.connected
        val intro = Prefs.scanIntro(context)
        // The power-on sweep only with the detector sound style and its start beep on (off by default).
        if (!intro && !(Prefs.detectorSound(context) && Prefs.startupSweep(context))) return
        if (Prefs.alertsSilenced(context) || !Prefs.soundEnabled(context) || !(inCar || ringerAllowsSound(context))) return
        val attrs = if (inCar) carToneAttributes else phoneToneAttributes
        if (intro) SoundClips.playIntro(context, attrs) else Beeper.playTones(attrs, Beeper.detectorStartup)
    }

    fun stopIntro() = SoundClips.stopIntro()

    /** Radar-detector sound style: "GPS connected" once the first good fix arrives. */
    fun gpsConnected(context: Context) {
        if (Prefs.detectorSound(context)) announce(context, "GPS connected")
    }

    /** A spoken navigation instruction (no beep, no vibration); silent when alerts are muted. */
    fun announce(context: Context, text: String) {
        if (Prefs.alertsSilenced(context)) return
        Voice.say(context.applicationContext, text, VoiceQueue.STRONG, if (CarState.connected) carSpeechAttributes else phoneSpeechAttributes)
    }

    /**
     * A spoken call-out only, no beep or vibration (Waze reports getting closer). It follows the voice
     * settings: spoken when voice alerts are on or Android Auto is connected, and never when muted or snoozed.
     */
    fun callout(context: Context, text: String) {
        val inCar = CarState.connected
        if (Prefs.alertsSilenced(context) || !(Prefs.voiceEnabled(context) || (inCar && Prefs.carVoice(context)))) return
        Voice.say(context.applicationContext, text, VoiceQueue.STRONG, if (inCar) carSpeechAttributes else phoneSpeechAttributes)
    }

    /** The Settings "Test voice" button: speaks right away with the chosen voice and speed. */
    fun testVoice(context: Context, short: Boolean = Prefs.shortVoice(context)) {
        Voice.test(context.applicationContext, if (short) "Body cam" else "RF Sentinel voice alerts. Axon body camera nearby.",
            if (CarState.connected) carSpeechAttributes else phoneSpeechAttributes)
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

    fun shutdown() = Voice.shutdown()
}
