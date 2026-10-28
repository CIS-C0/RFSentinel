package com.rfsentinel.app.usb

import com.rfsentinel.app.service.AdvertFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** USB Bluetooth: advertising events, the Realtek epatch, and a report turned into an advert. */
class UsbBluetoothParseTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    // Name "RFS" and maker 0x1234 data 01 02.
    private val ad = bytes(4, 0x09, 'R'.code, 'F'.code, 'S'.code, 5, 0xFF, 0x34, 0x12, 0x01, 0x02)

    @Test
    fun legacyReport() {
        val e = bytes(0x3E, 0, 0x02, 1, 0x00, 0x00, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, ad.size) + ad + bytes(0xC4)
        val r = UsbBluetooth.parse(e).single()
        assertEquals("11:22:33:44:55:66", r.mac)
        assertEquals(-60, r.rssi)
        assertTrue(r.connectable)
        assertEquals("LE 1M (legacy)", r.phy)
        assertArrayEquals(ad, r.data)
    }

    @Test
    fun extendedCodedReport() {
        val e = bytes(0x3E, 0, 0x0D, 1,
            0x00, 0x00,                         // event type: not connectable, data complete
            0x01,                               // random address
            0x06, 0x05, 0x04, 0x03, 0x02, 0xC1,
            0x03, 0x00, 0xFF, 0x7F, 0xB0,       // Coded primary, no secondary, SID, TX, RSSI -80
            0x00, 0x00, 0x00, 0, 0, 0, 0, 0, 0, // interval, direct address
            ad.size) + ad
        val r = UsbBluetooth.parse(e).single()
        assertEquals("C1:02:03:04:05:06", r.mac)
        assertEquals(-80, r.rssi)
        assertFalse(r.connectable)
        assertEquals("LE Coded (long range)", r.phy)
    }

    @Test
    fun truncatedEventIsDropped() {
        val e = bytes(0x3E, 0, 0x02, 1, 0x00, 0x00, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 30, 1, 2)
        assertTrue(UsbBluetooth.parse(e).isEmpty())
    }

    @Test
    fun reportToAdvert() {
        val r = UsbBluetooth.Report(bytes(0x66, 0x55, 0x44, 0x33, 0x22, 0x11), 0, -60, ad, true, "LE 1M (legacy)")
        val a = AdvertFactory.fromHci(r, now = 1L)
        assertEquals("11:22:33:44:55:66", a.mac)
        assertEquals("RFS", a.name)
        assertArrayEquals(bytes(0x01, 0x02), a.manufacturerData[0x1234])
    }

    /** A one-patch v1 epatch for project 1 (RTL8723B), ROM version 1, with its 6-byte patch at offset 22. */
    private val epatch = "Realtech".toByteArray() +
        bytes(0x01, 0x02, 0x03, 0x04, 0x01, 0x00,  // fw_version, one patch
            0x02, 0x00, 0x06, 0x00, 22, 0, 0, 0,   // chip id (ROM + 1), length, offset
            0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,    // the patch
            0x01, 0x01, 0x00,                      // project 1 (read backwards: opcode 0, length 1)
            0x51, 0x04, 0xFD, 0x77)

    @Test
    fun realtekPatchGetsTheFileVersion() {
        assertArrayEquals(bytes(0xAA, 0xBB, 0x01, 0x02, 0x03, 0x04), BtRealtek.parse(epatch, 0x8723, 1))
    }

    @Test(expected = IOException::class)
    fun realtekWrongChipRefused() { BtRealtek.parse(epatch, 0x8821, 1) }

    @Test(expected = IOException::class)
    fun realtekNoPatchForRom() { BtRealtek.parse(epatch, 0x8723, 5) }
}
