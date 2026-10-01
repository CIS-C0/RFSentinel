package com.rfsentinel.app.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
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

    fun durationMs(pattern: List<Pair<Int, Int>>) = pattern.sumOf { it.first + it.second }

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
    fun play(attributes: AudioAttributes, pattern: List<Pair<Int, Int>>): Int {
        val data = pcm(pattern)
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
            val ms = durationMs(pattern)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, ms + 300L)
            ms
        } catch (e: Exception) {
            0
        }
    }
}
