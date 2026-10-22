package com.rfsentinel.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rfsentinel.app.util.Permissions
import java.security.KeyPair
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Sentinel Link: RF Sentinel phones near each other share what they hear over Bluetooth LE, so every
 * linked phone shows the same devices, radar and map, whichever phone (or its ESP32 board, USB adapter,
 * SDR) heard a device first.
 *
 * No Android pairing: each phone runs a small GATT server and connects to the others as a client. Phones
 * find each other by an advert that only phones of the group can read ([LinkCrypto.groupTag]); everything
 * on a link is end-to-end encrypted with keys from the group key ([LinkCrypto]). A new phone joins by a
 * 6-digit code shown on both screens (see [host] / [join]).
 *
 * All state lives on the "rf-link" thread.
 */
@SuppressLint("MissingPermission") // every entry point checks canUse()
object PhoneLink {
    private const val TAG = "PhoneLink"

    val SERVICE: UUID = UUID.fromString("7c2f5a10-3b6e-4d1a-9f0e-52a1c0d3b7e1")
    /** Written by the phone that connected. */
    private val RX: UUID = UUID.fromString("7c2f5a11-3b6e-4d1a-9f0e-52a1c0d3b7e1")
    /** Notified to the phone that connected. */
    private val TX: UUID = UUID.fromString("7c2f5a12-3b6e-4d1a-9f0e-52a1c0d3b7e1")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Manufacturer id of the link adverts (reserved for tests: no real maker uses it). */
    const val COMPANY = 0xFFFF
    private const val AD_MEMBER = 0xA1
    private const val AD_PAIR = 0xA2

    private const val MTU = 517
    private const val MTU_FALLBACK_MS = 2_500L
    private const val SETUP_TIMEOUT_MS = 25_000L
    private const val IDLE_TIMEOUT_MS = 60_000L
    private const val KEEPALIVE_MS = 20_000L
    private const val SEND_STUCK_MS = 15_000L
    private const val RETRY_MS = 30_000L
    /** A Sentinel with a lower id connects to us; after this long without a link we connect ourselves. */
    private const val WAIT_FOR_LOWER_MS = 45_000L
    private const val MAX_LINKS = 6
    /** More waiting than this and the link is too slow: new batches skip it rather than pile up. */
    private const val MAX_QUEUED_BYTES = 256 * 1024
    private const val PAIR_TIMEOUT_MS = 3 * 60_000L
    private const val PAIR_PICK_MS = 2_500L
    private const val ADVERT_RECHECK_MS = 5_000L
    private const val TICK_MS = 5_000L

    private val thread = HandlerThread("rf-link").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var ctx: Context? = null
    private var sharing = false
    private var groupKey: ByteArray? = null
    private var myId = 0L
    private var names: Map<Long, String> = emptyMap()

    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var serverReady = false
    private val serverMtu = HashMap<String, Int>()

    private var advertised: String? = null
    private var advertCallback: AdvertiseCallback? = null
    private var pairTag = ByteArray(0)

    private val links = ArrayList<Link>()
    private val seen = SeenBatches()
    private val lastTry = HashMap<String, Long>()
    private val waitingSince = HashMap<Long, Long>()
    private val lastAdvert = ConcurrentHashMap<String, Long>()
    private var tagSlot = Long.MIN_VALUE
    private var groupTags: List<Pair<Long, ByteArray>> = emptyList()
    private var problem: String? = null
    private var ticking = false

    private var pairing: Pairing? = null

    /** The status line ("" while Sentinel Link isn't running). */
    @Volatile var status: String = ""
        private set

    /** A Sentinel this phone is linked to now. */
    data class Peer(val id: Long, val name: String, val since: Long)

    @Volatile var linked: List<Peer> = emptyList()
        private set

    /** When each Sentinel was last heard from (directly or relayed), wall clock. */
    val lastHeard = ConcurrentHashMap<Long, Long>()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    /** What another Sentinel heard ([LinkCodec.Batch]) and that Sentinel's name; called on the link thread. */
    @Volatile private var onBatch: ((LinkCodec.Batch, String) -> Unit)? = null

    fun canUse(context: Context) = Build.VERSION.SDK_INT < 31 ||
        (Permissions.granted(context, android.Manifest.permission.BLUETOOTH_CONNECT) &&
            Permissions.granted(context, android.Manifest.permission.BLUETOOTH_ADVERTISE))

    fun nameOf(id: Long): String = names[id] ?: "Sentinel"

    // ---------------------------------------------------------------- sharing (the scan service)

    /** Starts sharing with the group (scanning started with a group and sharing on). */
    fun start(context: Context, batches: (LinkCodec.Batch, String) -> Unit) {
        h.post {
            ctx = context.applicationContext
            onBatch = batches
            if (sharing) return@post
            sharing = true
            loadGroup()
            ensureServer()
            refreshAdvert()
            startTicking()
            updateStatus()
        }
    }

    fun stop() = h.post {
        if (!sharing) return@post
        sharing = false
        onBatch = null
        links.filter { !it.pairing }.forEach { close(it) }
        refreshAdvert()
        maybeStopServer()
        updateStatus()
    }

    /** The group was joined, left or replaced: links of the old group end. */
    fun groupChanged() = h.post {
        links.filter { !it.pairing }.forEach { close(it) }
        seen.clear(); waitingSince.clear(); lastTry.clear()
        loadGroup()
        refreshAdvert()
        if (sharing && groupKey == null) { sharing = false; maybeStopServer() }
        updateStatus()
    }

    /** The names changed (this phone renamed): tell the linked Sentinels. */
    fun meChanged() = h.post { loadGroup(); links.filter { it.ready }.forEach { sendInfo(it) } }

    /** What this phone heard in the last second, to every linked Sentinel. */
    fun send(b: LinkCodec.Batch) = h.post {
        if (!sharing) return@post
        seen.first(b.origin, b.seq)
        val bytes = LinkCodec.batch(b)
        links.filter { it.ready }.forEach { if (it.queuedBytes < MAX_QUEUED_BYTES) sendSealed(it, bytes) }
    }

    /**
     * A scan result from the scan service: true for a Sentinel Link advert (handled here, not a device to list).
     * Called on the scan's thread.
     */
    fun onScanResult(r: ScanResult): Boolean {
        val d = r.scanRecord?.getManufacturerSpecificData(COMPANY) ?: return false
        val type = d.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        if (!(type == AD_MEMBER && d.size == 9) && !(type == AD_PAIR && d.size == 5)) return false
        if (type == AD_MEMBER) {
            val addr = r.device.address
            val now = SystemClock.elapsedRealtime()
            val last = lastAdvert[addr]
            if (last == null || now - last >= ADVERT_RECHECK_MS) {
                lastAdvert[addr] = now
                if (lastAdvert.size > 500) lastAdvert.entries.removeAll { now - it.value > 60_000L }
                h.post { onMemberAdvert(r.device, d) }
            }
        }
        return true
    }

    private fun loadGroup() {
        val c = ctx ?: return
        groupKey = LinkStore.groupKey(c)
        myId = LinkStore.myId(c)
        names = LinkStore.members(c).associate { it.id to it.name }
        tagSlot = Long.MIN_VALUE
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        h.postDelayed(::tick, TICK_MS)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        for (l in links.toList()) {
            when {
                !l.ready && !l.pairing && now - l.createdAt > SETUP_TIMEOUT_MS -> close(l)
                now - l.lastRx > IDLE_TIMEOUT_MS && !l.pairing -> close(l)
                l.busy && now - l.busySince > SEND_STUCK_MS -> close(l)
                l.ready && now - l.lastTx > KEEPALIVE_MS -> sendInfo(l)
            }
        }
        // Bluetooth turned on or "Nearby devices" allowed since: start now.
        if (sharing && server == null) { ensureServer(); updateStatus() }
        refreshAdvert()
        if (sharing || pairing != null || links.isNotEmpty()) h.postDelayed(::tick, TICK_MS) else ticking = false
    }

    private fun updateStatus() {
        val peers = links.filter { it.ready }.mapNotNull { l -> l.peer?.let { Peer(it.id, it.name, l.readyAt) } }
            .groupBy { it.id }.map { (_, v) -> v.minBy { it.since } }
        linked = peers
        val n = peers.size
        status = when {
            !sharing -> ""
            problem != null -> "Sentinel Link: $problem"
            n == 0 -> "Sentinel Link · waiting for Sentinels"
            else -> "Sentinel Link · $n Sentinel${if (n == 1) "" else "s"} linked"
        }
        changed()
    }

    private fun adapter() = ctx?.getSystemService(BluetoothManager::class.java)?.adapter

    // ---------------------------------------------------------------- GATT server

    private fun ensureServer() {
        val c = ctx ?: return
        if (server != null) return
        if (!canUse(c)) { problem = "allow \"Nearby devices\" to link Sentinels"; return }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) { problem = "Bluetooth is off"; return }
        problem = null
        val s = runCatching { c.getSystemService(BluetoothManager::class.java)?.openGattServer(c, serverCallback) }.getOrNull()
        if (s == null) { problem = "Bluetooth link failed to start"; return }
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(rx); svc.addCharacteristic(tx)
        server = s
        txChar = tx
        serverReady = false
        if (!s.addService(svc)) { problem = "Bluetooth link failed to start"; s.close(); server = null }
    }

    private fun maybeStopServer() {
        if (sharing || pairing != null) return
        links.toList().forEach { close(it) }
        stopAdvert()
        runCatching { server?.close() }
        server = null; txChar = null; serverReady = false
        serverMtu.clear()
    }

    private fun serverLink(device: BluetoothDevice, create: Boolean): ServerLink? {
        links.firstOrNull { it is ServerLink && it.address == device.address }?.let { return it as ServerLink }
        if (!create || server == null) return null
        if (links.size >= MAX_LINKS + 2) return null
        return ServerLink(device).also { l ->
            serverMtu[device.address]?.let { l.payload = it - 3 }
            links += l
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            h.post {
                serverReady = status == BluetoothGatt.GATT_SUCCESS
                if (!serverReady) problem = "Bluetooth link failed to start"
                refreshAdvert(); updateStatus()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) h.post {
                serverMtu.remove(device.address)
                serverLink(device, false)?.let { close(it, cancel = false) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            h.post {
                serverMtu[device.address] = mtu
                serverLink(device, false)?.payload = mtu - 3
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val ok = descriptor.uuid == CCCD && !preparedWrite
            if (ok) h.post {
                val l = serverLink(device, true) ?: return@post
                l.open = value?.firstOrNull()?.toInt() == 1
                pump(l)
            }
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, value)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0, 0)) }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null) }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val ok = characteristic.uuid == RX && !preparedWrite && offset == 0 && value != null
            // Queued before answering: the next piece is only written after the answer, so pieces stay in order.
            if (ok) h.post { serverLink(device, true)?.let { onData(it, value!!) } }
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            h.post { serverLink(device, false)?.let { onSent(it) } }
        }
    }

    // ---------------------------------------------------------------- adverts

    private fun refreshAdvert() {
        val gk = groupKey
        val now = System.currentTimeMillis()
        val want: Pair<String, ByteArray>? = when {
            server == null || !serverReady -> null
            pairing?.host == true -> "pair" to (byteArrayOf(AD_PAIR.toByte()) + pairTag)
            sharing && gk != null -> {
                val slot = LinkCrypto.slot(now)
                "member $slot" to (byteArrayOf(AD_MEMBER.toByte()) + LinkCrypto.groupTag(gk, slot) + LinkCrypto.nodeTag(gk, slot, myId))
            }
            else -> null
        }
        if (want?.first == advertised) return
        stopAdvert()
        if (want == null) return
        val advertiser = runCatching { adapter()?.bluetoothLeAdvertiser }.getOrNull() ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (pairing?.host == true) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // No name, no services: only the tag, meaningless without the group key.
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addManufacturerData(COMPANY, want.second).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                h.post {
                    if (advertCallback !== this) return@post
                    Log.w(TAG, "advertising failed: $errorCode")
                    advertised = null; advertCallback = null
                    pairing?.takeIf { it.host }?.let { endPairing(it, "This phone can't advertise over Bluetooth ($errorCode)"); return@post }
                    problem = "this phone can't be found by other Sentinels (advertising failed)"
                    updateStatus()
                }
            }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                h.post { if (advertCallback === this && problem?.contains("advertising") == true) { problem = null; updateStatus() } }
            }
        }
        try {
            advertiser.startAdvertising(settings, data, cb)
            advertCallback = cb
            advertised = want.first
        } catch (e: Exception) {
            Log.w(TAG, "advertising unavailable", e)
        }
    }

    private fun stopAdvert() {
        val cb = advertCallback ?: return
        runCatching { adapter()?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        advertCallback = null
        advertised = null
    }

    /** Which Sentinel of the group sent this advert: (true, id) for a known one, (true, null) for one not met yet. */
    private fun identify(d: ByteArray): Pair<Boolean, Long?> {
        val gk = groupKey ?: return false to null
        val slot = LinkCrypto.slot(System.currentTimeMillis())
        if (slot != tagSlot) {
            tagSlot = slot
            groupTags = (slot - 1..slot + 1).map { it to LinkCrypto.groupTag(gk, it) }
        }
        val g = d.copyOfRange(1, 5)
        val s = groupTags.firstOrNull { it.second.contentEquals(g) }?.first ?: return false to null
        val n = d.copyOfRange(5, 9)
        return true to names.keys.firstOrNull { LinkCrypto.nodeTag(gk, s, it).contentEquals(n) }
    }

    private fun onMemberAdvert(device: BluetoothDevice, d: ByteArray) {
        if (!sharing || server == null) return
        val (ours, id) = identify(d)
        if (!ours || id == myId) return
        val now = SystemClock.elapsedRealtime()
        if (id != null && links.any { it.ready && it.peer?.id == id }) { waitingSince.remove(id); return }
        if (links.any { it.address == device.address }) return
        if (links.count { !it.pairing } >= MAX_LINKS) return
        // The lower id connects; wait for it, unless it never does.
        if (id != null && id < myId) {
            val since = waitingSince.getOrPut(id) { now }
            if (now - since < WAIT_FOR_LOWER_MS) return
        }
        val last = lastTry[device.address]
        if (last != null && now - last < RETRY_MS) return
        lastTry[device.address] = now
        if (lastTry.size > 200) lastTry.entries.removeAll { now - it.value > RETRY_MS }
        connect(device, pairing = false)
    }

    // ---------------------------------------------------------------- links

    private abstract class Link(val address: String) {
        var pairing = false
        var open = false
        var ready = false
        var readyAt = 0L
        var closed = false
        var peer: LinkCodec.Member? = null
        val reasm = LinkFrames.Reassembler()
        var payload = 20
        val queue = ArrayDeque<ByteArray>()
        var queuedBytes = 0
        var busy = false
        var busySince = 0L
        var closeWhenDrained = false
        val createdAt = SystemClock.elapsedRealtime()
        var lastRx = createdAt
        var lastTx = createdAt
        // Session.
        val nonce = LinkCrypto.random(LinkCrypto.NONCE_BYTES)
        var txKey: ByteArray? = null
        var rxKey: ByteArray? = null
        var txCounter = 0L
        var rxCounter = -1L
        abstract val isClient: Boolean
        /** Starts sending one piece; false when the radio can't take it now. */
        abstract fun write(chunk: ByteArray): Boolean
        abstract fun disconnect(cancel: Boolean)
    }

    private class ServerLink(val device: BluetoothDevice) : Link(device.address) {
        override val isClient = false
        override fun write(chunk: ByteArray): Boolean {
            val s = server ?: return false
            val c = txChar ?: return false
            return runCatching {
                if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(device, c, false, chunk) == BluetoothStatusCodes.SUCCESS
                else {
                    @Suppress("DEPRECATION") c.value = chunk
                    @Suppress("DEPRECATION") s.notifyCharacteristicChanged(device, c, false)
                }
            }.getOrDefault(false)
        }
        override fun disconnect(cancel: Boolean) {
            // Not while this phone is also the client on the same connection: that would end both.
            if (cancel && links.none { it is ClientLink && it.address == address }) runCatching { server?.cancelConnection(device) }
        }
    }

    private class ClientLink(val device: BluetoothDevice) : Link(device.address) {
        override val isClient = true
        var gatt: BluetoothGatt? = null
        var rx: BluetoothGattCharacteristic? = null
        var discovering = false
        override fun write(chunk: ByteArray): Boolean {
            val g = gatt ?: return false
            val c = rx ?: return false
            return runCatching {
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                else {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION") c.value = chunk
                    @Suppress("DEPRECATION") g.writeCharacteristic(c)
                }
            }.getOrDefault(false)
        }
        override fun disconnect(cancel: Boolean) {
            val g = gatt ?: return
            gatt = null
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    private fun connect(device: BluetoothDevice, pairing: Boolean): ClientLink? {
        val c = ctx ?: return null
        val l = ClientLink(device).also { it.pairing = pairing }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                h.post {
                    if (l.closed || l.gatt !== g) { if (l.gatt !== g) runCatching { g.close() }; return@post }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (!g.requestMtu(MTU)) discover(l, g)
                        else h.postDelayed({ if (l.gatt === g) discover(l, g) }, MTU_FALLBACK_MS)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) close(l, cancel = false)
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                h.post { if (l.gatt === g) { if (status == BluetoothGatt.GATT_SUCCESS) l.payload = mtu - 3; discover(l, g) } }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                h.post {
                    if (l.gatt !== g) return@post
                    val svc = g.getService(SERVICE)
                    val rx = svc?.getCharacteristic(RX)
                    val tx = svc?.getCharacteristic(TX)
                    val cccd = tx?.getDescriptor(CCCD)
                    if (rx == null || cccd == null) { close(l); return@post }
                    l.rx = rx
                    g.setCharacteristicNotification(tx, true)
                    val ok = runCatching {
                        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                        else {
                            @Suppress("DEPRECATION") cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                        }
                    }.getOrDefault(false)
                    if (!ok) close(l)
                }
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                h.post {
                    if (l.gatt !== g) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) { close(l); return@post }
                    l.open = true
                    onClientOpen(l)
                }
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                h.post {
                    if (l.gatt !== g) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) close(l) else onSent(l)
                }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (c.uuid == TX) h.post { if (l.gatt === g) onData(l, value) }
            }
            @Deprecated("Android 12 and older")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33 || c.uuid != TX) return
                val v = c.value?.copyOf() ?: return
                h.post { if (l.gatt === g) onData(l, v) }
            }
        }
        val g = runCatching { device.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE) }.getOrNull() ?: return null
        l.gatt = g
        links += l
        startTicking()
        return l
    }

    private fun discover(l: ClientLink, g: BluetoothGatt) {
        if (l.discovering) return
        l.discovering = true
        if (!g.discoverServices()) close(l)
    }

    private fun close(l: Link, cancel: Boolean = true) {
        if (l.closed) return
        l.closed = true
        links.remove(l)
        l.disconnect(cancel)
        val p = pairing
        if (p != null && p.link === l && !p.done) endPairing(p, "Lost the other phone")
        if (l.ready) updateStatus()
    }

    private fun send(l: Link, payload: ByteArray) {
        if (l.closed) return
        LinkFrames.chunks(LinkFrames.frame(payload), l.payload).forEach { l.queue.addLast(it); l.queuedBytes += it.size }
        pump(l)
    }

    private fun pump(l: Link) {
        if (l.busy || !l.open || l.closed) return
        val next = l.queue.firstOrNull()
        if (next == null) {
            if (l.closeWhenDrained) h.postDelayed({ close(l) }, 1_500L)
            return
        }
        if (l.write(next)) {
            l.queue.removeFirst()
            l.queuedBytes -= next.size
            l.busy = true
            l.busySince = SystemClock.elapsedRealtime()
        } else {
            h.postDelayed({ pump(l) }, 100L)
        }
    }

    private fun onSent(l: Link) {
        l.busy = false
        l.lastTx = SystemClock.elapsedRealtime()
        pump(l)
    }

    private fun onData(l: Link, chunk: ByteArray) {
        if (l.closed) return
        l.lastRx = SystemClock.elapsedRealtime()
        val messages = try { l.reasm.feed(chunk) } catch (e: Exception) { close(l); return }
        for (m in messages) {
            if (l.closed) return
            try { handle(l, m) } catch (e: Exception) { Log.w(TAG, "bad message", e); close(l); return }
        }
    }

    private fun handle(l: Link, m: ByteArray) {
        when (m[0].toInt() and 0xFF) {
            LinkCodec.PAIR_HELLO -> hostPairHello(l, m)
            LinkCodec.PAIR_REPLY -> joinerPairReply(l, m)
            LinkCodec.PAIR_CONFIRM -> hostPairConfirm(l, m)
            LinkCodec.PAIR_GROUP -> joinerPairGroup(l, m)
            LinkCodec.PAIR_CANCEL -> pairing?.takeIf { it.link === l }?.let { endPairing(it, "The other phone cancelled") } ?: close(l)
            LinkCodec.HELLO -> serverHello(l, m)
            LinkCodec.HELLO_REPLY -> clientHelloReply(l, m)
            LinkCodec.SEALED -> sealedIn(l, m)
        }
    }

    // ---------------------------------------------------------------- linked Sentinels

    private fun onClientOpen(l: ClientLink) {
        if (l.pairing) { joinerOpen(l); return }
        val gk = groupKey ?: run { close(l); return }
        send(l, LinkCodec.hello(LinkCodec.HELLO, l.nonce, LinkCrypto.proof(gk, "c", l.nonce)))
    }

    private fun serverHello(l: Link, m: ByteArray) {
        val gk = groupKey
        if (l.isClient || l.txKey != null || gk == null || !sharing) { close(l); return }
        val (nc, proof) = LinkCodec.readHello(m)
        if (!MessageDigest.isEqual(proof, LinkCrypto.proof(gk, "c", nc))) { close(l); return }
        send(l, LinkCodec.hello(LinkCodec.HELLO_REPLY, l.nonce, LinkCrypto.proof(gk, "s", nc, l.nonce)))
        l.txKey = LinkCrypto.sessionKey(gk, nc, l.nonce, toServer = false)
        l.rxKey = LinkCrypto.sessionKey(gk, nc, l.nonce, toServer = true)
        sendInfo(l)
    }

    private fun clientHelloReply(l: Link, m: ByteArray) {
        val gk = groupKey
        if (!l.isClient || l.txKey != null || gk == null) { close(l); return }
        val (ns, proof) = LinkCodec.readHello(m)
        if (!MessageDigest.isEqual(proof, LinkCrypto.proof(gk, "s", l.nonce, ns))) { close(l); return }
        l.txKey = LinkCrypto.sessionKey(gk, l.nonce, ns, toServer = true)
        l.rxKey = LinkCrypto.sessionKey(gk, l.nonce, ns, toServer = false)
        sendInfo(l)
    }

    private fun sendSealed(l: Link, inner: ByteArray) {
        val key = l.txKey ?: return
        val c = l.txCounter++
        send(l, LinkCodec.sealed(c, LinkCrypto.seal(key, c, inner)))
    }

    private fun sendInfo(l: Link) {
        val c = ctx ?: return
        sendSealed(l, LinkCodec.info(LinkStore.me(c), LinkStore.members(c)))
        l.lastTx = SystemClock.elapsedRealtime()
    }

    private fun sealedIn(l: Link, m: ByteArray) {
        val key = l.rxKey ?: run { close(l); return }
        val (counter, data) = LinkCodec.readSealed(m)
        if (counter <= l.rxCounter) { close(l); return }
        val plain = LinkCrypto.open(key, counter, data) ?: run { close(l); return }
        l.rxCounter = counter
        when (plain[0].toInt() and 0xFF) {
            LinkCodec.INFO -> onInfo(l, plain)
            LinkCodec.BATCH -> onBatchIn(l, plain)
        }
    }

    private fun onInfo(l: Link, plain: ByteArray) {
        val c = ctx ?: return
        val (them, known) = LinkCodec.readInfo(plain)
        if (them.id == myId || them.id == 0L) { close(l); return }
        val renamed = l.peer?.name != them.name
        l.peer = them
        lastHeard[them.id] = System.currentTimeMillis()
        if (LinkStore.addMembers(c, listOf(them) + known)) names = LinkStore.members(c).associate { it.id to it.name }
        if (!l.ready) {
            l.ready = true
            l.readyAt = System.currentTimeMillis()
            waitingSince.remove(them.id)
            Log.i(TAG, "linked")
            keepOneLink(l)
            updateStatus()
        } else if (renamed) updateStatus()
    }

    /**
     * Two phones that connected to each other at once have two links: both keep the one the lower id opened
     * (and, between two the same way, the newest), so they agree without talking about it.
     */
    private fun keepOneLink(l: Link) {
        val id = l.peer?.id ?: return
        val opener = { x: Link -> if (x.isClient) myId else id }
        val keepId = minOf(myId, id)
        for (other in links.filter { it !== l && it.ready && it.peer?.id == id }) {
            val loser = when {
                opener(other) == keepId && opener(l) != keepId -> l
                else -> other
            }
            close(loser, cancel = false)
            if (loser === l) return
        }
    }

    private fun onBatchIn(l: Link, plain: ByteArray) {
        val b = LinkCodec.readBatch(plain) ?: return
        if (b.origin == myId || !seen.first(b.origin, b.seq)) return
        lastHeard[b.origin] = System.currentTimeMillis()
        l.peer?.id?.let { lastHeard[it] = System.currentTimeMillis() }
        onBatch?.invoke(b, nameOf(b.origin))
        if (b.hops < LinkCodec.MAX_HOPS) {
            val targets = links.filter { it !== l && it.ready && it.peer?.id != b.origin && it.queuedBytes < MAX_QUEUED_BYTES }
            if (targets.isNotEmpty()) {
                val bytes = LinkCodec.batch(b.relayed())
                targets.forEach { sendSealed(it, bytes) }
            }
        }
    }

    // ---------------------------------------------------------------- linking a new phone

    /** Tells the Sentinel Link screen how linking goes; called on the main thread. */
    interface PairListener {
        fun onStatus(text: String)
        /** The 6 digits to compare with the other phone. */
        fun onCode(code: String)
        fun onLinked(name: String)
        fun onEnded(message: String)
    }

    private class Pairing(val host: Boolean, val listener: PairListener) {
        val kp: KeyPair = LinkCrypto.ecKeyPair()
        val nonce = LinkCrypto.random(LinkCrypto.NONCE_BYTES)
        var link: Link? = null
        var toPeer: ByteArray? = null
        var fromPeer: ByteArray? = null
        var confirmed = false
        var peer: LinkCodec.Member? = null
        var done = false
        var scan: ScanCallback? = null
        val found = HashMap<String, Pair<BluetoothDevice, Int>>()
        var picking = false
    }

    private fun tell(p: Pairing, block: PairListener.() -> Unit) = main.post { p.listener.block() }

    /** Lets a new phone join: advertises for it and shows the code once it connects. */
    fun host(context: Context, listener: PairListener) = h.post {
        ctx = context.applicationContext
        pairing?.let { endPairing(it, null) }
        if (!canUse(context)) { main.post { listener.onEnded("Allow \"Nearby devices\" first") }; return@post }
        if (adapter()?.isEnabled != true) { main.post { listener.onEnded("Turn Bluetooth on first") }; return@post }
        if (!LinkStore.hasGroup(context)) { LinkStore.createGroup(context); loadGroup() }
        val p = Pairing(host = true, listener = listener)
        pairing = p
        pairTag = LinkCrypto.random(4)
        ensureServer()
        if (server == null) { endPairing(p, problem?.replaceFirstChar { it.uppercase() } ?: "Bluetooth link failed to start"); return@post }
        refreshAdvert()
        startTicking()
        h.postDelayed({ if (pairing === p && !p.done) endPairing(p, "No phone joined in time") }, PAIR_TIMEOUT_MS)
        tell(p) { onStatus("On the other phone, tap \"Join a Sentinel\"…") }
    }

    /** Joins the group of a phone that's hosting ("Link up a Sentinel") nearby. */
    fun join(context: Context, listener: PairListener) = h.post {
        ctx = context.applicationContext
        pairing?.let { endPairing(it, null) }
        if (!canUse(context)) { main.post { listener.onEnded("Allow \"Nearby devices\" first") }; return@post }
        val scanner = runCatching { adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner }.getOrNull()
        if (scanner == null) { main.post { listener.onEnded("Turn Bluetooth on first") }; return@post }
        val p = Pairing(host = false, listener = listener)
        pairing = p
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val d = r.scanRecord?.getManufacturerSpecificData(COMPANY) ?: return
                if (d.size == 5 && (d[0].toInt() and 0xFF) == AD_PAIR) h.post { joinerFound(p, r.device, r.rssi) }
            }
            override fun onScanFailed(errorCode: Int) { h.post { if (pairing === p) endPairing(p, "Bluetooth search failed ($errorCode)") } }
        }
        val filter = ScanFilter.Builder().setManufacturerData(COMPANY, byteArrayOf(AD_PAIR.toByte()), byteArrayOf(0xFF.toByte())).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        try { scanner.startScan(listOf(filter), settings, cb); p.scan = cb } catch (e: Exception) {
            endPairing(p, "Bluetooth search failed"); return@post
        }
        startTicking()
        h.postDelayed({ if (pairing === p && !p.done) endPairing(p, "No Sentinel found - tap \"Link up a Sentinel\" on the other phone") }, PAIR_TIMEOUT_MS)
        tell(p) { onStatus("Looking for the other phone…") }
    }

    /** This phone's user saw the same code on both screens. */
    fun confirmCode() = h.post {
        val p = pairing ?: return@post
        val l = p.link ?: return@post
        val key = p.toPeer ?: return@post
        if (p.confirmed) return@post
        p.confirmed = true
        if (p.host) {
            if (!hostFinish(p)) tell(p) { onStatus("Waiting for the other phone to confirm…") }
        } else {
            val c = ctx ?: return@post
            send(l, byteArrayOf(LinkCodec.PAIR_CONFIRM.toByte()) + LinkCrypto.seal(key, 0, LinkCodec.member(LinkStore.me(c))))
            tell(p) { onStatus("Waiting for the other phone to confirm…") }
        }
    }

    fun cancelPairing() = h.post { pairing?.let { endPairing(it, null) } }

    /** Ends linking: [message] for the screen, or null when this phone's user cancelled. */
    private fun endPairing(p: Pairing, message: String?) {
        if (pairing === p) pairing = null
        val wasDone = p.done
        p.done = true
        p.scan?.let { cb -> runCatching { adapter()?.bluetoothLeScanner?.stopScan(cb) } }
        p.scan = null
        p.link?.let { l ->
            if (!l.closed && !wasDone) {
                send(l, byteArrayOf(LinkCodec.PAIR_CANCEL.toByte()))
                l.closeWhenDrained = true
                pump(l)
                h.postDelayed({ close(l) }, 3_000L)
            }
        }
        refreshAdvert()
        maybeStopServer()
        if (!wasDone && message != null) main.post { p.listener.onEnded(message) }
    }

    private fun joinerFound(p: Pairing, device: BluetoothDevice, rssi: Int) {
        if (pairing !== p || p.link != null) return
        p.found[device.address] = device to rssi
        if (p.picking) return
        p.picking = true
        // Gather for a moment, then take the nearest phone that's hosting.
        h.postDelayed({
            if (pairing !== p || p.link != null) return@postDelayed
            p.scan?.let { cb -> runCatching { adapter()?.bluetoothLeScanner?.stopScan(cb) } }
            p.scan = null
            val best = p.found.values.maxBy { it.second }.first
            val l = connect(best, pairing = true)
            if (l == null) { endPairing(p, "Couldn't connect to the other phone"); return@postDelayed }
            p.link = l
            tell(p) { onStatus("Found it - connecting…") }
        }, PAIR_PICK_MS)
    }

    private fun joinerOpen(l: ClientLink) {
        val p = pairing?.takeIf { it.link === l && !it.host } ?: run { close(l); return }
        send(l, LinkCodec.pairHello(LinkCodec.PAIR_HELLO, LinkCrypto.publicBytes(p.kp), p.nonce))
    }

    private fun hostPairHello(l: Link, m: ByteArray) {
        val p = pairing
        if (l.isClient || p == null || !p.host || p.link != null) {
            send(l, byteArrayOf(LinkCodec.PAIR_CANCEL.toByte())); l.closeWhenDrained = true; pump(l); return
        }
        l.pairing = true
        p.link = l
        val (theirPub, theirNonce) = LinkCodec.readPairHello(m)
        val myPub = LinkCrypto.publicBytes(p.kp)
        val secret = LinkCrypto.ecdh(p.kp, LinkCrypto.publicFrom(theirPub))
        p.toPeer = LinkCrypto.pairKey(secret, theirNonce, p.nonce, toHost = false)
        p.fromPeer = LinkCrypto.pairKey(secret, theirNonce, p.nonce, toHost = true)
        send(l, LinkCodec.pairHello(LinkCodec.PAIR_REPLY, myPub, p.nonce))
        val code = LinkCrypto.code(secret, theirPub, myPub, theirNonce, p.nonce)
        tell(p) { onCode(code) }
    }

    private fun joinerPairReply(l: Link, m: ByteArray) {
        val p = pairing?.takeIf { it.link === l && !it.host } ?: run { close(l); return }
        if (p.toPeer != null) { close(l); return }
        val (theirPub, theirNonce) = LinkCodec.readPairHello(m)
        val myPub = LinkCrypto.publicBytes(p.kp)
        val secret = LinkCrypto.ecdh(p.kp, LinkCrypto.publicFrom(theirPub))
        p.toPeer = LinkCrypto.pairKey(secret, p.nonce, theirNonce, toHost = true)
        p.fromPeer = LinkCrypto.pairKey(secret, p.nonce, theirNonce, toHost = false)
        val code = LinkCrypto.code(secret, myPub, theirPub, p.nonce, theirNonce)
        tell(p) { onCode(code) }
    }

    private fun hostPairConfirm(l: Link, m: ByteArray) {
        val p = pairing?.takeIf { it.link === l && it.host } ?: run { close(l); return }
        val key = p.fromPeer ?: run { close(l); return }
        val plain = LinkCrypto.open(key, 0, m.copyOfRange(1, m.size)) ?: run { endPairing(p, "The codes didn't match"); return }
        p.peer = LinkCodec.readMember(plain)
        if (!hostFinish(p)) tell(p) { onStatus("The other phone confirmed - check the code and tap Confirm") }
    }

    /** Both users confirmed: the new phone gets the group. */
    private fun hostFinish(p: Pairing): Boolean {
        val c = ctx ?: return false
        val l = p.link ?: return false
        val peer = p.peer ?: return false
        val key = p.toPeer ?: return false
        if (!p.confirmed) return false
        val gk = groupKey ?: LinkStore.groupKey(c) ?: return false
        LinkStore.addMembers(c, listOf(peer))
        names = LinkStore.members(c).associate { it.id to it.name }
        val members = listOf(LinkStore.me(c)) + LinkStore.members(c)
        send(l, byteArrayOf(LinkCodec.PAIR_GROUP.toByte()) + LinkCrypto.seal(key, 0, LinkCodec.group(gk, members)))
        l.closeWhenDrained = true
        pump(l)
        p.done = true
        pairing = null
        refreshAdvert()
        main.post { p.listener.onLinked(peer.name) }
        // Linked Sentinels learn about the new one.
        links.filter { it.ready }.forEach { sendInfo(it) }
        h.postDelayed({ maybeStopServer() }, 5_000L)
        return true
    }

    private fun joinerPairGroup(l: Link, m: ByteArray) {
        val c = ctx ?: return
        val p = pairing?.takeIf { it.link === l && !it.host && it.confirmed } ?: run { close(l); return }
        val key = p.fromPeer ?: run { close(l); return }
        val plain = LinkCrypto.open(key, 0, m.copyOfRange(1, m.size)) ?: run { endPairing(p, "The codes didn't match"); return }
        val (gk, members) = LinkCodec.readGroup(plain)
        if (gk.size != LinkCrypto.KEY_BYTES) { endPairing(p, "The other phone sent a bad answer"); return }
        LinkStore.saveGroup(c, gk, members)
        p.done = true
        pairing = null
        close(l)
        main.post { p.listener.onLinked(members.firstOrNull()?.name ?: "Sentinel") }
        groupChanged()
        h.post { maybeStopServer() }
    }
}
