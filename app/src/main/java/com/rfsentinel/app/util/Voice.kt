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
    /** An utterance that hasn't started by then never will (the engine dropped it). */
    private const val START_TIMEOUT_MS = 5_000L
    /** An alert said again after a failure, if it's still this fresh. */
    private const val RETRY_MAX_AGE_MS = 30_000L

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
    /** The latest settings an alert / a driving direction asked for: each is spoken with its own kind's, whoever called last. */
    private var alertAttrs: AudioAttributes? = null
    private var navAttrs: AudioAttributes? = null

    /** The audio session speech plays in, with a loudness boost on it ([Prefs.voiceBoostDb]). */
    private var sessionId = 0
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

    /** English voices on this phone's engine, best first (filled once the engine is up). */
    @Volatile var voices: List<android.speech.tts.Voice> = emptyList()
        private set

    /** Queues [text] with a [VoiceQueue] priority; it's spoken as soon as nothing more urgent is. */
    fun say(context: Context, text: String, priority: Int, attrs: AudioAttributes) = main.post {
        app = context.applicationContext
        nextAttrs = attrs; alertAttrs = attrs
        val now = System.currentTimeMillis()
        if (!queue.add(text, priority, now)) return@post
        val current = speaking
        if (current != null && queue.preempts(priority, current.priority)) {
            tts?.stop() // onDone/onStop isn't guaranteed after stop(): move on ourselves
            done()
        }
        pump()
    }

    /**
     * Queues a driving direction. [urgent] ones (the last prompt before a turn) cut into an alert
     * that is being read - except a "following you" alert, which a direction never interrupts.
     */
    fun sayNav(context: Context, text: String, urgent: Boolean, attrs: AudioAttributes) = main.post {
        app = context.applicationContext
        nextAttrs = attrs; navAttrs = attrs
        if (!queue.addNav(text, System.currentTimeMillis())) return@post
        val current = speaking
        if (urgent && current != null && !current.nav && current.priority < VoiceQueue.FOLLOWING) {
            tts?.stop()
            done()
        }
        pump()
    }

    /** Says [text] right away, replacing anything queued (the Settings "Test voice" button). */
    fun test(context: Context, text: String, attrs: AudioAttributes) = main.post {
        app = context.applicationContext
        queue.clear()
        tts?.stop()
        done()
        nextAttrs = attrs; alertAttrs = attrs
        // Bypasses the repeat filter: the button can be pressed again and again.
        pending = VoiceQueue.Item(text, VoiceQueue.FOLLOWING + 1, System.currentTimeMillis())
        pendingTries = 0
        pump()
    }

    /** Said before the queue: the Test button, or an alert said again after the engine failed it. */
    private var pending: VoiceQueue.Item? = null
    private var pendingTries = 0
    /** The utterance being said, how many times it already failed, and whether it went to the boosted session. */
    private var utterance: String? = null
    private var tries = 0
    private var started = false
    private var boostedNow = false
    /** The boosted session failed twice in a row on this phone: speak without it (until the app restarts). */
    private var boostFailed = false

    /**
     * The engine went quiet on what's being said: it never started it, or never said it was done (the
     * speech service was updated or killed in the background). Without this, [speaking] stays set and
     * every alert after it waits forever, while the Test button, which clears it, still works.
     */
    private val watchdog = Runnable {
        val item = speaking ?: return@Runnable
        if (started) { done(); pump() } else failed(item)
    }

    private fun pump() {
        if (speaking != null) return
        val ctx = app ?: return
        val engine = tts
        if (engine == null || !ready) { if (engine == null) start(ctx); return }
        val now = System.currentTimeMillis()
        val retry = pending?.takeIf { now - it.time <= RETRY_MAX_AGE_MS }
        val retryTries = pendingTries
        pending = null
        val item = retry ?: queue.next(now) ?: run { releaseFocus(); return }
        val attrs = pickAttrs(item.nav, alertAttrs, navAttrs, nextAttrs) ?: return
        tries = if (retry != null) retryTries else 0
        speaking = item
        speakingAttrs = attrs
        holdFocus(ctx, attrs)
        engine.setAudioAttributes(attrs)
        engine.setSpeechRate(Prefs.voiceRate(ctx))
        val session = if (boostFailed) null else boostedSession(ctx)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
            session?.let { putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, it) }
        }
        val id = "rf-${System.nanoTime()}"
        utterance = id
        started = false
        boostedNow = session != null
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, START_TIMEOUT_MS)
        // Refused: no longer connected to the engine (updated or killed in the background).
        if (engine.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) failed(item)
    }

    /** Nothing is being said any more. */
    private fun done() {
        main.removeCallbacks(watchdog)
        speaking = null
        utterance = null
    }

    /**
     * The engine didn't say [item]. Starts it afresh and says [item] again, up to twice; after two
     * failures in a row in the boosted session, speech goes without the boost (in case that's what
     * this phone can't play).
     */
    private fun failed(item: VoiceQueue.Item) {
        done()
        val n = tries + 1
        if (boostedNow && n >= 2) boostFailed = true
        if (n <= 2) { pending = item; pendingTries = n }
        val ctx = app ?: return
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        start(ctx)
    }

    /** Talking takes about this long, with room to spare. */
    private fun talkMs(text: String, ctx: Context) =
        4_000L + (text.length * 120L / Prefs.voiceRate(ctx).coerceAtLeast(0.3f)).toLong()

    /**
     * The audio session to speak in, with Android's loudness enhancer (a gain with a limiter, so it
     * gets louder without distorting) set to the chosen boost; null with no boost or no enhancer.
     * The boost is on our own sound before it reaches the car, so it works over Android Auto too.
     */
    private fun boostedSession(ctx: Context): Int? {
        val db = Prefs.voiceBoostDb(ctx)
        if (db <= 0) { enhancer?.let { runCatching { it.enabled = false } }; return null }
        return runCatching {
            if (sessionId == 0) sessionId = ctx.getSystemService(AudioManager::class.java)!!.generateAudioSessionId()
            val e = enhancer ?: android.media.audiofx.LoudnessEnhancer(sessionId).also { enhancer = it }
            e.setTargetGain(db * 100)
            e.enabled = true
            sessionId
        }.getOrElse { enhancer = null; null }
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
            override fun onStart(id: String?) { main.post { onStarted(id) } }
            override fun onDone(id: String?) = finished(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = errored(id)
            override fun onError(id: String?, errorCode: Int) = errored(id)
            override fun onStop(id: String?, interrupted: Boolean) {}
        })
        voices = englishVoices(engine)
        applyVoice(engine)
        ready = true
        pump()
    }

    private fun onStarted(id: String?) {
        if (id == null || id != utterance) return
        started = true
        // Now it only has to finish.
        main.removeCallbacks(watchdog)
        app?.let { main.postDelayed(watchdog, talkMs(speaking?.text.orEmpty(), it)) }
    }

    /** Late callbacks of an utterance given up on are ignored. */
    private fun finished(id: String?) {
        main.post {
            if (id == null || id != utterance) return@post
            done()
            // A short breath between phrases, then the next (or give the audio back).
            main.postDelayed({ pump() }, 250)
        }
    }

    private fun errored(id: String?) {
        main.post {
            if (id == null || id != utterance) return@post
            val item = speaking ?: return@post
            if (started) { done(); main.postDelayed({ pump() }, 250) } else failed(item)
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
        // Music dips under the voice, or pauses for it (Settings), and comes back afterwards.
        val gain = if (Prefs.voicePausesMusic(ctx)) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(gain)
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
        pending = null
        tts?.stop()
        done()
        releaseFocus()
    }

    fun shutdown() = main.post {
        queue.clear()
        pending = null
        done()
        releaseFocus()
        tts?.shutdown()
        tts = null
        ready = false
        enhancer?.let { runCatching { it.release() } }
        enhancer = null
        sessionId = 0
    }
}

/** The audio settings an item is spoken with: its own kind's ([nav] or an alert's), else the latest caller's. */
internal fun <T> pickAttrs(nav: Boolean, alert: T?, navigation: T?, latest: T?): T? = (if (nav) navigation else alert) ?: latest
