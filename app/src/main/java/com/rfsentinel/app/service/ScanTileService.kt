package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rfsentinel.app.MainActivity
import com.rfsentinel.app.R
import com.rfsentinel.app.util.Permissions

/** Quick Settings tile for one-tap start/stop without opening the app. */
class ScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile(ScanForegroundService.isRunning)
    }

    override fun onClick() {
        super.onClick()
        if (ScanForegroundService.isRunning) {
            ScanForegroundService.stop(this)
            updateTile(false)
            return
        }
        if (Permissions.missingRequired(this).isNotEmpty()) {
            // Runtime permissions can only be requested from an activity.
            openApp()
            return
        }
        try {
            ScanForegroundService.start(this)
            // isRunning flips in onStartCommand; the service then calls
            // requestListeningState(), which re-syncs the tile.
            updateTile(true)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on some API levels / OEMs.
            openApp()
        }
    }

    private fun updateTile(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (running) R.string.tile_scanning else R.string.tile_idle)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
