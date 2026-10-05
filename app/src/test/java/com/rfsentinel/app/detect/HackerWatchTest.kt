package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HackerWatchTest {
    @Before fun reset() = HackerWatch.reset()

    private fun ble(mac: String, name: String? = null, uuids: List<Int> = emptyList(), mfr: Map<Int, ByteArray> = emptyMap()) =
        Advert(mac = mac, source = Advert.Source.BLE, rssi = -60, name = name,
            serviceUuids = uuids.map { Advert.uuid16(it) }, manufacturerData = mfr, timestamp = 0L)

    private fun ap(mac: String, ssid: String, caps: String, ies: List<Pair<Int, ByteArray>> = emptyList()) =
        Advert(mac = mac, source = Advert.Source.WIFI, rssi = -60, name = ssid,
            wifi = Advert.WifiInfo(2437, caps, null, ies), timestamp = 0L)

    @Test
    fun skimmerAndFlipperSignatures() {
        assertEquals(Category.SKIMMER, SignatureEngine.classify(ble("AA:00:00:00:00:01", "HC-05")).first().category)
        assertEquals(90, SignatureEngine.classify(ble("AA:00:00:00:00:02", uuids = listOf(0x3082))).first().confidence)
        assertEquals("Flipper Zero", SignatureEngine.classify(ble("AA:00:00:00:00:03", mfr = mapOf(0x0E29 to ByteArray(2)))).first().label)
        assertTrue(SignatureEngine.classify(ble("AA:00:00:00:00:04", mfr = mapOf(0x0FBA to ByteArray(2)))).none { it.category == Category.HACKER })
    }

    @Test
    fun pwnagotchiAndPineapple() {
        val ie = 221 to "{\"name\":\"pwny\",\"pwnd_tot\":12}".toByteArray()
        val h = SignatureEngine.classify(ap("DE:AD:BE:EF:00:01", "x", "[ESS]", listOf(ie))).first()
        assertEquals("Pwnagotchi (WiFi handshake grabber): pwny", h.label)
        assertTrue(SignatureEngine.classify(ap("00:11:22:33:44:55", "Pineapple_1A2B", "[ESS]")).any { it.label == "WiFi Pineapple" })
    }

    @Test
    fun evilTwinNeedsDifferentMakerAndSecurity() {
        assertNull(HackerWatch.evilTwin(ap("00:11:22:00:00:01", "CoffeeShop", "[WPA2-PSK-CCMP][ESS]")))
        assertNull(HackerWatch.evilTwin(ap("00:11:22:00:00:02", "CoffeeShop", "[ESS]"))) // same maker: second band / mesh
        assertNotNull(HackerWatch.evilTwin(ap("66:77:88:00:00:03", "CoffeeShop", "[ESS]")))
        assertNull(HackerWatch.evilTwin(ap("99:88:77:00:00:04", "Home", "[WPA2-PSK-CCMP][ESS]")))
        assertNull(HackerWatch.evilTwin(ap("12:34:56:00:00:05", "Home", "[WPA2-PSK-CCMP][ESS]"))) // both protected
    }

    @Test
    fun spamFloodAfterManyNewPopups() {
        val popup = mapOf(0x004C to byteArrayOf(0x0F, 0x05))
        var hit: Hit? = null
        for (i in 0 until HackerWatch.SPAM_THRESHOLD) hit = HackerWatch.bleSpam(ble("AA:BB:CC:00:00:%02X".format(i), mfr = popup), 1_000L + i * 100)
        assertNotNull(hit)
        assertNull(HackerWatch.bleSpam(ble("AA:BB:CC:00:01:00", mfr = popup), 10_000L)) // one alert, then quiet
        assertNull(HackerWatch.bleSpam(ble("AA:BB:CC:00:01:01"), 10_000L))              // not a pop-up
    }
}
