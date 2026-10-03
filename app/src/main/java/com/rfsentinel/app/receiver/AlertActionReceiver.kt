package com.rfsentinel.app.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rfsentinel.app.data.AlertLog
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TrackerMutes
import com.rfsentinel.app.data.WhitelistEntity
import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The buttons on an alert shown on the car screen: "Mute 30 min" silences alert
 * sound and voice for half an hour; "Ignore" stops alerting about that device
 * (a rotating tracker until its address changes, anything else via the
 * whitelist - undo from the device's details).
 */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_SNOOZE -> snooze(app)
            ACTION_IGNORE -> {
                val mac = intent.getStringExtra(EXTRA_MAC) ?: return
                val pending = goAsync()
                scope.launch {
                    try { ignore(app, mac) } finally { pending.finish() }
                }
            }
            else -> return
        }
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        if (id != 0) runCatching { app.getSystemService(NotificationManager::class.java).cancel(id) }
    }

    companion object {
        const val ACTION_SNOOZE = "com.rfsentinel.app.action.SNOOZE_ALERTS"
        const val ACTION_IGNORE = "com.rfsentinel.app.action.IGNORE_DEVICE"
        private const val EXTRA_MAC = "mac"
        private const val EXTRA_NOTIFICATION = "notification"
        const val SNOOZE_MS = 30 * 60_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun snoozeIntent(context: Context, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, AlertActionReceiver::class.java).setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun ignoreIntent(context: Context, mac: String, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context, mac.hashCode(),
            Intent(context, AlertActionReceiver::class.java).setAction(ACTION_IGNORE)
                .putExtra(EXTRA_MAC, mac).putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Silences alert sound and voice for [SNOOZE_MS] (also from the car's "More" screen). */
        fun snooze(context: Context, now: Long = System.currentTimeMillis()) {
            Prefs.setAlertsSnoozedUntil(context, now + SNOOZE_MS)
            Voice.silence()
        }

        /**
         * Stops alerting about [mac]: a tracker whose address rotates is ignored until the
         * address changes (it can't be whitelisted); anything else is whitelisted.
         * Returns what was done, for a toast.
         */
        suspend fun ignore(context: Context, mac: String): String {
            val snap = DeviceRegistry.get(mac)
            val label = snap?.best?.label ?: AlertLog.latestFor(context, mac)?.label ?: ""
            val rotatingTracker = snap != null && snap.best?.category == Category.TRACKER &&
                snap.addressType != AddressType.PUBLIC
            return if (rotatingTracker) {
                TrackerMutes.mute(context, mac, label, snap!!.rssi, follow = false)
                "Ignored until its address changes (at most 24 h)"
            } else {
                AppDatabase.getInstance(context).whitelistDao().add(WhitelistEntity(mac, label))
                "Whitelisted - won't alert again"
            }
        }
    }
}
