package com.rfsentinel.app.sdr

/**
 * P25 voice, fourth step: one call heard live. A voice channel's samples (as [P25Demod.feed] takes them) to speech:
 * its voice frames ([P25Voice]) through the vocoder ([ImbeDecoder]) to [onSpeech], 20 ms (8 kHz) at a time.
 * [ended] once the call's terminator is heard, or it turns out encrypted; [quietS]: seconds since the last voice frame.
 * [lsm]: the site is a simulcast one (its control channel decoded as LSM), so its voice channels are LSM too.
 * [onOtherCall]: another talkgroup's call going on, as the link control announces it (channel, talkgroup), each time
 * after the first; [talker] the radio talking, from its link control.
 * [onPosition]: a radio's GPS position from a data packet on the channel ([P25Pdu]); [dataPackets] how many were heard.
 */
class P25Follow(offsetHz: Double, lsm: Boolean = false, private val grantClear: Boolean = false,
                private val onOtherCall: ((Int, Int) -> Unit)? = null, private val onPosition: ((P25Tsbk.Position) -> Unit)? = null,
                private val onSpeech: (ShortArray) -> Unit) {

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
    private val updates = HashSet<Long>()
    var dataPackets = 0; private set
    /** The channel's NAC once two frames in a row said it, for damaged NIDs ([P25Frame.nid]). */
    private var nac: Int? = null
    private var nacRead: Int? = null

    private val demod: P25Demod = P25Demod(offsetHz, P25Voice.LDU_DIBITS, if (lsm) P25Demod.Mod.LSM else P25Demod.Mod.C4FM) { f ->
        val v = P25Voice.decode(f, demod.soft, nac)
        val nid = v?.nid ?: P25Frame.nid(P25Frame.stripStatus(f), demod.soft.takeIf { it.size == f.size }?.let { P25Frame.stripStatus(it) }, nac)
        if (nid != null) { if (nid.nac == nacRead) nac = nid.nac; nacRead = nid.nac }
        when {
            v == null -> if (nid?.duid in TERMINATORS) { ended = true; endedBy = "terminator" }
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

    init {
        onPosition?.let { send -> demod.onPdu = { f, s -> P25Pdu.decode(f, s)?.takeIf { it.stage != P25Pdu.Stage.HEADER }?.let { p -> dataPackets++; p.positions.forEach(send) } } }
    }

    /**
     * LCO 0x00 group voice channel user (who talks), 0x02 group voice channel update (other calls). Standard ones only: the
     * implicit-MFID flag set, or vendor byte 0 (SDRTrunk's LinkControlWord); protected ones skipped.
     */
    internal fun linkControl(lc: IntArray) {
        if (lc[0] and 0x80 != 0 || (lc[0] and 0x40 == 0 && lc[1] != 0)) return
        when (lc[0] and 0x3F) {
            0x00 -> run {
                // Reed-Solomon checked ([P25Voice.lc]): one read is enough.
                val id = lc[6] shl 16 or (lc[7] shl 8) or lc[8]
                if (id != 0) talker = id
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
