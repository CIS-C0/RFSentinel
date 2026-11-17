package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotColdTest {

    @Test
    fun warmerPingsOnlyForARealGain() {
        val h = HotCold()
        // The first readings set the mark without a ping.
        assertFalse(h.add(-80, 0.0, 0.0)); assertFalse(h.add(-80, 0.0, 0.0)); assertFalse(h.add(-80, 0.0, 0.0))
        // One lucky packet isn't warmer (the level is the middle of the last three)...
        assertFalse(h.add(-60, 1.0, 0.0))
        assertEquals(-80, h.level)
        // ...two in a row are.
        assertTrue(h.add(-62, 2.0, 0.0))
        assertEquals(-62, h.best)
        // A gain under the step, or a weaker reading, is nothing.
        assertFalse(h.add(-61, 3.0, 0.0))
        assertFalse(h.add(-75, 3.0, 0.0))
    }

    @Test
    fun passedItOnceTheSignalDropsAndYouWalkedOn() {
        val h = HotCold()
        repeat(4) { h.add(-70, 0.0, 0.0) }
        repeat(3) { h.add(-50, 5.0, 0.0) }           // strongest at x = 5
        assertNull(h.passedM(5.0, 0.0))
        repeat(3) { h.add(-55, 9.0, 0.0) }           // a 5 dB fade: not passed
        assertNull(h.passedM(9.0, 0.0))
        repeat(3) { h.add(-62, 6.0, 0.0) }           // dropped, but you barely moved: a fade, not distance
        assertNull(h.passedM(6.0, 0.0))
        repeat(3) { h.add(-62, 11.0, 0.0) }
        assertEquals(6, h.passedM(11.0, 0.0))
        // No position, or positions that can't be compared any more: no claim.
        assertNull(h.passedM(null, null))
        h.lostPlace()
        assertNull(h.passedM(11.0, 0.0))
    }
}
