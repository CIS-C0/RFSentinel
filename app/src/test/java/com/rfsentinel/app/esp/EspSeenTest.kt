package com.rfsentinel.app.esp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EspSeenTest {

    @Test
    fun marksForAWhileThenForgets() {
        EspSeen.clear()
        EspSeen.mark("70:c9:4e:11:22:33", now = 1_000)
        assertTrue(EspSeen.recent("70:C9:4E:11:22:33", now = 1_000 + EspSeen.WINDOW_MS - 1))
        assertFalse(EspSeen.recent("70:C9:4E:11:22:33", now = 1_000 + EspSeen.WINDOW_MS))
        assertFalse(EspSeen.recent("00:11:22:33:44:55", now = 1_000))
    }
}
