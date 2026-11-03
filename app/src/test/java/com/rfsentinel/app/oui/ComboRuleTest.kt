package com.rfsentinel.app.oui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboRuleTest {

    private fun facts(name: String? = null, vendor: String = "Panasonic Connect", band: String? = "5", rssi: Int = -60) =
        ComboRule.Facts(name, listOf(vendor), band, setOf("shape-0a1b2c3d"), "Laptop", rssi)

    @Test
    fun keyRoundTripsAndEveryConditionMustHold() {
        val rule = ComboRule.parse("all:vendor~panasonic & noname & band=5 & probe=shape-0A1B2C3D & type~lap & rssi>=-70")!!
        assertEquals(rule, ComboRule.parse(rule.key))
        assertTrue(rule.matches(facts()))
        assertFalse(rule.matches(facts(name = "CORP-WIFI")))     // has a name
        assertFalse(rule.matches(facts(band = "2.4")))           // other band
        assertFalse(rule.matches(facts(vendor = "Apple")))       // other maker
        assertFalse(rule.matches(facts(rssi = -80)))             // too weak
        assertEquals("Maker contains \"panasonic\" · no name · 5 GHz · probe shape 0a1b2c3d · type contains \"lap\" · signal -70 dBm or stronger",
            rule.describe())
    }

    @Test
    fun nameConditions() {
        val rule = ComboRule.parse("all:name^Flock & vendor~panasonic")!!
        assertTrue(rule.matches(facts(name = "Flock-12AB")))
        assertFalse(rule.matches(facts(name = "My Flock")))
        assertTrue(ComboRule.parse("all:name~flock")!!.matches(facts(name = "My Flock")))
    }

    @Test
    fun badRulesAreRefused() {
        assertNull(ComboRule.parse("all:"))
        assertNull(ComboRule.parse("all:vendor~"))
        assertNull(ComboRule.parse("all:band=9"))
        assertNull(ComboRule.parse("all:rssi>=loud"))
        assertNull(ComboRule.parse("all:colour=red"))
        assertNull(ComboRule.parse("name:Flock"))
        assertTrue(OuiWatchlist.isValidKey("all:noname & band=bt"))
        assertFalse(OuiWatchlist.isValidKey("all:nonsense"))
    }

    @Test
    fun bands() {
        assertEquals("bt", ComboRule.band(true, null))
        assertEquals("2.4", ComboRule.band(false, 2437))
        assertEquals("5", ComboRule.band(false, 5180))
        assertEquals("6", ComboRule.band(false, 5955))
        assertNull(ComboRule.band(false, null))
    }
}
