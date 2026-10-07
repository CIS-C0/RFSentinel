package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlockNoiseTest {

    private val liteon = Hit(Category.ALPR, "Liteon module seen on Flock cameras (community list)", 35,
        "Address is in the watchlisted block 12:34:56", "flock-you")
    private val fsName = Hit(Category.ALPR, "Flock Safety device", 70,
        "Name pattern \"FS-<hex>\" (generic white-label prefix - verify)", "test")
    private val penguin = Hit(Category.ALPR, "Flock Safety device", 70, "Name pattern \"Penguin-<digits>\"", "test")

    @Test
    fun namedGadgetsLoseTheModulePrefixMatch() {
        for (name in listOf("PHANTOM", "ClickShare-Room", "SILENCE-1234567", "Brompton_Elec", "RGB smart bulb"))
            assertTrue(name, FlockNoise.filter(listOf(liteon), name, flockRegion = true).isEmpty())
        // No name, or a Flock-style one: still a (weak) clue.
        assertEquals(1, FlockNoise.filter(listOf(liteon), null, true).size)
        assertEquals(1, FlockNoise.filter(listOf(liteon), "Penguin-1234567890", true).size)
        assertEquals(1, FlockNoise.filter(listOf(liteon), "FS Ext Battery", true).size)
    }

    @Test
    fun outsideFlocksMarketOnlyStrongSignaturesCount() {
        assertTrue(FlockNoise.filter(listOf(liteon, fsName), null, flockRegion = false).isEmpty())
        assertEquals(listOf(penguin), FlockNoise.filter(listOf(liteon, fsName, penguin), "Penguin-1234567890", false))
        assertEquals(1, FlockNoise.filter(listOf(fsName), "FS-ABC123", flockRegion = true).size)
    }

    @Test
    fun djiOsmoIsAnActionCameraNotADrone() {
        val osmo = SignatureEngine.classify(Advert("4C:43:F6:11:22:33", Advert.Source.BLE, -70, "OsmoNano-AB12"))
        assertTrue(osmo.none { it.category == Category.DRONE })
        assertEquals("DJI Osmo camera", osmo.first { it.category == Category.OTHER_CAMERA }.label)
        val drone = SignatureEngine.classify(Advert("4C:43:F6:11:22:34", Advert.Source.BLE, -70, "Mavic"))
        assertTrue(drone.any { it.category == Category.DRONE })
    }
}
