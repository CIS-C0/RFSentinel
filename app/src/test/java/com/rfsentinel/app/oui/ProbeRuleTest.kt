package com.rfsentinel.app.oui

import com.rfsentinel.app.detect.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRuleTest {

    @Test
    fun probeRulesAreValidKeys() {
        assertTrue(OuiWatchlist.isValidKey("probe:PD-MDT"))
        assertFalse(OuiWatchlist.isValidKey("probe:  "))
        assertEquals("probe:PD MDT", OuiWatchlist.normalizeKey("PROBE: PD MDT "))
        assertTrue(OuiEntry("probe:x", "x").isProbeRule)
        assertEquals("fp:a1b2c3d4", OuiWatchlist.normalizeKey("FP:A1B2C3D4"))
        assertTrue(OuiEntry("fp:a1b2c3d4", "x").isFingerprintRule)
    }

    @Test
    fun aRequestForAWatchedNameIsAnAlertHit() {
        val rule = OuiEntry("probe:PD-MDT", "Police laptop", "user-added", "custom")
        val rules = mapOf("pd-mdt" to rule)
        val hits = OuiWatchlist.probeHits(rules, listOf("Starbucks", "pd-mdt"))
        assertEquals(1, hits.size)
        assertEquals("Police laptop", hits[0].label)
        assertEquals(Category.CUSTOM, hits[0].category)
        assertEquals(100, hits[0].confidence)
        assertTrue(hits[0].evidence.contains("pd-mdt"))
        assertTrue(OuiWatchlist.probeHits(rules, listOf("PD-MDT2")).isEmpty()) // exact name only
    }
}
