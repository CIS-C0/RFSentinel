package com.rfsentinel.app.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.launch
import com.rfsentinel.app.RFSentinelApp
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.service.TripRecorder
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs

/**
 * Starts scanning (and, if chosen, a trace) when the phone connects to your car (its
 * Bluetooth, chosen in Settings, or Android Auto) and stops it when you leave - but only
 * if it was started this way, so a scan or trace you started yourself is never cut off.
 *
 * Android lets an app start a foreground service from the background for
 * Bluetooth broadcasts that need BLUETOOTH_CONNECT, which is what makes this work.
 */
object CarAutoStart {

    private const val TAG = "CarAutoStart"

    fun onCarConnected(context: Context, why: String) {
        val scan = Prefs.carAutoStart(context)
        val record = Prefs.carAutoRecord(context)
        if (!scan && !record) return
        if (Permissions.missingRequired(context).isNotEmpty()) return
        try {
            if (!ScanForegroundService.isRunning) {
                ScanForegroundService.start(context)
                Prefs.setStartedByCar(context, true)
                Log.i(TAG, "Scanning started: $why")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start scanning ($why)", e)
            return
        }
        if (record && !TripRecorder.isRecording) {
            val app = context.applicationContext as RFSentinelApp
            app.appScope.launch {
                runCatching { TripRecorder.start(app) }.onFailure { Log.w(TAG, "Could not start the trace ($why)", it) }.getOrNull() ?: return@launch
                Prefs.setRecordingByCar(app, true)
                Log.i(TAG, "Trace started: $why")
                refreshLocation(app)
            }
        }
    }

    fun onCarDisconnected(context: Context, why: String) {
        if (Prefs.recordingByCar(context)) {
            Prefs.setRecordingByCar(context, false)
            // Detached at once, so the scan stopping next can't end it a second time; written in the app's scope.
            TripRecorder.detach()?.let { trip ->
                val app = context.applicationContext as RFSentinelApp
                app.appScope.launch { runCatching { TripRecorder.persist(app, trip) } }
                Log.i(TAG, "Trace saved: $why")
                if (!Prefs.startedByCar(context)) refreshLocation(app)
            }
        }
        if (!Prefs.startedByCar(context)) return
        Prefs.setStartedByCar(context, false)
        if (ScanForegroundService.isRunning) {
            ScanForegroundService.stop(context)
            Log.i(TAG, "Scanning stopped: $why")
        }
    }

    /** The running scan switches its GPS rate for the trace starting or ending. */
    private fun refreshLocation(context: Context) {
        if (ScanForegroundService.isRunning) runCatching {
            context.startService(Intent(context, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_REFRESH_LOCATION))
        }
    }
}

/** Bluetooth connect / disconnect of one of the chosen car devices. */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        if (address !in Prefs.carDevices(context)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> CarAutoStart.onCarConnected(context, "car Bluetooth connected")
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> CarAutoStart.onCarDisconnected(context, "car Bluetooth disconnected")
        }
    }
}
