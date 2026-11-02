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
    /** 8-bit samples summed [P25Demod.DECIM] at a time reach ±5100: this fills 16 bits without clipping. */
    private const val SCALE = 6.0

    /** Mixes [offsetHz] down to 0 and integrates-and-dumps to [RATE], collecting 16-bit I/Q pairs. */
    class Baseband(val offsetHz: Long, sampleRate: Int = P25Demod.SAMPLE_RATE) {
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
                    short(accR); short(accI); samples++
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

    /** A 44-byte PCM WAV header: 2 channels, 16 bits, [RATE]. */
    internal fun header(dataBytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4).putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    /** Saves a call's recording ("p25_20261102_142233_tg1234.wav") and drops the oldest past [KEEP]. */
    fun save(context: Context, call: P25History.Call, wav: ByteArray): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(call.time))
        val who = call.tg?.let { "tg$it" } ?: call.target?.let { "to$it" } ?: "call"
        val f = File(dir, "p25_${stamp}_$who.wav").apply { writeBytes(wav) }
        files(context).drop(KEEP).forEach { it.delete() }
        return f
    }

    /** The kept recordings, newest first. */
    fun files(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedByDescending { it.name }
}
