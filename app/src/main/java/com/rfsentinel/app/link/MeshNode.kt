package com.rfsentinel.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Meshtastic node (LoRa board with the official firmware) used by Sentinel Link to reach Sentinels far away.
 *
 * Talks to the node over Bluetooth with Meshtastic's phone API, like the Meshtastic app does: asks for its config,
 * makes sure it has the group's private "RFSentinel" channel (adds it to a free slot when missing, key made from
 * the group key), then sends and receives RF Sentinel's packets on that channel. Reconnects on its own.
 * The node serves one app at a time: the Meshtastic app on this phone can't use it meanwhile.
 */
@SuppressLint("MissingPermission")
object MeshNode {
    private const val TAG = "MeshNode"
    val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    private val TO_RADIO = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val FROM_RADIO = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    private val FROM_NUM = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val MTU = 512
    private const val TICK_MS = 5_000L
    private const val OP_TIMEOUT_MS = 15_000L
    private const val SETUP_TIMEOUT_MS = 45_000L
    private const val BOND_WAIT_MS = 90_000L
    private const val RETRY_MIN_MS = 10_000L
    private const val RETRY_MAX_MS = 120_000L
    /** While the node is out of reach, packets beyond this wait are dropped (they'd be stale). */
    private const val MAX_QUEUED = 12

    private val thread = HandlerThread("rf-mesh").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    /** One line for the screens, "" while off. */
    @Volatile var status = ""; private set
    /** Connected, set up, and the RFSentinel channel in place. */
    @Volatile var ready = false; private set
    @Volatile var lastSent = 0L; private set
    @Volatile var lastHeard = 0L; private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    private var ctx: Context? = null
    private var running = false
    private var address: String? = null
    private var channelPsk: ByteArray? = null
    private var onPacket: ((ByteArray) -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var connectStarted = 0L
    private var bondStarted = 0L
    private var retryMs = RETRY_MIN_MS
    private var retryPending = false
    private var ticking = false

    // What the node told us.
    private var myNode = 0L
    private var hopLimit = 3
    private var region = -1
    private var txEnabled = true
    private val channels = HashMap<Int, MeshProto.From.ChannelInfo>()
    /** The node's slot RF Sentinel sends on (1-7), -1 until set up. */
    @Volatile var channelIndex = -1; private set
    private var channelTries = 0
    /** Why the node can't be used as it is (no region, no free channel): kept on screen while it retries. */
    private var setupProblem: String? = null

    // One Bluetooth operation at a time.
    private sealed class Op {
        class Write(val data: ByteArray) : Op()
        object Read : Op()
    }
    private val ops = ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var opSince = 0L
    private var busyTries = 0
    private var ioReady = false
    private var draining = false
    private var drainAgain = false

    /** Starts using the node at [nodeAddress]; [packets] gets RF Sentinel payloads from the mesh (on this thread). */
    fun start(context: Context, nodeAddress: String, groupKey: ByteArray, packets: (ByteArray) -> Unit) = h.post {
        ctx = context.applicationContext
        onPacket = packets
        val psk = MeshCodec.channelKey(groupKey)
        val changedNode = nodeAddress != address || !psk.contentEquals(channelPsk)
        address = nodeAddress
        channelPsk = psk
        if (running && !changedNode) return@post
        if (running) teardown()
        running = true
        channelTries = 0
        retryMs = RETRY_MIN_MS
        startTicking()
        connect()
    }

    /** The chosen channel slot changed: set the channel up again where it now belongs. */
    fun slotChanged() = h.post {
        if (!running || !ioReady) return@post
        ready = false
        channelIndex = -1
        channelTries = 0
        setupProblem = null
        askConfig()
    }

    fun stop() = h.post {
        if (!running) return@post
        running = false
        onPacket = null
        teardown()
        setStatus("")
    }

    /** Sends one RF Sentinel payload on the RFSentinel channel; false when the node isn't ready. */
    fun send(payload: ByteArray, urgent: Boolean): Boolean {
        if (!ready) return false
        h.post {
            if (!ready || channelIndex < 0 || !txEnabled) return@post
            if (ops.count { it is Op.Write } >= MAX_QUEUED) return@post
            val hops = minOf(ctx?.let { LinkStore.meshHops(it) } ?: LinkStore.MESH_HOPS_MIN, hopLimit)
            enqueue(Op.Write(MeshProto.packet(MeshProto.BROADCAST, channelIndex, MeshProto.PORT_PRIVATE_APP, payload,
                packetId(), hops, if (urgent) MeshProto.PRIORITY_RELIABLE else MeshProto.PRIORITY_BACKGROUND)))
            lastSent = System.currentTimeMillis()
            changed()
        }
        return true
    }

    // ---------------------------------------------------------------- finding and pairing a node

    /** Looks for nodes nearby; [found] (main thread) gets each one's name, address and signal. Returns how to stop. */
    fun find(context: Context, found: (name: String, address: String, rssi: Int) -> Unit): (() -> Unit)? {
        val scanner = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        }.getOrNull() ?: return null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: r.device.address
                main.post { found(name, r.device.address, r.rssi) }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, cb)
        } catch (e: Exception) {
            return null
        }
        return { runCatching { scanner.stopScan(cb) } }
    }

    /**
     * Pairs with the node now, while the user is looking (from a screen, so Android shows its PIN dialog
     * rather than a notification): the PIN is on the node's screen, or 123456. False when it's already paired.
     */
    fun pair(context: Context, nodeAddress: String): Boolean = runCatching {
        val d = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(nodeAddress) ?: return false
        d.bondState == BluetoothDevice.BOND_NONE && d.createBond()
    }.getOrDefault(false)

    // ---------------------------------------------------------------- connection

    private fun adapter() = ctx?.getSystemService(BluetoothManager::class.java)?.adapter

    private fun connect() {
        if (!running || gatt != null) return
        val c = ctx ?: return
        val addr = address ?: return
        if (!PhoneLink.canUse(c)) { setStatus("Meshtastic node: allow \"Nearby devices\""); scheduleRetry(); return }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) { setStatus("Meshtastic node: Bluetooth is off"); scheduleRetry(); return }
        val device = runCatching { adapter.getRemoteDevice(addr) }.getOrNull()
            ?: run { setStatus("Meshtastic node: choose the node again"); return }
        // Meshtastic nodes want a paired phone (PIN on the node's screen, or 123456). Pairing asked from the
        // background only gets Android's small "pairing request" notification, often cut off with no PIN box:
        // ours opens Sentinel Link, which asks in the foreground so Android shows its PIN dialog.
        when (device.bondState) {
            BluetoothDevice.BOND_NONE -> {
                if (bondStarted == 0L || SystemClock.elapsedRealtime() - bondStarted > BOND_WAIT_MS) {
                    bondStarted = SystemClock.elapsedRealtime()
                    com.rfsentinel.app.util.NotificationHelper.showMeshPairing(c)
                }
                setStatus("Meshtastic node: not paired - tap here to pair")
                scheduleRetry(5_000L)
                return
            }
            BluetoothDevice.BOND_BONDING -> { setStatus("Meshtastic node: pairing - enter the PIN Android asks for"); scheduleRetry(3_000L); return }
        }
        if (bondStarted != 0L) com.rfsentinel.app.util.NotificationHelper.cancelMeshPairing(c)
        bondStarted = 0L
        setStatus("Meshtastic node: connecting…")
        connectStarted = SystemClock.elapsedRealtime()
        gatt = runCatching { device.connectGatt(c, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) { setStatus("Meshtastic node: can't connect"); scheduleRetry() }
    }

    private fun teardown() {
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        toRadio = null; fromRadio = null
        ops.clear(); inFlight = null; ioReady = false; busyTries = 0
        draining = false; drainAgain = false
        channels.clear(); channelIndex = -1; myNode = 0L; setupProblem = null
        if (ready) { ready = false; changed() }
    }

    private fun scheduleRetry(delay: Long = retryMs) {
        if (!running || retryPending) return
        retryPending = true
        if (delay == retryMs) retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
        h.postDelayed({ retryPending = false; connect() }, delay)
    }

    private fun lost(why: String) {
        Log.i(TAG, "lost: $why")
        teardown()
        setStatus("Meshtastic node: $why - retrying")
        scheduleRetry()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        h.postDelayed(::tick, TICK_MS)
    }

    private fun tick() {
        if (!running) { ticking = false; return }
        val now = SystemClock.elapsedRealtime()
        val problem = setupProblem
        if (gatt != null && !ready && now - connectStarted > SETUP_TIMEOUT_MS) {
            // Settings the user has to change: say that, not "no answer", and look again later.
            if (problem != null) { teardown(); setStatus("Meshtastic node: $problem"); scheduleRetry() } else lost("no answer")
        }
        else if (inFlight != null && now - opSince > OP_TIMEOUT_MS) lost("stopped answering")
        h.postDelayed(::tick, TICK_MS)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (gatt !== g) { runCatching { g.close() }; return@post }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(MTU)) discover(g)
                    BluetoothProfile.STATE_DISCONNECTED -> lost(if (ready) "out of reach" else "can't connect")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { h.post { if (gatt === g) discover(g) } }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                if (gatt !== g) return@post
                val svc = g.getService(SERVICE) ?: run { lost("not a Meshtastic node"); return@post }
                toRadio = svc.getCharacteristic(TO_RADIO)
                fromRadio = svc.getCharacteristic(FROM_RADIO)
                val num = svc.getCharacteristic(FROM_NUM)
                if (toRadio == null || fromRadio == null) { lost("not a Meshtastic node"); return@post }
                val cccd = num?.getDescriptor(CCCD)
                if (num == null || cccd == null) { onIoReady(); return@post }
                g.setCharacteristicNotification(num, true)
                val ok = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    else {
                        @Suppress("DEPRECATION") cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                    }
                }.getOrDefault(false)
                if (!ok) onIoReady()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post { if (gatt === g && !ioReady) onIoReady() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post {
                if (gatt !== g) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { lost("refused a message ($status)"); return@post }
                done()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            h.post { if (gatt === g) onRead(value, status) }
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: ByteArray(0)
            h.post { if (gatt === g) onRead(v, status) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == FROM_NUM) h.post { if (gatt === g) drain() }
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33 || c.uuid != FROM_NUM) return
            h.post { if (gatt === g) drain() }
        }
    }

    private fun discover(g: BluetoothGatt) { if (!g.discoverServices()) lost("can't read the node") }

    private fun onIoReady() {
        ioReady = true
        setStatus("Meshtastic node: reading its settings…")
        askConfig()
    }

    /** The node's settings and channels (not its node list). */
    private fun askConfig() {
        channels.clear()
        enqueue(Op.Write(MeshProto.wantConfig(MeshProto.CONFIG_ONLY)))
        drain()
    }

    // ---------------------------------------------------------------- operations

    private fun enqueue(op: Op) { ops.addLast(op); next() }

    private fun next() {
        val g = gatt ?: return
        if (inFlight != null || !ioReady) return
        val op = ops.removeFirstOrNull() ?: return
        val ok = runCatching {
            when (op) {
                is Op.Write -> {
                    val c = toRadio ?: return
                    if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    else {
                        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION") c.value = op.data
                        @Suppress("DEPRECATION") g.writeCharacteristic(c)
                    }
                }
                Op.Read -> g.readCharacteristic(fromRadio ?: return)
            }
        }.getOrDefault(false)
        if (!ok) {
            // The stack is busy for a moment: try again shortly, not forever.
            ops.addFirst(op)
            if (++busyTries > 50) { lost("stopped answering"); return }
            h.postDelayed(::next, 200L)
            return
        }
        busyTries = 0
        inFlight = op
        opSince = SystemClock.elapsedRealtime()
    }

    private fun done() {
        inFlight = null
        opSince = 0L
        next()
    }

    /** Reads everything the node has for the phone. */
    private fun drain() {
        if (draining) { drainAgain = true; return }
        draining = true
        enqueue(Op.Read)
    }

    private fun onRead(value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) { lost("can't read the node ($status)"); return }
        inFlight = null
        opSince = 0L
        if (value.isEmpty()) {
            draining = false
            if (drainAgain) { drainAgain = false; drain() }
        } else {
            runCatching { handle(MeshProto.readFromRadio(value)) }.onFailure { Log.w(TAG, "bad message from the node", it) }
            ops.addFirst(Op.Read)
        }
        next()
    }

    private fun handle(m: MeshProto.From) {
        when (m) {
            is MeshProto.From.MyInfo -> myNode = m.nodeNum
            is MeshProto.From.ChannelInfo -> channels[m.index] = m
            is MeshProto.From.Lora -> { hopLimit = if (m.hopLimit in 1..7) m.hopLimit else 3; region = m.region; txEnabled = m.txEnabled }
            is MeshProto.From.ConfigComplete -> configDone()
            is MeshProto.From.Packet -> if (m.port == MeshProto.PORT_PRIVATE_APP && (channelIndex < 0 || m.channel == channelIndex)) {
                lastHeard = System.currentTimeMillis()
                onPacket?.invoke(m.payload)
                changed()
            }
            is MeshProto.From.Queue -> if (m.res != 0) Log.i(TAG, "node queue: ${m.res}, ${m.free} free")
            MeshProto.From.Rebooted -> lost("restarted")
            MeshProto.From.Other -> Unit
        }
    }

    /** Settings read: use the RFSentinel channel, adding it first when it's missing or has another group's key. */
    private fun configDone() {
        val psk = channelPsk ?: return
        if (region == 0) { stuck("set its LoRa region in the Meshtastic app first"); return }
        // Never the primary (0): it's often a whole city's public channel.
        val wanted = ctx?.let { LinkStore.meshSlot(it) } ?: 0
        val ours = channels.values.filter {
            it.index in 1..7 && it.role != MeshProto.ROLE_DISABLED && it.name == MeshCodec.CHANNEL_NAME && it.psk.contentEquals(psk)
        }.let { l -> if (wanted > 0) l.firstOrNull { it.index == wanted } else l.minByOrNull { it.index } }
        if (ours != null) {
            channelIndex = ours.index
            ready = true
            retryMs = RETRY_MIN_MS
            setStatus(if (!txEnabled) "Meshtastic node: its LoRa transmit is off" else "Meshtastic node · channel ${ours.index}, encrypted")
            return
        }
        if (channelTries >= 2 || myNode == 0L) {
            stuck("couldn't add the ${MeshCodec.CHANNEL_NAME} channel")
            return
        }
        // Only an empty slot or our own channel (another group's key): never a channel someone set up.
        fun free(i: Int) = channels[i]?.let { it.role == MeshProto.ROLE_DISABLED || it.name == MeshCodec.CHANNEL_NAME } == true
        val slot = if (wanted > 0) wanted.takeIf { free(it) }
            else channels.values.firstOrNull { it.index in 1..7 && it.name == MeshCodec.CHANNEL_NAME }?.index
                ?: (1..7).firstOrNull { channels[it]?.role == MeshProto.ROLE_DISABLED }
        if (slot == null) {
            stuck(if (wanted > 0) "channel $wanted is in use - pick another slot" else "no free channel - free one in the Meshtastic app")
            return
        }
        channelTries++
        setStatus("Meshtastic node: adding the ${MeshCodec.CHANNEL_NAME} channel…")
        // Admin message to the node itself (from this phone: no admin key needed); channels change without a restart.
        enqueue(Op.Write(MeshProto.packet(myNode, 0, MeshProto.PORT_ADMIN_APP,
            MeshProto.setChannel(slot, MeshCodec.CHANNEL_NAME, psk), packetId(), 0, MeshProto.PRIORITY_RELIABLE)))
        val g = gatt
        h.postDelayed({ if (gatt === g && g != null && !ready) askConfig() }, 3_000L)
    }

    private fun stuck(why: String) {
        setupProblem = why
        setStatus("Meshtastic node: $why")
    }

    private fun packetId(): Long {
        var id = 0L
        while (id == 0L) id = LinkCrypto.randomId() and 0xFFFFFFFFL
        return id
    }

    private fun setStatus(s: String) {
        if (s == status) return
        status = s
        changed()
    }
}
