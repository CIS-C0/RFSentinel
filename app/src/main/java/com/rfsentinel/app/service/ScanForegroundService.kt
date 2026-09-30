package com.rfsentinel.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rfsentinel.app.RFSentinelApp
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.alpr.KnownCameras
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.DroneProximity
import com.rfsentinel.app.detect.EvidenceFusion
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.RemoteId
import com.rfsentinel.app.detect.SignatureEngine
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.ui.StatusWidget
import com.rfsentinel.app.util.AlertPlayer
import com.rfsentinel.app.util.NotificationHelper
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive, receive-only scanning service. Does not transmit, connect to,
 * jam, or interact with any detected device beyond reading its publicly
 * broadcast advertisement/beacon (BLE) or access-point announcement (WiFi).
 */
class ScanForegroundService : Service() {

    companion object {
        private const val TAG = "ScanForegroundService"
        const val ACTION_STOP = "com.rfsentinel.app.action.STOP"
        /** Re-evaluates location updates, e.g. after trace recording starts or stops. */
        const val ACTION_REFRESH_LOCATION = "com.rfsentinel.app.action.REFRESH_LOCATION"

        /** Android downgrades an unfiltered BLE scan to opportunistic after ~30 min; restart before. */
        private const val BLE_RESTART_INTERVAL_MS = 25 * 60 * 1000L

        /** At most one logged row per device per this interval (BLE repeats many times a second). */
        private const val LOG_THROTTLE_MS = 30_000L

        /** Re-run identification/signatures per device at most this often; RSSI still updates every advert. */
        private const val CLASSIFY_INTERVAL_MS = 2_000L

        private const val STATUS_INTERVAL_MS = 10_000L
        /** Each periodic job holds a short wake lock only while it runs. */
        private const val WAKE_SLICE_MS = 10_000L
        private const val HISTORY_FLUSH_MS = 60_000L
        private const val DRONE_OVERHEAD_REPEAT_MS = 5 * 60_000L
        private const val KNOWN_ALPR_REPEAT_MS = 30 * 60_000L
        private const val CELL_CHECK_MS = 15_000L
        private const val CELL_REPEAT_MS = 30 * 60_000L

        /** True while an instance is alive in this process. Source of truth for the UI. */
        @Volatile
        var isRunning = false
            @VisibleForTesting internal set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScanForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScanForegroundService::class.java))
        }
    }

    private class Classified(val time: Long, val hits: List<Hit>, val identity: DeviceIntel.Identity, val vendor: String?)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Scan results arrive on the main thread (BLE callbacks, the WiFi broadcast).
     * Detection runs here instead, one result at a time in arrival order, so a
     * busy street never stalls the UI.
     */
    private lateinit var pipelineThread: HandlerThread
    private lateinit var pipeline: Handler

    private lateinit var bleEngine: BleScanEngine
    private lateinit var wifiEngine: WifiScanEngine

    private val classified = ConcurrentHashMap<String, Classified>()
    private val lastAlerted = ConcurrentHashMap<String, Long>()
    private val lastLogged = ConcurrentHashMap<String, Long>()
    private val lastDroneOverhead = ConcurrentHashMap<String, Long>()
    private var wifiPollJob: Job? = null
    private var bleRestartJob: Job? = null
    private var housekeepingJob: Job? = null
    private var cellJob: Job? = null
    private var bubbleJob: Job? = null
    private var cellMonitor: CellMonitor? = null
    private val lastCellAlert = HashMap<String, Long>()
    private var btStateReceiverRegistered = false
    private var lastHistoryFlush = 0L

    @Volatile private var lastLocation: Location? = null
    private var locationListening = false
    private var locationFastMode = false
    private val locationListener = LocationListener {
        lastLocation = it
        TripRecorder.onLocation(it)
        checkKnownAlpr(it)
        cellMonitor?.onLocation(it)
    }

    /** Known plate cameras: distance at the previous fix, and when each last alerted. */
    private val alprLastDistance = HashMap<String, Double>()
    private val alprLastAlert = HashMap<String, Long>()

    /** Starts BLE scanning if the user turns Bluetooth on after the service started. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> if (Prefs.bleEnabled(context)) startBle()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> bleEngine.stop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        OuiWatchlist.load(this)

        pipelineThread = HandlerThread("rf-pipeline").apply { start() }
        pipeline = Handler(pipelineThread.looper)
        bleEngine = BleScanEngine(this) { r -> pipeline.post { AdvertFactory.fromBle(r)?.let(::process) } }
        wifiEngine = WifiScanEngine(this) { results ->
            pipeline.post { results.forEach { r -> AdvertFactory.fromWifi(r)?.let(::process) } }
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RFSentinel::ScanWakeLock")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_LOCATION) {
            // A settings refresh must never start a scan by itself.
            if (!isRunning) { stopSelf(); return START_NOT_STICKY }
            updateLocationUpdates()
            return START_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!btStateReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            btStateReceiverRegistered = true
        }

        if (!isRunning) {
            if (Prefs.autoRecordTrace(this) && !TripRecorder.isRecording && hasFineLocation()) {
                serviceScope.launch { TripRecorder.start(this@ScanForegroundService); updateLocationOnMain() }
            }
            DeviceRegistry.startSession()
            classified.clear()
            serviceScope.launch { pruneOldData() }
        }

        // Re-evaluated on every start command, so Settings changes apply live.
        if (Prefs.bleEnabled(this)) { bleEngine.stop(); startBle() } else stopBle()
        if (Prefs.wifiEnabled(this)) startWifiPolling() else stopWifiPolling()
        updateLocationUpdates()
        startHousekeeping()
        startCellChecks()
        startBubble()

        isRunning = true
        refreshTile()
        StatusWidget.updateAll(this)
        return START_STICKY
    }

    /**
     * Promotes the service to the foreground. The `location` type is what lets
     * WiFi scan results (and GPS tagging / follower detection) keep working in
     * the background, but Android only allows it when location permission is
     * granted AND the start came from a foreground context. When started from
     * boot it can be refused, so fall back to `connectedDevice` alone.
     */
    private fun enterForeground(): Boolean {
        val notification = NotificationHelper.buildServiceNotification(this, "Listening for nearby devices...")
        val hasLocation = hasFineLocation()
        val types = mutableListOf<Int>()
        if (hasLocation) {
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        types += ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        for (type in types) {
            try {
                ServiceCompat.startForeground(this, NotificationHelper.SERVICE_NOTIFICATION_ID, notification, type)
                return true
            } catch (e: Exception) {
                // SecurityException / ForegroundServiceStartNotAllowedException / InvalidForegroundServiceTypeException
                Log.w(TAG, "startForeground refused for type $type", e)
            }
        }
        return false
    }

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // ---- Scanning -----------------------------------------------------------

    private fun startBle() {
        val watched = OuiWatchlist.allEntries().filter { it.isCustom && it.prefix.length == 17 }.map { it.prefix }
        bleEngine.start(Prefs.bleScanMode(this), watched)
        bleRestartJob?.cancel()
        bleRestartJob = serviceScope.launch {
            while (isActive) {
                delay(BLE_RESTART_INTERVAL_MS)
                bleEngine.stop()
                delay(1_000)
                bleEngine.start(Prefs.bleScanMode(this@ScanForegroundService), watched)
            }
        }
    }

    private fun stopBle() {
        bleRestartJob?.cancel()
        bleEngine.stop()
    }

    private fun startWifiPolling() {
        wifiEngine.start()
        wifiPollJob?.cancel()
        wifiPollJob = serviceScope.launch {
            while (isActive) {
                holdAwake()
                wifiEngine.requestScan()
                delay(Prefs.scanIntervalMs(this@ScanForegroundService))
            }
        }
    }

    private fun stopWifiPolling() {
        wifiPollJob?.cancel()
        wifiEngine.stop()
    }

    /**
     * Location is needed for GPS tagging, follower alerts and trace recording.
     * While recording, fixes come every ~3 s / 5 m for a smooth trace; otherwise
     * every ~20 s / 25 m to save battery.
     */
    @SuppressLint("MissingPermission") // checked by hasFineLocation()
    fun updateLocationUpdates() {
        val wanted = hasFineLocation() &&
            (Prefs.gpsTaggingEnabled(this) || Prefs.followerAlerts(this) || TripRecorder.isRecording || knownAlprActive())
        // Frequent fixes while recording a trace or watching for known plate cameras
        // (at highway speed a 20 s interval could skip right past one).
        val fast = TripRecorder.isRecording || knownAlprActive()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (locationListening && (!wanted || fast != locationFastMode)) {
            lm.removeUpdates(locationListener)
            locationListening = false
        }
        if (wanted && !locationListening) {
            locationFastMode = fast
            val providers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.hasProvider(LocationManager.FUSED_PROVIDER)
            ) {
                listOf(LocationManager.FUSED_PROVIDER)
            } else {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
            }
            providers.forEach { p ->
                try {
                    if (fast) lm.requestLocationUpdates(p, 3_000L, 5f, locationListener, Looper.getMainLooper())
                    else lm.requestLocationUpdates(p, 20_000L, 25f, locationListener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { if (lastLocation == null) lastLocation = it }
                } catch (e: Exception) {
                    Log.w(TAG, "Location provider $p unavailable", e)
                }
            }
            locationListening = true
        }
    }

    // ---- Pipeline -------------------------------------------------------------

    private fun process(a: Advert) {
        val now = a.timestamp
        val mac = a.mac

        // Remote ID messages arrive one type at a time; merge every packet.
        var remoteId: RemoteId.Info? = null
        if (a.isBle) {
            a.serviceData[Advert.uuid16(0xFFFA)]?.let { remoteId = RemoteId.decodeBle(it, DeviceRegistry.remoteIdOf(mac)) }
        } else {
            a.wifi?.infoElements?.firstOrNull { it.first == 221 && RemoteId.isWifiIe(it.second) }
                ?.let { remoteId = RemoteId.decodeWifiIe(it.second, DeviceRegistry.remoteIdOf(mac)) }
        }

        val prev = classified[mac]
        val c = if (prev == null || now - prev.time >= CLASSIFY_INTERVAL_MS) {
            classify(a, now).also { classified[mac] = it }
        } else {
            prev
        }

        val loc = lastLocation
        val geo = loc?.let { DeviceRegistry.GeoSample(now, it.latitude, it.longitude) }
        val isFirst = DeviceRegistry.report(a, c.hits, c.identity, c.vendor, remoteId, geo, now)
        if (isFirst) serviceScope.launch { loadHistory(mac) }
        if (TripRecorder.isRecording) {
            TripRecorder.onDevice(
                mac, a.rssi, a.source.name,
                if (WhitelistCache.contains(mac)) null else c.hits.firstOrNull(),
                a.name, c.vendor, c.identity.type, loc, now
            )
        }

        remoteId?.let { info -> if (loc != null) maybeAlertDroneOverhead(a, info, loc, now) }

        val best = c.hits.firstOrNull() ?: return
        if (WhitelistCache.contains(mac)) return

        maybeLog(a, best, c.vendor, loc, now)
        maybeAlert(a, best, now)

        if (loc != null && Prefs.followerAlerts(this) &&
            DeviceRegistry.checkFollowing(
                mac,
                Prefs.followMinMinutes(this) * 60_000L,
                Prefs.followMinMeters(this).toDouble()
            )
        ) {
            NotificationHelper.sendAlert(this, mac, best, a.rssi, following = true)
            AlertPlayer.play(this, best.tier, "Warning. ${best.label} may be following you.", following = true)
        }
    }

    private fun classify(a: Advert, now: Long): Classified {
        val macVendor = VendorDb.macVendor(a.mac)
        val identity = DeviceIntel.identify(a, macVendor)
        val companyVendors = a.manufacturerData.keys.mapNotNull { VendorDb.company(it) }
        val vendor = macVendor ?: companyVendors.firstOrNull()
        // Per-packet rules, the watchlist, matches carried over from a rotated address and the
        // patrol-vehicle cluster, then fused into one calibrated score.
        val raw = SignatureEngine.classify(a) +
            OuiWatchlist.hits(a.mac, a.name, listOfNotNull(macVendor) + companyVendors) +
            DeviceRegistry.inheritedHits(a.mac) +
            listOfNotNull(DeviceRegistry.clusterHit(a.mac, now))
        val hits = EvidenceFusion.fuse(raw.filter { Prefs.categoryEnabled(this, it.category) })
        return Classified(now, hits, identity, vendor)
    }

    private fun maybeLog(a: Advert, best: Hit, vendor: String?, loc: Location?, now: Long) {
        val prevLogged = lastLogged[a.mac] ?: 0L
        if (now - prevLogged < LOG_THROTTLE_MS) return
        lastLogged[a.mac] = now
        val tag = Prefs.gpsTaggingEnabled(this)
        serviceScope.launch {
            AppDatabase.getInstance(this@ScanForegroundService).detectionDao().insert(
                DetectionEntity(
                    mac = a.mac,
                    label = best.label,
                    source = a.source.name,
                    rssi = a.rssi,
                    timestamp = now,
                    latitude = if (tag) loc?.latitude else null,
                    longitude = if (tag) loc?.longitude else null,
                    category = best.category.name,
                    confidence = best.confidence,
                    evidence = best.evidence,
                    vendor = vendor,
                    deviceName = a.name
                )
            )
        }
    }

    private fun maybeAlert(a: Advert, best: Hit, now: Long) {
        // Someone else's tracker nearby is normal; trackers only alert when they follow you.
        if (best.category == Category.TRACKER) return
        if (best.confidence < Prefs.alertThreshold(this)) return
        val window = Prefs.dedupeWindowMs(this)
        val last = lastAlerted[a.mac] ?: 0L
        if (now - last <= window) return
        lastAlerted[a.mac] = now
        NotificationHelper.sendAlert(this, a.mac, best, a.rssi)
        AlertPlayer.play(this, best.tier, "${best.label} nearby")
    }

    private fun knownAlprActive() = Prefs.knownAlprAlerts(this) &&
        Prefs.categoryEnabled(this, Category.ALPR) && AlprStore.cameras.isNotEmpty()

    /**
     * Warns once (per camera, per 30 minutes) when you're approaching a plate
     * reader mapped in OpenStreetMap: within ~20 s of travel and getting closer.
     * Runs on the main thread with each location fix; the lookup is a cheap
     * bounding-box filter.
     */
    private fun checkKnownAlpr(loc: Location) {
        if (!knownAlprActive()) return
        val now = System.currentTimeMillis()
        val radius = KnownCameras.warnRadius(if (loc.hasSpeed()) loc.speed else null)
        val near = KnownCameras.near(AlprStore.cameras, loc.latitude, loc.longitude, radius)
        val nearIds = near.map { it.first.osmId }.toSet()
        alprLastDistance.keys.retainAll(nearIds)
        for ((cam, d) in near) {
            val prev = alprLastDistance.put(cam.osmId, d)
            val approaching = prev == null || d < prev - 3
            if (!approaching || now - (alprLastAlert[cam.osmId] ?: 0L) < KNOWN_ALPR_REPEAT_MS) continue
            alprLastAlert[cam.osmId] = now
            val hit = Hit(
                Category.ALPR, "Known plate camera ahead", 80,
                "${cam.label}, ~${d.toInt()} m away" + (cam.operator?.let { ", operated by $it" } ?: "") +
                    ". Mapped in OpenStreetMap - it may not broadcast any signal.",
                "OpenStreetMap (surveillance:type=ALPR), ODbL"
            )
            NotificationHelper.sendMapAlert(this, "alpr:" + cam.osmId, hit)
            AlertPlayer.play(this, hit.tier, "Plate camera ahead")
            break // one warning per fix is enough
        }
    }

    /** Remote ID puts the drone within ~200 m of you: alert once per drone per 5 minutes. */
    private fun maybeAlertDroneOverhead(a: Advert, info: RemoteId.Info, loc: Location, now: Long) {
        if (!Prefs.categoryEnabled(this, Category.DRONE) || WhitelistCache.contains(a.mac)) return
        val hit = DroneProximity.overheadHit(info, loc.latitude, loc.longitude) ?: return
        val last = lastDroneOverhead[a.mac] ?: 0L
        if (now - last < DRONE_OVERHEAD_REPEAT_MS) return
        lastDroneOverhead[a.mac] = now
        NotificationHelper.sendAlert(this, a.mac, hit, a.rssi)
        AlertPlayer.play(this, hit.tier, "Drone overhead")
    }

    // ---- History & housekeeping -----------------------------------------------

    private suspend fun loadHistory(mac: String) {
        val known = AppDatabase.getInstance(this).knownDeviceDao().get(mac)
        DeviceRegistry.setKnown(mac, known)
    }

    /**
     * Keeps the CPU awake for one short slice of periodic work (a WiFi scan
     * request, a status/trace/history update). The lock is never held between
     * slices, so the phone can still deep-sleep; BLE results wake it by themselves.
     */
    private fun holdAwake() {
        runCatching { wakeLock?.acquire(WAKE_SLICE_MS) }
    }

    /** Sightings to merge into known_devices, taken synchronously from memory. */
    private class HistoryBatch(val seen: List<DeviceRegistry.Snapshot>, val sessionStart: Long)

    private fun takeHistory(): HistoryBatch? {
        val since = lastHistoryFlush
        lastHistoryFlush = System.currentTimeMillis()
        val seen = DeviceRegistry.summaries(since)
        return if (seen.isEmpty()) null else HistoryBatch(seen, DeviceRegistry.sessionStart)
    }

    /** Merges this session's sightings into known_devices (throttled, batched). */
    private suspend fun flushHistory() {
        takeHistory()?.let { writeHistory(it) }
    }

    private suspend fun writeHistory(batch: HistoryBatch) {
        val dao = AppDatabase.getInstance(this).knownDeviceDao()
        val sessionStart = batch.sessionStart
        batch.seen.chunked(400).forEach { chunk ->
            val existing = dao.getAll(chunk.map { it.mac }).associateBy { it.mac }
            dao.upsertAll(chunk.map { s ->
                val e = existing[s.mac]
                KnownDeviceEntity(
                    mac = s.mac,
                    firstSeen = e?.firstSeen ?: s.firstSeen,
                    lastSeen = s.lastSeen,
                    sessions = (e?.sessions ?: 0) + if (e == null || e.lastSeen < sessionStart) 1 else 0,
                    detectCount = (e?.detectCount ?: 0) + 1,
                    name = s.name ?: e?.name,
                    vendor = s.vendor ?: e?.vendor,
                    deviceType = s.deviceType,
                    favorite = e?.favorite ?: false,
                    note = e?.note
                )
            })
        }
    }

    private suspend fun pruneOldData() {
        val days = Prefs.retentionDays(this)
        if (days <= 0) return
        val before = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val db = AppDatabase.getInstance(this)
        db.detectionDao().prune(before)
        db.knownDeviceDao().prune(before)
    }

    private fun startHousekeeping() {
        housekeepingJob?.cancel()
        housekeepingJob = serviceScope.launch {
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                holdAwake()
                val devices = DeviceRegistry.snapshot()
                val flagged = devices.count { d -> d.best != null && !WhitelistCache.contains(d.mac) }
                val text = "${devices.size} device${if (devices.size == 1) "" else "s"} nearby" +
                    if (flagged > 0) " · $flagged flagged" else ""
                NotificationHelper.updateServiceNotification(this@ScanForegroundService, text)
                StatusWidget.updateAll(this@ScanForegroundService, devices.size, flagged)
                runCatching { TripRecorder.flush(this@ScanForegroundService) }
                    .onFailure { Log.w(TAG, "Trace flush failed", it) }
                if (System.currentTimeMillis() - lastHistoryFlush >= HISTORY_FLUSH_MS) {
                    runCatching { flushHistory() }.onFailure { Log.w(TAG, "History flush failed", it) }
                }
                // Forget classification cache for devices that left.
                classified.keys.retainAll(devices.map { it.mac }.toSet())
            }
        }
    }

    /**
     * Fake-cell-tower check every 15 s: reads the cells the phone already sees
     * (passive, no extra permission beyond location). Probable signs alert like a
     * device match; weak ones are only written to the match history.
     */
    private fun startCellChecks() {
        cellJob?.cancel()
        if (!Prefs.categoryEnabled(this, Category.CELL_ANOMALY) || !hasFineLocation()) return
        val monitor = cellMonitor ?: CellMonitor(this).also { cellMonitor = it }
        cellJob = serviceScope.launch {
            while (isActive) {
                runCatching { monitor.check() }.getOrNull().orEmpty().forEach { onCellAnomaly(it) }
                delay(CELL_CHECK_MS)
            }
        }
    }

    private fun onCellAnomaly(a: com.rfsentinel.app.detect.CellAnalyzer.Anomaly) {
        val now = System.currentTimeMillis()
        if (now - (lastCellAlert[a.key] ?: 0L) < CELL_REPEAT_MS) return
        lastCellAlert[a.key] = now
        CellMonitor.lastAnomaly = now to a
        val hit = Hit(Category.CELL_ANOMALY, a.title, a.confidence, a.detail, "Android cell info (heuristic, not proof)")
        val loc = lastLocation
        val tag = Prefs.gpsTaggingEnabled(this)
        serviceScope.launch {
            AppDatabase.getInstance(this@ScanForegroundService).detectionDao().insert(
                DetectionEntity(
                    mac = "CELL " + a.key.substringAfter(':'), label = a.title, source = "CELL", rssi = 0,
                    timestamp = now,
                    latitude = if (tag) loc?.latitude else null, longitude = if (tag) loc?.longitude else null,
                    category = Category.CELL_ANOMALY.name, confidence = a.confidence, evidence = a.detail
                )
            )
        }
        if (a.confidence >= Prefs.alertThreshold(this)) {
            NotificationHelper.sendMapAlert(this, "cell:" + a.key, hit, com.rfsentinel.app.ui.DetectionLogActivity::class.java)
            AlertPlayer.play(this, hit.tier, "Cell network warning")
        }
    }

    /** Keeps the floating threat bubble in sync (hidden while our own screens are visible). */
    private fun startBubble() {
        bubbleJob?.cancel()
        if (!Prefs.threatBubble(this)) { com.rfsentinel.app.ui.ThreatBubble.hide(this); return }
        bubbleJob = serviceScope.launch {
            while (isActive) {
                if (RFSentinelApp.inForeground || !com.rfsentinel.app.ui.ThreatBubble.canShow(this@ScanForegroundService)) {
                    com.rfsentinel.app.ui.ThreatBubble.hide(this@ScanForegroundService)
                } else {
                    val flagged = DeviceRegistry.snapshot().filter { it.best != null && !WhitelistCache.contains(it.mac) }
                    val threshold = Prefs.alertThreshold(this@ScanForegroundService)
                    val top = flagged.maxOfOrNull { it.best!!.confidence } ?: 0
                    val cellWarning = CellMonitor.lastAnomaly?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }
                    val level = when {
                        flagged.any { it.following } || top >= 80 -> com.rfsentinel.app.ui.ThreatBubble.Level.DANGER
                        top >= threshold || cellWarning != null -> com.rfsentinel.app.ui.ThreatBubble.Level.PROBABLE
                        flagged.isNotEmpty() -> com.rfsentinel.app.ui.ThreatBubble.Level.WEAK
                        else -> com.rfsentinel.app.ui.ThreatBubble.Level.CLEAR
                    }
                    com.rfsentinel.app.ui.ThreatBubble.update(this@ScanForegroundService, level, flagged.size)
                }
                delay(2_000)
            }
        }
    }

    private fun updateLocationOnMain() {
        android.os.Handler(Looper.getMainLooper()).post { updateLocationUpdates() }
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, ScanTileService::class.java))
    }

    override fun onDestroy() {
        bleEngine.stop()
        wifiEngine.stop()
        if (btStateReceiverRegistered) {
            try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
            btStateReceiverRegistered = false
        }
        if (locationListening) {
            runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener) }
            locationListening = false
        }
        serviceScope.cancel()
        pipelineThread.quitSafely()
        cellMonitor?.close()
        com.rfsentinel.app.ui.ThreatBubble.hide(this)
        // Take the final history and trace synchronously, so a scan restarted right
        // away (new session, new trace) can't clear or reuse them; write them after.
        val history = takeHistory()
        val trip = TripRecorder.detach() // stopping the scanner ends the trace recording too
        val app = application as RFSentinelApp
        app.appScope.launch {
            history?.let { runCatching { writeHistory(it) }.onFailure { e -> Log.w(TAG, "History flush failed", e) } }
            trip?.let { runCatching { TripRecorder.persist(app, it) }.onFailure { e -> Log.w(TAG, "Trace save failed", e) } }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        Prefs.setStartedByCar(this, false)
        isRunning = false
        refreshTile()
        StatusWidget.updateAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
