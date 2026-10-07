package com.rfsentinel.app.service

import android.app.Application
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.ui.LiveWindow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live alerts stop once a flagged device is out of range, and a removed watchlist match doesn't linger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlertRangeTest {

    private val t0 = 1_000_000L
    private val hit = Hit(Category.BODY_CAM, "Watched device", 90, "Your watchlist", "Your watchlist")
    private val id = DeviceIntel.Identity("Device", emptyList())

    @Before fun setUp() { DeviceRegistry.clear(); DeviceRegistry.startSession(t0); LiveWindow.wifiScanMs = 30_000L }
    @After fun tearDown() = DeviceRegistry.clear()

    @Test
    fun bluetoothStopsAlertingSecondsAfterItIsLastHeard() {
        DeviceRegistry.report(Advert("AA:00:00:00:00:01", Advert.Source.BLE, -60, "x"), listOf(hit), id, null, null, null, t0)
        val s = DeviceRegistry.get("AA:00:00:00:00:01")!!
        assertTrue(LiveWindow.alerting(s, t0 + 5_000))
        assertFalse(LiveWindow.alerting(s, t0 + LiveWindow.BLE_IN_RANGE_MS + 1_000))
    }

    @Test
    fun wifiStopsAlertingAfterAMissedScanRound() {
        DeviceRegistry.report(Advert("AA:00:00:00:00:02", Advert.Source.WIFI, -60, "x"), listOf(hit), id, null, null, null, t0)
        val s = DeviceRegistry.get("AA:00:00:00:00:02")!!
        assertTrue(LiveWindow.alerting(s, t0 + 35_000))
        assertFalse(LiveWindow.alerting(s, t0 + 41_000))
    }

    @Test
    fun flaggedDevicesFollowTheListSlider() {
        DeviceRegistry.report(Advert("AA:00:00:00:00:04", Advert.Source.BLE, -60, "x"), listOf(hit), id, null, null, null, t0)
        val s = DeviceRegistry.get("AA:00:00:00:00:04")!!
        assertTrue(LiveWindow.keep(s, t0 + 9_000, 10_000, 60_000))
        assertFalse(LiveWindow.keep(s, t0 + 11_000, 10_000, 60_000))
        // Never removed while it still counts as in range, even with a shorter setting.
        assertTrue(LiveWindow.keep(s, t0 + 7_000, 5_000, 60_000))
    }

    @Test
    fun wifiListWindowBelow30sIsSavedOnlyUsedWithFastScans() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        android.provider.Settings.Global.putInt(ctx.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        com.rfsentinel.app.util.Prefs.setScanIntervalMs(ctx, 10_000L)
        com.rfsentinel.app.util.Prefs.setLiveWifiSec(ctx, 5)
        org.junit.Assert.assertEquals(5, com.rfsentinel.app.util.Prefs.liveWifiSec(ctx))
        // Back to a 30 s interval: the saved 5 s is raised to 30 s.
        com.rfsentinel.app.util.Prefs.setScanIntervalMs(ctx, 30_000L)
        org.junit.Assert.assertEquals(30, com.rfsentinel.app.util.Prefs.liveWifiSec(ctx))
    }

    @Test
    fun removedWatchlistMatchIsDroppedRightAway() {
        val mac = "AA:00:00:00:00:03"
        DeviceRegistry.report(Advert(mac, Advert.Source.BLE, -60, "x"), listOf(hit), id, null, null, null, t0)
        DeviceRegistry.replaceHits(mac, emptyList())
        assertNull(DeviceRegistry.get(mac)!!.best)
        // The next sighting (no match any more) doesn't bring the held match back.
        DeviceRegistry.report(Advert(mac, Advert.Source.BLE, -60, "x"), emptyList(), id, null, null, null, t0 + 1_000)
        assertNull(DeviceRegistry.get(mac)!!.best)
    }
}
