package com.rfsentinel.app.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat

/**
 * Whether the phone is currently connected to Android Auto (projection) or
 * running on an Android Automotive head unit. Used to route alert audio to the
 * car speakers the way navigation prompts are.
 *
 * Reads what androidx.car.app's [CarConnection] reads, but not through it: its LiveData never closes the
 * cursor Android Auto answers with, and an open cursor ties this app's life to Android Auto's process
 * (the phone killed RF Sentinel when that process died: "depends on provider ...ProjectionStateProvider").
 */
object CarState {

    @Volatile var connected = false
        private set

    // CarConnection's contract (androidx.car.app 1.7.0).
    private const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    private const val COLUMN = "CarConnectionState"
    private val URI: Uri = Uri.parse("content://androidx.car.app.connection")

    private val main = Handler(Looper.getMainLooper())
    /** One thread: the answers arrive in the order the changes did. */
    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Call once from the main thread (Application.onCreate). */
    fun start(context: Context) {
        val app = context.applicationContext
        runCatching {
            // Android Automotive: this is the car.
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) { set(app, true); return }
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = read(app)
            }, IntentFilter(ACTION_UPDATED), ContextCompat.RECEIVER_EXPORTED)
            read(app)
        }
    }

    private fun read(app: Context) = reader.execute {
        // An unstable client, closed at once: Android Auto dying is an exception here, never this app's end.
        val type = runCatching {
            app.contentResolver.acquireUnstableContentProviderClient(URI)?.let { client ->
                try {
                    client.query(URI, arrayOf(COLUMN), null, null, null)?.use { c ->
                        val col = c.getColumnIndex(COLUMN)
                        if (col >= 0 && c.moveToNext()) c.getInt(col) else null
                    }
                } finally { client.close() }
            }
        }.getOrNull() ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        main.post { set(app, type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE) }
    }

    private fun set(app: Context, now: Boolean) {
        if (now && !connected) com.rfsentinel.app.receiver.CarAutoStart.onCarConnected(app, "Android Auto connected")
        if (!now && connected) {
            com.rfsentinel.app.receiver.CarAutoStart.onCarDisconnected(app, "Android Auto disconnected")
            // The next drive starts a fresh alert conversation.
            com.rfsentinel.app.util.CarMessages.clear(app)
        }
        connected = now
    }
}
