package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietIdentityTest {

    @Test
    fun aKindTurnedOffStillNamesTheDevice() {
        val plain = DeviceIntel.Identity("Smart-home / camera device", listOf("Vendor" to "Arlo"))
        val hit = Hit(Category.NETWORK_CAMERA, "Arlo camera base station", 88, "WiFi network \"ARLO_VMB_1\"", "test")
        val named = DeviceIntel.quietlyIdentified(plain, hit)
        assertEquals("Arlo camera base station", named.type)
        assertTrue(named.facts.containsAll(plain.facts))
        assertTrue(named.facts.any { it.second == hit.evidence })
        // More facts than before, so the registry keeps it over the plain identity.
        assertTrue(named.facts.size > plain.facts.size)
    }
}
