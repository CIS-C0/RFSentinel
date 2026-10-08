package com.rfsentinel.app.online

import com.rfsentinel.app.util.Spoken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeFeedTest {

    private fun report(id: String, type: WazePolice.Type) =
        WazePolice.Report(id, 10.0, 10.0, null, null, null, 0, null, type)

    @Test
    fun screensHearEveryPublishAndStopWhenRemoved() {
        var calls = 0
        val stop = WazePolice.addListener { calls++ }
        val hit = WazePolice.hit(report("a", WazePolice.Type.POLICE), 10.0, 10.0, 0L)!!.first
        WazePolice.publish(listOf(WazePolice.Shown(report("a", WazePolice.Type.POLICE), hit, WazePolice.Where(0.0, "N", null, null))), 5L)
        assertEquals(1, calls)
        assertEquals(1, WazePolice.latest.size)
        assertEquals(5L, WazePolice.latestAt)
        WazePolice.publish(emptyList(), 0L)          // a cleared list is a change too
        assertEquals(2, calls)
        assertTrue(WazePolice.latest.isEmpty())
        stop()
        WazePolice.publish(emptyList(), 0L)
        assertEquals(2, calls)                          // removed: no more calls
    }

    @Test
    fun checkNowIsSpacedOut() {
        WazePolice.lastCheckStartedAt = 0L
        assertNull("none yet: asked at once", WazePolice.requestCheckNow(10, now = 1_000_000L))
        WazePolice.lastCheckStartedAt = 1_000_000L
        assertEquals("3 s after the last: wait 7 more", 7, WazePolice.requestCheckNow(10, now = 1_003_000L))
        assertNull(WazePolice.requestCheckNow(10, now = 1_011_000L))
        assertEquals(1_011_000L, WazePolice.checkNowAt)
        WazePolice.lastCheckStartedAt = 0L
    }

    @Test
    fun aBrokenListenerDoesNotStopTheOthers() {
        var heard = false
        val bad = WazePolice.addListener { throw IllegalStateException("boom") }
        val good = WazePolice.addListener { heard = true }
        WazePolice.publish(emptyList(), 0L)
        bad(); good()
        assertTrue(heard)
    }

    @Test
    fun theSpokenWordNamesTheEventNotAlwaysPolice() {
        fun said(t: WazePolice.Type) = Spoken.shortWord(WazePolice.hit(report("x", t), 10.0, 10.0, 0L)!!.first)
        assertEquals("Waze police", said(WazePolice.Type.POLICE))
        assertEquals("Waze accident", said(WazePolice.Type.ACCIDENT))
        assertEquals("Waze hazard", said(WazePolice.Type.HAZARD))
        assertEquals("Waze road closure", said(WazePolice.Type.ROAD_CLOSED))
        assertEquals("Waze traffic jam", said(WazePolice.Type.JAM))
    }

    @Test
    fun everyEventTypeScoresAboveItsOwnFloorButBelowPolice() {
        val police = WazePolice.score(50.0, 0, 0, base = WazePolice.Type.POLICE.baseScore)
        for (t in WazePolice.Type.entries.filter { it != WazePolice.Type.POLICE }) {
            val s = WazePolice.score(50.0, 0, 0, base = t.baseScore)
            assertTrue("$t $s", s in 50 until police)
        }
    }
}
