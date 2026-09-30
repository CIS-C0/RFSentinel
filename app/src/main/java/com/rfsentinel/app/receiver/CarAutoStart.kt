package com.rfsentinel.app.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs

/**
 * Starts scanning when the phone connects to your car (its Bluetooth, chosen in
 * Settings, or Android Auto) and stops it when you leave - but only if it was
 * started this way, so a scan you started yourself is never cut off.
 *
 * Android lets an app start a foreground service from the background for
 * Bluetooth broadcasts that need BLUETOOTH_CONNECT, which is what makes this work.
 */
object CarAutoStart {

    private const val TAG = "CarAutoStart"

    fun onCarConnected(context: Context, why: String) {
        if (!Prefs.carAutoStart(context) || ScanForegroundService.isRunning) return
        if (Permissions.missingRequired(context).isNotEmpty()) return
        try {
            ScanForegroundService.start(context)
            Prefs.setStartedByCar(context, true)
            Log.i(TAG, "Scanning started: $why")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start scanning ($why)", e)
        }
    }

    fun onCarDisconnected(context: Context, why: String) {
        if (!Prefs.startedByCar(context)) return
        Prefs.setStartedByCar(context, false)
        if (ScanForegroundService.isRunning) {
            ScanForegroundService.stop(context)
            Log.i(TAG, "Scanning stopped: $why")
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
