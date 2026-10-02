package com.rfsentinel.app.esp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

/**
 * ESP32 boards on USB (OTG cable), used alongside the phone's own radios while
 * scanning. Supports OUI-Spy (Flock-You, Detector, Sky Spy) and GhostESP.
 * Android asks once per board for permission; plugging a board in while
 * scanning starts it.
 */
object EspBoards {

    private const val ACTION_PERMISSION = "com.rfsentinel.app.ESP_USB_PERMISSION"

    private class Running(val reader: EspReader, val thread: Thread)

    private val running = HashMap<String, Running>()
    private var receiver: BroadcastReceiver? = null
    private var onSightings: ((List<EspSighting>) -> Unit)? = null

    /** Latest status per board, for Settings and the notification. */
    @Volatile var status: String = ""
        private set
    var onStatusChanged: (() -> Unit)? = null

    fun isEsp32(d: UsbDevice) = SerialPort.chipOf(d.vendorId, d.productId) != null

    /** Starts every attached board (asking permission where needed) and watches for new ones. */
    @Synchronized
    fun start(context: Context, sightings: (List<EspSighting>) -> Unit) {
        val app = context.applicationContext
        onSightings = sightings
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED, ACTION_PERMISSION -> connectAll(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            deviceOf(intent)?.let { stopBoard(it.deviceName) }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_PERMISSION)
            }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        connectAll(app)
    }

    @Synchronized
    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        running.keys.toList().forEach { stopBoard(it) }
        onSightings = null
        setStatus("")
    }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    @Synchronized
    private fun connectAll(context: Context) {
        val mgr = context.getSystemService(UsbManager::class.java) ?: return
        val boards = runCatching { mgr.deviceList.values.filter { isEsp32(it) } }.getOrDefault(emptyList())
        if (boards.isEmpty() && running.isEmpty()) setStatus("")
        for (d in boards) {
            if (running.containsKey(d.deviceName)) continue
            if (!mgr.hasPermission(d)) {
                val pi = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE
                )
                setStatus("ESP32 found - allow USB access on the prompt")
                runCatching { mgr.requestPermission(d, pi) }
                continue
            }
            val conn = mgr.openDevice(d) ?: continue
            val port = SerialPort.open(conn, d)
            if (port == null) { runCatching { conn.close() }; setStatus("ESP32: couldn't open its USB serial port"); continue }
            val reader = EspReader(port, ::setStatus) { list -> onSightings?.invoke(list) }
            val t = Thread({
                runCatching { reader.run() }
                runCatching { port.close() }
                synchronized(this) { running.remove(d.deviceName) }
            }, "esp32-${d.deviceName}")
            running[d.deviceName] = Running(reader, t)
            t.start()
        }
    }

    @Synchronized
    private fun stopBoard(name: String) {
        running.remove(name)?.reader?.stop()
        if (running.isEmpty()) setStatus("ESP32 disconnected")
    }

    private fun setStatus(s: String) {
        status = s
        onStatusChanged?.invoke()
    }
}
