package com.rfsentinel.app.data

import com.rfsentinel.app.data.TrustedNetworks.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedNetworksTest {

    // Made-up network names.
    private val rules = listOf(Rule("FLEET", exact = false), Rule("Depot WiFi", exact = true))

    @Test
    fun keywordMatchesAnyPartOfTheNameWhateverTheCase() {
        assertTrue(TrustedNetworks.matches(rules, "FLEET-Bus-123"))
        assertTrue(TrustedNetworks.matches(rules, "WiFi-fleet"))
        assertTrue(TrustedNetworks.matches(rules, "myFleetNet"))
    }

    @Test
    fun exactNameMatchesOnlyTheWholeName() {
        assertTrue(TrustedNetworks.matches(rules, "depot wifi"))
        assertTrue(TrustedNetworks.matches(rules, " Depot WiFi "))
        assertFalse(TrustedNetworks.matches(rules, "Depot WiFi 2"))
    }

    @Test
    fun otherAndHiddenNetworksAreNotTrusted() {
        assertFalse(TrustedNetworks.matches(rules, "Patrol-7"))
        assertFalse(TrustedNetworks.matches(rules, ""))
        assertFalse(TrustedNetworks.matches(rules, null))
        assertFalse(TrustedNetworks.matches(emptyList(), "FLEET-Bus-123"))
    }
}
