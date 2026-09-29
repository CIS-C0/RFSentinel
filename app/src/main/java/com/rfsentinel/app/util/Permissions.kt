package com.rfsentinel.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    /**
     * Permissions scanning can't work without. On API 31+ FINE location must be
     * requested together with COARSE or the system dialog ignores the request.
     * Location is needed for WiFi scan results on every API level, and for BLE
     * scanning on API <= 30.
     */
    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
    }

    /** Nice to have: without it scanning still works, alerts just aren't shown. */
    fun optional(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missingRequired(context: Context): List<String> = required().filterNot { granted(context, it) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
