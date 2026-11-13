package com.rfsentinel.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedRouterTest {

    private fun hit(label: String) = Hit(Category.PUBLIC_SAFETY, label, 40, "", "")
    private val router = hit("Cradlepoint vehicle router - police cars, buses, stores, traffic-signal cabinets")
    private val axon = hit("Police car network - Axon Fleet in-car video")

    @Test
    fun familiarRouterOnlyIsQuiet() {
        FixedRouter.load(listOf("00:30:44:aa:bb:cc"))
        assertTrue(FixedRouter.quiet("00:30:44:AA:BB:CC", listOf(router)))
        // Something else backs it: it still alerts.
        assertFalse(FixedRouter.quiet("00:30:44:AA:BB:CC", listOf(router, axon)))
        // A router not heard often: alerts.
        assertFalse(FixedRouter.quiet("00:30:44:11:22:33", listOf(router)))
        assertFalse(FixedRouter.routerOnly(emptyList()))
    }

    @Test
    fun openOrBranchRouterNetworkIsQuiet() {
        FixedRouter.load(emptyList())
        val mac = "00:30:44:3E:B2:CF"
        // Open WiFi: passenger or shop WiFi.
        assertTrue(FixedRouter.quiet(mac, listOf(router), "Public-2ce", "[ESS]"))
        // A branch / kiosk Cradlepoint on its default name.
        assertTrue(FixedRouter.quiet(mac, listOf(router), "E100-67c-5g", "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertTrue(FixedRouter.quiet(mac, listOf(router), "AER2200-1f0", "[RSN-PSK-CCMP][ESS]"))
        // A vehicle model's default name, a protected network, a monitor-mode sighting (security unread): alerts.
        assertFalse(FixedRouter.quiet(mac, listOf(router), "IBR900-3ab", "[RSN-PSK-CCMP][ESS]"))
        assertFalse(FixedRouter.quiet(mac, listOf(router), "GROUPE-TCI", "[WPA2-PSK-CCMP][ESS]"))
        assertFalse(FixedRouter.quiet(mac, listOf(router), "Public-2ce", ""))
        // Open, but backed by a police network name: alerts.
        assertFalse(FixedRouter.quiet(mac, listOf(router, axon), "Axon12-5g", "[ESS]"))
    }
}
