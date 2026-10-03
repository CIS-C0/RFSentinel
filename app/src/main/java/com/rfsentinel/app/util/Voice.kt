package com.rfsentinel.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken alerts on the phone's own speech engine (Google's when installed - the
 * one Maps uses), with an English voice since every phrase is English: a French
 * or Spanish voice reading "Axon body camera nearby" is hard to understand.
 *
 * One phrase at a time from a [VoiceQueue] (most urgent first, no stale or
 * repeated announcements, pile-ups merged); other audio dips only while it
 * speaks. Speed and voice are chosen in Settings.
 */
object Voice {

    private const val GOOGLE_TTS = "com.google.android.tts"

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var app: Context? = null
    private val queue = VoiceQueue()
    private var speaking: VoiceQueue.Item? = null
    private var speakingAttrs: AudioAttributes? = null
    private var focus: AudioFocusRequest? = null
    /** Attributes for queued items (the latest caller's: car or phone). */
    private var nextAttrs: AudioAttributes? = null

    /** English voices on this phone's engine, best first (filled once the engine is up). */
    @Volatile var voices: List<android.speech.tts.Voice> = emptyList()
        private set

    /** Queues [text] with a [VoiceQueue] priority; it's spoken as soon as nothing more urgent is. */
    fun say(context: Context, text: String, priority: Int, attrs: AudioAttributes) = main.post {
        app = context.applicationContext
        nextAttrs = attrs
        val now = System.currentTimeMillis()
        if (!queue.add(text, priority, now)) return@post
        val current = speaking
        if (current != null && queue.preempts(priority, current.priority)) {
            tts?.stop() // onDone/onStop isn't guaranteed after stop(): move on ourselves
            speaking = null
        }
        pump()
    }

    /** Says [text] right away, replacing anything queued (the Settings "Test voice" button). */
    fun test(context: Context, text: String, attrs: AudioAttributes) = main.post {
        app = context.applicationContext
        queue.clear()
        tts?.stop()
        speaking = null
        nextAttrs = attrs
        testText = text // bypasses the repeat filter: the button can be pressed again and again
        pump()
    }

    private var testText: String? = null

    private fun pump() {
        if (speaking != null) return
        val ctx = app ?: return
        val engine = tts
        if (engine == null || !ready) { if (engine == null) start(ctx); return }
        val now = System.currentTimeMillis()
        val item = testText?.let { VoiceQueue.Item(it, VoiceQueue.FOLLOWING + 1, now) }?.also { testText = null }
            ?: queue.next(now) ?: run { releaseFocus(); return }
        val attrs = nextAttrs ?: return
        speaking = item
        speakingAttrs = attrs
        holdFocus(ctx, attrs)
        engine.setAudioAttributes(attrs)
        engine.setSpeechRate(Prefs.voiceRate(ctx))
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (engine.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, "rf-${System.nanoTime()}") != TextToSpeech.SUCCESS) {
            speaking = null
            releaseFocus()
        }
    }

    private fun start(ctx: Context) {
        ready = false
        val google = runCatching { ctx.packageManager.getPackageInfo(GOOGLE_TTS, 0) }.isSuccess
        val listener = TextToSpeech.OnInitListener { status -> main.post { onInit(status) } }
        tts = if (google) TextToSpeech(ctx, listener, GOOGLE_TTS) else TextToSpeech(ctx, listener)
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            // Engine missing or updating: try again with the next alert instead of staying silent.
            runCatching { engine.shutdown() }
            tts = null
            queue.clear()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished()
            override fun onError(id: String?, errorCode: Int) = finished()
            override fun onStop(id: String?, interrupted: Boolean) {}
        })
        voices = englishVoices(engine)
        applyVoice(engine)
        ready = true
        pump()
    }

    private fun finished() {
        main.post {
            speaking = null
            // A short breath between phrases, then the next (or give the audio back).
            main.postDelayed({ pump() }, 250)
        }
    }

    /** English voices, best first: installed (offline) before network, then by quality, US/GB first. */
    private fun englishVoices(engine: TextToSpeech): List<android.speech.tts.Voice> =
        runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(
                compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { if (it.locale.country == "US") 0 else if (it.locale.country == "GB") 1 else 2 }
                    .thenBy { it.name }
            )

    private fun applyVoice(engine: TextToSpeech) {
        val ctx = app ?: return
        val chosen = Prefs.voiceName(ctx)?.let { name -> voices.firstOrNull { it.name == name } }
        val voice = chosen ?: voices.firstOrNull()
        if (voice != null) engine.voice = voice
        else engine.language = Locale.US
    }

    /** After the voice was changed in Settings. */
    fun voiceChanged(context: Context) = main.post {
        app = context.applicationContext
        tts?.let { if (ready) applyVoice(it) }
    }

    /** Starts the engine early (Settings opens the voice list). */
    fun warmUp(context: Context, onReady: () -> Unit = {}) = main.post {
        app = context.applicationContext
        if (tts == null) start(context.applicationContext)
        fun waitReady(tries: Int) {
            if (ready) onReady() else if (tries > 0) main.postDelayed({ waitReady(tries - 1) }, 200)
        }
        waitReady(50)
    }

    private fun holdFocus(ctx: Context, attrs: AudioAttributes) {
        if (focus != null) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        val req = focus ?: return
        focus = null
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
    }

    /** Stops what's being said and drops what's queued (alerts muted); the engine stays ready. */
    fun silence() = main.post {
        queue.clear()
        testText = null
        tts?.stop()
        speaking = null
        releaseFocus()
    }

    fun shutdown() = main.post {
        queue.clear()
        speaking = null
        releaseFocus()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
