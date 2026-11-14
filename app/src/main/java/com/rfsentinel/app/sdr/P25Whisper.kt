package com.rfsentinel.app.sdr

import android.content.Context
import java.io.File

/**
 * Settings › Whisper model: the offline Whisper model ([WhisperNative], full version) that turns saved P25 calls into
 * text on any Android version, without the microphone permission. Downloaded only when asked, checked against its
 * SHA-256, kept in the app's own files. Nothing about the user goes with the download.
 */
object P25Whisper {
    private const val NAME = "ggml-tiny.en.bin"
    // Pinned to one revision of the whisper.cpp model repository, and checked against its hash.
    private const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.en.bin"
    private const val SHA256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f"
    const val SIZE = 77_704_715L

    @Volatile private var cancelled = false

    private fun log(line: String) = com.rfsentinel.app.util.HwLogs.sdr.log("P25 transcribe: $line")

    fun modelFile(context: Context) = File(File(context.filesDir, "whisper"), NAME)

    /** Whisper can transcribe on this phone now (library loaded, model downloaded whole). */
    fun ready(context: Context) = WhisperNative.available && modelFile(context).length() == SIZE

    /**
     * Downloads the model on a thread of its own: [progress] in percent, then [done] with null when ready or why not.
     * Both are called on that thread.
     */
    fun download(context: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
        cancelled = false
        val app = context.applicationContext
        Thread({ done(runCatching { fetch(app, progress) }.fold({ it }, { "${it.javaClass.simpleName}: ${it.message}" })) }, "P25Whisper").start()
    }

    fun cancel() { cancelled = true }

    fun delete(context: Context) { modelFile(context).delete(); log("Whisper model deleted") }

    private fun fetch(context: Context, progress: (Int) -> Unit): String? {
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "$NAME.part")
        log("Whisper model download started")
        val conn = java.net.URL(URL).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) return "the server answered ${conn.responseCode}".also { log("Whisper model download failed: $it") }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var got = 0L; var shown = -1
            conn.inputStream.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) return "cancelled"
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); digest.update(buf, 0, n); got += n
                    val pct = (got * 100 / SIZE).toInt().coerceAtMost(100)
                    if (pct != shown) { shown = pct; progress(pct) }
                }
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != SIZE || hash != SHA256) return "the file didn't match (${got} bytes)".also { log("Whisper model download failed: $it") }
            dest.delete()
            if (!tmp.renameTo(dest)) return "couldn't save it".also { log("Whisper model download failed: $it") }
            log("Whisper model ready")
            return null
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    /** What was said in [pcm] (8 kHz, as saved calls are), or null when Whisper failed; blocking, never on the main thread. */
    fun transcribe(context: Context, pcm: ShortArray): String? {
        val handle = WhisperNative.load(modelFile(context).path)
        if (handle == 0L) { log("the Whisper model couldn't be read - delete it and download it again in Settings"); return null }
        // shortcut: the model is loaded for each call (well under a second for tiny); keep it loaded if calls queue up a lot.
        try {
            return WhisperNative.transcribe(handle, samples16k(pcm), Runtime.getRuntime().availableProcessors().coerceIn(1, 4))?.trim()
        } finally { WhisperNative.free(handle) }
    }

    /** 8 kHz 16-bit to Whisper's 16 kHz float: every other sample halfway between its neighbours. */
    internal fun samples16k(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size * 2)
        for (i in pcm.indices) {
            val a = pcm[i] / 32768f
            val b = (if (i + 1 < pcm.size) pcm[i + 1] else pcm[i]) / 32768f
            out[2 * i] = a; out[2 * i + 1] = (a + b) / 2
        }
        return out
    }
}
