package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P25TsbkTest {

    /** A block from its opcode and 64 argument bits (MFID 0). */
    private fun tsbk(opcode: Int, args: Long, mfid: Int = 0) =
        P25Frame.Tsbk(ByteArray(10) { i -> when (i) { 0 -> (0x80 or opcode).toByte(); 1 -> mfid.toByte(); else -> (args ushr (8 * (9 - i))).toByte() } })

    @Test
    fun groupGrant() {
        // Options 0x40 (encrypted), channel 0x1123, talkgroup 0x0F2A, source radio 0x1B2C3D.
        val m = P25Tsbk.parse(tsbk(0x00, 0x40_1123_0F2A_1B2C3DL))
        assertEquals(P25Tsbk.Grant(0x1123, 0x0F2A, null, 0x1B2C3D, emergency = false, encrypted = true), m)
    }

    @Test
    fun unitToUnitAndUpdates() {
        assertEquals(P25Tsbk.Grant(0x1005, null, 0x000102, 0x0A0B0C, false, false),
            P25Tsbk.parse(tsbk(0x04, 0x1005_000102_0A0B0CL)))
        assertEquals(P25Tsbk.GrantUpdate(listOf(0x1001 to 100, 0x1002 to 200)),
            P25Tsbk.parse(tsbk(0x02, 0x1001_0064_1002_00C8L)))
        assertEquals(P25Tsbk.Affiliation(0x123456, 0x0300), P25Tsbk.parse(tsbk(0x28, 0x00_0000_0300_123456L)))
    }

    @Test
    fun channelTableGivesFrequencies() {
        // Table 1: base 851.00625 MHz (/5 Hz), spacing 6.25 kHz (/125 Hz); TDMA table 2: 2 slots a carrier.
        val iden = P25Tsbk.parse(tsbk(0x3D, (1L shl 60) or (50L shl 32) or (851_006_250L / 5))) as P25Tsbk.Iden
        assertEquals(P25Tsbk.Iden(1, 851_006_250L, 6_250, 1), iden)
        val tdma = P25Tsbk.parse(tsbk(0x33, (2L shl 60) or (3L shl 56) or (100L shl 32) or (769_006_250L / 5))) as P25Tsbk.Iden
        assertEquals(2, tdma.slots)
        val ch = P25Tsbk.Channels().apply { learn(iden); learn(tdma) }
        assertEquals(851_006_250L + 10 * 6_250, ch.hz(0x100A))
        assertEquals(769_006_250L + 2 * 12_500, ch.hz(0x2005)) // channel 5 = carrier 2, slot 1
        assertNull(ch.hz(0x3001))
    }

    @Test
    fun siteBroadcasts() {
        // WACN 0xBEE00, system 0x3A1, control channel 0x1040.
        assertEquals(P25Tsbk.Site(0xBEE00, 0x3A1, null, null, 0x1040),
            P25Tsbk.parse(tsbk(0x3B, 0x01_BEE00_3A1_1040_70L)))
        assertNull(P25Tsbk.parse(tsbk(0x00, 0L, mfid = 0x90))) // vendor message: skipped
    }
}
