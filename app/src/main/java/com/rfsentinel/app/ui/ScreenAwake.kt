package com.rfsentinel.app.ui

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.rfsentinel.app.util.Prefs

/**
 * Keeps the screen on while an RF Sentinel screen is open: always, only while
 * the phone is charging (car mount, desk - the default), or never (normal
 * timeout), as chosen in Settings.
 */
object ScreenAwake : Application.ActivityLifecycleCallbacks {

    private var current: Activity? = null

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The battery status can lag behind this broadcast: trust the action itself.
            current?.let { set(it, intent.action == Intent.ACTION_POWER_CONNECTED) }
        }
    }

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    private fun plugged(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** Re-evaluates the current screen (e.g. after the setting changed). */
    fun apply(activity: Activity) = set(activity, plugged(activity))

    private fun set(activity: Activity, charging: Boolean) {
        val on = when (Prefs.screenMode(activity)) {
            Prefs.ScreenMode.ALWAYS_ON -> true
            Prefs.ScreenMode.ON_WHILE_CHARGING -> charging
            Prefs.ScreenMode.NORMAL -> false
        }
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onActivityResumed(activity: Activity) {
        current = activity
        ContextCompat.registerReceiver(
            activity.applicationContext, power,
            IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        apply(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current === activity) current = null
        runCatching { activity.applicationContext.unregisterReceiver(power) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
