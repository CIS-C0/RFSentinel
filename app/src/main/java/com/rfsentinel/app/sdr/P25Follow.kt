package com.rfsentinel.app.sdr

/**
 * P25 voice, fourth step: one call heard live. A voice channel's samples (as [P25Demod.feed] takes them) to speech:
 * its voice frames ([P25Voice]) through the vocoder ([ImbeDecoder]) to [onSpeech], 20 ms (8 kHz) at a time.
 * [ended] once the call's terminator is heard, or it turns out encrypted; [quietS]: seconds since the last voice frame.
 * [lsm]: the site is a simulcast one (its control channel decoded as LSM), so its voice channels are LSM too.
 * [onOtherCall]: another talkgroup's call going on, as the link control announces it (channel, talkgroup), each time
 * after the first; [talker] the radio talking, once its link control read the same twice.
 */
class P25Follow(offsetHz: Double, lsm: Boolean = false, private val grantClear: Boolean = false,
                private val onOtherCall: ((Int, Int) -> Unit)? = null, private val onSpeech: (ShortArray) -> Unit) {

    private val imbe = ImbeDecoder()
    private var samples = 0L
    private var lastVoice = 0L
    /** Voice frames played (each 180 ms). */
    var frames = 0; private set
    var ended = false; private set
    /** Why it [ended]: "terminator" or "encrypted". */
    var endedBy: String? = null; private set
    /** LDU2s that read as encrypted once, not confirmed by the next ([P25Voice.confirmsEncryption]): skipped. */
    var garbled = 0; private set
    private var lastAlgid: Int? = null
    var talker: Int? = null; private set
    private var talkerRead: Int? = null
    private val updates = HashSet<Long>()

    private val demod = P25Demod(offsetHz, P25Voice.LDU_DIBITS, if (lsm) P25Demod.Mod.LSM else P25Demod.Mod.C4FM) { f ->
        val v = P25Voice.decode(f)
        when {
            v == null -> if (P25Frame.nid(P25Frame.stripStatus(f))?.duid in TERMINATORS) { ended = true; endedBy = "terminator" }
            v.encrypted -> {
                // A grant that said clear takes a real cipher ID: weak clear calls were cut by two garbled LDU2s alike.
                if (P25Voice.confirmsEncryption(lastAlgid, v.algid!!) && (!grantClear || v.algid in P25Voice.CIPHERS)) {
                    ended = true; endedBy = "encrypted (ALGID 0x%02X)".format(v.algid)
                }
                else { garbled++; lastVoice = samples } // still a voice frame: the call goes on
                lastAlgid = v.algid
            }
            else -> {
                if (v.algid != null) lastAlgid = v.algid
                frames++; lastVoice = samples; for (c in v.voice) onSpeech(imbe.decode(c.bits, c.errors))
                v.lc?.let(::linkControl)
            }
        }
    }

    /** LCO 0x00 group voice channel user (who talks), 0x02 group voice channel update (other calls); protected ones skipped. */
    internal fun linkControl(lc: IntArray) {
        if (lc[0] and 0x80 != 0) return
        when (lc[0] and 0x3F) {
            0x00 -> if (lc[1] == 0 || lc[1] == 1) {
                val id = lc[6] shl 16 or (lc[7] shl 8) or lc[8]
                if (id != 0 && id == talkerRead) talker = id
                talkerRead = id
            }
            0x02 -> for ((ch, tg) in listOf((lc[1] shl 8 or lc[2]) to (lc[3] shl 8 or lc[4]), (lc[5] shl 8 or lc[6]) to (lc[7] shl 8 or lc[8])))
                if (tg != 0 && !updates.add(ch.toLong() shl 16 or tg.toLong())) onOtherCall?.invoke(ch, tg)
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
