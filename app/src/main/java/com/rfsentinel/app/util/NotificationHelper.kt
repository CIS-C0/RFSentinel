package com.rfsentinel.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rfsentinel.app.car.RFSentinelCarAppService
import com.rfsentinel.app.MainActivity
import com.rfsentinel.app.R
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.DeviceDetailActivity

object NotificationHelper {
    const val CHANNEL_SERVICE = "rf_sentinel_service"
    const val CHANNEL_ALERTS = "rf_sentinel_alerts"
    private const val ALERT_NOTIFICATION_ID_BASE = 1000
    const val SERVICE_NOTIFICATION_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE, "Scanner status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Ongoing passive-scan service notification" }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS, "Equipment alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when watchlisted equipment is detected nearby"
            // Sound/vibration come from AlertPlayer (tier-coded patterns), not the channel.
            setSound(null, null)
            enableVibration(false)
        }

        val downloadChannel = NotificationChannel(
            CHANNEL_DOWNLOADS, "Camera downloads", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progress while known cameras are downloaded from OpenStreetMap" }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
        nm.createNotificationChannel(downloadChannel)
    }

    const val CHANNEL_DOWNLOADS = "rf_sentinel_downloads"
    private const val DOWNLOAD_NOTIFICATION_ID = 2

    /**
     * Progress of a camera download: a bar while [ongoing] (silent, can't be
     * swiped away), then the result, which can. Tapping opens the map.
     */
    fun showCameraDownload(context: Context, title: String, text: String, done: Int, total: Int, ongoing: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, DOWNLOAD_NOTIFICATION_ID,
            Intent(context, com.rfsentinel.app.ui.MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (ongoing) b.setProgress(total, done, total == 0)
        runCatching { nm.notify(DOWNLOAD_NOTIFICATION_ID, b.build()) } // no-op without the notification permission
    }

    /** Clears a progress notification left behind when the app was killed mid-download. */
    fun clearStaleCameraDownload(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val stale = runCatching { nm.activeNotifications.any { it.id == DOWNLOAD_NOTIFICATION_ID && it.isOngoing } }.getOrDefault(false)
        if (stale) nm.cancel(DOWNLOAD_NOTIFICATION_ID)
    }

    /**
     * Brings the app back exactly like tapping its launcher icon: the existing
     * task comes to the front on whatever screen you left, or MainActivity
     * starts fresh if the app isn't open.
     */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * The ongoing "Passive scan active" notification. Tapping it returns to the
     * app; the Stop action ends scanning without opening it.
     */
    fun buildServiceNotification(context: Context, text: String): Notification {
        val discreet = Prefs.discreetMode(context)
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle(if (discreet) "Scanner active" else "Passive scan active")
            .setContentText(if (discreet) "Tap to open" else text)
            .setSmallIcon(R.drawable.ic_tile_scan)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Stop", stop)
            .build()
    }

    fun updateServiceNotification(context: Context, text: String) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(context, text))
        } catch (e: SecurityException) {
            // Notifications denied.
        }
    }

    /**
     * Posts an alert about a mapped place rather than a device (e.g. a known plate
     * camera): tapping it opens the map. Sound/vibration/voice are played by the caller.
     */
    fun sendMapAlert(
        context: Context, key: String, hit: Hit,
        target: Class<out android.app.Activity> = com.rfsentinel.app.ui.MapActivity::class.java
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val discreet = Prefs.discreetMode(context)
        val open = PendingIntent.getActivity(
            context, key.hashCode(),
            Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(if (discreet) "RF Sentinel" else hit.label)
            .setContentText(if (discreet) "New alert - tap to view" else hit.evidence)
            .setStyle(if (discreet) null else NotificationCompat.BigTextStyle().bigText(hit.evidence))
            .setSmallIcon(R.drawable.ic_tile_scan)
            .setColor(hit.category.colorArgb)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(open)
            .setVisibility(if (discreet) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(if (discreet) "RF Sentinel" else hit.label)
                    .setContentText(if (discreet) "New alert" else hit.evidence)
                    .setSmallIcon(R.drawable.ic_tile_scan)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )
        try {
            nm.notify(ALERT_NOTIFICATION_ID_BASE + key.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied - sound and voice still alert.
        }
    }

    /** Posts the match notification; sound/vibration/voice are played by the caller. */
    fun sendAlert(context: Context, mac: String, hit: Hit, rssi: Int, following: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val discreet = Prefs.discreetMode(context)
        val detail = PendingIntent.getActivity(
            context, mac.hashCode(),
            Intent(context, DeviceDetailActivity::class.java)
                .putExtra(DeviceDetailActivity.EXTRA_MAC, mac)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            following -> "May be following you: ${hit.label}"
            else -> "Nearby: ${hit.label}"
        }
        val text = "${hit.category.title} · ${hit.tier.label} (${hit.confidence}%) · $rssi dBm"

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle("RF Sentinel")
            .setContentText("New alert")
            .setSmallIcon(R.drawable.ic_tile_scan)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(if (discreet) "RF Sentinel" else title)
            .setContentText(if (discreet) "New alert - tap to view" else text)
            .setStyle(if (discreet) null else NotificationCompat.BigTextStyle().bigText("$text\n$mac\n${hit.evidence}"))
            .setSmallIcon(R.drawable.ic_tile_scan)
            .setColor(hit.category.colorArgb)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(detail)
            .setVisibility(if (discreet) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // Android Auto: show as a heads-up on the car screen; tapping it opens
            // this device in the car app. (The tone/voice come from AlertPlayer.)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(if (discreet) "RF Sentinel" else title)
                    .setContentText(if (discreet) "New alert" else "${hit.category.shortTag} \u00b7 ${hit.tier.label} \u00b7 $rssi dBm")
                    .setSmallIcon(R.drawable.ic_tile_scan)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setContentIntent(
                        CarPendingIntent.getCarApp(
                            context, mac.hashCode(),
                            Intent(Intent.ACTION_VIEW)
                                .setComponent(ComponentName(context, RFSentinelCarAppService::class.java))
                                .putExtra(RFSentinelCarAppService.EXTRA_MAC, mac),
                            PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                    .build()
            )
        try {
            nm.notify(ALERT_NOTIFICATION_ID_BASE + mac.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied - the in-app list and sound still alert.
        }
    }
}
