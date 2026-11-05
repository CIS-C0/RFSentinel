package com.rfsentinel.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MT7610U: USB IDs, the tables taken from the Linux driver, and the EEPROM maths. */
class Mt7610uDriverTest {

    @Test
    fun usbIds() {
        assertEquals(UsbWifi.Driver.MT7610U, UsbWifi.chipOf(0x0e8d, 0x7610)?.driver)
        assertEquals(UsbWifi.Driver.MT7610U, UsbWifi.chipOf(0x2357, 0x0105)?.driver) // Archer T1U
        assertEquals("Linux lists it for both chips; it stays on the MT7612U", UsbWifi.Driver.MT7612U, UsbWifi.chipOf(0x7392, 0xb711)?.driver)
        assertEquals("mt7610u.bin", Firmware.needed(UsbWifi.Driver.MT7610U).single().name)
    }

    @Test
    fun autoResponderStaysOff() {
        val mac = Mt7610uTables.MAC
        val i = (mac.indices step 2).single { mac[it] == 0x1404 }
        assertEquals(0, mac[i + 1] and 1)
    }

    @Test
    fun everyHopChannelHasARow() {
        val have = Mt7610uTables.CHANNELS.associateBy { it[0] }
        val hop = (1..14) + listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
        for (ch in hop) assertEquals("channel $ch", 21, have[ch]?.size)
        assertTrue(have.values.all { it[1] and (if (it[0] <= 14) 0x100 else 0x200) != 0 })
    }

    @Test
    fun freqOffset() {
        assertEquals(0x1b, Mt7612uMonitor.x0FreqOffset(0x20, 0x85)) // top bit set: positive, subtracted
        assertEquals(0x25, Mt7612uMonitor.x0FreqOffset(0x20, 0x05))
        assertEquals(0, Mt7612uMonitor.x0FreqOffset(0xff, 0xff)) // unwritten
    }

    @Test
    fun agcGainLoweredByTwiceTheLna() {
        assertEquals(0x12341a78, Mt7612uMonitor.x0AgcGain(0x12341e78, 2))
        assertEquals(0x12347f78, Mt7612uMonitor.x0AgcGain(0x12340178, 1)) // wraps within the 7-bit field, as Linux does
    }
}
