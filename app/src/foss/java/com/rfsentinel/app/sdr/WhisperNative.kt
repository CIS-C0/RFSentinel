package com.rfsentinel.app.sdr

/** The FOSS version has no Whisper (no native code of ours, so F-Droid's build matches): Android's recognizer only. */
object WhisperNative {
    val available = false
    fun load(path: String): Long = 0
    fun transcribe(handle: Long, samples: FloatArray, threads: Int): String? = null
    fun free(handle: Long) {}
}
