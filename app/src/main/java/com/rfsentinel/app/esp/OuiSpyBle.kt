package com.rfsentinel.app.esp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rfsentinel.app.util.Permissions

/**
 * Connects to an OUI-SPY board running the "App-Controlled" (Bluetooth)
 * firmware, like its companion app does, but only for detection: it switches
 * on the Flock-BLE, Flock-WiFi, Sky Spy and Detector engines, listens to
 * Detection Events, and switches them off again when scanning stops. No cable
 * needed; the board just needs power. Reconnects when the board comes back.
 */
object OuiSpyBle {

    private const val TAG = "OuiSpyBle"
    private const val MTU = 247 // detections are up to 155 bytes
    private const val RECONNECT_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var appContext: Context? = null
    private var address: String? = null
    private var onSightings: ((List<EspSighting>) -> Unit)? = null
    @Volatile private var active = false
    private var inSpool = false
    private var detections = 0
    /** GATT writes must be sent one at a time. */
    private val pending = ArrayDeque<ByteArray>()

    @Volatile var status: String = ""
        private set
    var onStatusChanged: (() -> Unit)? = null

    private fun setStatus(s: String) { status = s; onStatusChanged?.invoke() }

    fun canConnect(context: Context) = Build.VERSION.SDK_INT < 31 ||
        Permissions.granted(context, android.Manifest.permission.BLUETOOTH_CONNECT)

    /** Starts (or keeps) the link to the board at [boardAddress] while scanning. */
    fun start(context: Context, boardAddress: String, sightings: (List<EspSighting>) -> Unit) = main.post {
        onSightings = sightings
        if (active && address == boardAddress) return@post
        stopNow()
        appContext = context.applicationContext
        address = boardAddress
        active = true
        connect()
    }

    fun stop() = main.post { stopNow() }

    @SuppressLint("MissingPermission") // checked by canConnect()
    private fun stopNow() {
        active = false
        main.removeCallbacksAndMessages(null)
        val g = gatt ?: return
        // Switch our engines off so the board goes quiet, then let go.
        val ctl = g.getService(OuiSpyBleProtocol.SERVICE)?.getCharacteristic(OuiSpyBleProtocol.ENGINE_CONTROL)
        if (ctl != null) runCatching { write(g, ctl, OuiSpyBleProtocol.disableAll()) }
        main.postDelayed({ runCatching { g.disconnect(); g.close() } }, 300)
        gatt = null
        setStatus("")
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val ctx = appContext ?: return
        val addr = address ?: return
        if (!active) return
        if (!canConnect(ctx)) { setStatus("OUI-SPY: allow \"Nearby devices\" to connect to the board"); return }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) { setStatus("OUI-SPY: Bluetooth is off"); retry(); return }
        val device = runCatching { adapter.getRemoteDevice(addr) }.getOrNull() ?: return
        setStatus("OUI-SPY: connecting…")
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retry() {
        if (!active) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ connect() }, RECONNECT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNext(g: BluetoothGatt) {
        val next = pending.removeFirstOrNull() ?: run {
            setStatus("OUI-SPY · live · Flock, Sky Spy and Detector engines on")
            return
        }
        val ctl = g.getService(OuiSpyBleProtocol.SERVICE)?.getCharacteristic(OuiSpyBleProtocol.ENGINE_CONTROL) ?: return
        write(g, ctl, next)
    }

    private fun handle(value: ByteArray) {
        when {
            OuiSpyBleProtocol.isSpoolHeader(value) -> { inSpool = true; return }
            OuiSpyBleProtocol.isSpoolEnd(value) -> { inSpool = false; return }
            inSpool -> return // stored while no phone was connected: old, no location
        }
        val s = OuiSpyBleProtocol.decode(value) ?: return
        detections++
        onSightings?.invoke(listOf(s))
        setStatus("OUI-SPY · live · $detections detection${if (detections == 1) "" else "s"}")
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("OUI-SPY: connected, setting up…")
                    if (!g.requestMtu(MTU)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                    gatt = null
                    if (active) { setStatus("OUI-SPY: out of reach - reconnecting…"); retry() }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            main.post { if (g === gatt) g.discoverServices() }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                if (g !== gatt) return@post
                val svc = g.getService(OuiSpyBleProtocol.SERVICE)
                val det = svc?.getCharacteristic(OuiSpyBleProtocol.DETECTION_EVENTS)
                if (det == null) {
                    setStatus("OUI-SPY: this board's firmware isn't the Bluetooth (App-Controlled) version")
                    return@post
                }
                g.setCharacteristicNotification(det, true)
                val cccd = det.getDescriptor(OuiSpyBleProtocol.CCCD) ?: return@post
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post {
                if (g !== gatt) return@post
                inSpool = false
                pending.clear()
                OuiSpyBleProtocol.ENGINES.forEach { pending.addLast(OuiSpyBleProtocol.enable(it)) }
                writeNext(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post {
                if (g !== gatt) return@post
                if (st != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "engine write failed: $st")
                writeNext(g)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == OuiSpyBleProtocol.DETECTION_EVENTS) main.post { handle(value) }
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value ?: return
            if (c.uuid == OuiSpyBleProtocol.DETECTION_EVENTS) main.post { handle(v) }
        }
    }
}
