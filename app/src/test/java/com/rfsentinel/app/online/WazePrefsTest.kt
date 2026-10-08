package com.rfsentinel.app.online

import android.app.Application
import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WazePrefsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun defaultsAreTheShippedBehaviour() {
        assertEquals(2000, Prefs.wazeAlertM(ctx))
        assertEquals(5, Prefs.wazeViewKm(ctx))
        assertEquals(60, Prefs.wazeIntervalS(ctx, direct = true))
        assertEquals(240, Prefs.wazeIntervalS(ctx, direct = false))
        assertEquals(setOf("POLICE"), Prefs.wazeTypes(ctx))
    }

    @Test
    fun savedValuesSnapToTheSliderSteps() {
        Prefs.setWazeAlertM(ctx, 1234)
        assertEquals(1000, Prefs.wazeAlertM(ctx))
        Prefs.setWazeAlertM(ctx, 50)
        assertEquals(100, Prefs.wazeAlertM(ctx))               // never below 100 m
        Prefs.setWazeIntervalS(ctx, true, 20)
        assertEquals(15, Prefs.wazeIntervalS(ctx, true))
        Prefs.setWazeIntervalS(ctx, false, 10)
        assertEquals(120, Prefs.wazeIntervalS(ctx, false))     // OpenWeb Ninja is never polled faster than 2 min
        Prefs.setWazeViewKm(ctx, 99)
        assertEquals(20, Prefs.wazeViewKm(ctx))
    }

    @Test
    fun emptyTypeSetFallsBackToPolice() {
        Prefs.setWazeTypes(ctx, emptySet())
        assertEquals(setOf("POLICE"), Prefs.wazeTypes(ctx))
        Prefs.setWazeTypes(ctx, setOf("POLICE", "HAZARD"))
        assertEquals(setOf("POLICE", "HAZARD"), Prefs.wazeTypes(ctx))
    }

    @Test
    fun theNewSwitchesStartWhereTheyShouldAndRememberTheirChoice() {
        assertTrue("adapting is on by default", Prefs.wazeAdaptive(ctx))
        assertFalse("ahead-only is opt-in", Prefs.wazeAheadOnly(ctx))
        assertTrue("call-outs are on by default", Prefs.wazeApproach(ctx))
        assertFalse(Prefs.wazePaused(ctx))
        Prefs.setWazeAdaptive(ctx, false); Prefs.setWazeAheadOnly(ctx, true); Prefs.setWazeApproach(ctx, false); Prefs.setWazePaused(ctx, true)
        assertFalse(Prefs.wazeAdaptive(ctx)); assertTrue(Prefs.wazeAheadOnly(ctx)); assertFalse(Prefs.wazeApproach(ctx)); assertTrue(Prefs.wazePaused(ctx))
    }

    @Test
    fun eachEventTypeRemembersWhatItsAlertDoes() {
        assertEquals(WazePolice.Level.LOUD, Prefs.wazeLevel(ctx, WazePolice.Type.POLICE))
        assertEquals(WazePolice.Level.LOG, Prefs.wazeLevel(ctx, WazePolice.Type.JAM))
        Prefs.setWazeLevel(ctx, WazePolice.Type.JAM, WazePolice.Level.LOUD)
        Prefs.setWazeLevel(ctx, WazePolice.Type.POLICE, WazePolice.Level.NOTIFY)
        assertEquals(WazePolice.Level.LOUD, Prefs.wazeLevel(ctx, WazePolice.Type.JAM))
        assertEquals(WazePolice.Level.NOTIFY, Prefs.wazeLevel(ctx, WazePolice.Type.POLICE))
        assertEquals("the others are untouched", WazePolice.Level.NOTIFY, Prefs.wazeLevel(ctx, WazePolice.Type.HAZARD))
    }

    @Test
    fun readableLabels() {
        assertEquals("100 m", Prefs.formatRange(100))
        assertEquals("1.5 km", Prefs.formatRange(1500))
        assertEquals("10 km", Prefs.formatRange(10000))
        assertEquals("15 s", Prefs.formatInterval(15))
        assertEquals("90 s", Prefs.formatInterval(90))
        assertEquals("2 min", Prefs.formatInterval(120))
        assertEquals("10 min", Prefs.formatInterval(600))
    }
}
