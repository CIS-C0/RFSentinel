package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

/** Signatures from Fieldwatch's catalogs 94-113 (OffGridPete, MIT): PTT buttons, ExpressLRS setup networks. */
class FieldwatchIdeasTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    @Before fun reset() = HackerWatch.reset()

    private fun ble(name: String? = null, uuids: List<UUID> = emptyList()) =
        Advert(mac = "5a:11:22:33:44:55", source = Advert.Source.BLE, rssi = -60, name = name, serviceUuids = uuids, timestamp = 0L)

    private fun wifi(ssid: String, client: Boolean = false) =
        Advert(mac = "02:12:34:56:78:9a", source = Advert.Source.WIFI, rssi = -60, name = ssid,
            wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP][ESS]", null, emptyList(), client = client), timestamp = 0L)

    private fun ptt(a: Advert) = SignatureEngine.classify(a).firstOrNull { it.label.startsWith("Push-to-talk") }

    @Test
    fun pushToTalkButtons() {
        assertEquals(30, ptt(ble("ASB99C2F0"))!!.confidence)
        assertEquals(Category.PUBLIC_SAFETY, ptt(ble(uuids = listOf(UUID.fromString("2320ae58-8394-4652-95f7-0a872ac0958f"))))!!.category)
        assertEquals(30, ptt(ble("PTT-Z01"))!!.confidence)
        assertNull(ptt(ble("ASBESTOS")))
        assertNull(ptt(ble("PTT-Zone")))
    }

    @Test
    fun expressLrsSetupNetwork() {
        val hit = SignatureEngine.classify(wifi("ExpressLRS TX Backpack")).first { it.category == Category.DRONE }
        assertEquals(50, hit.confidence)
        assertNull(SignatureEngine.classify(wifi("ExpressLRS RX", client = true)).firstOrNull { it.category == Category.DRONE })
    }
}
