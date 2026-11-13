package com.rfsentinel.app.sdr

/**
 * P25 control channel, third stage: a trunking block ([P25Frame.Tsbk]) to what it says. Metadata only -
 * who was granted a channel for which talkgroup, who affiliated or registered, and the system's own
 * broadcasts (its IDs and the tables that turn channel numbers into frequencies). Standard (MFID 0)
 * outbound messages per TIA-102.AABC, plus Motorola's patches (MFID 0x90); other vendors' are skipped. Field positions
 * cross-checked with OP25 and SDRTrunk.
 * Pure: unit-tested.
 */
object P25Tsbk {

    sealed class Msg
    /** A voice channel granted: to talkgroup [tg], or radio to radio ([target]); [source] the radio keying up. */
    data class Grant(val channel: Int, val tg: Int?, val target: Int?, val source: Int,
                     val emergency: Boolean, val encrypted: Boolean) : Msg()
    /** Calls still going on: (channel, talkgroup) pairs. */
    data class GrantUpdate(val calls: List<Pair<Int, Int>>) : Msg()
    data class Affiliation(val radio: Int, val tg: Int) : Msg()
    data class Registration(val radio: Int, val accepted: Boolean) : Msg()
    /** Channel table [id]: channel n is [baseHz] + n × [spacingHz], or n / [slots] on a TDMA (Phase 2) carrier.
     *  [txOffsetHz]: where the radios transmit, from the base station's frequency (0: not given). */
    data class Iden(val id: Int, val baseHz: Long, val spacingHz: Int, val slots: Int, val txOffsetHz: Long = 0) : Msg()
    /** This site: [wacn] and [sysId] the system, [rfss] / [site] where, [channel] its control channel. */
    data class Site(val wacn: Int?, val sysId: Int, val rfss: Int?, val site: Int?, val channel: Int) : Msg()
    /** A neighbouring site's control channel. */
    data class Neighbor(val rfss: Int, val site: Int, val channel: Int) : Msg()
    /** This site's other control channels (secondary control channel broadcast): where to look when the main one fades. */
    data class Secondary(val rfss: Int, val site: Int, val channels: List<Int>) : Msg()

    /**
     * Something the site relays between radios, or from dispatch to a radio: [kind] one of [EVENT_KINDS]; [source] who
     * sent it, [target] the radio it is for; [code] the status or message number when there is one.
     */
    data class RadioEvent(val kind: String, val source: Int, val target: Int, val code: Int? = null) : Msg()
    /** Motorola patch (or regroup): talkgroups [members] carried together as supergroup [sg] ([add] false: taken apart). */
    data class Patch(val sg: Int, val members: List<Int>, val add: Boolean) : Msg()
    /** A packet data channel granted to radio [target] (location reports, messages): [channel] the site's side, [inputChannel] where the radio sends. */
    data class DataGrant(val channel: Int, val inputChannel: Int, val target: Int) : Msg()
    /**
     * Not a trunking block: a radio's GPS position from a data packet ([P25Pdu], LRRP). [radio] the packet's
     * logical link ID: the sender when inbound, the radio it goes to when [outbound]. Shown on the screen, never logged.
     */
    data class Position(val radio: Int, val outbound: Boolean, val lat: Double, val lon: Double, val radiusM: Double?,
                        val altitudeM: Double?) : Msg()

    /**
     * A radio's own message to the site, heard on the control channel's input: [radio] its unit ID, [kind] what it sent.
     * Radios send these without anyone talking (switching on, changing talkgroup or site, status, data, acknowledging
     * a page from the system), so they show a radio that isn't in a call.
     */
    data class Inbound(val radio: Int, val kind: String)

    fun parse(t: P25Frame.Tsbk): Msg? {
        if (t.protected) return null
        val a = args(t.bytes)
        fun f(shift: Int, width: Int) = (a ushr (shift - 16) and ((1L shl width) - 1)).toInt()
        if (t.mfid == MFID_MOTOROLA) return when (t.opcode) {
            // Patch add / delete: the supergroup, then up to 3 talkgroups (unused ones 0 or the supergroup again).
            0x00, 0x01 -> f(64, 16).takeIf { it != 0 }?.let { sg ->
                Patch(sg, listOf(f(48, 16), f(32, 16), f(16, 16)).filter { it != 0 && it != sg }.distinct(), t.opcode == 0x00) }
            // A patched call: granted to the supergroup, laid out like a talkgroup grant.
            0x02 -> f(72, 8).let { o -> Grant(f(56, 16), f(40, 16), null, f(16, 24), o and 0x80 != 0, o and 0x40 != 0) }
            0x03 -> GrantUpdate(listOf(f(64, 16) to f(48, 16), f(32, 16) to f(16, 16)).filter { it.second != 0 }.distinct())
            else -> null
        }
        if (t.mfid != 0x00 && t.mfid != 0x01) return null
        return when (t.opcode) {
            0x00 -> f(72, 8).let { o -> Grant(f(56, 16), f(40, 16), null, f(16, 24), o and 0x80 != 0, o and 0x40 != 0) }
            0x02 -> GrantUpdate(listOf(f(64, 16) to f(48, 16), f(32, 16) to f(16, 16)).distinct())
            0x03 -> GrantUpdate(listOf(f(48, 16) to f(16, 16)))
            0x04, 0x06 -> Grant(f(64, 16), null, f(40, 24), f(16, 24), false, false)
            // Unit to unit: the target at bits 40-63, the radio (or console) sending at 16-39. Status: unit status, user status.
            0x05 -> RadioEvent("private call request", f(16, 24), f(40, 24))
            0x14 -> DataGrant(f(56, 16), f(40, 16), f(16, 24))
            0x18 -> RadioEvent("status update", f(16, 24), f(40, 24), f(64, 16))
            0x1C -> RadioEvent("message", f(16, 24), f(40, 24), f(64, 16))
            // Here the source comes first (SDRTrunk's RadioUnitMonitorCommand).
            0x1D -> RadioEvent("radio unit monitor", f(40, 24), f(16, 24))
            0x1F -> RadioEvent("call alert", f(16, 24), f(40, 24))
            0x28 -> Affiliation(f(16, 24), f(40, 16))
            0x2C -> Registration(f(16, 24), f(76, 2) == 0)
            0x3D -> Iden(f(76, 4), f(16, 32) * 5L, f(48, 10) * 125, 1, offset(f(58, 9), 8, 250_000L))
            0x34 -> Iden(f(76, 4), f(16, 32) * 5L, f(48, 10) * 125, 1, offset(f(58, 14), 13, f(48, 10) * 125L))
            0x33 -> Iden(f(76, 4), f(16, 32) * 5L, f(48, 10) * 125, SLOTS[f(72, 4)], offset(f(58, 14), 13, f(48, 10) * 125L))
            0x3A -> Site(null, f(56, 12), f(48, 8), f(40, 8), f(24, 16))
            0x3B -> Site(f(52, 20), f(40, 12), null, null, f(24, 16))
            0x3C -> Neighbor(f(48, 8), f(40, 8), f(24, 16))
            0x39 -> Secondary(f(72, 8), f(64, 8), listOf(f(48, 16), f(24, 16)).filter { it != 0 && it != 0xffff }.distinct())
            else -> null
        }
    }

    /**
     * An inbound block (a radio to the site, TIA-102.AABC ISPs) to who sent it. The sending radio's unit ID is the last
     * 24 argument bits in every request and response listed; others (and vendor ones) give null.
     */
    fun parseInbound(t: P25Frame.Tsbk): Inbound? {
        if (t.protected || (t.mfid != 0x00 && t.mfid != 0x01)) return null
        val kind = INBOUND[t.opcode] ?: return null
        val radio = (args(t.bytes) and 0xffffffL).toInt()
        // 0 and the top addresses are "nobody" / system-wide: not a radio.
        return if (radio == 0 || radio >= 0xfffff0) null else Inbound(radio, kind)
    }

    private const val MFID_MOTOROLA = 0x90

    /** What a [RadioEvent] can be. "radio unit monitor": dispatch keys a radio's microphone from afar to listen in. */
    val EVENT_KINDS = listOf("private call request", "status update", "message", "radio unit monitor", "call alert")

    private val INBOUND = mapOf(
        0x00 to "talkgroup call request", 0x04 to "private call request", 0x05 to "private call answer",
        0x12 to "data channel request", 0x18 to "status update", 0x19 to "status reply", 0x1C to "message",
        0x1F to "call alert", 0x20 to "acknowledgement", 0x27 to "emergency alarm", 0x24 to "function reply", 0x28 to "talkgroup affiliation",
        0x2B to "sign-off", 0x2C to "registration", 0x2D to "location registration")

    /** A transmit offset: the bits below sign bit [sign] (set: the radios transmit above the base station) times [step]. */
    private fun offset(v: Int, sign: Int, step: Long): Long =
        (v and ((1 shl sign) - 1)) * step * (if (v shr sign and 1 == 1) 1 else -1)

    /** Bytes 2-9: the 64 argument bits (bit 0 of the result is bit 16 of the 96-bit block). */
    private fun args(b: ByteArray): Long {
        var v = 0L
        for (i in 2 until 10) v = v shl 8 or (b[i].toLong() and 0xff)
        return v
    }

    /** Slots per carrier by TDMA channel type (types above 5 are reserved). */
    private val SLOTS = intArrayOf(1, 1, 1, 2, 4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)

    /**
     * The channel tables a site broadcasts, to turn a 16-bit channel (4-bit table, 12-bit number) into the
     * downlink frequency. Null until that table has been heard.
     */
    class Channels {
        private val tables = arrayOfNulls<Iden>(16)
        fun learn(i: Iden) { tables[i.id] = i }
        /** True when [channel] is on a TDMA (Phase 2) carrier; false when FDMA or its table isn't known yet. */
        fun tdma(channel: Int) = (tables[channel shr 12 and 0xf]?.slots ?: 1) > 1
        fun hz(channel: Int): Long? {
            val t = tables[channel shr 12 and 0xf] ?: return null
            return t.baseHz + t.spacingHz.toLong() * ((channel and 0xfff) / t.slots)
        }
        /** Where the radios transmit on [channel] (the site's input), or null until its table, with an offset, is heard. */
        fun inputHz(channel: Int): Long? {
            val t = tables[channel shr 12 and 0xf]?.takeIf { it.txOffsetHz != 0L } ?: return null
            return hz(channel)?.plus(t.txOffsetHz)
        }
    }

    /** "1-0123" style: the table and the channel number, for a channel whose frequency isn't known yet. */
    fun channelName(channel: Int) = "%d-%04d".format(channel shr 12 and 0xf, channel and 0xfff)
}
