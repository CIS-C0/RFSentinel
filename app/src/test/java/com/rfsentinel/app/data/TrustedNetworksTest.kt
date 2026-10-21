package com.rfsentinel.app.data

import com.rfsentinel.app.data.TrustedNetworks.Match
import com.rfsentinel.app.data.TrustedNetworks.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedNetworksTest {

    // Made-up network names.
    private val rules = listOf(Rule("FLEET", Match.CONTAINS), Rule("Depot WiFi", Match.EXACT), Rule("Metro-", Match.STARTS))

    @Test
    fun keywordMatchesAnyPartOfTheNameWhateverTheCase() {
        assertTrue(TrustedNetworks.matches(rules, "FLEET-Bus-123"))
        assertTrue(TrustedNetworks.matches(rules, "WiFi-fleet"))
        assertTrue(TrustedNetworks.matches(rules, "myFleetNet"))
    }

    @Test
    fun startMatchesOnlyTheBeginningOfTheName() {
        assertTrue(TrustedNetworks.matches(rules, "Metro-Bus-42"))
        assertTrue(TrustedNetworks.matches(rules, "metro-rail"))
        assertFalse(TrustedNetworks.matches(rules, "MyMetro-Bus"))
        assertFalse(TrustedNetworks.matches(rules, "Metro Bus"))
    }

    @Test
    fun aRuleSavedWithoutAMatchTypeMatchesByContaining() {
        assertTrue(TrustedNetworks.matches(listOf(Rule("FLEET", null)), "Bus-fleet-3"))
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
