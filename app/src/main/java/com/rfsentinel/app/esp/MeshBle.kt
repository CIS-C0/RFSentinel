package com.rfsentinel.app.esp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.rfsentinel.app.link.MeshNode
import com.rfsentinel.app.link.MeshProto
import java.util.UUID

/**
 * A Meshtastic or MeshCore radio paired over Bluetooth, read while scanning: every node it hears lands on the map.
 * Only listens: it asks for the node list and never changes the radio. The radio serves one app at a time.
 */
@SuppressLint("MissingPermission")
object MeshBle {
    private const val TAG = "MeshBle"
    private const val MTU = 512
    private const val RECONNECT_MS = 10_000L
    private const val SETUP_TIMEOUT_MS = 45_000L // leaves time for the pairing prompt
    private val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val TO_RADIO = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val FROM_RADIO = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    private val FROM_NUM = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var address: String? = null
    private var gatt: BluetoothGatt? = null
    private var onSightings: ((List<EspSighting>) -> Unit)? = null
    private var active = false
    private var live = false

    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var meshtastic: MeshtasticLog? = null
    private var meshCore: MeshCoreLog? = null
    // GATT runs one operation at a time.
    private val writes = ArrayDeque<ByteArray>()
    private var busy = false
    private var wantRead = false

    @Volatile var status = ""; private set
    var onStatusChanged: (() -> Unit)? = null
    private fun setStatus(s: String) { com.rfsentinel.app.usb.UsbWifi.logStatus("Mesh radio", s); status = s; onStatusChanged?.invoke() }

    class Found(val address: String, val name: String?, val rssi: Int)

    /** Looks for radios nearby for [ms]. MeshCore has no service of its own (Nordic UART): the name tells them apart in the list. */
    fun find(context: Context, ms: Long = 6_000L, done: (List<Found>) -> Unit) {
        val scanner = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        }.getOrNull()
        if (scanner == null) { done(emptyList()); return }
        val seen = LinkedHashMap<String, Found>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull()
                val addr = r.device.address
                main.post { seen[addr] = Found(addr, name ?: seen[addr]?.name, r.rssi) }
            }
        }
        val filters = listOf(MeshNode.SERVICE, NUS).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        try { scanner.startScan(filters, settings, cb) } catch (e: Exception) { Log.w(TAG, "search failed", e); com.rfsentinel.app.usb.UsbWifi.log("Mesh radio: Bluetooth search failed: ${com.rfsentinel.app.usb.UsbWifi.describe(e)}"); done(emptyList()); return }
        main.postDelayed({
            runCatching { scanner.stopScan(cb) }
            done(seen.values.sortedByDescending { it.rssi })
        }, ms)
    }

    /** Starts (or keeps) the link to the radio at [radio] while scanning. */
    fun start(context: Context, radio: String, sightings: (List<EspSighting>) -> Unit) = main.post {
        onSightings = sightings
        if (active && address == radio) return@post
        stopNow()
        appContext = context.applicationContext
        address = radio
        active = true
        connect()
    }

    fun stop() = main.post { stopNow() }

    private fun stopNow() {
        active = false
        main.removeCallbacks(connector); main.removeCallbacks(stall)
        drop()
        setStatus("")
    }

    private fun drop() {
        gatt?.let { g -> runCatching { g.disconnect(); g.close() } }
        gatt = null; live = false; busy = false; wantRead = false
        writes.clear(); toRadio = null; fromRadio = null; meshtastic = null; meshCore = null
    }

    private val connector = Runnable { connect() }
    private val stall = Runnable { if (active && !live) lost("mesh radio: no answer - reconnecting…") }

    private fun lost(why: String) {
        drop()
        if (!active) return
        setStatus(why)
        main.removeCallbacks(stall); main.removeCallbacks(connector)
        main.postDelayed(connector, RECONNECT_MS)
    }

    private fun connect() {
        val ctx = appContext ?: return
        if (!active) return
        if (!OuiSpyBle.canConnect(ctx)) { setStatus("Mesh radio: allow \"Nearby devices\" to connect to it"); return }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) { lost("Mesh radio: Bluetooth is off"); return }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        setStatus("Mesh radio: connecting…")
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        main.postDelayed(stall, SETUP_TIMEOUT_MS)
    }

    private fun send(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION") run { c.value = value; g.writeCharacteristic(c) }
        }

    private fun pump(g: BluetoothGatt) {
        if (busy) return
        val c = toRadio ?: return
        val next = writes.removeFirstOrNull()
        if (next != null) { busy = send(g, c, next); return }
        if (wantRead) { wantRead = false; fromRadio?.let { busy = g.readCharacteristic(it) } }
    }

    private fun report(s: EspSighting?, count: Int, what: String) {
        if (!live) { live = true; main.removeCallbacks(stall) }
        s?.let { onSightings?.invoke(listOf(it)) }
        setStatus("$what · live · $count node${if (count == 1) "" else "s"} heard")
    }

    private fun onFrame(g: BluetoothGatt, f: ByteArray) {
        val log = meshCore ?: return
        if (MeshCoreLog.isSelfInfo(f)) { writes.addLast(MeshCoreLog.GET_CONTACTS); pump(g) }
        report(log.feed(f), log.heard.size, "MeshCore radio")
    }

    private fun onRead(g: BluetoothGatt, value: ByteArray) {
        busy = false
        val log = meshtastic
        if (value.isNotEmpty() && log != null) {
            wantRead = true
            val m = runCatching { MeshProto.readFromRadio(value) }.getOrNull()
            if (m != null) report(log.feed(m), log.heard.size, "Meshtastic radio")
        }
        pump(g)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("Mesh radio: connected, setting up…")
                    if (!g.requestMtu(MTU)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) lost("Mesh radio: out of reach - reconnecting…")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { main.post { if (g === gatt) g.discoverServices() } }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                if (g !== gatt) return@post
                val mt = g.getService(MeshNode.SERVICE)
                val nus = g.getService(NUS)
                val notify = if (mt != null) {
                    toRadio = mt.getCharacteristic(TO_RADIO); fromRadio = mt.getCharacteristic(FROM_RADIO)
                    meshtastic = MeshtasticLog()
                    mt.getCharacteristic(FROM_NUM)
                } else {
                    toRadio = nus?.getCharacteristic(NUS_WRITE)
                    meshCore = MeshCoreLog()
                    nus?.getCharacteristic(NUS_NOTIFY)
                }
                val cccd = notify?.getDescriptor(CCCD)
                if (cccd == null || toRadio == null) { setStatus("Mesh radio: not a Meshtastic or MeshCore radio"); return@post }
                g.setCharacteristicNotification(notify, true)
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post {
                if (g !== gatt) return@post
                writes.addLast(if (meshtastic != null) MeshProto.wantConfig(MeshProto.NODES_ONLY) else MeshCoreLog.APP_START)
                pump(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post {
                if (g !== gatt) return@post
                busy = false
                if (meshtastic != null) wantRead = true
                pump(g)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            main.post { if (g === gatt) onRead(g, value) }
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value ?: ByteArray(0)
            main.post { if (g === gatt) onRead(g, v) }
        }

        private fun changed(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post {
                if (g !== gatt) return@post
                if (c.uuid == FROM_NUM) { wantRead = true; pump(g) } else if (c.uuid == NUS_NOTIFY) onFrame(g, value)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = changed(g, c, value)

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            changed(g, c, c.value ?: return)
        }
    }
}
