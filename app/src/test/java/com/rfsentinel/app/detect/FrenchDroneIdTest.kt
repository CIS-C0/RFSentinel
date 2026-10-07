package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FrenchDroneIdTest {

    /** A beacon IE as the reference implementation (khancyr/droneID_FR) builds it, from the OUI on. */
    private fun ie(lat: Int, lon: Int, id: String = "ILLDEMO00000000000000000000001"): ByteArray {
        val o = ByteArrayOutputStream()
        fun tlv(t: Int, v: ByteArray) { o.write(t); o.write(v.size); o.write(v) }
        fun be(x: Int, n: Int) = ByteArray(n) { ((x shr (8 * (n - 1 - it))) and 0xFF).toByte() }
        o.write(byteArrayOf(0x6A, 0x5C, 0x35, 0x01))
        tlv(1, byteArrayOf(1))
        tlv(2, id.padEnd(30, '0').toByteArray())
        tlv(4, be(lat, 4)); tlv(5, be(lon, 4))
        tlv(6, be(-12, 2)); tlv(7, be(85, 2))
        tlv(8, be(1_040_000, 4)); tlv(9, be(-2_040_000, 4))
        tlv(10, byteArrayOf(7)); tlv(11, be(270, 2))
        return o.toByteArray()
    }

    @Test
    fun decodesFrenchElectronicId() {
        val b = ie(1_050_000, -2_050_000)
        assertTrue(RemoteId.isWifiIe(b))
        assertTrue(RemoteId.isFrenchIe(b))
        assertFalse(RemoteId.isAstmWifiIe(b))
        val i = RemoteId.decodeWifiIe(b, null)!!
        assertEquals("ILLDEMO00000000000000000000001", i.uasId)
        assertEquals("French ID (signalement électronique)", i.idType)
        assertEquals(10.5, i.latitude!!, 1e-6)
        assertEquals(-20.5, i.longitude!!, 1e-6)
        assertEquals(-12.0, i.altitudeGeoM!!, 0.0)
        assertEquals(85.0, i.heightM!!, 0.0)
        assertEquals(10.4, i.operatorLatitude!!, 1e-6)
        assertEquals(7.0, i.speedMs!!, 0.0)
        assertEquals(270, i.directionDeg)
    }

    @Test
    fun negativeCoordinatesAndNoFix() {
        val west = RemoteId.decodeWifiIe(ie(-2_050_000, -3_050_000), null)!!
        assertEquals(-20.5, west.latitude!!, 1e-6)
        assertEquals(-30.5, west.longitude!!, 1e-6)
        val noFix = RemoteId.decodeWifiIe(ie(0, 0), west)!!
        assertEquals(-20.5, noFix.latitude!!, 1e-6)
    }

    @Test
    fun otherVendorIesAreIgnored() {
        assertFalse(RemoteId.isWifiIe(byteArrayOf(0x6A, 0x5C, 0x35, 0x02, 0x01)))
        assertNull(RemoteId.decodeWifiIe(byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04), null))
    }
}
