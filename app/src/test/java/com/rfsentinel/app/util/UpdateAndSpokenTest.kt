package com.rfsentinel.app.util

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAndSpokenTest {

    @Test
    fun versionsCompareNumerically() {
        assertTrue(UpdateChecker.isNewer("2.11.1", "2.11.0"))
        assertTrue(UpdateChecker.isNewer("v2.12.0", "2.11.9"))
        assertTrue(UpdateChecker.isNewer("2.11.10", "2.11.9")) // not a string comparison
        assertFalse(UpdateChecker.isNewer("2.11.0", "2.11.0"))
        assertFalse(UpdateChecker.isNewer("2.10.6", "2.11.0"))
        assertTrue(UpdateChecker.isNewer("3.0", "2.11.0"))
    }

    private fun hit(c: Category, label: String) = Hit(c, label, 80, "", "")

    @Test
    fun shortWordsAreShort() {
        assertEquals("Body cam", Spoken.shortWord(hit(Category.BODY_CAM, "Axon body camera")))
        assertEquals("Police car", Spoken.shortWord(hit(Category.PUBLIC_SAFETY, "Two-way radio in a possible police vehicle (3 kinds of gear together)")))
        assertEquals("Police gear", Spoken.shortWord(hit(Category.PUBLIC_SAFETY, "Motorola Solutions equipment")))
        Category.entries.forEach { c -> assertTrue(Spoken.shortWord(hit(c, "x")).split(' ').size <= 2) }
    }
}
