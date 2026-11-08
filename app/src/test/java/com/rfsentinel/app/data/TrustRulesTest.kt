package com.rfsentinel.app.data

import com.rfsentinel.app.data.TrustRules.Facts
import com.rfsentinel.app.data.TrustRules.Kind
import com.rfsentinel.app.data.TrustRules.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustRulesTest {

    // Made-up addresses and names.
    private fun printer(mac: String = "02:11:22:33:44:55", name: String? = "PRN-000001", labels: List<String> = listOf("Zebra printer")) =
        Facts(mac, name, "Zebra Technologies", labels)

    @Test
    fun ignoreAllLikeThisTrustsEveryDeviceWithTheSameMatch() {
        val like = Rule(Kind.LABEL, "zebra printer")
        assertTrue(TrustRules.matches(like, printer()))
        assertTrue(TrustRules.matches(like, printer(mac = "0A:BB:CC:DD:EE:FF", name = null)))
        assertFalse(TrustRules.matches(like, printer(labels = listOf("Zebra device (mobile printer or handheld)"))))
    }

    @Test
    fun theWatchlistsWaysWorkTheOtherWayRound() {
        val f = printer(mac = "AC:23:3F:12:34:56")
        assertTrue(TrustRules.matches(Rule(Kind.PREFIX, "AC:23:3F"), f))
        assertTrue(TrustRules.matches(Rule(Kind.PREFIX, "AC:23:3F:1"), f))
        assertFalse(TrustRules.matches(Rule(Kind.PREFIX, "AC:23:3F:2"), f))
        assertTrue(TrustRules.matches(Rule(Kind.STARTS, "prn-"), f))
        assertTrue(TrustRules.matches(Rule(Kind.CONTAINS, "N-00"), f))
        assertTrue(TrustRules.matches(Rule(Kind.VENDOR, "zebra"), f))
        assertTrue(TrustRules.matches(Rule(Kind.ADVERT, "1A2B3C4D"), Facts("x", null, null, emptyList(), advert = { "1a2b3c4d" })))
        assertTrue(TrustRules.matches(Rule(Kind.FINGERPRINT, "deadbeef"), Facts("x", null, null, emptyList(), fingerprint = { "DEADBEEF" })))
        assertFalse(TrustRules.matches(Rule(Kind.ADVERT, "1a2b3c4d"), Facts("x", null, null, emptyList())))
    }

    @Test
    fun aPrefixShorterThanAMakerBlockNeverMatches() {
        assertFalse(TrustRules.matches(Rule(Kind.PREFIX, "AC:23"), printer(mac = "AC:23:3F:12:34:56")))
    }

    @Test
    fun aSightingWithoutTheNameKeepsANameRulesTrust() {
        val rule = Rule(Kind.STARTS, "PRN-")
        val rules = listOf(rule)
        // A scan response without the name: still trusted.
        assertEquals(rule, TrustRules.decide(rules, rule, printer(name = null, labels = emptyList())))
        // Its name changed: no longer trusted.
        assertNull(TrustRules.decide(rules, rule, printer(name = "Other")))
        // The rule was removed: no longer trusted, even without the name.
        assertNull(TrustRules.decide(emptyList(), rule, printer(name = null)))
    }

    @Test
    fun aMatchRuleLetsGoOnlyWhenTheDeviceIsMatchedAsSomethingElse() {
        val rule = Rule(Kind.LABEL, "Zebra printer")
        val rules = listOf(rule)
        assertEquals(rule, TrustRules.decide(rules, rule, printer(labels = emptyList())))
        assertNull(TrustRules.decide(rules, rule, printer(labels = listOf("Tracker"))))
    }
}
