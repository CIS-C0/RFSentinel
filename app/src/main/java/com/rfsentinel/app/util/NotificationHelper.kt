package com.rfsentinel.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
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
        target: Class<out android.app.Activity> = com.rfsentinel.app.ui.MapActivity::class.java,
        /** Where the thing is (a camera); else where you were. */
        lat: Double? = null, lon: Double? = null
    ) {
        recordAlert(context, key, null, hit, null, false, lat, lon)
        val discreet = Prefs.discreetMode(context)
        com.rfsentinel.app.ui.ThreatBubble.popup(context, if (discreet) "New alert" else hit.label,
            if (discreet) null else hit.category.shortTag, hit.category.colorArgb)
        if (toCar(context, if (discreet) "New alert" else "${hit.label}. ${hit.evidence}", null)) return
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
                    .addAction(R.drawable.ic_car_volume_off, "Mute 30 min",
                        com.rfsentinel.app.receiver.AlertActionReceiver.snoozeIntent(context, ALERT_NOTIFICATION_ID_BASE + key.hashCode()))
                    .build()
            )
        try {
            // Through CarNotificationManager: Android Auto shows CarAppExtender alerts only
            // when they're posted this way (it also posts the normal phone notification).
            CarNotificationManager.from(context).notify(ALERT_NOTIFICATION_ID_BASE + key.hashCode(), builder)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied - sound and voice still alert.
        }
    }

    /**
     * Adds the alert to the recent-alerts list (Android Auto). Positions are kept only
     * with Settings > GPS tagging on, like the match history.
     */
    private fun recordAlert(
        context: Context, key: String, mac: String?, hit: Hit, rssi: Int?, following: Boolean,
        lat: Double?, lon: Double?
    ) {
        runCatching {
            val tag = Prefs.gpsTaggingEnabled(context)
            val here = ScanForegroundService.lastFix
            com.rfsentinel.app.data.AlertLog.add(
                context, key, mac, hit, rssi, following,
                if (tag) lat ?: here?.latitude else null, if (tag) lon ?: here?.longitude else null
            )
        }
    }

    /**
     * On Android Auto the alert goes into the car's message conversation ([CarMessages])
     * instead of a phone notification: that's what reaches a real car from the GitHub
     * APK. Nothing is posted while alerts are muted. True when handled.
     */
    private fun toCar(context: Context, text: String, mac: String?): Boolean {
        if (!com.rfsentinel.app.car.CarState.connected) return false
        if (System.currentTimeMillis() >= Prefs.alertsSnoozedUntil(context)) CarMessages.post(context, text, mac)
        return true
    }

    /** One spoken-friendly line, e.g. "Nearby: Axon body camera. Strong match, about 40 m, signal -58 dBm." */
    internal fun carMessageText(title: String, hit: Hit, rssi: Int, distanceM: Double?): String =
        "$title. " + listOfNotNull(
            hit.tier.label.substringBefore(" -").replaceFirstChar { it.uppercase() } + " match",
            distanceM?.let { "about " + com.rfsentinel.app.detect.DeviceIntel.formatDistance(it).removePrefix("~") },
            "signal $rssi dBm"
        ).joinToString(", ") + "."

    /** Posts the match notification; sound/vibration/voice are played by the caller. */
    /**
     * The car heads-up's line, readable at a glance: what it is, how sure, about how far,
     * signal, and how many other devices are flagged around you.
     * e.g. "BODY CAM · strong · ~40 m · near (-58 dBm) · +2 more flagged"
     */
    internal fun carAlertText(hit: Hit, rssi: Int, distanceM: Double?, othersFlagged: Int): String = listOfNotNull(
        hit.category.shortTag,
        hit.tier.label.substringBefore(" -"),
        distanceM?.let { com.rfsentinel.app.detect.DeviceIntel.formatDistance(it) },
        "${ProximityUtil.band(rssi).lowercase()} ($rssi dBm)",
        othersFlagged.takeIf { it > 0 }?.let { "+$it more flagged" }
    ).joinToString(" · ")

    fun sendAlert(context: Context, mac: String, hit: Hit, rssi: Int, following: Boolean = false) {
        // A drone with a Remote ID position is logged where it is; anything else where you were.
        val rid = com.rfsentinel.app.service.DeviceRegistry.get(mac)?.remoteId?.takeIf { it.hasPosition }
        recordAlert(context, mac, mac, hit, rssi, following, rid?.latitude, rid?.longitude)
        val discreet = Prefs.discreetMode(context)
        com.rfsentinel.app.ui.ThreatBubble.popup(context,
            if (discreet) "New alert" else if (following) "Following you: ${hit.label}" else hit.label,
            if (discreet) null else listOfNotNull(hit.category.shortTag,
                com.rfsentinel.app.service.DeviceRegistry.get(mac)?.distanceM?.takeIf { it > 0 }
                    ?.let { "~" + com.rfsentinel.app.detect.DeviceIntel.formatDistance(it) },
                ProximityUtil.band(rssi).lowercase()).joinToString(" · "),
            hit.category.colorArgb)
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
        val snap = com.rfsentinel.app.service.DeviceRegistry.get(mac)
        if (toCar(context, if (discreet) "New alert" else carMessageText(title, hit, rssi, snap?.distanceM), mac)) return
        val others = com.rfsentinel.app.service.DeviceRegistry.snapshot()
            .count { it.mac != mac && com.rfsentinel.app.ui.DeviceColors.isFlagged(it) }
        val text = "${hit.category.title} · ${hit.tier.label} (${hit.confidence}%) · $rssi dBm" +
            (snap?.let { " · " + com.rfsentinel.app.detect.DeviceIntel.formatDistance(it.distanceM) } ?: "")

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
                    .setContentText(if (discreet) "New alert" else carAlertText(hit, rssi, snap?.distanceM, others))
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
                    // One tap from the car screen: silence alerts for a while, or stop
                    // alerting about this device (undo from its details).
                    .addAction(R.drawable.ic_car_volume_off, "Mute 30 min",
                        com.rfsentinel.app.receiver.AlertActionReceiver.snoozeIntent(context, ALERT_NOTIFICATION_ID_BASE + mac.hashCode()))
                    .addAction(R.drawable.ic_car_star_outline, "Ignore",
                        com.rfsentinel.app.receiver.AlertActionReceiver.ignoreIntent(context, mac, ALERT_NOTIFICATION_ID_BASE + mac.hashCode()))
                    .build()
            )
        try {
            CarNotificationManager.from(context).notify(ALERT_NOTIFICATION_ID_BASE + mac.hashCode(), builder)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied - the in-app list and sound still alert.
        }
    }
}
