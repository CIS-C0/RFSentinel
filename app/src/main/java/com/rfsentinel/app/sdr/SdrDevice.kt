package com.rfsentinel.app.sdr

/**
 * A receive-only SDR on USB, as [SdrRadio] uses it: tune, fixed gain, and raw 8-bit I/Q samples
 * (unsigned, 127.5 = zero, as the RTL2832U delivers them). [RtlSdr] and [HackRf] implement it.
 */
interface SdrDevice {
    /** Short name for the status line and the log ("RTL-SDR", "HackRF"). */
    val label: String
    val sampleRate: Int
    val ctlErrors: Int
    /** The device stopped answering (unplugged). */
    val lost: Boolean
    val tunerWriteFails: Int
    /** Reads to throw away after a retune, so no samples from the last frequency are measured. */
    val settleReads: Int

    /** False when this model can't be driven (an unsupported tuner, say). */
    fun open(): Boolean
    fun close()
    fun setSampleRate(rate: Int)
    /** Tunes to [hz]; false when it didn't take (PLL not locked, request failed). */
    fun tune(hz: Long): Boolean
    /** Fixed gain in tenths of a dB: a steady scale for comparing channels over time. */
    fun setGain(tenthsDb: Int)
    /** Restarts the sample stream after it stalled. */
    fun resetBuffer()
    /** Reads unsigned 8-bit I/Q into [buf]; bytes read, or a negative value on error. */
    fun read(buf: ByteArray, len: Int): Int
    fun read(buf: ByteArray): Int = read(buf, buf.size)

    /** The crystal's error in parts per million, taken out of every [tune] (0: none). */
    var ppm: Double
        get() = 0.0
        set(_) {}

    /** Power on the antenna port for an active antenna or amplifier; false when this device has no way to. */
    fun setBiasTee(on: Boolean): Boolean = false

    companion object {
        /** What to ask the tuner for so it lands on [hz] when its crystal is [ppm] off. */
        fun corrected(hz: Long, ppm: Double): Long = if (ppm == 0.0) hz else Math.round(hz / (1 + ppm / 1e6))
    }
}
