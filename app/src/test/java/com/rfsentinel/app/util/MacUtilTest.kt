package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacUtilTest {

    @Test
    fun normalize_uppercasesAndConvertsDashes() {
        assertEquals("00:25:DF:12:34:AB", MacUtil.normalize(" 00-25-df-12-34-ab "))
    }

    @Test
    fun oui_takesFirstThreeOctets() {
        assertEquals("00:25:DF", MacUtil.oui("00:25:df:12:34:ab"))
    }

    @Test
    fun validation() {
        assertTrue(MacUtil.isValidMac("aa-bb-cc-dd-ee-ff"))
        assertFalse(MacUtil.isValidMac("AA:BB:CC"))
        assertFalse(MacUtil.isValidMac("GG:BB:CC:DD:EE:FF"))
        assertTrue(MacUtil.isValidOui("d4-2d-c5"))
        assertFalse(MacUtil.isValidOui("D4:2D:C5:00"))
        assertFalse(MacUtil.isValidOui("D42DC5"))
    }

    @Test
    fun ieeeBlockPrefixes() {
        assertTrue(MacUtil.isValidBlock("00:25:DF"))       // MA-L 24-bit
        assertTrue(MacUtil.isValidBlock("EC:5B:CD:E"))     // MA-M 28-bit
        assertTrue(MacUtil.isValidBlock("8c-1f-64-df-0"))  // MA-S 36-bit (Cyberkar), any case/separator
        assertFalse(MacUtil.isValidBlock("8C:1F:64:DF"))   // 32 bits is not an IEEE block size
        assertFalse(MacUtil.isValidBlock("00:25:DF:11:22:33"))
        assertEquals("8C1F64DF0", MacUtil.hex("8c:1f:64:df:0"))
    }

    @Test
    fun randomizedAddressDetection() {
        assertFalse(MacUtil.isRandomized("00:25:DF:12:34:56")) // Axon, globally unique
        assertTrue(MacUtil.isRandomized("DA:A1:19:00:00:01"))  // 0xDA has the 0x02 bit set
        assertTrue(MacUtil.isRandomized("02:00:00:00:00:00"))
        assertFalse(MacUtil.isRandomized("zz"))
    }

    @Test
    fun proximityBands() {
        assertEquals("Near", ProximityUtil.band(-55))
        assertEquals("Nearby", ProximityUtil.band(-75))
        assertEquals("Far", ProximityUtil.band(-95))
    }
}
