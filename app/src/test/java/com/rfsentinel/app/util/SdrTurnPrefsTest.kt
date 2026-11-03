package com.rfsentinel.app.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.data.AlertKind
import com.rfsentinel.app.sdr.SdrRadio.Mode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › RTL-SDR radio: each scan's own turn length, and the talkgroup alert chips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SdrTurnPrefsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun eachScanStartsFromTheOldSharedTime() {
        Mode.entries.forEach { assertEquals(60, Prefs.sdrTurnS(ctx, it)) }
        // Saved by an older version: one time for all.
        ctx.getSharedPreferences("rf_sentinel_prefs", Context.MODE_PRIVATE).edit().putInt("sdr_turn_s", 120).commit()
        Mode.entries.forEach { assertEquals(120, Prefs.sdrTurnS(ctx, it)) }
        Prefs.setSdrTurnS(ctx, Mode.ADSB, 30)
        Prefs.setSdrTurnS(ctx, Mode.P25, 999)
        assertEquals(120, Prefs.sdrTurnS(ctx, Mode.SWEEP))
        assertEquals(30, Prefs.sdrTurnS(ctx, Mode.ADSB))
        assertEquals(300, Prefs.sdrTurnS(ctx, Mode.P25))
    }

    @Test
    fun talkgroupAlertsOffRoundTrip() {
        assertEquals(emptySet<AlertKind>(), Prefs.p25AlertsOff(ctx))
        Prefs.setP25AlertsOff(ctx, setOf(AlertKind.VOICE, AlertKind.SOUND))
        assertEquals(setOf(AlertKind.VOICE, AlertKind.SOUND), Prefs.p25AlertsOff(ctx))
        Prefs.setP25AlertsOff(ctx, emptySet())
        assertEquals(emptySet<AlertKind>(), Prefs.p25AlertsOff(ctx))
    }
}
