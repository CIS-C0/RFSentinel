package com.rfsentinel.app.ui

import com.rfsentinel.app.oui.OuiWatchlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionNoticeTest {

    @Test
    fun noticesOnlyWhereTheLawRestrictsDetectors() {
        assertNotNull(RegionNotice.textFor("france"))
        assertNull(RegionNotice.textFor("portugal"))
        assertNull(RegionNotice.textFor("uk"))
        assertTrue(RegionNotice.textFor("germany")!!.second.contains("23 Abs. 1c"))
        assertNull(RegionNotice.textFor("spain"))
        assertNull(RegionNotice.textFor("italy"))
        assertNull(RegionNotice.textFor("us"))
    }

    @Test
    fun canadaIsADefaultOnlyForInstallsSetUpBefore() {
        assertEquals(setOf("global"), OuiWatchlist.defaultPresets(setupDone = false))
        assertEquals(setOf("global", "canada"), OuiWatchlist.defaultPresets(setupDone = true))
    }

    @Test
    fun newRegionsAreAvailable() {
        assertTrue(OuiWatchlist.availablePresets.containsAll(listOf("france", "uk", "portugal", "germany", "spain", "italy")))
    }
}
