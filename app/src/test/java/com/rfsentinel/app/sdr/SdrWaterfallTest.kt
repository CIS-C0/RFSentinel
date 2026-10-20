package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The waterfall's rows: the usable middle of a chunk, with the receiver's own centre spike levelled. */
class SdrWaterfallTest {

    @Test
    fun aSegmentIsTheMiddleOfTheChunkWithoutTheCentreSpike() {
        val n = 1024; val rate = 2_048_000 // 2 kHz per bin
        val psd = FloatArray(n) { -60f }
        psd[n / 2] = 0f; psd[n / 2 + 3] = -10f      // DC spike, and its skirt inside the guard
        psd[n / 2 + 200] = -20f                       // a real signal 400 kHz up
        val s = SdrWaterfall.segment(155_000_000L, psd, rate, RadioWatch.USABLE_HZ, "VHF")
        assertEquals(155_000_000L - 800_000L, s.startHz)
        assertEquals(155_000_000L + 800_000L, s.endHz)
        assertEquals(801, s.db.size)
        val mid = s.db.size / 2
        assertEquals("spike levelled to the noise beside it", -60f, s.db[mid], 0f)
        assertEquals(-60f, s.db[mid + 3], 0f)
        assertEquals("a real signal stays", -20f, s.db[mid + 200], 0f)
        assertEquals("VHF", s.band)
        assertTrue("the input is left alone", psd[n / 2] == 0f)
    }
}
