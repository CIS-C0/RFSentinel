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
    /** Lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        com.rfsentinel.app.ui.ThemeManager.install(this)
        NotificationHelper.createChannels(this)
        OuiWatchlist.load(this)
        WhitelistCache.start(this, appScope)
        CarState.start(this)
        // ~60k vendor rows: load off the main thread; lookups return null until ready.
        // A trace left "recording" by a crash / force-stop gets closed.
        appScope.launch { runCatching { com.rfsentinel.app.service.TripRecorder.closeStale(this@RFSentinelApp) } }
        appScope.launch(Dispatchers.IO) {
            runCatching { VendorDb.load { assets.open(it) } }
                .onFailure { Log.e("RFSentinelApp", "Vendor database failed to load", it) }
        }
    }
}
