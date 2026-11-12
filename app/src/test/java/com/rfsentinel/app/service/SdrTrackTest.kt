package com.rfsentinel.app.service

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SdrTrackTest {

    @Test
    fun linesKeepTimePositionAndQuoteTheText() {
        val e = SdrTrack.Event(0L, 45.5, -73.25, "P25 call, \"talkgroup\" 1")
        val line = SdrTrack.line(e)
        assertTrue(line, line.endsWith(",45.500000,-73.250000,\"P25 call, \"\"talkgroup\"\" 1\"\n"))
        // No fix yet: empty position, the line still kept.
        assertTrue(SdrTrack.line(SdrTrack.Event(0L, null, null, "x")).contains(",,,\"x\""))
    }

    @Test
    fun aTraceGetsItsOwnFileUntilDeleted() {
        SdrTrack.init(ApplicationProvider.getApplicationContext())
        val id = 987_654L
        SdrTrack.delete(id)
        assertNull(SdrTrack.read(id))
        SdrTrack.add("first", 1_000L); SdrTrack.add("second", 2_000L)
        SdrTrack.append(id, SdrTrack.since(1_500L))
        val text = SdrTrack.read(id)!!
        assertTrue(text, text.startsWith("time,latitude,longitude,activity\n") && "second" in text && "first" !in text)
        SdrTrack.append(id, SdrTrack.since(0L))
        assertEquals(1, SdrTrack.read(id)!!.lines().count { it.startsWith("time,") })
        SdrTrack.delete(id)
        assertNull(SdrTrack.read(id))
    }
}
