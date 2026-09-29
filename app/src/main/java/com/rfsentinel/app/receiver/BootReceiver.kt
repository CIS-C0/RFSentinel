package com.rfsentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Prefs.autoStartOnBoot(context)) return
        if (Permissions.missingRequired(context).isNotEmpty()) return
        try {
            // BOOT_COMPLETED is exempt from background-start limits for the
            // connectedDevice type. The location type is usually refused here
            // (no while-in-use access at boot); the service falls back on its own.
            ScanForegroundService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not auto-start scanner", e)
        }
    }
}
