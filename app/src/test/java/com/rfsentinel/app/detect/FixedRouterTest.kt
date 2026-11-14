package com.rfsentinel.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedRouterTest {

    private fun hit(label: String) = Hit(Category.PUBLIC_SAFETY, label, 40, "", "")
    private val router = hit("Cradlepoint vehicle router - police cars, buses, stores, traffic-signal cabinets")
    private val axon = hit("Police car network - Axon Fleet in-car video")

    private fun spot(mac: String, lat: Double, lon: Double, d: Double = 0.0) =
        com.rfsentinel.app.data.Spot(mac, lat, lon, lat - d, lon - d, lat + d, lon + d)
    private val printer = Hit(Category.MOBILE_PRINTER, "Zebra mobile printer", 40, "", "")

    @Test
    fun familiarOnlyNearItsUsualSpot() {
        val mac = "00:30:44:AA:BB:CC"
        FixedRouter.load(listOf(mac), listOf(spot(mac, 10.0, 20.0, 0.001)))
        assertTrue(FixedRouter.quiet(mac, listOf(router), lat = 10.002, lon = 20.0)) // ~220 m away
        assertFalse(FixedRouter.quiet(mac, listOf(router), lat = 10.05, lon = 20.0)) // ~5.5 km: it moved
        assertTrue(FixedRouter.quiet(mac, listOf(router))) // no fix: as before
        assertFalse(FixedRouter.quiet(mac, listOf(router, axon), lat = 10.0, lon = 20.0))
        // A printer learns its spot too; one logged all over town never does.
        FixedRouter.load(listOf("AC:3F:A4:00:00:01", "AC:3F:A4:00:00:02"),
            listOf(spot("ac:3f:a4:00:00:01", 10.0, 20.0), spot("AC:3F:A4:00:00:02", 10.0, 20.0, 0.05)))
        assertTrue(FixedRouter.quiet("AC:3F:A4:00:00:01", listOf(printer), lat = 10.0, lon = 20.0))
        assertFalse(FixedRouter.quiet("AC:3F:A4:00:00:02", listOf(printer), lat = 10.0, lon = 20.0))
        // A printer on open WiFi isn't a shop router.
        FixedRouter.load(emptyList())
        assertFalse(FixedRouter.quiet("AC:3F:A4:00:00:03", listOf(printer), "Public-2ce", "[ESS]"))
    }

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
