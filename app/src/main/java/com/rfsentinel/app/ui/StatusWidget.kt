package com.rfsentinel.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.rfsentinel.app.MainActivity
import com.rfsentinel.app.R
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions

/** Home-screen widget: scanner status, live counts, and a start/stop button. */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (ScanForegroundService.isRunning) {
                ScanForegroundService.stop(context)
            } else if (Permissions.missingRequired(context).isEmpty()) {
                // Widget clicks are exempt from background foreground-service start limits.
                runCatching { ScanForegroundService.start(context) }.onFailure { openApp(context) }
            } else {
                openApp(context)
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.rfsentinel.app.widget.TOGGLE"
        @Volatile private var devices = 0
        @Volatile private var flagged = 0

        fun updateAll(context: Context, deviceCount: Int = devices, flaggedCount: Int = flagged) {
            devices = deviceCount
            flagged = flaggedCount
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val running = ScanForegroundService.isRunning
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            views.setTextViewText(R.id.widgetStatus, if (running) "Scanning" else "Idle")
            views.setTextViewText(
                R.id.widgetCounts,
                if (running) "$devices nearby · $flagged flagged" else "Tap ▶ to start"
            )
            views.setTextViewText(R.id.widgetToggle, if (running) "■" else "▶")
            views.setOnClickPendingIntent(
                R.id.widgetToggle,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, StatusWidget::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setOnClickPendingIntent(
                R.id.widgetBody,
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            manager.updateAppWidget(ids, views)
        }

        private fun openApp(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
