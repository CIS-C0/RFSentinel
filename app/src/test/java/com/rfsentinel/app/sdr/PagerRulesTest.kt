package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagerRulesTest {

    private fun page(capcode: Long, text: String) = PagerLog.Page("POCSAG 1200", capcode, PagerLog.Kind.ALPHA, text)

    @Test fun `a list line is a capcode with an optional name, or else words`() {
        assertEquals(listOf(
            PagerRules.Watch(1234567, null, "Station 4"),
            PagerRules.Watch(42, null, ""),
            PagerRules.Watch(null, "cardiac arrest", "cardiac arrest")
        ), PagerRules.watches(" 1234567  Station 4\n\n42\ncardiac arrest\n"))
    }

    @Test fun `a page matches on its capcode or on words in any case, and tone pages only on the capcode`() {
        val rules = PagerRules.watches("1234567 Station 4\ncardiac arrest")
        assertEquals("Station 4", PagerRules.match(rules, page(1234567, ""))?.name)
        assertEquals("cardiac arrest", PagerRules.match(rules, page(9, "CARDIAC ARREST, Main St"))?.name)
        assertNull(PagerRules.match(rules, page(9, "")))
    }

    @Test fun `ignored keeps the capcodes and skips anything else`() {
        assertEquals(setOf(5L, 6L), PagerRules.ignored("5 test pager\n6\nnot a number"))
    }

    @Test fun `a saved list comes back from its own file, and a CSV works too`() {
        val (text, added, _) = RadioSettings.addPagerFile("", "466.075 Fire dispatch\n153.35")
        assertEquals(2, added)
        assertEquals(listOf(466_075_000L, 153_350_000L), RadioSettings.targets(text).map { it.freqHz })
        assertEquals("Fire dispatch", RadioSettings.targets(text).first().label)
        assertEquals(0, RadioSettings.addPagerFile(text, text).second)
        assertEquals(1, RadioSettings.addPagerFile(text, "Frequency,Description\n929.5625,County EMS").second)
    }
}
