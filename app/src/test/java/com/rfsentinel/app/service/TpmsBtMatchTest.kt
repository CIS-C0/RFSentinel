package com.rfsentinel.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsBtMatchTest {

    private val min = 60_000L

    @Test fun secondEncounterAfterAGapMatches() {
        val m = TpmsBtMatch()
        assertTrue(m.heard("tpms:a:1", listOf("BT1"), 0, 0.0, 0.0).isEmpty())
        assertTrue(m.heard("tpms:a:1", listOf("BT1"), 5 * min, 0.0, 0.0).isEmpty()) // same encounter
        val hit = m.heard("tpms:a:1", listOf("BT1"), 60 * min, 0.0, 0.0)
        assertEquals(listOf("BT1"), hit.map { it.mac })
        assertEquals(2, hit[0].encounters)
        assertTrue(m.heard("tpms:a:1", listOf("BT1"), 200 * min, 0.0, 0.0).isEmpty()) // reported once
    }

    @Test fun travellingTogetherMatchesAtThreePlaces() {
        val m = TpmsBtMatch()
        val km = 0.009 // about 1 km of latitude
        assertTrue(m.heard("t", listOf("B"), 0, 0.0, 0.0).isEmpty())
        assertTrue(m.heard("t", listOf("B"), 1 * min, 1 * km, 0.0).isEmpty()) // under 2 km: same place
        assertTrue(m.heard("t", listOf("B"), 2 * min, 2.5 * km, 0.0).isEmpty())
        assertEquals(1, m.heard("t", listOf("B"), 4 * min, 5 * km, 0.0).size)
    }

    @Test fun deviceWithYouEverywhereNeverMatches() {
        val m = TpmsBtMatch()
        // Your own (unwhitelisted) gadget meets nine cars, each twice.
        for (car in 1..9) m.heard("tpms:$car", listOf("MINE"), car * min, 0.0, 0.0)
        val second = (1..9).flatMap { m.heard("tpms:$it", listOf("MINE"), 100 * min + it * min, 0.0, 0.0) }
        assertTrue(second.isEmpty())
    }

    @Test fun saveAndLoadKeepTheCounts() {
        val m = TpmsBtMatch()
        m.heard("tpms:a:1", listOf("BT1"), 0, 1.23456, 2.34567)
        assertTrue(m.changed)
        val text = m.save()
        val n = TpmsBtMatch().apply { load(text + "\nbroken line") }
        assertEquals(1, n.size)
        assertEquals(1, n.heard("tpms:a:1", listOf("BT1"), 60 * min, 1.2346, 2.3457).size)
    }

    @Test fun pruneDropsOldPairs() {
        val m = TpmsBtMatch()
        m.heard("t", listOf("B"), 0, 0.0, 0.0)
        m.prune(TpmsBtMatch.KEEP_MS + 1)
        assertEquals(0, m.size)
    }
}
