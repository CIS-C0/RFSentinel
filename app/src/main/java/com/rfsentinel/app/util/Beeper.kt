package com.rfsentinel.app.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthesized alert beeps, played with the given audio attributes so the same
 * pattern works on the phone speaker and through the car. No sound file, no
 * dependence on the user's notification sound or notification volume.
 */
object Beeper {

    private const val RATE = 22_050
    private const val FREQ_HZ = 1_760.0
    private const val FADE_MS = 6

    /** (beep ms, pause ms) pairs. */
    fun pattern(beeps: Int, following: Boolean): List<Pair<Int, Int>> = when {
        following -> listOf(450 to 120, 130 to 120, 450 to 0)
        else -> List(beeps.coerceIn(1, 3)) { i -> 140 to if (i == beeps - 1) 0 else 110 }
    }

    /**
     * Milliseconds between proximity ticks (radar beeps and Locate): slow while faint, then
     * sharply faster up close, like a detector locking on. -95 dBm -> 1.3 s, -70 -> ~0.4 s,
     * -55 -> ~0.14 s, -45 dBm and closer -> 0.06 s.
     */
    fun tickIntervalMs(rssi: Int): Long {
        val t = ((rssi + 95).coerceIn(0, 50) / 50.0).pow(1.4)
        return (1300 * (60.0 / 1300).pow(t)).toLong()
    }

    fun durationMs(pattern: List<Pair<Int, Int>>) = pattern.sumOf { it.first + it.second }

    // ---- Radar-detector sound style (synthesized, in the spirit of a dash radar detector) ----

    /** A tone that glides from [fromHz] to [toHz] over [ms], then [gapMs] of silence. */
    data class Tone(val ms: Int, val fromHz: Double, val toHz: Double = fromHz, val gapMs: Int = 0)

    /** Strong / following: fast high warble ("Ka" style). Probable: two-tone ("K"). Weak: slow single tone ("X"). */
    fun detectorAlert(strength: Int): List<Tone> = when {
        strength >= 3 -> List(5) { listOf(Tone(50, 2600.0, 3500.0, 10), Tone(50, 3500.0, 2600.0, 40)) }.flatten()
        strength == 2 -> List(3) { listOf(Tone(90, 1450.0, gapMs = 25), Tone(90, 1050.0, gapMs = 110)) }.flatten()
        else -> List(2) { Tone(170, 900.0, gapMs = 150) }
    }

    /** One radar-mode tick: a short rising chirp. */
    val detectorTick = listOf(Tone(45, 2500.0, 3300.0))

    /** Power-on: a rising sweep and two confirmation blips. */
    val detectorStartup = listOf(Tone(380, 500.0, 2600.0, 70), Tone(90, 2200.0, gapMs = 50), Tone(90, 3000.0))

    fun toneDurationMs(tones: List<Tone>) = tones.sumOf { it.ms + it.gapMs }

    /** 16-bit mono PCM for a tone list, phase-continuous through each glide (pure; unit-tested). */
    fun tonePcm(tones: List<Tone>): ShortArray {
        val out = ShortArray(toneDurationMs(tones) * RATE / 1000)
        var pos = 0
        val fade = FADE_MS * RATE / 1000
        for (t in tones) {
            val n = t.ms * RATE / 1000
            var phase = 0.0
            for (i in 0 until n) {
                val f = t.fromHz + (t.toHz - t.fromHz) * i / n
                phase += 2 * PI * f / RATE
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                if (pos + i < out.size) out[pos + i] = (sin(phase) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += n + t.gapMs * RATE / 1000
        }
        return out
    }

    fun playTones(attributes: AudioAttributes, tones: List<Tone>): Int =
        playPcm(attributes, tonePcm(tones), toneDurationMs(tones))

    /** 16-bit mono PCM for a pattern (pure; unit-tested). */
    fun pcm(pattern: List<Pair<Int, Int>>): ShortArray {
        val out = ShortArray(durationMs(pattern) * RATE / 1000)
        var pos = 0
        val fade = FADE_MS * RATE / 1000
        for ((on, off) in pattern) {
            val n = on * RATE / 1000
            for (i in 0 until n) {
                // Short fade in/out so the beep doesn't click.
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                out[pos + i] = (sin(2 * PI * FREQ_HZ * i / RATE) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += n + off * RATE / 1000
        }
        return out
    }

    /** Plays the pattern; returns how long it lasts (ms), or 0 if audio failed. */
    fun play(attributes: AudioAttributes, pattern: List<Pair<Int, Int>>): Int =
        playPcm(attributes, pcm(pattern), durationMs(pattern))

    private fun playPcm(attributes: AudioAttributes, data: ShortArray, ms: Int): Int {
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, ms + 300L)
            ms
        } catch (e: Exception) {
            0
        }
    }
}
