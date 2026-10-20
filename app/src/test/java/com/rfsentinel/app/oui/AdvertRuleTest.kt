package com.rfsentinel.app.oui

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.AdvertFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "adv:" rules: a Bluetooth device's advert pattern, learned with "I saw it", whatever its address. */
class AdvertRuleTest {

    private fun ble(mac: String, name: String? = "BWC-1234", mfg: Map<Int, ByteArray> = mapOf(0x0A1B to ByteArray(6))) =
        Advert(mac = mac, source = Advert.Source.BLE, rssi = -60, name = name, manufacturerData = mfg)

    @Test
    fun advertRulesAreValidKeys() {
        assertTrue(OuiWatchlist.isValidKey("adv:1a2b3c4d"))
        assertTrue(!OuiWatchlist.isValidKey("adv: "))
        assertEquals("adv:1a2b3c4d", OuiWatchlist.normalizeKey("ADV: 1A2B3C4D "))
        assertTrue(OuiEntry("adv:1a2b3c4d", "x").isAdvertRule)
        assertTrue(!OuiEntry("adv:1a2b3c4d", "x").isFingerprintRule)
    }

    @Test
    fun theKeyStaysTheSameWhenTheAddressChanges() {
        val k = AdvertFingerprint.key(ble("C1:11:22:33:44:55"))!!
        assertTrue(k, Regex("[0-9a-f]{8}").matches(k))
        assertEquals(k, AdvertFingerprint.key(ble("D2:66:77:88:99:AA")))
        assertNotEquals(k, AdvertFingerprint.key(ble("C1:11:22:33:44:55", name = "Other")))
    }

    @Test
    fun anAdvertThatSaysNothingHasNoKey() {
        assertNull("Apple-only adverts: every iPhone looks alike", AdvertFingerprint.key(ble("C1:11:22:33:44:55", null, mapOf(0x004C to ByteArray(10)))))
        assertNull("WiFi has none", AdvertFingerprint.key(Advert(mac = "C1:11:22:33:44:55", source = Advert.Source.WIFI, rssi = -60, name = "x")))
    }

    @Test
    fun aLearnedPatternIsAProbableMatchNotAStrongOne() {
        val e = OuiEntry("adv:1a2b3c4d", "Pole camera", OuiEntry.LEARNED, "custom", com.rfsentinel.app.ui.DeviceActions.LEARNED_SCORE, "ALPR")
        val hit = e.toHit("x")
        assertEquals(60, hit.confidence)
        assertEquals(com.rfsentinel.app.detect.Category.ALPR, hit.category)
        assertEquals("an exact address stays certain", 100, OuiEntry("AA:BB:CC:DD:EE:FF", "Pole camera", OuiEntry.LEARNED, "custom").effectiveScore)
    }
}
