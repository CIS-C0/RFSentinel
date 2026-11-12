package com.rfsentinel.app.sdr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P25LinkTest {

    @After fun tearDown() = P25Link.clear()

    @Test
    fun aDeviceAroundEachTimeARadioKeysUpIsLinked() {
        val min = 60_000L
        // Radio 5001 keys up near you three separate times (30 min apart); the body cam and your earbuds are there each time.
        for (t in 0 until 3) P25Link.heard(5001, listOf("CAM", "EARBUDS"), t * 30 * min)
        P25Link.heard(5001, listOf("CAM"), 2 * 30 * min + 2 * min) // the same occasion again: not a fourth
        assertEquals(5001 to 3, P25Link.radioFor("CAM"))
        // Your earbuds are also there for two other radios: not mostly with 5001, so no link.
        P25Link.heard(6002, listOf("EARBUDS"), 200 * min)
        P25Link.heard(7003, listOf("EARBUDS"), 300 * min)
        assertNull(P25Link.radioFor("EARBUDS"))
        assertEquals("Rides with P25 radio 5001", P25Link.hitFor("CAM")?.label)
        // Twice isn't enough.
        P25Link.heard(8004, listOf("PHONE"), 0); P25Link.heard(8004, listOf("PHONE"), 30 * min)
        assertNull(P25Link.hitFor("PHONE"))
    }

    @Test
    fun pairsNotSeenForADayAreForgotten() {
        val min = 60_000L
        for (t in 0 until 3) P25Link.heard(5001, listOf("CAM"), t * 30 * min)
        P25Link.heard(6002, listOf("PHONE"), 23 * 60 * min)
        P25Link.prune(26 * 60 * min) // CAM last heard at 1 h: over a day ago
        assertNull(P25Link.radioFor("CAM"))
        // Back with the radio after that: counted from scratch, not against the forgotten times.
        for (t in 0 until 3) P25Link.heard(5001, listOf("CAM"), 27 * 60 * min + t * 30 * min)
        assertEquals(5001 to 3, P25Link.radioFor("CAM"))
    }
}
