package com.rfsentinel.app.esp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardByTest {

    @Test
    fun marksForAWhileThenForgets() {
        val r = RecentMacs()
        r.mark("70:c9:4e:11:22:33", now = 1_000)
        assertTrue(r.recent("70:C9:4E:11:22:33", now = 1_000 + HeardBy.WINDOW_MS - 1))
        assertFalse(r.recent("70:C9:4E:11:22:33", now = 1_000 + HeardBy.WINDOW_MS))
        assertFalse(r.recent("00:11:22:33:44:55", now = 1_000))
    }

    @Test
    fun boardAndPhoneAreTrackedSeparately() {
        HeardBy.esp.clear(); HeardBy.phone.clear()
        HeardBy.esp.mark("AA:BB:CC:00:00:01", now = 5_000)
        assertTrue(HeardBy.esp.recent("AA:BB:CC:00:00:01", now = 5_000))
        assertFalse(HeardBy.phone.recent("AA:BB:CC:00:00:01", now = 5_000))
    }
}
