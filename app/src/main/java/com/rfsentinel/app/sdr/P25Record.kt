package com.rfsentinel.app.sdr

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * P25 voice, first step: saves the radio signal of clear (not encrypted) calls, so the voice decoder can be built and
 * tested on real calls. The voice channel [Baseband.offsetHz] from the tuned centre is mixed down and cut to 48 kS/s the
 * way [P25Demod] does it, and saved as a two-channel (I, Q) 16-bit WAV: the "baseband recording" SDR tools open.
 * Nothing is demodulated or played. Pure except [save] and [files].
 */
object P25Record {

    const val RATE = P25Demod.CHANNEL_RATE
    /** How long each call is recorded: the control channel is away meanwhile. */
    const val SECONDS = 6
    /** Recordings kept; the oldest go first. */
    const val KEEP = 20
    private const val DIR = "p25rec"
    /** 8-bit samples summed [P25Demod.DECIM] at a time reach ±5100, and ±7200 once mixed: this fills 16 bits without clipping. */
    private const val SCALE = 4.5

    /** Mixes [offsetHz] down to 0 and integrates-and-dumps to [RATE], collecting 16-bit I/Q pairs, or handing each to [onSample]. */
    class Baseband(val offsetHz: Long, sampleRate: Int = P25Demod.SAMPLE_RATE, private val onSample: ((Double, Double) -> Unit)? = null) {
        private val decim = sampleRate / RATE
        private val stepR: Double; private val stepI: Double
        init { val w = -2 * PI * offsetHz / sampleRate; stepR = cos(w); stepI = sin(w) }
        private var pr = 1.0; private var pi = 0.0
        private var accR = 0.0; private var accI = 0.0; private var accN = 0
        private val out = ByteArrayOutputStream()
        /** Output samples so far. */
        var samples = 0; private set

        /** Unsigned 8-bit I/Q as the dongle delivers it: [count] complex samples from [buf]. */
        fun feed(buf: ByteArray, count: Int) {
            var k = 0
            for (s in 0 until count) {
                val i = (buf[k++].toInt() and 0xff) - 127.5
                val q = (buf[k++].toInt() and 0xff) - 127.5
                accR += i * pr - q * pi
                accI += i * pi + q * pr
                val nr = pr * stepR - pi * stepI
                pi = pr * stepI + pi * stepR; pr = nr
                if (++accN == decim) {
                    if (onSample != null) onSample.invoke(accR, accI) else { short(accR); short(accI) }
                    samples++
                    accN = 0; accR = 0.0; accI = 0.0
                    val m = 1.5 - 0.5 * (pr * pr + pi * pi); pr *= m; pi *= m // keeps the phasor on the unit circle
                }
            }
        }

        private fun short(v: Double) {
            val s = (v * SCALE).roundToInt().coerceIn(-32768, 32767)
            out.write(s and 0xff); out.write(s shr 8 and 0xff)
        }

        /** The recording as a WAV file. */
        fun wav(): ByteArray = out.toByteArray().let { header(it.size) + it }
    }

    /** A 44-byte PCM WAV header, 16 bits: by default 2 channels at [RATE]. */
    internal fun header(dataBytes: Int, channels: Int = 2, rate: Int = RATE): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    /** Saves a call's recording ("p25_20261102_142233_tg1234.wav") and drops the oldest past [KEEP]. */
    fun save(context: Context, call: P25History.Call, wav: ByteArray): File = keep(context, DIR, KEEP, "p25_${stamp(call.time)}_${who(call)}.wav", wav)

    /** The kept recordings, newest first. */
    fun files(context: Context): List<File> = list(context, DIR)

    /**
     * Played calls' sound (Settings › RTL-SDR radio › Save played calls), to listen to on the P25 talkgroups screen: P25 calls
     * ("20261112_114720_tg54221.wav") and transmissions the radio sweep played ("20261112_114720_462562500hz.wav").
     */
    private const val CALLS_DIR = "p25calls"
    const val CALLS_KEEP = 200

    /** A played P25 call's speech ([P25Follow]'s 8 kHz pieces) saved as a mono WAV; the oldest past [CALLS_KEEP] go. */
    fun saveSpeech(context: Context, call: P25History.Call, pcm: List<ShortArray>, talker: Int? = null): File =
        keep(context, CALLS_DIR, CALLS_KEEP, "${stamp(call.time)}_${who(call)}${talker?.let { "_r$it" } ?: ""}.wav", speechWav(pcm))

    /**
     * A transmission the radio sweep played (a walkie-talkie...), heard at [hz]: its sound at [rate]. [unit]: who sent it,
     * "u1A2B" an MDC-1200 unit ID, "r123456" a P25 radio.
     */
    fun saveTransmission(context: Context, time: Long, hz: Long, pcm: List<ShortArray>, rate: Int, unit: String? = null): File =
        keep(context, CALLS_DIR, CALLS_KEEP, "${stamp(time)}_${hz}hz${unit?.let { "_$it" } ?: ""}.wav", speechWav(pcm, rate))

    /** The saved played calls, newest first. */
    fun speechFiles(context: Context): List<File> = list(context, CALLS_DIR)

    internal fun speechWav(pcm: List<ShortArray>, rate: Int = ImbeDecoder.RATE): ByteArray {
        val b = java.nio.ByteBuffer.allocate(2 * pcm.sumOf { it.size }).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (piece in pcm) for (s in piece) b.putShort(s)
        return header(b.capacity(), 1, rate) + b.array()
    }

    /** A saved call, from its file name: when, its talkgroup, the radio a private call went to, or where a transmission was heard; how long. */
    class Saved(val file: File, val time: Long, val tg: Int?, val target: Long?, val hz: Long?, val seconds: Double, val unit: String? = null) {
        /** "unit 1A2B" (MDC-1200) or "radio 123456" (P25), or null when not heard. */
        val who: String? get() = unit?.let { if (it.startsWith("u")) "unit " + it.drop(1) else "radio " + it.drop(1) }
    }

    internal fun saved(f: File): Saved? {
        val m = Regex("""(\d{8}_\d{6})_(?:(tg|to)(\d+)|(\d+)hz)(?:_(u[0-9A-F]{4}|r\d{1,8}))?\.wav""").matchEntire(f.name) ?: return null
        val time = runCatching { java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(m.groupValues[1])!!.time }.getOrNull() ?: return null
        // The WAV header's sample rate: 8 kHz P25 speech, the sweep's analog audio faster.
        val rate = runCatching { java.io.RandomAccessFile(f, "r").use { r -> r.seek(24); Integer.reverseBytes(r.readInt()) } }.getOrNull()
            ?.takeIf { it > 0 } ?: ImbeDecoder.RATE
        val seconds = (f.length() - 44).coerceAtLeast(0) / (2.0 * rate)
        val n = m.groupValues[3].ifEmpty { m.groupValues[4] }.toLongOrNull() ?: return null
        val unit = m.groupValues[5].ifEmpty { null }
        return when (m.groupValues[2]) {
            "tg" -> Saved(f, time, n.toInt(), null, null, seconds, unit)
            "to" -> Saved(f, time, null, n, null, seconds, unit)
            else -> Saved(f, time, null, null, n, seconds, unit)
        }
    }

    private fun stamp(time: Long) = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(time))
    private fun who(call: P25History.Call) = call.tg?.let { "tg$it" } ?: call.target?.let { "to$it" } ?: "call"

    private fun keep(context: Context, dirName: String, max: Int, name: String, wav: ByteArray): File {
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val f = File(dir, name).apply { writeBytes(wav) }
        list(context, dirName).drop(max).forEach { it.delete(); P25Transcribe.sidecar(it).delete() }
        return f
    }

    private fun list(context: Context, dirName: String): List<File> =
        File(context.filesDir, dirName).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedByDescending { it.name }
}
