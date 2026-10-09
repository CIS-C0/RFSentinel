package com.rfsentinel.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rtl8821au5gTest {

    @Test
    fun twoPointFourIsUnchangedUnlessTheBandSwitchIsAsked() {
        val plain = Rtl8821auTables.channelTune(6)
        // Without 5 GHz on, 2.4 GHz tuning is exactly the old table (no band-switch writes).
        assertTrue(plain.none { it[1] == 0x4E || it[1] == 0x4F })
        val switched = Rtl8821auTables.channelTune(6, bandSwitch = true)
        assertEquals(plain.size + 5, switched.size)
        assertArrayEquals(intArrayOf(Rtl8821auTables.BB, 0xCB4, 0x30000000, 0x1), switched[4])
    }

    @Test
    fun fiveGhzSetsTheBandSwitchSubBandAndChannel() {
        val t = Rtl8821auTables.channelTune(149)
        assertTrue(t.any { it.contentEquals(intArrayOf(Rtl8821auTables.BB, 0xCB4, 0x30000000, 0x2)) })
        assertTrue(t.any { it.contentEquals(intArrayOf(Rtl8821auTables.RF, 0x18, 0x70300, 0x501)) })
        assertTrue(t.any { it.contentEquals(intArrayOf(Rtl8821auTables.RF, 0x18, 0xFF, 149)) })
        assertTrue(t.any { it.contentEquals(intArrayOf(Rtl8821auTables.BB, 0x860, 0x1FFE0000, 0x412)) })
        val low = Rtl8821auTables.channelTune(36)
        assertTrue(low.any { it.contentEquals(intArrayOf(Rtl8821auTables.RF, 0x18, 0x70300, 0x101)) })
        assertTrue(low.any { it.contentEquals(intArrayOf(Rtl8821auTables.BB, 0x82C, 0x3, 0x1)) })
    }
}
