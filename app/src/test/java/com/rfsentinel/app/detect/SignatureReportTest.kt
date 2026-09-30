package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureReportTest {

    private fun ble(mac: String, name: String?) = Advert(
        mac = mac, source = Advert.Source.BLE, rssi = -60, name = name,
        manufacturerData = mapOf(0x0A12 to byteArrayOf(0x01, 0x02, 0x33, 0x44, 0x55, 0x66)),
        serviceUuids = listOf(Advert.uuid16(0x180F)),
        addressType = AddressType.PUBLIC, connectable = true
    )

    @Test
    fun namePatternHidesSerialsButKeepsModelDigits() {
        assertEquals("ABCDE#########", SignatureReport.namePattern("ABCDE123456789"))
        assertEquals("Forerunner 265", SignatureReport.namePattern("Forerunner 265"))
    }

    @Test
    fun keepsSignatureFieldsAndDropsIdentifyingOnes() {
        val r = SignatureReport.build(ble("10:20:30:AB:CD:EF", "UNIT-0042"), "Acme", "Printer",
            randomized = false, userNote = "ticket printer", appVersion = "test")
        assertTrue(r.contains("\"oui\": \"10:20:30\""))
        assertTrue(r.contains("UNIT-####"))
        assertTrue(r.contains("0x0A12"))
        assertTrue(r.contains("\"header\": \"0102\""))
        assertTrue(r.contains("0x180F"))
        assertTrue(r.contains("ticket printer"))
        assertFalse("full address leaked", r.contains("AB:CD:EF"))
        assertFalse("serial leaked", r.contains("0042"))
        assertFalse("payload beyond header leaked", r.contains("3344"))
    }

    @Test
    fun randomizedAddressHasNoPrefix() {
        val r = SignatureReport.build(ble("DA:20:30:AB:CD:EF", null), null, null,
            randomized = true, userNote = null, appVersion = "test")
        assertFalse(r.contains("\"oui\""))
        assertFalse(r.contains("DA:20:30"))
    }
}
