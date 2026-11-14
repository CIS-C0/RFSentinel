package com.rfsentinel.app.sdr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Settings › RTL-SDR radio › Transcribe saved voice: each saved call or transmission's speech goes to Android's on-device speech
 * recognizer (Android 13+), fed from memory: the microphone is never opened (Android only asks that the app may).
 * The text is kept next to the call's sound file ([textOf]) and nothing leaves the phone. One call at a time, the
 * rest queued; a recognizer that never answers is given up on. With the Whisper model downloaded ([P25Whisper], full
 * version) Whisper does it instead, on any Android version and without the microphone permission.
 */
object P25Transcribe {
    /** Settings › Transcribe saved voice, set by the scan service. */
    @Volatile var enabled = false

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    // Main thread only.
    private val queue = ArrayDeque<Pair<File, ShortArray>>()
    private var busy = false
    private var unavailableLogged = false

    private fun log(line: String) = com.rfsentinel.app.util.HwLogs.sdr.log("P25 transcribe: $line")

    /** What was said in the saved call [wav], or null when it wasn't transcribed (or no words were heard). */
    fun textOf(wav: File): String? = runCatching { sidecar(wav).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } }.getOrNull()

    /** The text file kept beside [wav] (deleted with it, [P25Record]). */
    fun sidecar(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".txt")

    /**
     * [wav] was saved from [pcm] at [rate] (8 kHz P25 speech, the sweep's analog audio faster): transcribe it when the
     * setting is on and this phone can.
     */
    fun enqueue(context: Context, wav: File, pcm: List<ShortArray>, rate: Int = ImbeDecoder.RATE) {
        if (!enabled || Build.VERSION.SDK_INT < 33 && !P25Whisper.ready(context)) return
        val flat = ShortArray(pcm.sumOf { it.size })
        var at = 0
        for (p in pcm) { p.copyInto(flat, at); at += p.size }
        val speech = to8k(flat, rate)
        val app = context.applicationContext
        main.post {
            if (queue.size >= MAX_QUEUED) { log("$MAX_QUEUED calls waiting, this one skipped"); return@post }
            queue.addLast(wav to speech)
            next(app)
        }
    }

    private fun next(context: Context) {
        if (busy) return
        val (wav, pcm) = queue.removeFirstOrNull() ?: return
        if (P25Whisper.ready(context)) { whisper(context, wav, pcm); return }
        if (Build.VERSION.SDK_INT < 33) { queue.clear(); return } // the model was deleted meanwhile
        recognize(context, wav, pcm)
    }

    /** Whisper on a thread of its own; the next call once it's done. */
    private fun whisper(context: Context, wav: File, pcm: ShortArray) {
        busy = true
        val secs = pcm.size.toDouble() / ImbeDecoder.RATE
        Thread({
            val start = System.currentTimeMillis()
            val text = runCatching { P25Whisper.transcribe(context, pcm) }
                .onFailure { log("Whisper failed (${it.javaClass.simpleName})") }.getOrNull()
            val took = (System.currentTimeMillis() - start) / 1000.0
            main.post {
                if (!text.isNullOrEmpty() && wav.exists()) runCatching { sidecar(wav).writeText(text) }
                    .onFailure { log("text not saved (${it.javaClass.simpleName})") }
                log(String.format(java.util.Locale.US, "%.1f s call: Whisper %s in %.1f s, %d words", secs,
                    if (text == null) "failed" else "done", took, if (text.isNullOrEmpty()) 0 else text.split(Regex("\\s+")).size))
                busy = false
                next(context)
            }
        }, "P25Whisper").start()
    }

    @RequiresApi(33)
    private fun recognize(context: Context, wav: File, pcm: ShortArray) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            if (!unavailableLogged) { unavailableLogged = true; log("this phone has no on-device speech recognition; calls not transcribed") }
            queue.clear(); return
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("the speech permission was taken back; calls not transcribed (Settings › Transcribe saved voice asks again)")
            queue.clear(); return
        }
        busy = true
        val secs = pcm.size.toDouble() / ImbeDecoder.RATE
        val pipe = ParcelFileDescriptor.createPipe()
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val parts = ArrayList<String>()
        var done = false
        lateinit var timeout: Runnable
        fun finish(why: String) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            runCatching { rec.destroy() }
            runCatching { pipe[0].close() } // the writer, if still blocked, fails and ends
            val text = parts.joinToString(" ").trim()
            if (text.isNotEmpty() && wav.exists()) runCatching { sidecar(wav).writeText(text) }
                .onFailure { log("text not saved (${it.javaClass.simpleName})") }
            log(String.format(java.util.Locale.US, "%.1f s call: %s, %d words", secs, why, if (text.isEmpty()) 0 else text.split(Regex("\\s+")).size))
            busy = false
            next(context)
        }
        timeout = Runnable { finish("no answer from the recognizer, given up") }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segment: Bundle) {
                segment.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            override fun onEndOfSegmentedSession() = finish("done")
            override fun onResults(results: Bundle) { onSegmentResults(results); finish("done") }
            override fun onError(error: Int) = finish(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no words recognised"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "the phone's language isn't downloaded for offline speech"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no speech permission"
                else -> "recognizer error $error"
            })
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE_OUT)
            // With the audio given, the session ends when it runs out, and each pause makes a segment.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        main.postDelayed(timeout, (secs * 2_000).toLong() + ANSWER_MS)
        val started = runCatching { rec.startListening(intent) }
        if (started.isFailure) {
            runCatching { pipe[1].close() }
            finish("couldn't start (${started.exceptionOrNull()?.javaClass?.simpleName})")
            return
        }
        Thread({
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(upsample(pcm)) } }
        }, "P25Transcribe").start()
    }

    /** 8 kHz to [RATE_OUT] (the rate recognizers take): every other sample halfway between its neighbours; then a short silence. */
    internal fun upsample(pcm: ShortArray): ByteArray {
        val out = java.nio.ByteBuffer.allocate((pcm.size * 2 + TAIL_SAMPLES) * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in pcm.indices) {
            out.putShort(pcm[i])
            out.putShort(((pcm[i] + (if (i + 1 < pcm.size) pcm[i + 1] else pcm[i]).toInt()) / 2).toShort())
        }
        return out.array() // the tail stays zero
    }

    /** [pcm] at [rate] to 8 kHz: each sample the mean of those it covers (a crude low-pass, enough for speech). */
    internal fun to8k(pcm: ShortArray, rate: Int): ShortArray {
        // shortcut: audio at 8 kHz or slower is kept as it is; nothing slower is saved today. Resample up if that changes.
        if (rate <= ImbeDecoder.RATE) return pcm
        val out = ShortArray((pcm.size.toLong() * ImbeDecoder.RATE / rate).toInt())
        for (i in out.indices) {
            val a = (i.toLong() * rate / ImbeDecoder.RATE).toInt()
            val b = ((i + 1L) * rate / ImbeDecoder.RATE).toInt().coerceAtMost(pcm.size)
            var sum = 0L
            for (j in a until b) sum += pcm[j]
            out[i] = (sum / maxOf(1, b - a)).toInt().toShort()
        }
        return out
    }

    private const val RATE_OUT = 16_000
    private const val TAIL_SAMPLES = RATE_OUT / 2
    private const val MAX_QUEUED = 20
    private const val ANSWER_MS = 30_000L
}
