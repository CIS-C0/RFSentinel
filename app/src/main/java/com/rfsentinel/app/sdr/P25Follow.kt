package com.rfsentinel.app.sdr

/**
 * P25 voice, fourth step: one call heard live. A voice channel's samples (as [P25Demod.feed] takes them) to speech:
 * its voice frames ([P25Voice]) through the vocoder ([ImbeDecoder]) to [onSpeech], 20 ms (8 kHz) at a time.
 * [ended] once the call's terminator is heard, or it turns out encrypted; [quietS]: seconds since the last voice frame.
 * [lsm]: the site is a simulcast one (its control channel decoded as LSM), so its voice channels are LSM too.
 */
class P25Follow(offsetHz: Double, lsm: Boolean = false, private val onSpeech: (ShortArray) -> Unit) {

    private val imbe = ImbeDecoder()
    private var samples = 0L
    private var lastVoice = 0L
    /** Voice frames played (each 180 ms). */
    var frames = 0; private set
    var ended = false; private set

    private val demod = P25Demod(offsetHz, P25Voice.LDU_DIBITS, if (lsm) P25Demod.Mod.LSM else P25Demod.Mod.C4FM) { f ->
        val v = P25Voice.decode(f)
        when {
            v == null -> if (P25Frame.nid(P25Frame.stripStatus(f))?.duid in TERMINATORS) ended = true
            v.encrypted -> ended = true
            else -> { frames++; lastVoice = samples; for (c in v.voice) onSpeech(imbe.decode(c.bits, c.errors)) }
        }
    }

    fun feed(buf: ByteArray, count: Int) {
        if (ended) return
        demod.feed(buf, count)
        samples += count
    }

    val quietS get() = (samples - lastVoice).toDouble() / P25Demod.SAMPLE_RATE

    companion object {
        /** The end of a call: a terminator, plain or with link control. */
        private val TERMINATORS = setOf(0x3, 0xF)
    }
}
