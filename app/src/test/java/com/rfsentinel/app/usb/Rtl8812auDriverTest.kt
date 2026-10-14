package com.rfsentinel.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RTL8812AU driver: its tables came out of the vendor's condition blocks right, the channel tuning, the efuse map and the USB IDs. */
class Rtl8812auDriverTest {

    private val T = Rtl8812auTables

    private fun find(tab: IntArray, addr: Int): List<Int> = (0 until tab.size / 2).filter { tab[it * 2] == addr }.map { tab[it * 2 + 1] }

    @Test
    fun theTablesAreWholePairsOfTheRightSizes() {
        for ((name, tab) in listOf("MAC" to T.MAC_REG, "BB" to T.PHY_REG, "AGC" to T.AGC_TAB, "A" to T.RADIOA, "B" to T.RADIOB))
            assertEquals("$name has pairs", 0, tab.size % 2)
        assertTrue(T.MAC_REG.size / 2 in 100..130)
        assertTrue(T.PHY_REG.size / 2 in 200..230)
        assertTrue(T.AGC_TAB.size / 2 in 120..150)
        assertTrue(T.RADIOA.size / 2 in 190..230)
        assertTrue(T.RADIOB.size / 2 in 180..220)
    }

    @Test
    fun theVendorsConditionBlocksPickedTheUsbBoardBranch() {
        // 0x011: the USB branch (0x66), not the PCIe one (0x5A).
        assertEquals(listOf(0x66), find(T.MAC_REG, 0x011))
        // No condition marker is left in a table (they have bit 31 or 30 set in the address).
        for (tab in listOf(T.MAC_REG, T.PHY_REG, T.AGC_TAB)) for (i in tab.indices step 2) {
            val a = tab[i]
            assertTrue("address 0x%X is an address".format(a), a == T.DELAY || a in 0..0xFFF)
        }
        // The plain-board branch of the radio: no external LNA / PA, no duplicate of one register back to back with a condition's value.
        assertTrue(find(T.RADIOA, 0x018).isNotEmpty())
        assertTrue(find(T.RADIOB, 0x018).isNotEmpty())
        assertNotNull(find(T.PHY_REG, 0x800).singleOrNull())
        assertEquals(0x8020D010.toInt(), find(T.PHY_REG, 0x800).single())
    }

    @Test
    fun thePowerOnSequenceEndsAndPollsTheReadyBits() {
        val steps = T.PWR_ON
        assertEquals(T.E, steps.last()[0])
        assertTrue("polls power ready (0x06 bit 1)", steps.any { it[0] == T.P && it[1] == 0x6 && it[2] == 0x2 })
        assertTrue("no PCIe-only step is in it", steps.none { it[1] == 0x301 })
        assertTrue(steps.size in 10..25)
    }

    @Test
    fun aChannelIsWrittenToBothRadioPaths() {
        for (ch in intArrayOf(1, 6, 13, 36, 149, 165)) {
            val ops = T.channelTune(ch, rfe = 0, cut = 2)
            fun chan(kind: Int) = ops.filter { it[0] == kind && it[1] == 0x18 && it[2] == 0xFF }.map { it[3] }
            assertEquals("path A channel $ch", listOf(ch), chan(T.RF))
            assertEquals("path B channel $ch", listOf(ch), chan(T.RFB))
            // 20 MHz on both paths.
            assertEquals(1, ops.count { it[0] == T.RF && it[1] == 0x18 && it[2] == 0xC00 && it[3] == 3 })
            assertEquals(1, ops.count { it[0] == T.RFB && it[1] == 0x18 && it[2] == 0xC00 && it[3] == 3 })
        }
    }

    @Test
    fun theBandSwitchFollowsTheChannel() {
        fun has(ops: Array<IntArray>, kind: Int, a: Int, mask: Int, v: Int) = ops.any { it[0] == kind && it[1] == a && it[2] == mask && it[3] == v }
        val g24 = T.channelTune(6, 0, 2)
        val g5 = T.channelTune(44, 0, 2)
        assertTrue("2.4 GHz AGC table", has(g24, T.BB, 0x82C, 0x3, 0)); assertTrue("5 GHz AGC table", has(g5, T.BB, 0x82C, 0x3, 1))
        assertTrue(has(g24, T.MAC8, 0x454, 0x80, 0)); assertTrue(has(g5, T.MAC8, 0x454, 0x80, 1))
        assertTrue("5 GHz sub-band", has(g5, T.BB, 0x860, 0x1FFE0000, 0x494))
        assertTrue("UNII-3", T.channelTune(149, 0, 2).any { it[0] == T.BB && it[1] == 0x860 && it[3] == 0x412 })
        assertTrue("RF mode for UNII-3", T.channelTune(149, 0, 2).any { it[0] == T.RF && it[1] == 0x18 && it[2] == 0x70300 && it[3] == 0x501 })
        assertTrue("RF mode for 2.4", has(g24, T.RF, 0x18, 0x70300, 0))
    }

    @Test
    fun receiveStopsDuringTheBandSwitchAndComesBackWithoutCckIn5Ghz() {
        fun rx(ops: Array<IntArray>) = ops.filter { it[0] == T.BB && it[1] == 0x808 && it[2] == 0x30000000 }.map { it[3] }
        assertEquals("2.4 GHz: off, then OFDM and CCK", listOf(0, 3), rx(T.channelTune(6, 0, 2)))
        assertEquals("5 GHz: off, then OFDM only", listOf(0, 2), rx(T.channelTune(44, 0, 2)))
    }

    @Test
    fun cckSignalUsesThe8812aSteps() {
        assertEquals(-100, T.cckRssi(7, 30)); assertEquals(-90, T.cckRssi(7, 22))
        assertEquals(-48, T.cckRssi(6, 2)); assertEquals(-24, T.cckRssi(3, 7)); assertEquals(14, T.cckRssi(0, 0))
    }

    @Test
    fun theAntennaPinsFollowTheBoardType() {
        fun pinmux(rfe: Int, g5: Boolean) = T.rfePins(rfe, g5).first { it[1] == T.RFE_PINMUX_A }[3]
        assertEquals(0x77777777, pinmux(0, false)); assertEquals(0x77337717, pinmux(0, true))
        assertEquals(0x54337770, pinmux(3, false)); assertEquals(0x77337777, pinmux(2, true))
        assertEquals(0, T.rfePins(9, true).size)
        // Type 3 also switches the antenna select line.
        assertTrue(T.rfePins(3, false).any { it[1] == 0x900 })
    }

    @Test
    fun theCCutHasItsOwnSpurFix() {
        fun spur(cut: Int, ch: Int) = T.channelTune(ch, 0, cut).filter { it[0] == T.BB && it[1] == 0x8C4 && it[2] == 0x40000000 }.map { it[3] }
        // Older cuts leave the FIFO clock to the bandwidth step (always off at 20 MHz); the C cut turns it on for 2472 / 2484 MHz.
        assertTrue(spur(0, 13).all { it == 0 })
        assertEquals(1, spur(2, 13).last()); assertEquals(0, spur(2, 6).last())
    }

    // ---- the efuse ---------------------------------------------------------------------------

    /** Raw efuse bytes: a header for section [offset] with the words in [words] enabled, then those words. */
    private fun pg(offset: Int, vararg words: Pair<Int, Int>): List<Int> {
        var wren = 0xF
        for ((w, _) in words) wren = wren and (1 shl w).inv()
        val out = mutableListOf((offset shl 4) or wren)
        for ((w, v) in words.sortedBy { it.first }) { out += v and 0xFF; out += v shr 8 }
        return out
    }

    private fun logical(raw: List<Int>): IntArray = Rtl8812auMonitor.EfuseMap.logical(512) { raw.getOrElse(it) { 0xFF } }

    @Test
    fun theEfuseMapCollectsWordsFromTheirHeaders() {
        // Section 12 (bytes 0x60..0x67) word 1 = bytes 0x62, 0x63.
        val map = logical(pg(12, 1 to 0xBEEF))
        assertEquals(0xEF, map[0x62]); assertEquals(0xBE, map[0x63])
        assertEquals("what is not written stays 0xFF", 0xFF, map[0x60])
    }

    @Test
    fun theRfeTypeByteIsFoundThroughAnExtendedHeader() {
        // Offset 0xCA is in section 25 (0xC8..0xCF), word 1 - section 25 needs an extended header: low 3 bits | next nibble.
        val section = 25
        val ext = listOf(((section and 0x7) shl 5) or 0x0F, ((section shr 3) shl 4) or (0xF and (1 shl 1).inv()))
        val map = logical(ext + listOf(0x02, 0xFF))
        assertEquals(0x02, map[0xCA])
    }

    @Test
    fun anEmptyEfuseIsAllOnes() {
        val map = logical(emptyList())
        assertEquals(512, map.size)
        assertTrue(map.all { it == 0xFF })
    }

    // ---- the adapters ------------------------------------------------------------------------

    @Test
    fun theUsbIdsOfRtl8812auAdaptersAreSupported() {
        for ((v, p) in listOf(0x0bda to 0x8812, 0x0bda to 0x881a, 0x2357 to 0x0101, 0x2357 to 0x0103, 0x0b05 to 0x17d2, 0x7392 to 0xa822)) {
            val chip = UsbWifi.chipOf(v, p)
            assertNotNull("%04x:%04x".format(v, p), chip)
            assertEquals(UsbWifi.Driver.RTL8812AU, chip!!.driver)
        }
        // The neighbours keep their own drivers.
        assertEquals(UsbWifi.Driver.RTL88XXAU, UsbWifi.chipOf(0x0bda, 0x0811)!!.driver)
        assertEquals(UsbWifi.Driver.RTL8814AU, UsbWifi.chipOf(0x0bda, 0x8813)!!.driver)
        assertEquals(UsbWifi.Driver.RTL88X2BU, UsbWifi.chipOf(0x0bda, 0xb812)!!.driver)
        assertNull(UsbWifi.chipOf(0x0bda, 0xc811))
    }
}
