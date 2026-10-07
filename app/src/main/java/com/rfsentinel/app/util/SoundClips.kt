package com.rfsentinel.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.rfsentinel.app.R

/**
 * Recorded sounds for the radar-detector sound style (res/raw): four alert effects, a
 * pulse of each for the proximity beeps, and the optional scan intro. Short clips go through a SoundPool
 * (loaded once, ready before the first alert); the long intro streams from a MediaPlayer.
 */
object SoundClips {

    /** The four detector alert sounds and, for each, one of its pulses for the proximity beeps. */
    enum class Clip(val res: Int, val ms: Int) {
        EFFECT_1(R.raw.detector_effect_1, 2_950),
        EFFECT_2(R.raw.detector_effect_2, 2_950),
        EFFECT_3(R.raw.detector_effect_3, 2_950),
        EFFECT_4(R.raw.detector_effect_4, 3_800),
        TICK_1(R.raw.detector_tick_1, 75),
        TICK_2(R.raw.detector_tick_2, 70),
        TICK_3(R.raw.detector_tick_3, 90),
        TICK_4(R.raw.detector_tick_4, 125);

        companion object {
            fun effect(n: Int) = entries[(n - 1).coerceIn(0, 3)]
            fun tick(n: Int) = entries[4 + (n - 1).coerceIn(0, 3)]
        }
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<Clip, Int>()
    private val ready = HashSet<Int>()
    private var intro: MediaPlayer? = null
    private var previewStream = 0

    /** Loads the clips (async); call early, e.g. when a scan starts. */
    @Synchronized
    fun preload(context: Context, attributes: AudioAttributes) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(this) { ready += id } }
        val app = context.applicationContext
        for (c in Clip.entries) ids[c] = p.load(app, c.res, 1)
        pool = p
    }

    /** Plays a clip; returns its length (ms), or 0 if it isn't loaded yet (caller falls back). */
    @Synchronized
    fun play(context: Context, attributes: AudioAttributes, clip: Clip): Int {
        preload(context, attributes)
        val id = ids[clip] ?: return 0
        if (id !in ready) return 0
        return if ((pool?.play(id, 1f, 1f, 1, 0, 1f) ?: 0) != 0) clip.ms else 0
    }

    /** A Settings preview: stops the previous preview first so two never overlap. */
    @Synchronized
    fun playPreview(context: Context, attributes: AudioAttributes, clip: Clip): Boolean {
        stopPreview()
        preload(context, attributes)
        val id = ids[clip] ?: return false
        if (id !in ready) return false
        previewStream = pool?.play(id, 1f, 1f, 1, 0, 1f) ?: 0
        return previewStream != 0
    }

    @Synchronized
    fun stopPreview() {
        if (previewStream != 0) pool?.stop(previewStream)
        previewStream = 0
    }

    @Synchronized
    fun introPlaying() = intro != null

    /** The optional scan intro (about 12 s); [onDone] runs when it ends or fails. */
    @Synchronized
    fun playIntro(context: Context, attributes: AudioAttributes, onDone: (() -> Unit)? = null) {
        stopIntro()
        val mp = MediaPlayer()
        runCatching {
            context.applicationContext.resources.openRawResourceFd(R.raw.scan_intro).use {
                mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            mp.setAudioAttributes(attributes)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { synchronized(this) { if (intro === it) intro = null }; it.release(); onDone?.invoke() }
            mp.prepareAsync()
            intro = mp
        }.onFailure { mp.release(); onDone?.invoke() }
    }

    @Synchronized
    fun stopIntro() {
        intro?.let { runCatching { it.stop() }; it.release() }
        intro = null
    }
}
