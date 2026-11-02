package com.rfsentinel.app.sdr

/**
 * P25 control channel, third stage: a trunking block ([P25Frame.Tsbk]) to what it says. Metadata only -
 * who was granted a channel for which talkgroup, who affiliated or registered, and the system's own
 * broadcasts (its IDs and the tables that turn channel numbers into frequencies). Standard (MFID 0)
 * outbound messages per TIA-102.AABC; vendor ones are skipped. Field positions cross-checked with OP25.
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

    fun parse(t: P25Frame.Tsbk): Msg? {
        if (t.protected || (t.mfid != 0x00 && t.mfid != 0x01)) return null
        val a = args(t.bytes)
        fun f(shift: Int, width: Int) = (a ushr (shift - 16) and ((1L shl width) - 1)).toInt()
        return when (t.opcode) {
            0x00 -> f(72, 8).let { o -> Grant(f(56, 16), f(40, 16), null, f(16, 24), o and 0x80 != 0, o and 0x40 != 0) }
            0x02 -> GrantUpdate(listOf(f(64, 16) to f(48, 16), f(32, 16) to f(16, 16)).distinct())
            0x03 -> GrantUpdate(listOf(f(48, 16) to f(16, 16)))
            0x04, 0x06 -> Grant(f(64, 16), null, f(40, 24), f(16, 24), false, false)
            0x28 -> Affiliation(f(16, 24), f(40, 16))
            0x2C -> Registration(f(16, 24), f(76, 2) == 0)
            0x3D -> Iden(f(76, 4), f(16, 32) * 5L, f(48, 10) * 125, 1, offset(f(58, 9), 8, 250_000L))
            0x34 -> Iden(f(76, 4), f(16, 32) * 5L, f(48, 10) * 125, 1, offset(f(58, 14), 13, f(48, 10) * 125L))
            0x33 -> Iden(f(76, 4), f(16, 32) * 5L, f(48, 10) * 125, SLOTS[f(72, 4)], offset(f(58, 14), 13, f(48, 10) * 125L))
            0x3A -> Site(null, f(56, 12), f(48, 8), f(40, 8), f(24, 16))
            0x3B -> Site(f(52, 20), f(40, 12), null, null, f(24, 16))
            0x3C -> Neighbor(f(48, 8), f(40, 8), f(24, 16))
            else -> null
        }
    }

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
