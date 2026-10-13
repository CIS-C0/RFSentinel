package com.rfsentinel.app.nav

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rfsentinel.app.R
import com.rfsentinel.app.ui.MapActivity
import com.rfsentinel.app.util.NotificationHelper
import java.text.DateFormat
import java.util.Date

/**
 * Keeps guidance alive with the screen off or the app in the background (Android otherwise stops a
 * background app's GPS), and shows the next turn and the arrival time in an ongoing notification.
 * Runs only while a route is being followed; [Navigator] starts and stops it.
 */
class NavigationService : Service() {

    companion object {
        private const val ACTION_STOP = "com.rfsentinel.app.NAV_STOP"
        const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            // Not allowed to start from the background on newer Android: guidance still runs, just without the notification.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java)) }
        }

        /** True from the moment the service has called startForeground until it is destroyed. */
        @Volatile internal var up = false

        fun stop(context: Context) {
            // A service just asked to start has not called startForeground yet, and stopping it now crashes the
            // app (Android: "did not then call startForeground") - guidance that ends at once, arriving on the very
            // first fix, is exactly that. It finds out for itself that guidance is over when it starts, and ends.
            if (up) runCatching { context.stopService(Intent(context, NavigationService::class.java)) }
        }
    }

    private var removeListener: (() -> Unit)? = null
    private var shown = ""
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Navigator.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Started with startForegroundService, so it must call startForeground at once - even if the route
        // ended in the meantime (arriving on the very first fix): Android crashes the app otherwise.
        val n = build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, n)
            foreground = true
            up = true
        }
        if (!Navigator.active) { stopSelf(); return START_NOT_STICKY }
        if (removeListener == null) removeListener = Navigator.addListener { refresh() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        up = false
        removeListener?.invoke(); removeListener = null
        super.onDestroy()
    }

    private fun refresh() {
        if (!Navigator.active) { stopSelf(); return }
        val key = lines().joinToString("|")
        if (key == shown) return // only when the words change, not at every fix
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build()) }
    }

    /** The notification's two lines: the next turn, and the arrival. */
    private fun lines(): List<String> {
        // Discreet mode: nothing about the trip on the notification.
        if (com.rfsentinel.app.util.Prefs.discreetMode(this)) return listOf("Navigation active", "Tap to open")
        val r = Navigator.route ?: return listOf("Navigating", "")
        val s = Navigator.state ?: return listOf("Navigating to ${Navigator.destination?.name.orEmpty()}", Navigator.status.orEmpty())
        val miles = NavFormat.miles(this)
        val banner = Prompts.banner(r, s.stepIndex, s.toManeuverM)
        val turn = banner?.text?.ifBlank { null } ?: r.steps.getOrNull(s.stepIndex + 1)?.instruction?.removeSuffix(".").orEmpty()
        val title = if (Navigator.status != null) Navigator.status!! else NavFormat.distance(s.toManeuverM, miles) + (if (turn.isBlank()) "" else " · $turn")
        val left = Navigator.remainingS ?: s.remainingS
        val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (left * 1000).toLong()))
        return listOf(title, "Arrive $eta · ${NavFormat.duration(left)} · ${NavFormat.distance(s.remainingM, miles)}")
    }

    private fun build(): Notification {
        val l = lines()
        shown = l.joinToString("|")
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_NAV)
            .setSmallIcon(R.drawable.ic_nav_navigation)
            .setContentTitle(l[0])
            .setContentText(l.getOrElse(1) { "" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "End navigation", stop)
            .build()
    }
}
