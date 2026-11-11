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
    fun aDongleBackAtOnceResetItself() {
        assertEquals(true, SdrRadio.resetBack("0bda:2838", 1_000L, "0bda:2838", 1_400L))
        // Back much later (unplugged and plugged in again), another dongle, or none dropped off: not a reset.
        assertEquals(false, SdrRadio.resetBack("0bda:2838", 1_000L, "0bda:2838", 60_000L))
        assertEquals(false, SdrRadio.resetBack("0bda:2838", 1_000L, "1d50:6089", 1_400L))
        assertEquals(false, SdrRadio.resetBack(null, 0L, "0bda:2838", 1_400L))
    }

    private fun set(field: String, v: Any?) = SdrRadio::class.java.getDeclaredField(field).apply { isAccessible = true }.set(SdrRadio, v)

    @Test
    fun theWaterfallGetsItsFrequencyBackAfterAReset() {
        try {
            // Zoomed in and listening when the dongle reset itself; Android's USB prompt closed the screen.
            set("resetAt", System.currentTimeMillis())
            SdrRadio.tuneHz = 462_562_500L; SdrRadio.listen = true
            SdrRadio.screenClosed(); SdrRadio.tuneHz = null; SdrRadio.listen = false
            SdrRadio.screenOpened()
            assertEquals(462_562_500L, SdrRadio.tuneHz); assertEquals(true, SdrRadio.listen)
            // Given back once: the Sweep button afterwards isn't undone by the next open.
            SdrRadio.tuneHz = null; SdrRadio.listen = false
            SdrRadio.screenOpened()
            assertEquals(null, SdrRadio.tuneHz)
            // No reset (closed the screen normally): it opens on the sweep as before.
            set("resetAt", 0L)
            SdrRadio.tuneHz = 462_562_500L
            SdrRadio.screenClosed(); SdrRadio.tuneHz = null
            SdrRadio.screenOpened()
            assertEquals(null, SdrRadio.tuneHz)
        } finally { set("resetAt", 0L); set("resetTune", null); SdrRadio.tuneHz = null; SdrRadio.listen = false }
    }

    @Test
    fun turnsGoRoundAsBefore() {
        assertEquals(Mode.P25 to null, SdrRadio.pickTurn(false, Mode.ADSB, false, null, all))
        assertEquals(Mode.SWEEP to null, SdrRadio.pickTurn(false, Mode.PAGER, false, null, all))
        // Back from zooming in: the interrupted scan carries on.
        assertEquals(Mode.ADSB to null, SdrRadio.pickTurn(false, Mode.ADSB, true, null, all))
    }
}
