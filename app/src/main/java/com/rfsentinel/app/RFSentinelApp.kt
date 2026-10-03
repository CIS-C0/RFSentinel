package com.rfsentinel.app

import android.app.Application
import android.util.Log
import com.rfsentinel.app.car.CarState
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RFSentinelApp : Application() {
    companion object {
        /** Number of this app's activities currently started (visible). */
        @Volatile var visibleActivities = 0
            private set
        val inForeground get() = visibleActivities > 0
    }

    /** Lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        com.rfsentinel.app.ui.ThemeManager.install(this)
        com.rfsentinel.app.ui.ScreenAwake.install(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { visibleActivities++ }
            override fun onActivityStopped(a: android.app.Activity) { visibleActivities = (visibleActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        NotificationHelper.createChannels(this)
        // A fresh process means no camera download is running: drop any leftover progress.
        NotificationHelper.clearStaleCameraDownload(this)
        OuiWatchlist.load(this)
        WhitelistCache.start(this, appScope)
        appScope.launch(Dispatchers.IO) { com.rfsentinel.app.data.TrackerMutes.load(this@RFSentinelApp) }
        appScope.launch(Dispatchers.IO) { com.rfsentinel.app.alpr.IgnoredCameras.load(this@RFSentinelApp) }
        CarState.start(this)
        // ~60k vendor rows: load off the main thread; lookups return null until ready.
        // A trace left "recording" by a crash / force-stop gets closed.
        appScope.launch { runCatching { com.rfsentinel.app.service.TripRecorder.closeStale(this@RFSentinelApp) } }
        appScope.launch(Dispatchers.IO) {
            runCatching { com.rfsentinel.app.alpr.AlprStore.load(this@RFSentinelApp) }
            runCatching { com.rfsentinel.app.alpr.DeflockBulk.refreshIfDue(this@RFSentinelApp) }
            // A scanner started before the cache loaded (e.g. at boot) must now watch for cameras.
            if (com.rfsentinel.app.service.ScanForegroundService.isRunning && com.rfsentinel.app.alpr.AlprStore.cameras.isNotEmpty()) {
                runCatching {
                    startService(android.content.Intent(this@RFSentinelApp, com.rfsentinel.app.service.ScanForegroundService::class.java)
                        .setAction(com.rfsentinel.app.service.ScanForegroundService.ACTION_REFRESH_LOCATION))
                }
            }
        }
        appScope.launch(Dispatchers.IO) {
            runCatching { VendorDb.load { assets.open(it) } }
                .onFailure { Log.e("RFSentinelApp", "Vendor database failed to load", it) }
        }
    }
}
