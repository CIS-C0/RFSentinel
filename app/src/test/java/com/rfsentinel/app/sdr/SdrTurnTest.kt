package com.rfsentinel.app.sdr

import com.rfsentinel.app.sdr.SdrRadio.Mode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SdrRadio needs Android's main looper to load. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SdrTurnTest {
    private val all = setOf(Mode.SWEEP, Mode.ADSB, Mode.P25, Mode.PAGER)

    @Test
    fun theOpenWaterfallKeepsTheSweepAndRemembersTheCutTurn() {
        assertEquals(Mode.SWEEP to Mode.P25, SdrRadio.pickTurn(true, Mode.P25, false, null, all))
        // Still open after an antenna check: the remembered scan isn't lost.
        assertEquals(Mode.SWEEP to Mode.P25, SdrRadio.pickTurn(true, Mode.SWEEP, true, Mode.P25, all))
        // Sweep not ticked, or nothing ticked: it still draws.
        assertEquals(Mode.SWEEP to Mode.ADSB, SdrRadio.pickTurn(true, Mode.ADSB, false, null, setOf(Mode.ADSB)))
        assertEquals(Mode.SWEEP to null, SdrRadio.pickTurn(true, null, false, null, emptySet()))
    }

    @Test
    fun closingItGivesTheCutTurnBack() {
        assertEquals(Mode.P25 to null, SdrRadio.pickTurn(false, Mode.SWEEP, false, Mode.P25, all))
        // Unticked meanwhile: the next one instead.
        assertEquals(Mode.ADSB to null, SdrRadio.pickTurn(false, Mode.SWEEP, false, Mode.P25, setOf(Mode.SWEEP, Mode.ADSB)))
        // Only ADS-B ticked, swept for the screen: straight back to ADS-B; nothing ticked: idle.
        assertEquals(Mode.ADSB to null, SdrRadio.pickTurn(false, Mode.SWEEP, false, null, setOf(Mode.ADSB)))
        assertEquals(null to null, SdrRadio.pickTurn(false, Mode.SWEEP, false, null, emptySet()))
    }

    @Test
    fun turnsGoRoundAsBefore() {
        assertEquals(Mode.P25 to null, SdrRadio.pickTurn(false, Mode.ADSB, false, null, all))
        assertEquals(Mode.SWEEP to null, SdrRadio.pickTurn(false, Mode.PAGER, false, null, all))
        // Back from zooming in: the interrupted scan carries on.
        assertEquals(Mode.ADSB to null, SdrRadio.pickTurn(false, Mode.ADSB, true, null, all))
    }
}
