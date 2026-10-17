package com.rfsentinel.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRateWatchTest {

    private fun BleRateWatch.feed(perSecond: Int, ticks: Int, start: Long): Pair<Boolean, Long> {
        var t = start; var fired = false
        repeat(ticks) { t += 10_000; if (tick(perSecond * 10, 10_000, t)) fired = true }
        return fired to t
    }

    @Test
    fun restartsWhenTheRateCollapses() {
        val w = BleRateWatch()
        val (_, t) = w.feed(40, 6, 0)
        assertFalse("one low tick is not enough", w.tick(20, 10_000, t + 10_000))
        assertTrue(w.tick(20, 10_000, t + 20_000))
    }

    @Test
    fun aQuietAreaIsNotACollapse() {
        val w = BleRateWatch()
        val (fired, t) = w.feed(2, 6, 0)
        assertFalse(fired)
        assertFalse("below the minimum baseline", w.feed(0, 3, t).first)
    }

    @Test
    fun relearnsAfterARestartAndWaitsBetweenRestarts() {
        val w = BleRateWatch()
        var (_, t) = w.feed(40, 6, 0)
        assertTrue(w.feed(1, 2, t).first); t += 20_000
        // Still quiet after the restart: that's the new normal, not another collapse.
        val (fired, _) = w.feed(1, 30, t)
        assertFalse(fired)
    }

    @Test
    fun aDropThatHalvesTheRateIsFine() {
        val w = BleRateWatch()
        val (_, t) = w.feed(40, 6, 0)
        assertFalse(w.feed(20, 10, t).first)
    }
}
