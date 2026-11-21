package com.rfsentinel.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {
    @Test
    fun namesStartingWithTheSearchRankFirst() {
        val m = { name: String, q: String -> SettingsActivity.matchScore(name, q) }
        assertEquals(3, m("Waze reports", "waze"))
        assertEquals(3, m("▾  Waze status", "waze")) // a header's fold arrow doesn't count
        assertEquals(2, m("Default zoom: 17 · street level, close to Waze", "waze"))
        assertEquals(2, m("Waze-like zoom", "zoom"))
        assertEquals(1, m("Fullscreen", "screen"))
        assertEquals(0, m("Units", "waze"))
    }
}
