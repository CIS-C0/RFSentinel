package com.rfsentinel.app.sdr

/** Whisper (whisper.cpp, built from source in src/full/cpp) for P25 call transcripts ([P25Whisper]). Full version only. */
object WhisperNative {
    /** The library loaded on this phone (false: [P25Transcribe] keeps Android's recognizer). */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("rfswhisper") }
            .onFailure { com.rfsentinel.app.util.HwLogs.sdr.log("P25 transcribe: Whisper library not loaded (${it.javaClass.simpleName})") }
            .isSuccess
    }

    /** A loaded model, 0 when it couldn't be read. */
    external fun load(path: String): Long
    /** What was said in [samples] (16 kHz mono, -1..1), null on failure. */
    external fun transcribe(handle: Long, samples: FloatArray, threads: Int): String?
    external fun free(handle: Long)
}
