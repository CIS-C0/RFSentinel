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
import androidx.core.location.LocationListenerCompat
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
import com.rfsentinel.app.data.AlertKind
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.DeviceDetailsEntity
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.data.LastHeard
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
import com.rfsentinel.app.link.LinkCodec
import com.rfsentinel.app.link.LinkHardware
import com.rfsentinel.app.link.LinkOutbox
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
        /** Radar beeps: start after the alert's own beeps, last up to 2 min, stop once the device is out of range. */
        private const val RADAR_QUIET_MS = 2_500L
        private const val OWNER_CHECK_MS = 30_000L
        private const val RADAR_HOLD_MS = 120_000L
        /** A watched talkgroup's call: the user asked for it, like a watched frequency. */
        const val P25_CONFIDENCE = 70
        private const val PAGER_ALERT_GAP_MS = 60_000L
        /** A talkgroup's radio heard on the site's input, [snr] dB above the noise ([min]: the radio threshold): near you. */
        fun p25InputConfidence(snr: Int, min: Int) = if (snr >= min + 10) 75 else 60
        /** A P25 radio heard checking in with the site, nobody talking: one report per radio this often. */
        private const val P25_NEARBY_GAP_MS = 2 * 60_000L
        /** Tire sensors that follow you within this of the first one are your own car's, learned instead of alerted. */
        private const val TPMS_LEARN_MS = 60 * 60_000L
        /** One tire-sensor follow alert this often: a car has four sensors. */
        private const val TPMS_ALERT_GAP_MS = 10 * 60_000L
        private const val TPMS_CONFIDENCE = 60
        /** A talkgroup much busier than usual: probable, not certain (an event, a shift change, a drill). */
        private const val P25_UNUSUAL_CONFIDENCE = 55
        private const val P25_UNUSUAL_CHECK_MS = 5 * 60_000L
        /** One unusual-activity alert per talkgroup this often. */
        private const val P25_UNUSUAL_AGAIN_MS = 2 * 60 * 60_000L
        /** "GPS connected" again only after the fix was lost this long. */
        private const val GPS_RECONNECT_MS = 120_000L
        /** The latest position's time and accuracy, for the GPS dot (null: none while scanning). */
        @Volatile var gpsAt = 0L; private set
        @Volatile var gpsAccuracyM: Float? = null; private set
        /** How old the latest position may get before the GPS dot calls it lost: fixes come every ~20 s on balanced. */
        @Volatile var gpsStaleMs = 10_000L; private set
        /** No fused fix this long after asking (precise / balanced): raw GPS and network fill in. */
        private const val FUSED_SILENT_FAST_MS = 15_000L
        private const val FUSED_SILENT_MS = 30_000L

        private const val TAG = "ScanForegroundService"
        const val ACTION_STOP = "com.rfsentinel.app.action.STOP"
        /** Re-evaluates location updates, e.g. after trace recording starts or stops. */
        const val ACTION_REFRESH_LOCATION = "com.rfsentinel.app.action.REFRESH_LOCATION"
        /** Re-applies Sentinel Link (joined, left, sharing switched) to a running scan. */
        const val ACTION_REFRESH_LINK = "com.rfsentinel.app.action.REFRESH_LINK"
        const val ACTION_RESCAN = "com.rfsentinel.app.action.RESCAN"
        /** Saves a snapshot of the device list now, without opening the app (the notification's button, the car). */
        const val ACTION_SNAPSHOT = "com.rfsentinel.app.action.SNAPSHOT"

        /** [ACTION_SNAPSHOT] from anywhere; [said] gets the line to show ("Snapshot saved: 42 devices"). */
        fun snapshotNow(context: Context, said: (String) -> Unit) {
            val app = context.applicationContext
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val n = runCatching { com.rfsentinel.app.data.Snapshots.saveNow(app) }.getOrDefault(-1)
                val msg = when {
                    n < 0 -> "Couldn't save the snapshot"
                    n == 0 -> "The list is empty: nothing to save"
                    else -> "Snapshot saved: $n device${if (n == 1) "" else "s"}"
                }
                android.os.Handler(Looper.getMainLooper()).post { said(msg) }
            }
        }

        /** Android downgrades an unfiltered BLE scan to opportunistic after ~30 min; restart before. */
        private const val BLE_RESTART_INTERVAL_MS = 25 * 60 * 1000L
        /** Bluetooth back on this soon after it went off: Android restarting it after a crash, not you turning it off. */
        private const val BT_RESTART_MS = 15_000L
        /** Watchdog: a scanner silent this long is restarted. */
        private const val BLE_SILENCE_MS = 2 * 60_000L
        /** After the screen comes on, the unfiltered BLE scan should be delivering again within this long. */
        private const val SCREEN_ON_CHECK_MS = 10_000L
        private const val WIFI_SILENCE_MS = 3 * 60_000L
        private const val MAX_SILENCE_MS = 16 * 60_000L

        /** At most one logged row per device per this interval (BLE repeats many times a second). */
        private const val LOG_THROTTLE_MS = 30_000L

        /** Re-run identification/signatures per device at most this often; RSSI still updates every advert. */
        private const val CLASSIFY_INTERVAL_MS = 2_000L

        private const val STATUS_INTERVAL_MS = 10_000L
        /** Each periodic job holds a short wake lock only while it runs. */
        private const val WAKE_SLICE_MS = 10_000L
        private const val HISTORY_FLUSH_MS = 60_000L
        /** A device page's details are saved at least this often while it's heard (and whenever its matches change). */
        private const val DETAILS_SAVE_MS = 5 * 60_000L
        /** Saved device pages kept (newest first, plus every favourite / named / noted device): a few KB each. */
        private const val DETAILS_KEEP = 10_000
        private const val DRONE_OVERHEAD_REPEAT_MS = 5 * 60_000L
        private const val KNOWN_ALPR_REPEAT_MS = 30 * 60_000L
        private const val CELL_CHECK_MS = 15_000L
        private const val CELL_REPEAT_MS = 30 * 60_000L
        private const val PLACING_MAX_AGE_MS = 30_000L
        private const val ESP_HIT_TTL_MS = 5 * 60_000L
        /** Sentinel Link: what this phone gathers goes out about this often. */
        private const val LINK_FLUSH_MS = 1_000L
        /** A device this phone (or its hardware) heard this recently isn't taken from another Sentinel. */
        private const val HEARD_HERE_MS = 10_000L
        /** Mesh: how often the send queue is checked, the shortest gap for a watchlist match, a presence packet, the name. */
        private const val MESH_TICK_MS = 5_000L
        private const val MESH_MIN_GAP_MS = 15_000L
        /** The node's own telemetry older than this is not used for the automatic pace. */
        private const val OWN_STATS_MS = 2 * 3_600_000L
        private const val MESH_PRESENCE_MS = 10 * 60_000L
        private const val MESH_NAME_MS = 10 * 60_000L
        private const val MQTT_TICK_MS = 5_000L
        /** A hardware change goes over Bluetooth and the internet relay at once, but not more often than this. */
        private const val HW_NET_GAP_MS = 10_000L
        private const val MQTT_PRESENCE_MS = 60_000L
        /** Packets per tick at most: a burst of new devices goes out over a few ticks. */
        private const val MQTT_MAX_PACKETS = 8
        private const val MQTT_PLANES_MS = 10_000L
        private const val MQTT_PLANE_PACKETS = 4
        private const val LINK_PLANES_MS = 5_000L
        /** A network fix this soon after a raw GPS fix is ignored. */
        private const val RAW_GPS_PREFERRED_MS = 10_000L
        private const val PLACING_MAX_ACCURACY_M = 50f
        /** Pinpointing: precise 1 s fixes for this long after a flagged device was last heard. */
        private const val LOCATING_HOLD_MS = 120_000L

        /**
         * Maps on screen (phone or car). While any is, location switches to precise
         * GPS so the devices they show are placed where they really were heard.
         */
        private val mapViewers = java.util.concurrent.atomic.AtomicInteger(0)
        val mapVisible: Boolean get() = mapViewers.get() > 0

        fun mapShown(context: Context) { mapViewers.incrementAndGet(); refreshLocation(context) }
        fun mapHidden(context: Context) { mapViewers.updateAndGet { (it - 1).coerceAtLeast(0) }; refreshLocation(context) }

        /** Re-applies the location mode of a running scan (never starts one). */
        private fun refreshLocation(context: Context) {
            // The debug demo pretends to scan: never start real location updates for it.
            if (!isRunning || com.rfsentinel.app.ui.DemoData.fakeLocation != null) return
            runCatching {
                context.startService(Intent(context, ScanForegroundService::class.java).setAction(ACTION_REFRESH_LOCATION))
            }
        }

        /** Latest location fix while scanning (for the car map), or null. */
        @Volatile
        var lastFix: Location? = null
            private set

        /** Tests only: pretend the scanner has this fix. */
        @androidx.annotation.VisibleForTesting
        fun setLastFixForTest(l: Location?) { lastFix = l }

        /** Last watchdog restart (time, which scanner), for the status line. */
        @Volatile
        var lastWatchdogRestart: Pair<Long, String>? = null
            private set

        /** The restarted-scan note was hidden by the user. */
        fun forgetWatchdogRestart() { lastWatchdogRestart = null }

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

        /** The list's pull-down: a running scan scans WiFi and checks the cells right now (never starts a scan). */
        fun rescan(context: Context) {
            if (!isRunning) return
            runCatching { context.startService(Intent(context, ScanForegroundService::class.java).setAction(ACTION_RESCAN)) }
        }

        /** Sentinel Link changed: a running scan starts or stops linking (never starts a scan). */
        fun refreshLink(context: Context) {
            if (!isRunning) return
            runCatching { context.startService(Intent(context, ScanForegroundService::class.java).setAction(ACTION_REFRESH_LINK)) }
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
    /** Flagged devices that keep turning up at places along your way (pipeline thread only). */
    private val placeFollow = PlaceFollow()
    /** Drone Remote ID over Wi-Fi Aware, on phones that have it. */
    private var nanRemoteId: NanRemoteId? = null

    private val classified = ConcurrentHashMap<String, Classified>()
    private val lastAlerted = ConcurrentHashMap<String, Long>()
    private val lastLogged = ConcurrentHashMap<String, Long>()
    private val lastDroneOverhead = ConcurrentHashMap<String, Long>()
    private var wifiPollJob: Job? = null
    private var bleRestartJob: Job? = null
    private var housekeepingJob: Job? = null
    private var cellJob: Job? = null
    private var bubbleJob: Job? = null
    private var radarJob: Job? = null
    private var cellMonitor: CellMonitor? = null
    private val lastCellAlert = HashMap<String, Long>()
    private var btStateReceiverRegistered = false
    private var lastHistoryFlush = 0L

    @Volatile private var lastLocation: Location? = null
    private var locationListening = false
    /** 0 = balanced, 1 = precise every 3 s, 2 = precise every second (pinpointing a flagged device). */
    private var locationMode = 0
    /** A flagged device was heard recently: GPS runs precise and fast so it can be pinpointed. */
    @Volatile private var locatingUntil = 0L
    /** This round of [locatingUntil] pinpoints (the setting is on and precise location allowed). */
    @Volatile private var pinpointing = false
    private val locatingHandler = android.os.Handler(Looper.getMainLooper())
    private val locatingCheck = object : Runnable {
        override fun run() {
            val left = locatingUntil - System.currentTimeMillis()
            if (left > 0) locatingHandler.postDelayed(this, left + 1_000) else updateLocationUpdates()
        }
    }
    /** Radar-detector sound style: last good GPS fix, to say "GPS connected" when it locks or comes back. */
    private var lastGoodGpsAt = 0L

    /**
     * Fused location can stay silent on a phone without Google's location services: if it hasn't
     * given a single fix by [backstopCheck], raw GPS / network fill in until it does.
     */
    private var fusedEverFixed = false
    private var backstopOn = false
    private val backstopListener = LocationListenerCompat { onFix(it) }
    private val backstopCheck = Runnable { engageBackstop() }

    private val locationListener = LocationListenerCompat {
        if (it.provider == LocationManager.FUSED_PROVIDER && !fusedEverFixed) {
            fusedEverFixed = true
            locatingHandler.removeCallbacks(backstopCheck)
            if (backstopOn) {
                Log.i(TAG, "Fused location is giving fixes: raw GPS backstop off")
                runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(backstopListener) }
                backstopOn = false
            }
        }
        onFix(it)
    }

    /** When raw GPS last gave a fix (backstop, or a phone without fused location). */
    private var lastRawGpsAt = 0L

    private fun onFix(it: Location) {
        // Raw GPS and network run side by side: while GPS gives fixes, the coarser network ones would make the
        // position (and a recorded trace) jump back and forth between the two.
        if (it.provider == LocationManager.GPS_PROVIDER) lastRawGpsAt = System.currentTimeMillis()
        else if (it.provider == LocationManager.NETWORK_PROVIDER && System.currentTimeMillis() - lastRawGpsAt < RAW_GPS_PREFERRED_MS) return
        if (it.provider == LocationManager.GPS_PROVIDER && it.hasAccuracy() && it.accuracy <= 50f) {
            val now = System.currentTimeMillis()
            if (now - lastGoodGpsAt > GPS_RECONNECT_MS) AlertPlayer.gpsConnected(this)
            lastGoodGpsAt = now
        }
        lastLocation = it
        lastFix = it
        if (it.hasAccuracy()) { gpsAt = System.currentTimeMillis(); gpsAccuracyM = it.accuracy }
        com.rfsentinel.app.ui.MapGps.offer(it)
        TripRecorder.onLocation(it)
        checkKnownAlpr(it)
        cellMonitor?.onLocation(it)
    }

    /** Known plate cameras: distance at the previous fix, and when each last alerted. */
    private val alprLastDistance = HashMap<String, Double>()
    private val alprLastAlert = HashMap<String, Long>()

    /** When Bluetooth went off during an extended scan; 0 when it didn't. */
    private var btDroppedAt = 0L

    /** Starts BLE scanning if the user turns Bluetooth on after the service started. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    // Off and straight back on during an extended scan: Android restarting its crashed Bluetooth.
                    if (System.currentTimeMillis() - btDroppedAt < BT_RESTART_MS) BleScanEngine.strike(context, "Bluetooth restarted")
                    btDroppedAt = 0L
                    if (bleWanted()) startBle()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (bleEngine.extended) btDroppedAt = System.currentTimeMillis()
                    stopBle() // its periodic restart too
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        OuiWatchlist.load(this)
        serviceScope.launch {
            val today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            runCatching { com.rfsentinel.app.detect.FixedRouter.load(AppDatabase.getInstance(this@ScanForegroundService)
                .knownDeviceDao().familiarRouters(com.rfsentinel.app.detect.FixedRouter.MIN_SESSIONS, today)) }
        }

        pipelineThread = HandlerThread("rf-pipeline").apply { start() }
        pipeline = Handler(pipelineThread.looper)
        bleEngine = BleScanEngine(this) { r ->
            val t = System.currentTimeMillis()
            lastBleResultAt = t
            bleResults.incrementAndGet()
            bleWatchdogRestartAt?.let { onRecovered("Bluetooth", it, t); bleWatchdogRestartAt = null; bleSilenceMs = BLE_SILENCE_MS }
            // Another Sentinel's link advert: handled by Sentinel Link, not a device to list.
            if (com.rfsentinel.app.link.PhoneLink.onScanResult(r)) return@BleScanEngine
            pipeline.post { AdvertFactory.fromBle(r)?.let(::processPhone) }
        }
        wifiEngine = WifiScanEngine(this) { results ->
            val t = System.currentTimeMillis()
            lastWifiResultAt = t
            wifiWatchdogRestartAt?.let { onRecovered("WiFi", it, t); wifiWatchdogRestartAt = null; wifiSilenceMs = WIFI_SILENCE_MS }
            pipeline.post { results.forEach { r -> AdvertFactory.fromWifi(r)?.let(::processPhone) } }
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
        if (intent?.action == ACTION_SNAPSHOT) {
            if (!isRunning) { stopSelf(); return START_NOT_STICKY }
            snapshotNow(this) { android.widget.Toast.makeText(applicationContext, it, android.widget.Toast.LENGTH_SHORT).show() }
            return START_STICKY
        }
        if (intent?.action == ACTION_RESCAN) {
            if (!isRunning) { stopSelf(); return START_NOT_STICKY }
            // Bluetooth and the external boards report all the time; WiFi and the cells are polled.
            if (wifiPollJob?.isActive == true) wifiEngine.requestScan()
            if (cellJob?.isActive == true) startCellChecks()
            return START_STICKY
        }
        if (intent?.action == ACTION_REFRESH_LINK) {
            if (!isRunning) { stopSelf(); return START_NOT_STICKY }
            updateLink()
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
            lastGoodGpsAt = 0L
            gpsAt = 0L; gpsAccuracyM = null
            AlertPlayer.preload(this)
            AlertPlayer.startup(this)
            serviceScope.launch { pruneOldData() }
        }

        // Re-evaluated on every start command, so Settings changes apply live - but the scan is only restarted when
        // its settings changed: Android silently stops delivering to an app that starts more than 5 scans in 30 s
        // (each start here is two), and a car start, Android Auto and Record trace can each send a start command.
        applyBle()
        if (wifiWanted()) startWifiPolling() else stopWifiPolling()
        if (ownOn() && Prefs.categoryEnabled(this, Category.DRONE) && NanRemoteId.supported(this) && nearbyWifiAllowed()) {
            if (nanRemoteId == null) nanRemoteId = NanRemoteId(this) { a -> pipeline.post { processPhone(a) } }.also { it.start() }
        } else { nanRemoteId?.stop(); nanRemoteId = null }
        // ESP32 boards on USB (OUI-Spy / GhostESP / Marauder): their reports join the same pipeline.
        com.rfsentinel.app.esp.EspBoards.start(this) { list -> pipeline.post { list.forEach(::processEsp) } }
        // A USB WiFi adapter in monitor mode (e.g. AWUS036ACS): access points and client devices.
        com.rfsentinel.app.usb.UsbWifi.start(this) { list -> pipeline.post { list.forEach(::processUsbWifi) } }
        // A USB Bluetooth adapter (dongle or combo adapter), next to the phone's own Bluetooth (Settings › External hardware).
        com.rfsentinel.app.usb.UsbBluetooth.start(this) { list -> pipeline.post { list.forEach(::processUsbBt) } }
        com.rfsentinel.app.usb.DeauthWatch.onAttack = { a -> pipeline.post { onDeauth(a) } }
        com.rfsentinel.app.usb.KarmaWatch.onAttack = { a -> pipeline.post { onKarma(a) } }
        // What the SDR does (Settings > RTL-SDR radio): any mix, in turns.
        val radioOn = Prefs.categoryEnabled(this, Category.RADIO)
        val sdrModes = listOfNotNull(
            com.rfsentinel.app.sdr.SdrRadio.Mode.SWEEP.takeIf { radioOn && Prefs.sdrSweep(this) },
            com.rfsentinel.app.sdr.SdrRadio.Mode.ADSB.takeIf { Prefs.sdrAdsb(this) && Prefs.categoryEnabled(this, Category.AIRCRAFT) },
            com.rfsentinel.app.sdr.SdrRadio.Mode.P25.takeIf { radioOn && Prefs.sdrP25(this) },
            com.rfsentinel.app.sdr.SdrRadio.Mode.PAGER.takeIf { radioOn && Prefs.sdrPager(this) },
            com.rfsentinel.app.sdr.SdrRadio.Mode.TPMS.takeIf { radioOn && Prefs.sdrTpms(this) }
        ).toSet()
        com.rfsentinel.app.sdr.SdrRadio.modes = sdrModes
        com.rfsentinel.app.sdr.SdrRadio.turnMs = com.rfsentinel.app.sdr.SdrRadio.Mode.entries.associateWith { Prefs.sdrTurnS(this, it) * 1000L }
        com.rfsentinel.app.sdr.SdrRadio.stayOnActivity = Prefs.sdrStay(this)
        com.rfsentinel.app.sdr.SdrRadio.onP25Call = { tg -> pipeline.post { onP25Call(tg) } }
        com.rfsentinel.app.sdr.SdrRadio.p25Inputs = Prefs.p25Inputs(this)
        com.rfsentinel.app.sdr.SdrRadio.p25Record = Prefs.p25Record(this)
        com.rfsentinel.app.sdr.SdrRadio.p25Listen = com.rfsentinel.app.sdr.SdrRadio.Listen.entries[Prefs.p25Listen(this)]
        com.rfsentinel.app.sdr.SdrRadio.sweepListen = Prefs.radioListen(this)
        com.rfsentinel.app.sdr.SdrRadio.p25Watched = com.rfsentinel.app.sdr.P25Log.watched(Prefs.p25Talkgroups(this))
        com.rfsentinel.app.sdr.SdrRadio.p25Mute = Prefs.p25Mute(this)
        com.rfsentinel.app.sdr.SdrRadio.pagerChannel = Prefs.pagerChannel(this)
        com.rfsentinel.app.sdr.SdrRadio.onPagerPage = { page -> pipeline.post { onPagerPage(page) } }
        com.rfsentinel.app.sdr.SdrRadio.onP25Input = { call, hz, snr -> pipeline.post { onP25Input(call, hz, snr) } }
        com.rfsentinel.app.sdr.SdrRadio.onP25Nearby = { msg, snr, tg -> pipeline.post { onP25Nearby(msg, snr, tg) } }
        com.rfsentinel.app.sdr.SdrRadio.onTpms = { r -> pipeline.post { onTpms(r) } }
        com.rfsentinel.app.sdr.SdrRadio.location = { (lastLocation ?: lastFix)?.let { it.latitude to it.longitude } }
        com.rfsentinel.app.sdr.SdrRadio.onAircraft = { planes, unlocated ->
            if (onlineWatch.localAircraft(planes, unlocated)) com.rfsentinel.app.sdr.SdrRadio.activity(com.rfsentinel.app.sdr.SdrRadio.Mode.ADSB)
        }
        if (sdrModes.isNotEmpty() || radioOn) {
            // Settings > RTL-SDR radio: bands, excluded ranges, watched frequencies, sensitivity (re-read on every refresh).
            com.rfsentinel.app.sdr.SdrRadio.config = com.rfsentinel.app.sdr.RadioSettings.config(this)
            com.rfsentinel.app.sdr.FreqNames.load(this)
            com.rfsentinel.app.sdr.SdrRadio.start(this) { e -> pipeline.post { onRadio(e) } }
        }
        else com.rfsentinel.app.sdr.SdrRadio.stop(this)
        // An OUI-SPY board paired over Bluetooth (App-Controlled firmware).
        Prefs.ouiSpyBoard(this)?.let { addr ->
            // On the main thread, like every other start / stop of the phone's scan.
            com.rfsentinel.app.esp.OuiSpyBle.onLiveChanged = { locatingHandler.post { if (isRunning) applyBle() } }
            com.rfsentinel.app.esp.OuiSpyBle.start(this, addr, Prefs.ouiSpyRelayAll(this), Prefs.ouiSpyEngines(this)) { list ->
                pipeline.post { list.forEach(::processEsp) }
            }
        } ?: com.rfsentinel.app.esp.OuiSpyBle.stop()
        // A Meshtastic or MeshCore radio paired over Bluetooth, unless Sentinel Link already holds it (see startMesh).
        val link = com.rfsentinel.app.link.LinkStore
        Prefs.meshRadio(this)?.takeUnless { link.meshOn(this) && it.equals(link.meshNode(this), ignoreCase = true) }?.let { addr ->
            com.rfsentinel.app.esp.MeshBle.start(this, addr) { list -> pipeline.post { list.forEach(::processEsp) } }
        } ?: com.rfsentinel.app.esp.MeshBle.stop()
        updateLink()
        updateLocationUpdates()
        startHousekeeping()
        startCellChecks()
        startGnssChecks()
        onlineWatch.sync()
        startBubble()
        startRadarBeep()

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

    /**
     * Where you are now: the last fix moved on by your speed and course for the second or two
     * since it came, so readings taken between fixes aren't placed behind you.
     */
    private fun placeNow(l: Location, now: Long): DeviceRegistry.GeoSample {
        val ageS = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9
        val (lat, lon) = if (l.hasSpeed() && l.hasBearing())
            com.rfsentinel.app.detect.Locator.ahead(l.latitude, l.longitude, l.speed.toDouble(), l.bearing.toDouble(), ageS)
        else l.latitude to l.longitude
        return DeviceRegistry.GeoSample(now, lat, lon)
    }

    /**
     * A flagged device ([mac]) was heard: keep precise, fast GPS on for a while so it can be pinpointed, and show
     * its live read on the map ([com.rfsentinel.app.ui.LiveRead]).
     */
    private fun noteLocating(mac: String) {
        val now = System.currentTimeMillis()
        if (Prefs.pinpointAlertsOnly(this) && now - (lastAlerted[mac] ?: 0L) > LOCATING_HOLD_MS) return
        val was = now < locatingUntil
        locatingUntil = now + LOCATING_HOLD_MS
        // Called for every packet of a flagged device: only the switch-on does the setting / permission checks.
        if (!was) {
            pinpointing = Prefs.pinpointFlagged(this) && hasFineLocation()
            if (pinpointing) {
                updateLocationOnMain()
                locatingHandler.removeCallbacks(locatingCheck)
                locatingHandler.postDelayed(locatingCheck, LOCATING_HOLD_MS + 1_000)
            }
        }
        if (pinpointing) com.rfsentinel.app.ui.LiveRead.pinpoint(mac, now, locatingUntil)
    }

    /** A fix good enough to pin a device on the map: under 30 s old and within ~50 m. */
    private fun usableForPlacing(l: Location): Boolean {
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return ageMs in 0..PLACING_MAX_AGE_MS && (!l.hasAccuracy() || l.accuracy <= PLACING_MAX_ACCURACY_M)
    }

    // ---- Scanning -----------------------------------------------------------

    // Exact addresses only: a name rule can be 17 characters long too.
    private fun bleWatched() = OuiWatchlist.allEntries().filter { it.isCustom && com.rfsentinel.app.util.MacUtil.isValidMac(it.prefix) }.map { it.prefix }
    private fun bleSettingsKey() = "${bleMode()}|${bleEased()}|${bleWatched()}|${Prefs.blePassive(this)}|${Prefs.bleScreenOffAll(this)}"

    /** Settings › Ease off for Bluetooth audio, with headphones, a car or a speaker connected. */
    private fun bleEased(): Boolean {
        if (!Prefs.bleEaseForAudio(this)) return false
        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        val bt = setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_HEARING_AID, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER, android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST)
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in bt }
    }

    /** Maximum drops to Balanced while eased; Balanced and Battery saver stay as they are. */
    private fun bleMode(): Int = Prefs.bleScanMode(this).let {
        if (it == android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY && bleEased()) android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED else it
    }

    /** Bluetooth audio coming and going re-checks the scan (restarted only when easing changes it). */
    private val audioDevices = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) { if (isRunning) applyBle() }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) { if (isRunning) applyBle() }
    }
    private var audioDevicesWatched = false
    /** [bleSettingsKey] of the running scan. */
    private var bleStartedWith = ""

    /**
     * The phone's Bluetooth scan on or off as Settings say. With "Phone Bluetooth scan with OUI-SPY" off it pauses
     * while the OUI-SPY board is live (the board hears farther) and comes back as soon as the board drops out.
     */
    // Sentinel Link's "Show only the other Sentinels" turns the phone's own scans off without touching Settings.
    private fun ownOn() = !com.rfsentinel.app.link.LinkStore.ownSensorsOff(this)
    private fun bleWanted() = Prefs.bleEnabled(this) && ownOn()
    private fun wifiWanted() = Prefs.wifiEnabled(this) && ownOn()
    // ...and makes it send nothing either, so its own node's LoRa neighbours and plugged-in hardware stay off the others' lists.
    private fun sharing() = ownOn()

    private fun applyBle() {
        val toBoard = !Prefs.ouiSpyPhoneBle(this) && Prefs.ouiSpyBoard(this) != null && com.rfsentinel.app.esp.OuiSpyBle.live
        if (Prefs.bleEaseForAudio(this) != audioDevicesWatched) {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (audioDevicesWatched) am.unregisterAudioDeviceCallback(audioDevices)
            else am.registerAudioDeviceCallback(audioDevices, android.os.Handler(Looper.getMainLooper()))
            audioDevicesWatched = !audioDevicesWatched
        }
        if (bleWanted() && !toBoard) {
            if (!bleEngine.isScanning() || bleSettingsKey() != bleStartedWith) { bleEngine.stop(); startBle() }
        } else stopBle()
    }

    private fun startBle() {
        bleStartedAt = System.currentTimeMillis()
        val watched = bleWatched()
        bleStartedWith = bleSettingsKey()
        bleEngine.start(bleMode(), watched, bleEased())
        bleRestartJob?.cancel()
        bleRestartJob = serviceScope.launch {
            while (isActive) {
                delay(BLE_RESTART_INTERVAL_MS)
                bleEngine.stop()
                delay(1_000)
                bleStartedAt = System.currentTimeMillis()
                bleEngine.start(bleMode(), watched, bleEased())
            }
        }
    }

    private fun stopBle() {
        bleRestartJob?.cancel()
        bleEngine.stop()
    }

    private fun startWifiPolling() {
        wifiStartedAt = System.currentTimeMillis()
        wifiEngine.start()
        wifiPollJob?.cancel()
        wifiPollJob = serviceScope.launch {
            while (isActive) {
                holdAwake()
                val interval = Prefs.scanIntervalMs(this@ScanForegroundService)
                com.rfsentinel.app.ui.LiveWindow.wifiScanMs = interval
                com.rfsentinel.app.ui.LiveWindow.usbQuickClear = Prefs.usbQuickClear(this@ScanForegroundService)
                com.rfsentinel.app.ui.LiveWindow.wifiScanAt = System.currentTimeMillis()
                wifiEngine.requestScan()
                delay(interval)
            }
        }
    }

    /** Wi-Fi Aware needs location, and the nearby-WiFi permission from Android 13. */
    private fun nearbyWifiAllowed() = hasFineLocation() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)

    private fun stopWifiPolling() {
        wifiPollJob?.cancel()
        wifiEngine.stop()
    }

    /**
     * Location is needed for GPS tagging, follower alerts, trace recording and
     * known-camera warnings. While recording or watching for cameras, GPS fixes
     * come every ~3 s / 5 m; otherwise balanced fixes every ~20 s / 25 m to save battery.
     * While a flagged device is in range (pinpointing on), every second.
     */
    @SuppressLint("MissingPermission") // checked by hasFineLocation()
    fun updateLocationUpdates() {
        val locating = Prefs.pinpointFlagged(this) && System.currentTimeMillis() < locatingUntil
        val wanted = hasFineLocation() &&
            (Prefs.gpsTaggingEnabled(this) || Prefs.followerAlerts(this) || TripRecorder.isRecording || knownAlprActive() ||
                mapVisible || onlineActive() || locating)
        // Frequent fixes while recording a trace or watching for known cameras
        // (at highway speed a 20 s interval could skip right past one).
        val fast = TripRecorder.isRecording || knownAlprActive() || mapVisible || locating
        val mode = when { locating -> 2; fast -> 1; else -> 0 }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (locationListening && (!wanted || mode != locationMode)) {
            stopLocationUpdates(lm)
        }
        if (wanted && !locationListening) {
            locationMode = mode
            val fused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER)
            if (fused) {
                requestFixes(lm, listOf(LocationManager.FUSED_PROVIDER), locationListener)
                when {
                    backstopOn -> requestFixes(lm, rawProviders(lm), backstopListener)
                    // The first fused fix can take a while on a balanced, 20 s request.
                    !fusedEverFixed -> locatingHandler.postDelayed(backstopCheck, if (fast) FUSED_SILENT_FAST_MS else FUSED_SILENT_MS)
                }
            } else requestFixes(lm, rawProviders(lm), locationListener)
            requestDotFixes(lm)
            locationListening = true
        }
    }

    private fun rawProviders(lm: LocationManager) =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }

    @SuppressLint("MissingPermission") // only called from updateLocationUpdates(), which checks it
    private fun requestFixes(lm: LocationManager, providers: List<String>, listener: LocationListenerCompat) {
        val locating = locationMode == 2
        val fast = locationMode >= 1
        val intervalMs = if (locating) 1_000L else 3_000L
        providers.forEach { p ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Fused location defaults to "balanced" (often WiFi/cell, ~100 m, and
                    // paused while it thinks you're stationary): too coarse to warn about a
                    // camera a few hundred metres ahead. Fast mode asks for real GPS.
                    val request = android.location.LocationRequest.Builder(if (fast) intervalMs else 20_000L)
                        .setQuality(
                            if (fast) android.location.LocationRequest.QUALITY_HIGH_ACCURACY
                            else android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                        )
                        .setMinUpdateDistanceMeters(if (locating) 0f else if (fast) 5f else 25f)
                        .build()
                    lm.requestLocationUpdates(p, request, mainExecutor, listener)
                } else if (fast) lm.requestLocationUpdates(p, intervalMs, if (locating) 0f else 5f, listener, Looper.getMainLooper())
                else lm.requestLocationUpdates(p, 20_000L, 25f, listener, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { if (lastLocation == null) lastLocation = it }
            } catch (e: Exception) {
                Log.w(TAG, "Location provider $p unavailable", e)
            }
        }
    }

    /** No fused fix yet: GPS and network join in (fused stays on, and takes over at its first fix). */
    private fun engageBackstop() {
        if (!locationListening || fusedEverFixed || backstopOn || !hasFineLocation()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val raw = rawProviders(lm)
        if (raw.isEmpty()) { Log.w(TAG, "No fused fix yet, and GPS and network location are both off: nothing to fall back on"); return }
        Log.i(TAG, "No fused fix yet: raw ${raw.joinToString("+")} location on as a backstop")
        backstopOn = true
        requestFixes(lm, raw, backstopListener)
    }

    /**
     * Every fix the phone computes, with no distance filter: the requests above skip fixes until you move 5-25 m,
     * so standing still would leave the GPS dot red. Only refreshes the dot; the scan still uses [onFix].
     */
    private val dotListener = LocationListenerCompat {
        if (!it.hasAccuracy()) return@LocationListenerCompat
        if (it.provider == LocationManager.NETWORK_PROVIDER && System.currentTimeMillis() - lastRawGpsAt < RAW_GPS_PREFERRED_MS) return@LocationListenerCompat
        gpsAt = System.currentTimeMillis(); gpsAccuracyM = it.accuracy
    }

    @SuppressLint("MissingPermission") // only called from updateLocationUpdates(), which checks it
    private fun requestDotFixes(lm: LocationManager) {
        gpsStaleMs = if (locationMode >= 1) 10_000L else 60_000L
        if (!lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) return
        runCatching { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, dotListener, Looper.getMainLooper()) }
            .onFailure { Log.w(TAG, "Passive location unavailable", it) }
    }

    private fun stopLocationUpdates(lm: LocationManager) {
        locatingHandler.removeCallbacks(backstopCheck)
        runCatching { lm.removeUpdates(dotListener) }
        runCatching { lm.removeUpdates(locationListener) }
        runCatching { lm.removeUpdates(backstopListener) } // backstopOn stays: re-requested with the next mode
        locationListening = false
    }

    // ---- Pipeline -------------------------------------------------------------

    /** Something the phone's own Bluetooth or Wi-Fi chip heard. */
    private fun processPhone(a: Advert) {
        com.rfsentinel.app.esp.HeardBy.phone.mark(a.mac)
        if (sharing()) linkOutbox?.add(LinkCodec.Sighting(LinkCodec.KIND_PHONE, a))
        process(a)
    }

    /**
     * @param via the linked Sentinel that heard it (Sentinel Link), with [peerAt] where that Sentinel was;
     * null for what this phone and its hardware heard. [quiet]: listed and logged, no alert (Sentinel Link screen).
     */
    private fun process(a: Advert, reportedRemoteId: RemoteId.Info? = null, broadcast: DeviceRegistry.GeoSample? = null,
                        via: String? = null, peerAt: Location? = null, toMesh: Boolean = via == null, quiet: Boolean = false) {
        val now = a.timestamp
        val mac = a.mac
        if (OuiWatchlist.version != watchlistVersion) recheckWatchlist(now)
        if (a.isWifi) com.rfsentinel.app.data.TrustedNetworks.onSeen(mac, a.name) // before anything asks whether it's whitelisted

        // Remote ID messages arrive one type at a time; merge every packet.
        var remoteId: RemoteId.Info? = reportedRemoteId
        if (a.isBle) {
            a.serviceData[Advert.uuid16(0xFFFA)]?.let { remoteId = RemoteId.decodeBle(it, DeviceRegistry.remoteIdOf(mac)) }
        } else {
            a.wifi?.infoElements?.firstOrNull { it.first == 221 && RemoteId.isWifiIe(it.second) }
                ?.let { remoteId = RemoteId.decodeWifiIe(it.second, DeviceRegistry.remoteIdOf(mac)) }
        }

        // Every tracker advert counts toward a fake-tracker flood (HackerWatch).
        if (a.isBle) com.rfsentinel.app.detect.HackerWatch.tagFlood(a, now)?.let { onTagFlood(it) }

        val prev = classified[mac]
        // A newly heard address counts toward a Bluetooth spam flood (HackerWatch).
        val spam = if (prev == null) com.rfsentinel.app.detect.HackerWatch.bleSpam(a, now) else null
        val c = if (prev == null || now - prev.time >= CLASSIFY_INTERVAL_MS) {
            classify(a, now, listOfNotNull(spam, com.rfsentinel.app.detect.HackerWatch.evilTwin(a))).also { classified[mac] = it }
        } else {
            prev
        }
        // Whitelist rules (Ignore all like this...) look at its matches: before anything asks whether it's whitelisted.
        if (com.rfsentinel.app.data.TrustRules.rules.isNotEmpty())
            com.rfsentinel.app.data.TrustRules.onSeen(com.rfsentinel.app.data.TrustRules.factsOf(mac, a.name, c.vendor, c.hits, a))

        // Place devices only with a fresh, precise fix: a stale or WiFi/cell-based one
        // (often 100 m+ off) would put the dot far from where the device really was.
        val lora = com.rfsentinel.app.esp.isLoRaNode(mac, a.name)
        // A LoRa node can be kilometres away: never placed where this phone (or the Sentinel that heard it) was.
        val loc = if (lora) null else if (via != null) peerAt else lastLocation?.takeIf { usableForPlacing(it) }
        // Heard by another Sentinel: placed where that Sentinel was.
        val geo = if (via != null) loc?.let { DeviceRegistry.GeoSample(now, it.latitude, it.longitude) } else loc?.let { placeNow(it, now) }
        val here = lastLocation?.let { DeviceRegistry.GeoSample(now, it.latitude, it.longitude) }
        val isFirst = DeviceRegistry.report(a, c.hits, c.identity, c.vendor, remoteId, geo, now, broadcast, here, lora, via != null)
        if (isFirst) serviceScope.launch { loadHistory(mac) }
        // Over the mesh too (Meshtastic node): not the user's own whitelisted devices.
        if (toMesh && sharing() && !WhitelistCache.contains(mac)) { meshOutbox?.add(a, c.hits, now); mqttOutbox?.add(a, c.hits, now) }
        if (TripRecorder.isRecording) {
            TripRecorder.onDevice(
                mac, a.rssi, a.source.name,
                if (WhitelistCache.contains(mac)) null else c.hits.firstOrNull(),
                a.name, c.vendor, c.identity.type, loc, now,
                com.rfsentinel.app.usb.ProbeIntel.of(mac)?.probed.orEmpty()
            )
        }

        if (via == null) remoteId?.let { info -> if (loc != null) maybeAlertDroneOverhead(a, info, loc, now) }

        val best = c.hits.firstOrNull() ?: return
        // Ignored trackers (their address changes, so they can't be whitelisted); this is
        // also where an ignored "it's mine" tag carries its mute over to a new address.
        // Any tracker match counts, not just the top one.
        val trackerHit = c.hits.firstOrNull { it.category == Category.TRACKER }
        if (trackerHit != null && com.rfsentinel.app.data.TrackerMutes.onSeen(
                this, mac, trackerHit.label, a.rssi, now, DeviceRegistry.linkedFrom(mac))
        ) return
        if (WhitelistCache.contains(mac)) return
        if (!com.rfsentinel.app.detect.FixedRouter.quiet(mac, c.hits, Prefs.alertThreshold(this, best.category))) noteLocating(mac)

        maybeLog(a, best, c.vendor, loc, now, via)
        if (!quiet) maybeAlert(a, best, c.hits, now, via)
        // Following is about this phone: a device near another Sentinel isn't following it.
        if (via != null) return

        val trackerPaused = best.category == Category.TRACKER && now < Prefs.trackerFollowPausedUntil(this)
        val followMs = Prefs.followMinMinutes(this) * 60_000L
        val followM = Prefs.followMinMeters(this).toDouble()
        // Followers that stay in range, and ones that keep turning up at places along your way.
        if (loc != null && !best.listOnly) placeFollow.heard(mac, DeviceRegistry.linkedFrom(mac), now, loc.latitude, loc.longitude)
        // A tag that can't say it's with its owner (Tile, SmartTag, Chipolo...) and never leaves a phone's
        // side is that phone owner's, like an AirTag near its iPhone: no follow alert while that holds.
        val withOwner = loc != null && best.category == Category.TRACKER && !SignatureEngine.reportsOwnerState(best) &&
            ownerPhone(mac, now) != null
        if (loc != null && Prefs.followerAlerts(this) && !trackerPaused && !best.listOnly && !withOwner &&
            (DeviceRegistry.checkFollowing(mac, followMs, followM).also { if (it) placeFollow.alertedNow(mac, now) } ||
                placeFollow.follower(mac, now, followMs, followM).also { if (it) DeviceRegistry.markFollowing(mac) }) &&
            !com.rfsentinel.app.data.AlertMutes.isMuted(this, mac, now)
        ) {
            val off = alertsOff(best.category, mac, c.hits)
            NotificationHelper.sendAlert(this, mac, best, a.rssi, following = true, banner = AlertKind.BANNER !in off)
            AlertPlayer.play(this, best.tier, com.rfsentinel.app.util.Spoken.device(this, best, following = true), following = true, off = off)
        }
    }

    /** [DeviceRegistry.ownerPhone] per tracker, rechecked every [OWNER_CHECK_MS]. */
    private val ownerChecks = HashMap<String, Pair<Long, String?>>()

    private fun ownerPhone(mac: String, now: Long): String? = synchronized(ownerChecks) {
        ownerChecks[mac]?.takeIf { now - it.first < OWNER_CHECK_MS }?.let { return it.second }
        if (ownerChecks.size > 200) ownerChecks.entries.removeIf { now - it.value.first >= OWNER_CHECK_MS }
        DeviceRegistry.ownerPhone(mac).also { ownerChecks[mac] = now to it }
    }

    /** Matches an ESP32 board reported, kept a few minutes so re-classification keeps them. */
    private val espHits = HashMap<String, Pair<Long, List<Hit>>>()

    /** Watchlist matches from what external hardware heard in a device's frames, kept like [espHits]. */
    private val probeHits = HashMap<String, Pair<Long, List<Hit>>>()

    /**
     * What a USB adapter or a Marauder board heard from [mac] beyond its address: the
     * network names it asked for (kept in the requested-networks list when that's on),
     * its WPS maker / model / name and its probe fingerprint. All checked against the watchlist.
     */
    private fun noteProbes(mac: String, ssids: List<String>, rssi: Int, now: Long,
                           wps: com.rfsentinel.app.usb.ProbeIntel.Wps? = null, fingerprint: String? = null, hidden: Boolean = false,
                           skeleton: Long? = null, traits: String? = null) {
        com.rfsentinel.app.usb.ProbeIntel.note(mac, wps, fingerprint, ssids, hidden, skeleton, traits)
        // Kept in the requested-networks list when that's on, and always while a trace records.
        // Where it was asked for: this phone's position, when the fix is fresh and good enough to place devices.
        val at = lastFix?.takeIf { usableForPlacing(it) }
            ?.let { it.latitude to it.longitude }
        com.rfsentinel.app.data.ProbeLog.record(this, mac, ssids, rssi, now, force = TripRecorder.isRecording,
            wps = wps, fingerprint = fingerprint, at = at, traits = traits)
        val hits = OuiWatchlist.probeHits(ssids) + OuiWatchlist.fingerprintHits(fingerprint) + OuiWatchlist.wpsHits(wps) +
            OuiWatchlist.fingerprintHits(skeleton?.let { com.rfsentinel.app.usb.ProbeIntel.shapeKey(it) }) +
            OuiWatchlist.fingerprintHits(wps?.uuid?.let { com.rfsentinel.app.usb.ProbeIntel.uuidKey(it) })
        if (hits.isEmpty()) return
        val before = probeHits[mac]
        probeHits[mac] = now to hits
        if (before == null || before.second != hits || now - before.first >= ESP_HIT_TTL_MS) classified.remove(mac)
    }

    /** One ESP32 report: becomes a normal observation, with the board's own matches added. */
    private fun processUsbWifi(m: com.rfsentinel.app.usb.MonitorSighting) {
        val now = System.currentTimeMillis()
        val mac = com.rfsentinel.app.util.MacUtil.normalize(m.mac)
        com.rfsentinel.app.esp.HeardBy.usb.mark(mac, now)
        noteProbes(mac, m.probedSsids, m.rssi, now, m.wps, m.fingerprint, m.hiddenNetwork, m.probeSkeleton, m.probeTraits)
        val a = Advert(
            // A client has no network name; its WPS device name or model says what it is.
            mac = mac, source = Advert.Source.WIFI, rssi = m.rssi, name = m.ssid ?: m.wps?.label,
            addressType = com.rfsentinel.app.detect.AddressType.ofWifi(mac),
            wifi = Advert.WifiInfo(m.frequencyMhz, m.capabilities, m.standard, m.infoElements, client = !m.isAccessPoint,
                wildcardProbe = m.wildcardProbe, flockProbeIe = m.flockProbeIe, probeSkeleton = m.probeSkeleton,
                probeRun = m.probeRun,
                bssid = m.bssid?.takeIf { !m.isAccessPoint }?.let { com.rfsentinel.app.util.MacUtil.normalize(it) }?.takeIf { it != mac },
                upSince = m.upSince, dataBytes = m.dataBytes),
            timestamp = now
        )
        if (sharing()) linkOutbox?.add(LinkCodec.Sighting(LinkCodec.KIND_USB, a, probed = m.probedSsids, fingerprint = m.fingerprint, hidden = m.hiddenNetwork))
        process(a, null)
    }

    /** A USB Bluetooth adapter's advertising report: the same as one the phone's Bluetooth heard, badged USB BT. */
    private fun processUsbBt(r: com.rfsentinel.app.usb.UsbBluetooth.Report) {
        val a = AdvertFactory.fromHci(r)
        com.rfsentinel.app.esp.HeardBy.usbBt.mark(a.mac, a.timestamp)
        com.rfsentinel.app.usb.AntennaCheck.heardBt(a.mac, a.name, a.rssi, a.timestamp)
        if (sharing()) linkOutbox?.add(LinkCodec.Sighting(LinkCodec.KIND_PHONE, a))
        process(a)
    }

    private fun processEsp(e: com.rfsentinel.app.esp.EspSighting) {
        val now = System.currentTimeMillis()
        com.rfsentinel.app.esp.HeardBy.esp.mark(e.mac, now)
        noteProbes(e.mac, e.probedSsids, e.rssi, now)
        if (e.hits.isNotEmpty()) {
            espHits[e.mac] = now to e.hits
            classified.remove(e.mac) // re-classify with the new evidence
        }
        val advert = if (e.ble) Advert(
            mac = e.mac, source = Advert.Source.BLE, rssi = e.rssi, name = e.name,
            manufacturerData = e.companyId?.let { mapOf(it to ByteArray(0)) }.orEmpty(),
            serviceUuids = listOfNotNull(e.serviceUuid16?.let { Advert.uuid16(it) }),
            timestamp = now
        ) else Advert(
            mac = e.mac, source = Advert.Source.WIFI, rssi = e.rssi, name = e.name,
            wifi = Advert.WifiInfo(e.frequencyMhz, e.capabilities, null, emptyList(), client = e.client), timestamp = now
        )
        if (sharing()) linkOutbox?.add(LinkCodec.Sighting(LinkCodec.KIND_ESP, advert, hits = e.hits, probed = e.probedSsids))
        process(advert, e.remoteId, e.position?.let { DeviceRegistry.GeoSample(now, it.first, it.second) })
    }

    // ---- Sentinel Link -------------------------------------------------------------

    /** What this phone heard, waiting to go to the linked Sentinels (null while Sentinel Link is off). */
    @Volatile private var linkOutbox: LinkOutbox? = null
    private var linkPlanesAt = 0L

    /** This phone's own aircraft to share: police and government first, then the nearest. */
    private fun planesToShare(now: Long, lat: Double, lon: Double): List<com.rfsentinel.app.online.PoliceAircraft.Plane> {
        if (!sharing()) return emptyList()
        val air = com.rfsentinel.app.online.PoliceAircraft
        return air.own(now).sortedWith(compareBy<com.rfsentinel.app.online.PoliceAircraft.Seen>({ it.hit == null }, { if (lat.isNaN()) 0.0 else air.distance(it.plane, lat, lon) }))
            .map { it.plane }
    }

    /** Which external hardware runs here, for the group ([com.rfsentinel.app.link.LinkHardware]); null while this phone shares nothing. */
    private fun hardwareHere(): List<LinkHardware.Item>? {
        if (!sharing()) return null
        return LinkHardware.items(listOf(
            LinkHardware.Kind.WIFI to com.rfsentinel.app.usb.UsbWifi.status, LinkHardware.Kind.BT to com.rfsentinel.app.usb.UsbBluetooth.status,
            LinkHardware.Kind.SDR to com.rfsentinel.app.sdr.SdrRadio.status, LinkHardware.Kind.ESP to com.rfsentinel.app.esp.EspBoards.status,
            LinkHardware.Kind.OUISPY to com.rfsentinel.app.esp.OuiSpyBle.status, LinkHardware.Kind.MESH to com.rfsentinel.app.esp.MeshBle.status))
    }

    /** The hardware summary as a sealed packet (the same one on every way of linking). */
    private fun hardwarePacket(hw: List<LinkHardware.Item>, now: Long): ByteArray? {
        val link = com.rfsentinel.app.link.LinkStore
        val key = link.groupKey(this) ?: return null
        val codec = com.rfsentinel.app.link.MeshCodec
        return codec.sealHardware(codec.messageKey(key), link.myId(this), link.nextMeshCounter(this), now, hw)
    }

    private var linkHwBeat = LinkHardware.Beat(HW_NET_GAP_MS, LinkHardware.BT_HEARTBEAT_MS)
    private var mqttHwBeat = LinkHardware.Beat(HW_NET_GAP_MS, LinkHardware.NET_HEARTBEAT_MS)
    private var meshHwBeat = LinkHardware.Beat(LinkHardware.MESH_GAP_MS, LinkHardware.MESH_HEARTBEAT_MS)

    private val linkFlush = object : Runnable {
        override fun run() {
            val box = linkOutbox ?: return
            val loc = lastLocation?.takeIf { usableForPlacing(it) }
            val now = System.currentTimeMillis()
            hardwareHere()?.takeIf { linkHwBeat.due(it, now) }?.let { hw ->
                hardwarePacket(hw, now)?.let { com.rfsentinel.app.link.PhoneLink.sendHardware(it); linkHwBeat.sent(hw, now) }
            }
            if (now - linkPlanesAt >= LINK_PLANES_MS && sharing()) {
                linkPlanesAt = now
                box.setPlanes(com.rfsentinel.app.online.PoliceAircraft.own(now).map { it.plane })
            }
            box.drain(System.currentTimeMillis(), loc?.latitude ?: Double.NaN, loc?.longitude ?: Double.NaN)
                ?.let { com.rfsentinel.app.link.PhoneLink.send(it) }
            pipeline.postDelayed(this, LINK_FLUSH_MS)
        }
    }

    private val onBatch: (LinkCodec.Batch, String, com.rfsentinel.app.link.PhoneLink.Path) -> Unit =
        { b, name, path -> pipeline.post { onPeerBatch(b, name, path) } }

    /**
     * Sentinel Link runs with the scan when this phone is in a group: over Bluetooth while sharing is on, over the
     * Meshtastic node and the internet relay while each is on - each one without the others.
     */
    private fun updateLink() {
        val link = com.rfsentinel.app.link.LinkStore
        if (link.hasGroup(this) && link.enabled(this)) {
            if (linkOutbox == null) {
                linkOutbox = LinkOutbox(link.myId(this))
                linkHwBeat = LinkHardware.Beat(HW_NET_GAP_MS, LinkHardware.BT_HEARTBEAT_MS)
                pipeline.postDelayed(linkFlush, LINK_FLUSH_MS)
            }
            com.rfsentinel.app.link.PhoneLink.start(this, onBatch)
        } else stopBluetoothLink()
        updateMesh()
        updateMqtt()
        if (linkOutbox == null && meshOutbox == null && mqttOutbox == null) com.rfsentinel.app.link.PhoneLink.unlisten()
        else com.rfsentinel.app.link.PhoneLink.listen(this, onBatch)
    }

    /** What goes out through the MQTT broker (null while it's off). */
    @Volatile private var mqttOutbox: com.rfsentinel.app.link.MeshOutbox? = null
    private var mqttLastSend = 0L
    private var mqttLastName = 0L

    /** The internet relay runs while it's turned on and a broker is set (Bluetooth sharing on or off). */
    private fun updateMqtt() {
        val link = com.rfsentinel.app.link.LinkStore
        val relay = com.rfsentinel.app.link.MqttRelay
        val broker = relay.parse(link.mqttBroker(this))
        val key = link.groupKey(this)
        if (!link.mqttOn(this) || broker == null || key == null) { stopMqtt(); return }
        if (mqttOutbox == null) {
            mqttOutbox = com.rfsentinel.app.link.MeshOutbox(com.rfsentinel.app.link.MeshOutbox.NET_REPEAT_MS)
            mqttHwBeat = LinkHardware.Beat(HW_NET_GAP_MS, LinkHardware.NET_HEARTBEAT_MS)
            mqttLastSend = 0L
            mqttLastName = 0L
            pipeline.postDelayed(mqttFlush, MQTT_TICK_MS)
        }
        relay.start(broker, link.mqttUser(this), link.mqttPassword(this), link.mqttCheckCert(this), key) { com.rfsentinel.app.link.PhoneLink.meshIn(it, viaNet = true) }
    }

    private fun stopMqtt() {
        if (mqttOutbox == null) return
        mqttOutbox = null
        pipeline.removeCallbacks(mqttFlush)
        com.rfsentinel.app.link.MqttRelay.stop()
    }

    /**
     * Every few seconds, the devices due (the mesh's turns: watchlist matches first, repeats spaced out), as many
     * packets as they fill; a packet with none every minute so the others know this Sentinel is there and where.
     */
    private val mqttFlush = object : Runnable {
        override fun run() {
            val box = mqttOutbox ?: return
            pipeline.postDelayed(this, MQTT_TICK_MS)
            val relay = com.rfsentinel.app.link.MqttRelay
            val now = System.currentTimeMillis()
            relay.keepAlive(now)
            if (!relay.ready) return
            val ctx = this@ScanForegroundService
            val link = com.rfsentinel.app.link.LinkStore
            val key = link.groupKey(ctx) ?: return
            val loc = lastLocation?.takeIf { usableForPlacing(it) }
            val lat = loc?.latitude ?: Double.NaN
            val lon = loc?.longitude ?: Double.NaN
            val codec = com.rfsentinel.app.link.MeshCodec
            hardwareHere()?.takeIf { mqttHwBeat.due(it, now) }?.let { hw ->
                if (relay.send(codec.sealHardware(codec.messageKey(key), link.myId(ctx), link.nextMeshCounter(ctx), now, hw))) mqttHwBeat.sent(hw, now)
            }
            if (now - mqttPlanesAt >= MQTT_PLANES_MS) {
                mqttPlanesAt = now
                var planes = planesToShare(now, lat, lon)
                for (i in 0 until MQTT_PLANE_PACKETS) {
                    val part = codec.fit(planes, codec.MAX_BODY - codec.baseSize(!lat.isNaN(), null))
                    if (part.isEmpty()) break
                    if (!relay.send(codec.sealAircraft(codec.messageKey(key), link.myId(ctx), link.nextMeshCounter(ctx), now, lat, lon, null, part))) break
                    planes = planes.drop(part.size)
                }
            }
            var presence = now - mqttLastSend >= MQTT_PRESENCE_MS
            repeat(MQTT_MAX_PACKETS) {
                val name = if (now - mqttLastName >= MESH_NAME_MS) link.myName(ctx) else null
                val devices = box.take(now, codec.MAX_BODY - codec.baseSize(!lat.isNaN(), name))
                if (devices.isEmpty() && !presence) return
                val bytes = codec.seal(codec.messageKey(key), link.myId(ctx), link.nextMeshCounter(ctx), now, lat, lon, name, devices)
                if (!relay.send(bytes)) return
                mqttLastSend = now
                presence = false
                if (name != null) mqttLastName = now
            }
        }
    }

    /** What goes out over the Meshtastic node (null while it's off). */
    @Volatile private var meshOutbox: com.rfsentinel.app.link.MeshOutbox? = null
    private var meshLastSend = 0L
    private var meshPlanesAt = 0L
    private var mqttPlanesAt = 0L
    private var meshLastName = 0L

    /** The Meshtastic node runs while it's turned on and chosen (Bluetooth sharing on or off). */
    private fun updateMesh() {
        val link = com.rfsentinel.app.link.LinkStore
        val node = link.meshNode(this)
        val key = link.groupKey(this)
        if (!link.meshOn(this) || node == null || key == null) { stopMesh(); return }
        if (meshOutbox == null) {
            meshOutbox = com.rfsentinel.app.link.MeshOutbox()
            meshHwBeat = LinkHardware.Beat(LinkHardware.MESH_GAP_MS, LinkHardware.MESH_HEARTBEAT_MS)
            meshLastSend = 0L
            meshLastName = 0L
            pipeline.postDelayed(meshFlush, MESH_TICK_MS)
        }
        val meshLog = com.rfsentinel.app.esp.MeshtasticLog()
        com.rfsentinel.app.link.MeshNode.heard = { m -> meshLog.feed(m)?.let { s -> pipeline.post { processEsp(s) } } }
        com.rfsentinel.app.link.MeshNode.start(this, node, key) { com.rfsentinel.app.link.PhoneLink.meshIn(it) }
    }

    private fun stopMesh() {
        if (meshOutbox == null) return
        meshOutbox = null
        pipeline.removeCallbacks(meshFlush)
        com.rfsentinel.app.link.MeshNode.stop()
        com.rfsentinel.app.link.MeshNode.heard = null
    }

    /**
     * One packet now and then: at the chosen interval, sooner (15 s apart at least) for a new watchlist match,
     * and every 10 minutes even with nothing new so the others know this Sentinel is there and where.
     */
    private val meshFlush = object : Runnable {
        override fun run() {
            val box = meshOutbox ?: return
            pipeline.postDelayed(this, MESH_TICK_MS)
            val node = com.rfsentinel.app.link.MeshNode
            if (!node.canSend) return
            val ctx = this@ScanForegroundService
            val link = com.rfsentinel.app.link.LinkStore
            val now = System.currentTimeMillis()
            val since = now - meshLastSend
            val urgent = box.urgent(now)
            val presence = since >= MESH_PRESENCE_MS
            val interval = link.meshIntervalSec(ctx).let { s ->
                if (s != link.MESH_INTERVAL_AUTO) s * 1000L
                else com.rfsentinel.app.esp.LoRaStats.let { st ->
                    val own = st.own?.takeIf { now - st.ownAt < OWN_STATS_MS }
                    com.rfsentinel.app.link.MeshPace.intervalMs(own?.channelUtil, own?.airUtilTx,
                        com.rfsentinel.app.link.MeshNode.queueFree, box.waiting(now))
                }
            }
            val loc = lastLocation?.takeIf { usableForPlacing(it) }
            val lat = loc?.latitude ?: Double.NaN
            val lon = loc?.longitude ?: Double.NaN
            val codec = com.rfsentinel.app.link.MeshCodec
            // Hardware: a few bytes when it changes (a minute apart at most) and every 15 minutes; devices follow a tick later.
            if (!urgent && since >= MESH_MIN_GAP_MS) hardwareHere()?.takeIf { meshHwBeat.due(it, now) }?.let { hw ->
                val packet = hardwarePacket(hw, now) ?: return
                if (node.send(packet, false)) { meshHwBeat.sent(hw, now); return }
            }
            // Aircraft: one packet of their own at the same pace, police first; the devices' packet follows a tick later.
            if (!urgent && now - meshPlanesAt >= interval && since >= MESH_TICK_MS) {
                meshPlanesAt = now
                val planes = codec.fit(planesToShare(now, lat, lon), codec.MAX_BODY - codec.baseSize(!lat.isNaN(), null))
                val key = link.groupKey(ctx) ?: return
                if (planes.isNotEmpty() &&
                    node.send(codec.sealAircraft(codec.messageKey(key), link.myId(ctx), link.nextMeshCounter(ctx), now, lat, lon, null, planes), false)) return
            }
            if (since < interval && !(urgent && since >= MESH_MIN_GAP_MS) && !presence) return
            val key = link.groupKey(ctx) ?: return
            val name = if (now - meshLastName >= MESH_NAME_MS) link.myName(ctx) else null
            val devices = box.take(now, codec.MAX_BODY - codec.baseSize(!lat.isNaN(), name))
            if (devices.isEmpty() && !presence) return
            val bytes = codec.seal(codec.messageKey(key), link.myId(ctx), link.nextMeshCounter(ctx), now, lat, lon, name, devices)
            if (node.send(bytes, urgent)) {
                meshLastSend = now
                if (name != null) meshLastName = now
            }
        }
    }

    private fun stopLink() {
        stopMesh()
        stopMqtt()
        stopBluetoothLink()
        com.rfsentinel.app.link.PhoneLink.unlisten()
        com.rfsentinel.app.esp.HeardBy.peer.clear()
        com.rfsentinel.app.esp.HeardBy.peerMesh.clear()
        com.rfsentinel.app.online.PoliceAircraft.shared.clear()
    }

    private fun stopBluetoothLink() {
        if (linkOutbox == null) return
        linkOutbox = null
        pipeline.removeCallbacks(linkFlush)
        com.rfsentinel.app.link.PhoneLink.stop()
        com.rfsentinel.app.esp.HeardBy.peerBluetooth.clear()
    }

    /**
     * What a linked Sentinel heard: its hardware's evidence is kept like this phone's own, and a device this
     * phone isn't hearing itself goes through the same pipeline (list, radar, map, alerts) placed where that
     * Sentinel was.
     */
    private fun onPeerBatch(b: LinkCodec.Batch, name: String, path: com.rfsentinel.app.link.PhoneLink.Path) {
        if (linkOutbox == null && meshOutbox == null && mqttOutbox == null) return
        val now = System.currentTimeMillis()
        val via = when (path) {
            com.rfsentinel.app.link.PhoneLink.Path.BLUETOOTH -> name
            com.rfsentinel.app.link.PhoneLink.Path.MESH -> "$name · mesh"
            com.rfsentinel.app.link.PhoneLink.Path.NET -> "$name · internet"
        }
        val byMesh = path != com.rfsentinel.app.link.PhoneLink.Path.BLUETOOTH
        val quiet = !com.rfsentinel.app.link.LinkStore.alerts(this, path)
        // Phones' clocks differ: ages are kept, times are this phone's.
        val shift = now - b.time
        val at = if (b.lat.isNaN() || b.lon.isNaN()) null else Location("sentinel-link").apply {
            latitude = b.lat; longitude = b.lon; time = now
        }
        val heardBy = com.rfsentinel.app.esp.HeardBy
        for (s in b.sightings) {
            val mac = s.advert.mac
            val a = LinkCodec.rebased(s.advert, (s.advert.timestamp + shift).coerceAtMost(now))
            heardBy.peer.mark(mac, via, now, if (byMesh) heardBy.MESH_HOLD_MS else heardBy.WINDOW_MS)
            (if (byMesh) heardBy.peerMesh else heardBy.peerBluetooth).mark(mac, now)
            if (s.hits.isNotEmpty()) {
                espHits[mac] = now to s.hits
                classified.remove(mac)
            }
            if (s.probed.isNotEmpty() || s.fingerprint != null) {
                noteProbes(mac, s.probed, a.rssi, now, fingerprint = s.fingerprint, hidden = s.hidden, skeleton = a.wifi?.probeSkeleton)
            }
            // Heard here too: this phone's own reading (signal, place) is the one shown.
            if (heardBy.phone.within(mac, HEARD_HERE_MS, now) || heardBy.esp.within(mac, HEARD_HERE_MS, now) ||
                heardBy.usb.within(mac, HEARD_HERE_MS, now) || heardBy.usbBt.within(mac, HEARD_HERE_MS, now)) continue
            // Heard by a Sentinel linked here over Bluetooth: this phone's node passes it on to the mesh.
            process(a, via = via, peerAt = at, toMesh = b.hops == 0, quiet = quiet)
        }
        if (b.planes.isNotEmpty()) onlineWatch.sharedAircraft(b.planes, via, quiet)
        for (r in b.radios) {
            val kind = runCatching { com.rfsentinel.app.sdr.RadioWatch.Kind.valueOf(r.kind) }
                .getOrDefault(com.rfsentinel.app.sdr.RadioWatch.Kind.SHARED)
            onRadio(com.rfsentinel.app.sdr.RadioWatch.Event(r.freqHz, r.snrDb,
                com.rfsentinel.app.sdr.RadioWatch.Band(r.bandLabel, r.bandStartHz, r.bandEndHz, kind), r.confidence,
                r.targetLabel?.let { com.rfsentinel.app.sdr.RadioWatch.Target(r.freqHz, it) }, r.repeat, r.tdma), via, quiet)
        }
    }

    /** The watchlist version the cached classifications were made with. */
    private var watchlistVersion = OuiWatchlist.version

    /**
     * The watchlist changed (entry added or removed, preset switched): re-check every device
     * now instead of waiting for cached results and held matches to expire. A device that is
     * no longer flagged stops its radar beeps and can alert again if it's re-added.
     */
    private fun recheckWatchlist(now: Long) {
        watchlistVersion = OuiWatchlist.version
        classified.clear()
        for (a in DeviceRegistry.lastAdverts()) {
            val c = classify(a, now)
            DeviceRegistry.replaceHits(a.mac, c.hits)
            if (c.hits.isEmpty()) lastAlerted.remove(a.mac)
        }
    }

    private fun classify(a: Advert, now: Long, extra: List<Hit> = emptyList()): Classified {
        val macVendor = if (a.randomBle) null else VendorDb.macVendor(a.mac)
        val identity = DeviceIntel.identify(a, macVendor)
        val companyVendors = a.manufacturerData.keys.mapNotNull { VendorDb.company(it) }
        val vendor = macVendor ?: companyVendors.firstOrNull()
        // Per-packet rules, the watchlist, matches carried over from a rotated address and the
        // patrol-vehicle cluster, then fused into one calibrated score.
        // A network whose name has one of your ignored words (Settings › Ignored network names): listed, never flagged.
        val ignored = a.source == Advert.Source.WIFI && a.wifi?.client != true &&
            a.name?.let { SignatureEngine.ignoredSsid(it, Prefs.ignoredSsidAll(this)) } == true
        val raw = if (ignored) emptyList() else SignatureEngine.classify(a) +
            OuiWatchlist.hits(a.mac, a.name, listOfNotNull(macVendor) + companyVendors) +
            OuiWatchlist.advertHits(a) +
            OuiWatchlist.comboHits(com.rfsentinel.app.oui.ComboRule.Facts(a.name, listOfNotNull(macVendor) + companyVendors,
                com.rfsentinel.app.oui.ComboRule.band(a.isBle, a.wifi?.frequencyMhz),
                com.rfsentinel.app.usb.ProbeIntel.of(a.mac)?.let { p -> setOfNotNull(p.fingerprint, p.skeleton?.let { com.rfsentinel.app.usb.ProbeIntel.shapeKey(it) }) }.orEmpty(),
                identity.type, a.rssi)) +
            DeviceRegistry.inheritedHits(a.mac) +
            listOfNotNull(DeviceRegistry.clusterHit(a.mac, now), com.rfsentinel.app.sdr.P25Link.hitFor(a.mac)) +
            espHits[a.mac]?.takeIf { now - it.first < ESP_HIT_TTL_MS }?.second.orEmpty() +
            probeHits[a.mac]?.takeIf { now - it.first < ESP_HIT_TTL_MS }?.second.orEmpty() +
            extra
        val noAirTags = Prefs.excludeAirTags(this)
        val kept = DeviceIntel.dropTrainRouter(com.rfsentinel.app.detect.FlockNoise.filter(raw, a.name, OuiWatchlist.flockRegion, Prefs.flockCameras(this)), a)
        // Non-stop probing backs up a fixed-camera match that survived the noise filters (never alone).
        val sifted = kept + listOfNotNull(com.rfsentinel.app.detect.ProbeBehaviour.supportHit(a, kept))
        val (on, off) = sifted.filter { !(noAirTags && SignatureEngine.isAppleFindMy(it)) }
            .partition { Prefs.categoryEnabled(this, it.category) }
        val hits = EvidenceFusion.fuse(on)
        // A kind turned off in the detection list still names the device (an Arlo base station with home
        // cameras off): listed like any other device, no colour, no alert, not logged.
        val quiet = if (hits.isEmpty()) EvidenceFusion.fuse(off).firstOrNull()?.takeIf { !it.listOnly } else null
        val named = quiet?.let { DeviceIntel.quietlyIdentified(identity, it) } ?: identity
        return Classified(now, hits, named, vendor)
    }

    private fun maybeLog(a: Advert, best: Hit, vendor: String?, loc: Location?, now: Long, via: String? = null) {
        if (via != null && !Prefs.saveLinked(this)) return
        val prevLogged = lastLogged[a.mac] ?: 0L
        if (now - prevLogged < LOG_THROTTLE_MS) return
        lastLogged[a.mac] = now
        logDetection(a.mac, a.source.name, best, now, loc?.latitude, loc?.longitude,
            rssi = a.rssi, vendor = vendor, deviceName = a.name,
            radio = com.rfsentinel.app.detect.DeviceIntel.radioSummary(a),
            heardBy = via?.let { "via $it" } ?: com.rfsentinel.app.esp.HeardBy.own(a.mac, a.source, a.name))
    }

    private fun logDetection(
        mac: String, source: String, hit: Hit, now: Long, latitude: Double?, longitude: Double?,
        rssi: Int = 0, vendor: String? = null, deviceName: String? = null, radio: String? = null, heardBy: String? = null
    ) {
        val tag = Prefs.gpsTaggingEnabled(this)
        val row = DetectionEntity(
            mac = mac, label = hit.label, source = source, rssi = rssi, timestamp = now,
            latitude = if (tag) latitude else null, longitude = if (tag) longitude else null,
            category = hit.category.name, confidence = hit.confidence, evidence = hit.evidence,
            vendor = vendor, deviceName = deviceName, radio = radio, heardBy = heardBy
        )
        serviceScope.launch {
            AppDatabase.getInstance(this@ScanForegroundService).detectionDao().insert(row)
        }
    }

    /**
     * The ways not to alert you about this: what its category has turned off (Settings › What to detect)
     * plus what your watchlist entry for the device has.
     */
    private fun alertsOff(category: Category, mac: String? = null, hits: List<Hit> = emptyList()): Set<AlertKind> =
        Prefs.categoryAlertsOff(this, category) + (if (mac != null) OuiWatchlist.alertsOff(mac, hits) else emptySet())

    private fun maybeAlert(a: Advert, best: Hit, hits: List<Hit>, now: Long, via: String? = null) {
        // Someone else's tracker nearby is normal; trackers only alert when they follow you.
        if (best.category == Category.TRACKER) return
        if (best.listOnly || best.confidence < Prefs.alertThreshold(this, best.category)) return
        // A shop's router you pass every day: listed and logged, no alert.
        if (com.rfsentinel.app.detect.FixedRouter.quiet(a.mac, hits, Prefs.alertThreshold(this, best.category))) return
        // Acknowledged from the list: listed and logged, no alert.
        if (com.rfsentinel.app.data.AlertMutes.isMuted(this, a.mac, now)) return
        val window = Prefs.dedupeWindowMs(this)
        val last = lastAlerted[a.mac] ?: 0L
        if (now - last <= window) return
        lastAlerted[a.mac] = now
        // The ways your watchlist entry for it says not to alert.
        val off = alertsOff(best.category, a.mac, hits)
        NotificationHelper.sendAlert(this, a.mac, best, a.rssi, via = via, banner = AlertKind.BANNER !in off)
        AlertPlayer.play(this, best.tier, com.rfsentinel.app.util.Spoken.device(this, best, via = via), off = off)
    }

    private fun knownAlprActive() = (Prefs.knownAlprAlerts(this) || Prefs.speedCameraAlerts(this)) &&
        Prefs.mappedCameras(this) && AlprStore.cameras.isNotEmpty()

    private fun wantsAlert(kind: com.rfsentinel.app.alpr.KnownCamera.Kind) =
        if (kind == com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR) Prefs.knownAlprAlerts(this) else Prefs.speedCameraAlerts(this)

    /**
     * Warns once (per camera, per 30 minutes) when you're approaching a plate
     * reader (~20 s of travel) or a speed / red-light camera (~30 s) mapped in
     * OpenStreetMap, and getting closer.
     * Runs on the main thread with each location fix; the lookup is a cheap
     * bounding-box filter.
     */
    private fun checkKnownAlpr(loc: Location) {
        if (!knownAlprActive()) return
        val now = System.currentTimeMillis()
        val speed = if (loc.hasSpeed()) loc.speed else null
        // Search out to the larger (speed camera) radius, then apply each kind's own.
        val radius = KnownCameras.warnRadius(speed, com.rfsentinel.app.alpr.KnownCamera.Kind.SPEED)
        // Moving (~7 km/h or more): a camera facing across your way watches a cross street, not you.
        val heading = if (loc.hasBearing() && (speed ?: 0f) >= 2f) loc.bearing.toDouble() else null
        val near = KnownCameras.near(AlprStore.cameras, loc.latitude, loc.longitude, radius)
            .filter { (cam, d) ->
                wantsAlert(cam.type) && d <= KnownCameras.warnRadius(speed, cam.type) && KnownCameras.watchesYourWay(cam, heading, com.rfsentinel.app.alpr.CameraDirections.of(this, cam)) &&
                    !com.rfsentinel.app.alpr.IgnoredCameras.contains(this, cam.osmId) // silenced from the map
            }
        val nearIds = near.map { it.first.osmId }.toSet()
        alprLastDistance.keys.retainAll(nearIds)
        alprLastAlert.entries.removeAll { now - it.value >= KNOWN_ALPR_REPEAT_MS } // a long drive passes many cameras
        for ((cam, d) in near) {
            val prev = alprLastDistance.put(cam.osmId, d)
            // Only once a second fix shows you getting closer: a camera you just passed
            // (or one near where the scan started) mustn't warn.
            val approaching = prev != null && d < prev - 3
            if (!approaching || now - (alprLastAlert[cam.osmId] ?: 0L) < KNOWN_ALPR_REPEAT_MS) continue
            alprLastAlert[cam.osmId] = now
            val alpr = cam.type == com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR
            val hit = Hit(
                Category.ALPR, if (alpr) "Known plate camera ahead" else "${cam.label} ahead", 80,
                "${cam.label}, ~${com.rfsentinel.app.util.Units.distance(d)} away" + (cam.operator?.let { ", operated by $it" } ?: "") +
                    ". Mapped in OpenStreetMap" + (if (alpr) " - it may not broadcast any signal." else "."),
                "OpenStreetMap (" + (if (alpr) "surveillance:type=ALPR" else "highway=speed_camera / enforcement") + "), ODbL"
            )
            val off = alertsOff(hit.category)
            NotificationHelper.sendMapAlert(this, "alpr:" + cam.osmId, hit, lat = cam.lat, lon = cam.lon, banner = AlertKind.BANNER !in off)
            AlertPlayer.play(this, hit.tier, com.rfsentinel.app.util.Spoken.camera(this, cam), off = off)
            break // one warning per fix is enough
        }
    }

    /** Remote ID puts the drone within ~200 m of you: alert once per drone per 5 minutes. */
    private fun maybeAlertDroneOverhead(a: Advert, info: RemoteId.Info, loc: Location, now: Long) {
        if (!Prefs.categoryEnabled(this, Category.DRONE) || WhitelistCache.contains(a.mac)) return
        if (com.rfsentinel.app.data.AlertMutes.isMuted(this, a.mac, now)) return
        val hit = DroneProximity.overheadHit(info, loc.latitude, loc.longitude) ?: return
        val last = lastDroneOverhead[a.mac] ?: 0L
        if (now - last < DRONE_OVERHEAD_REPEAT_MS) return
        lastDroneOverhead[a.mac] = now
        val off = alertsOff(hit.category, a.mac)
        NotificationHelper.sendAlert(this, a.mac, hit, a.rssi, banner = AlertKind.BANNER !in off)
        AlertPlayer.play(this, hit.tier, "Drone overhead", off = off)
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

    /** Sightings to merge into known_devices, and the pages to save ([LastHeard]), taken synchronously from memory. */
    private class HistoryBatch(val seen: List<DeviceRegistry.Snapshot>, val sessionStart: Long, val details: List<DeviceDetailsEntity>)

    /** When each device's page was last saved, and the matches it had then. */
    private val detailsSaved = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    /** [final]: the scan is stopping, so every page with something newer is saved. */
    private fun takeHistory(final: Boolean = false): HistoryBatch? {
        val since = lastHistoryFlush
        val now = System.currentTimeMillis()
        lastHistoryFlush = now
        // Forget long-unsaved devices (rotating addresses): one heard again is just saved again.
        detailsSaved.values.removeIf { now - it.first > 2 * DETAILS_SAVE_MS }
        // Settings › Sentinel Link: devices only a linked Sentinel heard are saved only when that's on.
        val saveLinked = Prefs.saveLinked(this)
        val seen = DeviceRegistry.summaries(since).filter {
            saveLinked || !com.rfsentinel.app.esp.HeardBy.peer.recent(it.mac) || com.rfsentinel.app.esp.HeardBy.ownRecent(it.mac)
        }
        if (seen.isEmpty()) return null
        // A page is saved when first heard, when its matches change, and otherwise every few minutes.
        val details = seen.mapNotNull { sum ->
            val (savedAt, hits) = detailsSaved[sum.mac] ?: (0L to 0)
            val due = savedAt == 0L || hits != sum.hits.hashCode() ||
                (if (final) sum.lastSeen > savedAt else now - savedAt >= DETAILS_SAVE_MS)
            if (!due) return@mapNotNull null
            val s = DeviceRegistry.get(sum.mac) ?: return@mapNotNull null
            runCatching { DeviceDetailsEntity(s.mac, now, LastHeard.toJson(LastHeard.of(this, s))) }
                .onFailure { Log.w(TAG, "Device page save failed", it) }.getOrNull()
                ?.also { detailsSaved[s.mac] = now to s.hits.hashCode() }
        }
        return HistoryBatch(seen, DeviceRegistry.sessionStart, details)
    }

    /** Merges this session's sightings into known_devices (throttled, batched). */
    private suspend fun flushHistory() {
        val since = lastHistoryFlush
        val batch = takeHistory() ?: return
        try {
            writeHistory(batch)
        } catch (e: Exception) {
            // Taking the batch moved the clock on: without this, what failed would never be written.
            lastHistoryFlush = since
            batch.details.forEach { detailsSaved.remove(it.mac) }
            throw e
        }
    }

    private suspend fun writeHistory(batch: HistoryBatch) {
        val dao = AppDatabase.getInstance(this).knownDeviceDao()
        val sessionStart = batch.sessionStart
        batch.seen.chunked(400).forEach { chunk ->
            dao.recordSightings(chunk.map { s ->
                KnownDeviceEntity(
                    mac = s.mac,
                    firstSeen = s.firstSeen,
                    lastSeen = s.lastSeen,
                    sessions = 1,
                    detectCount = 1,
                    name = s.name,
                    vendor = s.vendor,
                    deviceType = s.deviceType
                )
            }, sessionStart)
        }
        batch.details.chunked(200).forEach { AppDatabase.getInstance(this).deviceDetailsDao().upsertAll(it) }
    }

    private suspend fun pruneOldData() {
        val db = AppDatabase.getInstance(this)
        db.deviceDetailsDao().keepNewest(DETAILS_KEEP)
        val days = Prefs.retentionDays(this)
        if (days <= 0) return
        val before = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        db.detectionDao().prune(before)
        db.knownDeviceDao().prune(before)
        db.deviceDetailsDao().pruneOrphans()
    }

    private fun startHousekeeping() {
        housekeepingJob?.cancel()
        housekeepingJob = serviceScope.launch {
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                holdAwake()
                val devices = DeviceRegistry.snapshot()
                val flagged = devices.count { d -> com.rfsentinel.app.ui.LiveWindow.alerting(d) }
                val text = "${devices.size} device${if (devices.size == 1) "" else "s"} nearby" +
                    (if (flagged > 0) " · $flagged flagged" else "") +
                    (com.rfsentinel.app.sdr.SdrRadio.p25Hold?.let { " · holding P25 talkgroup $it" } ?: "")
                NotificationHelper.updateServiceNotification(this@ScanForegroundService, text)
                StatusWidget.updateAll(this@ScanForegroundService, devices.size, flagged)
                runCatching { TripRecorder.flush(this@ScanForegroundService) }
                    .onFailure { Log.w(TAG, "Trace flush failed", it) }
                // Settings > Data > Live export: files another mapping app can read (every 30 s).
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.rfsentinel.app.util.LiveExport.maybeWrite(this@ScanForegroundService, DeviceRegistry.sessionStart, lastLocation ?: lastFix)
                    }
                }.onFailure { Log.w(TAG, "Live export failed", it) }
                if (System.currentTimeMillis() - lastHistoryFlush >= HISTORY_FLUSH_MS) {
                    runCatching { flushHistory() }.onFailure { Log.w(TAG, "History flush failed", it) }
                }
                // Forget classification cache for devices that left.
                classified.keys.retainAll(devices.map { it.mac }.toSet())
                pruneTimestamps(System.currentTimeMillis())
                watchdog()
            }
        }
    }

    /**
     * Drops per-address timestamps old enough to make no difference any more (an entry past its window acts
     * the same as none), so a long scan past thousands of rotating addresses doesn't keep every one of them.
     */
    private fun pruneTimestamps(now: Long) {
        val alertKeep = maxOf(Prefs.dedupeWindowMs(this), RADAR_HOLD_MS)
        lastAlerted.entries.removeAll { now - it.value > alertKeep }
        lastLogged.entries.removeAll { now - it.value >= LOG_THROTTLE_MS }
        lastDroneOverhead.entries.removeAll { now - it.value >= DRONE_OVERHEAD_REPEAT_MS }
        // These two belong to the pipeline thread.
        pipeline.post {
            espHits.entries.removeAll { now - it.value.first >= ESP_HIT_TTL_MS }
            probeHits.entries.removeAll { now - it.value.first >= ESP_HIT_TTL_MS }
        }
    }

    // ---- Watchdog ----------------------------------------------------------------

    @Volatile private var lastBleResultAt = 0L
    @Volatile private var lastWifiResultAt = 0L
    @Volatile private var bleStartedAt = 0L
    @Volatile private var wifiStartedAt = 0L
    /** Set when the watchdog restarted a scanner; cleared by its next result. */
    @Volatile private var bleWatchdogRestartAt: Long? = null
    @Volatile private var wifiWatchdogRestartAt: Long? = null
    /** Silence allowed before a restart; doubles after each restart that brought nothing back. */
    @Volatile private var bleSilenceMs = BLE_SILENCE_MS
    @Volatile private var wifiSilenceMs = WIFI_SILENCE_MS
    /** Bluetooth results since the last watchdog tick, for [bleRate]. */
    private val bleResults = java.util.concurrent.atomic.AtomicInteger()
    private val bleRate = BleRateWatch()
    private var rateTickAt = 0L
    private var wasInteractive = false
    /** When the screen last came on, and whether the unfiltered scan was checked since. */
    private var screenOnAt = 0L
    private var screenOnChecked = true

    /** Results came back after a restart: the scanner really was stuck, so say so. */
    private fun onRecovered(which: String, restartAt: Long, now: Long) {
        if (now - restartAt <= 90_000L) {
            Log.i(TAG, "Watchdog: $which scan recovered after restart")
            lastWatchdogRestart = restartAt to which
        }
    }

    /**
     * A scanner that goes silent is restarted. Android (or a phone maker's battery
     * manager) can stop delivering results without reporting an error - the scan
     * looks alive but nothing arrives until it's restarted. Nearly every place has
     * some Bluetooth or WiFi around, so minutes of silence mean a stuck scanner.
     * Skipped with the screen off: Android pauses unfiltered BLE scans then by design.
     */
    private fun watchdog() {
        val now = System.currentTimeMillis()
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val adapterOn = runCatching {
            (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter?.isEnabled == true
        }.getOrDefault(false)
        if (interactive && adapterOn && bleWanted() &&
            now - maxOf(lastBleResultAt, bleStartedAt) > bleSilenceMs
        ) {
            Log.w(TAG, "Watchdog: no Bluetooth results for ${(now - maxOf(lastBleResultAt, bleStartedAt)) / 1000} s - restarting the scan")
            com.rfsentinel.app.util.HwLogs.phone.log("Watchdog: no Bluetooth results for ${(now - maxOf(lastBleResultAt, bleStartedAt)) / 1000} s - restarting the scan")
            // A quiet area isn't a stuck scanner: back off while restarts bring nothing.
            if (bleWatchdogRestartAt != null) bleSilenceMs = (bleSilenceMs * 2).coerceAtMost(MAX_SILENCE_MS)
            bleWatchdogRestartAt = now
            android.os.Handler(Looper.getMainLooper()).post { bleEngine.stop(); startBle() }
        }
        // Throttled rather than silent: results still trickle in, at a tenth of the usual rate.
        val count = bleResults.getAndSet(0)
        val span = now - rateTickAt
        rateTickAt = now
        val bleRunning = adapterOn && bleWanted() && bleEngine.isScanning()
        var restarted = bleWatchdogRestartAt == now
        if (interactive && wasInteractive && bleRunning && !restarted) {
            if (bleRate.tick(count, span, now)) {
                Log.w(TAG, "Watchdog: Bluetooth results down to %.1f/s - restarting the scan".format(count * 1000.0 / span))
                com.rfsentinel.app.util.HwLogs.phone.log("Watchdog: Bluetooth results down to %.1f/s - restarting the scan".format(count * 1000.0 / span))
                android.os.Handler(Looper.getMainLooper()).post { bleEngine.stop(); startBle() }
                restarted = true
            }
        } else if (!bleRunning || restarted) bleRate.reset()
        // With the screen off the baseline learned with it on is kept, so a scan that doesn't come back is still spotted.
        if (interactive && !wasInteractive) { screenOnAt = now; screenOnChecked = false }
        // Screen back on: Android should resume the unfiltered scan by itself, but it can stay paused while the
        // filtered one keeps running - alerts still come, the list stays empty. Nothing unfiltered since: restart once.
        if (interactive && !screenOnChecked && now - screenOnAt >= SCREEN_ON_CHECK_MS) {
            screenOnChecked = true
            if (bleRunning && !restarted && bleEngine.lastUnfilteredAt < screenOnAt && now - bleStartedAt >= SCREEN_ON_CHECK_MS) {
                Log.w(TAG, "Watchdog: no unfiltered Bluetooth results since the screen came on - restarting the scan")
                com.rfsentinel.app.util.HwLogs.phone.log("Watchdog: no unfiltered Bluetooth results since the screen came on - restarting the scan")
                android.os.Handler(Looper.getMainLooper()).post { bleEngine.stop(); startBle() }
            }
        }
        wasInteractive = interactive
        val wifiOn = runCatching { (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).isScanAlwaysAvailable ||
            (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled }.getOrDefault(false)
        if (wifiOn && wifiWanted() &&
            now - maxOf(lastWifiResultAt, wifiStartedAt) > maxOf(wifiSilenceMs, 4 * Prefs.scanIntervalMs(this))
        ) {
            Log.w(TAG, "Watchdog: no WiFi results - restarting the WiFi scan")
            com.rfsentinel.app.util.HwLogs.phone.log("Watchdog: no WiFi results - restarting the WiFi scan")
            if (wifiWatchdogRestartAt != null) wifiSilenceMs = (wifiSilenceMs * 2).coerceAtMost(MAX_SILENCE_MS)
            wifiWatchdogRestartAt = now
            android.os.Handler(Looper.getMainLooper()).post { wifiEngine.stop(); startWifiPolling() }
        }
    }

    /**
     * Fake-cell-tower check every 15 s: reads the cells the phone already sees
     * (passive, no extra permission beyond location). Probable signs alert like a
     * device match; weak ones are only written to the match history.
     */
    private fun startCellChecks() {
        cellJob?.cancel()
        if (!ownOn() || !Prefs.categoryEnabled(this, Category.CELL_ANOMALY) || !hasFineLocation()) return
        val monitor = cellMonitor ?: CellMonitor(this).also { cellMonitor = it }
        cellJob = serviceScope.launch {
            while (isActive) {
                runCatching { monitor.check() }.getOrNull().orEmpty().forEach { onCellAnomaly(it) }
                checkServingCellChange()
                delay(CELL_CHECK_MS)
            }
        }
    }

    /** Serving cell of the previous check, for the optional "cell tower changed" alert. */
    private var lastServing: com.rfsentinel.app.detect.CellAnalyzer.Cell? = null

    /**
     * Optional (Settings): says so each time the phone moves to another serving cell.
     * A switch while you stand still is the classic sign of a fake tower pulling phones in.
     */
    private fun checkServingCellChange() {
        val serving = CellTowerStore.current.firstOrNull { it.registered && it.cellId != null } ?: return
        val prev = lastServing
        lastServing = serving
        if (prev == null || prev.key == serving.key || !Prefs.cellChangeAlerts(this)) return
        val now = System.currentTimeMillis()
        val detail = "Your phone moved from ${prev.rat.label} cell ${prev.cellId} (area ${prev.area ?: "?"}) to " +
            "${serving.rat.label} cell ${serving.cellId} (area ${serving.area ?: "?"})" +
            (serving.operator?.let { " on $it" } ?: "") + (serving.dbm?.let { ", $it dBm" } ?: "") +
            ". Normal while moving; a change while you're parked is worth a look."
        val hit = Hit(Category.CELL_ANOMALY, "Cell tower changed", 30, detail, "Android cell info")
        val off = alertsOff(hit.category)
        NotificationHelper.sendMapAlert(this, "cellchange", hit, com.rfsentinel.app.ui.CellTowersActivity::class.java, banner = AlertKind.BANNER !in off)
        AlertPlayer.play(this, hit.tier, "Cell tower changed", off = off)
        val loc = lastLocation
        logDetection("CELL " + serving.key, "CELL", hit, now, loc?.latitude, loc?.longitude,
            rssi = serving.dbm ?: 0)
    }

    /**
     * A strong two-way radio transmission on a normally quiet public-safety channel, from
     * an RTL-SDR ([com.rfsentinel.app.sdr.RadioWatch]): logged, and an alert when it clears
     * the alert threshold. Only signal strength is known - nothing that was said.
     */
    private fun onRadio(e: com.rfsentinel.app.sdr.RadioWatch.Event, via: String? = null, quiet: Boolean = false) {
        val now = System.currentTimeMillis()
        if (via == null && sharing()) linkOutbox?.addRadio(LinkCodec.Radio(e.freqHz, e.snrDb, e.band.label, e.band.startHz, e.band.endHz,
            e.band.kind.name, e.confidence, e.target?.label, e.repeat, e.tdma, now))
        if (!Prefs.categoryEnabled(this, Category.RADIO)) return
        // Who uses this frequency: a watched frequency's own label, the imported list, or RadioReference.
        val named = com.rfsentinel.app.sdr.FreqNames.lookup(e.freqHz, Prefs.radioMatchKhz(this) * 1000L)
        val name = e.target?.label ?: named?.name
        if (named == null && com.rfsentinel.app.sdr.RadioReference.ready(this)) (lastLocation ?: lastFix)?.let { l ->
            serviceScope.launch { com.rfsentinel.app.sdr.RadioReference.refreshAround(this@ScanForegroundService, l.latitude, l.longitude) }
        }
        val threshold = Prefs.alertThreshold(this, Category.RADIO)
        val trend = com.rfsentinel.app.sdr.RadioLog.observe(e, name, Prefs.radioTrendDb(this), now)
        val cell = e.band.kind == com.rfsentinel.app.sdr.RadioWatch.Kind.CELLULAR_UPLINK
        if (trend != null && e.confidence >= threshold && via == null) {
            AlertPlayer.callout(this, (if (cell) "Cellular signal " else "Radio ${spokenMhz(e.freqHz)} ") +
                if (trend == com.rfsentinel.app.sdr.RadioLog.Trend.CLOSER) "getting closer" else "moving away")
        }
        if (e.repeat) return // same channel within 2 minutes: list and trend only, no new log entry or alert
        val near = when { e.snrDb >= 40 -> "very close"; e.snrDb >= 30 -> "close" else -> "nearby" }
        val heardBy = via?.let { "Heard by $it (Sentinel Link): \"$near\" is from that phone. " } ?: ""
        val detail = heardBy + "A radio transmitted on ${e.mhz} MHz (${e.band.label}), ${e.snrDb} dB above the noise - $near. " +
            (named?.let { "Matches ${"%.4f".format(java.util.Locale.US, it.hz / 1e6)} MHz: ${it.name} (${it.source}). " } ?: "") +
            when (e.band.kind) {
                com.rfsentinel.app.sdr.RadioWatch.Kind.PUBLIC_SAFETY_MOBILE ->
                    "Public-safety radios (police, fire, EMS) transmit on this band; not proof of who it is."
                com.rfsentinel.app.sdr.RadioWatch.Kind.TETRA -> if (e.tdma)
                    "It came in TETRA time-slot bursts: an emergency-services (police, fire, ambulance) TETRA handset or car radio. " +
                        "Encrypted, nothing decoded; not proof of who it is."
                else "Emergency-service TETRA handsets transmit on this band, but this signal had no TETRA time slots; a weak sign on its own."
                com.rfsentinel.app.sdr.RadioWatch.Kind.TARGET -> "One of the frequencies you watch (Settings > RTL-SDR radio)."
                com.rfsentinel.app.sdr.RadioWatch.Kind.CUSTOM -> "A band you added in Settings > RTL-SDR radio."
                else -> (if (e.tdma) "It came in time-slot bursts like a DMR handset's (a P25 Phase 2 radio looks the same). Nothing decoded. " else "") +
                    if (e.p25Nac != null) "" else "Police use this band, but so do businesses, schools and transit; a weak sign on its own."
            } + (e.p25Nac?.let { " It decoded as P25, the digital radio police, fire and EMS use (network access code %03X): a P25 radio, not just any transmitter. Voice not decoded; not proof of who it is.".format(it) } ?: "")
        val cellDetail = heardBy + "A cellular transmitter is on the air nearby on ${e.mhz} MHz (${e.band.label}), " +
            "${e.snrDb} dB above the quiet level - $near. Something is transmitting on the phone / modem side of an " +
            "LTE band: a phone, but also a vehicle modem, a Cradlepoint-style router or a camera with a SIM. Signal " +
            "strength only, nothing decoded - it can't tell devices or carriers apart. On its own it means little " +
            "(phones are everywhere); it matters most when it travels with you or lines up with a device flagged over Wi-Fi / Bluetooth."
        val label = when {
            cell -> "Cellular transmitter nearby (LTE uplink)"
            name != null -> "Radio transmitting: $name"
            e.tdma -> "${e.slots} radio transmitting nearby"
            e.p25Nac != null -> "P25 radio transmitting nearby"
            else -> "Two-way radio transmitting nearby"
        } + (via?.let { " · via $it" } ?: "")
        val hit = Hit(Category.RADIO, label, e.confidence, if (cell) cellDetail else detail, when {
            e.tdma -> "SDR signal strength and ${e.slots} burst timing (no decoding)"
            e.p25Nac != null -> "SDR signal strength and a P25 frame header (no voice)"
            else -> "SDR signal strength (no decoding)"
        })
        // Spoken: the frequency (and its name) when that's on, else the short phrase.
        val spoken = when {
            cell -> "Cellular transmitter nearby"
            Prefs.radioSpeakFreq(this) -> (if (e.tdma) "${e.slots} radio " else "Radio ") + spokenMhz(e.freqHz) + (name?.let { ", $it" } ?: "")
            e.tdma -> "${e.slots} radio nearby"
            e.p25Nac != null -> "P25 radio nearby"
            else -> "Radio transmitting nearby"
        } + (via?.let { ", via $it" } ?: "")
        radioDetection(hit, "RADIO " + e.mhz + " MHz", e.snrDb, now, "radio:" + e.freqHz, spoken, e.confidence >= threshold && !quiet, via)
    }

    /** A radio [hit] into the detection history (tagged with where you are), and alerted when [alert]. [talkgroup]: a P25
     *  alert, which also follows Settings › RTL-SDR radio › Talkgroup alerts. */
    private fun radioDetection(hit: Hit, mac: String, rssi: Int, now: Long, key: String, spoken: String, alert: Boolean,
                               via: String? = null, talkgroup: Boolean = false) {
        val loc = lastLocation
        if (via == null || Prefs.saveLinked(this))
            logDetection(mac, "SDR", hit, now, loc?.latitude, loc?.longitude, rssi = rssi, heardBy = via?.let { "via $it" })
        // Dismissed from the list (swiped left): logged, no alert.
        if (!alert || com.rfsentinel.app.data.AlertMutes.isMuted(this, key, now)) return
        val off = alertsOff(hit.category) + (if (talkgroup) Prefs.p25AlertsOff(this) else emptySet())
        NotificationHelper.sendMapAlert(this, key, hit, com.rfsentinel.app.ui.DetectionLogActivity::class.java,
            banner = AlertKind.BANNER !in off)
        AlertPlayer.play(this, hit.tier, spoken, off = off)
    }

    /** A call started on a P25 talkgroup: reported when it is one you watch (Settings > RTL-SDR radio). */
    private fun onP25Call(tg: Int) {
        if (!Prefs.categoryEnabled(this, Category.RADIO)) return
        checkP25Unusual()
        val name = com.rfsentinel.app.sdr.P25Log.watched(Prefs.p25Talkgroups(this))[tg] ?: return
        val row = com.rfsentinel.app.sdr.SdrRadio.p25Log.rows().firstOrNull { it.tg == tg }
        val called = name.ifEmpty { "talkgroup $tg" }
        val detail = "A call started on P25 talkgroup $tg${if (name.isNotEmpty()) " ($name)" else ""}, one you watch. " +
            (row?.lastSource?.let { "Radio $it keyed up. " } ?: "") +
            (if (row?.emergency == true) "Marked as an emergency call. " else "") +
            (if (row?.encrypted == true) "Encrypted. " else "") +
            "Heard on the site's control channel: the radios may be anywhere the site covers, not necessarily near you."
        val hit = Hit(Category.RADIO, "Talkgroup active: $called", P25_CONFIDENCE, detail, "P25 control channel (signalling only, no voice)")
        radioDetection(hit, "P25 TG $tg", 0, System.currentTimeMillis(), "p25:$tg", "Talkgroup $called active",
            P25_CONFIDENCE >= Prefs.alertThreshold(this, Category.RADIO), talkgroup = true)
    }

    private val pagerAlertAt = HashMap<String, Long>()

    /**
     * A page matching the watch list (Settings > RTL-SDR radio). Stays out of the detection history, and its text out of
     * the alert log: pages are memory only. Paging networks repeat a page, so one watch alerts once per [PAGER_ALERT_GAP_MS].
     */
    private fun onPagerPage(page: com.rfsentinel.app.sdr.PagerLog.Page) {
        if (!Prefs.categoryEnabled(this, Category.RADIO)) return
        val rules = com.rfsentinel.app.sdr.PagerRules
        val watch = rules.match(rules.watches(Prefs.pagerWatch(this)), page) ?: return
        val key = "pager:" + (watch.capcode ?: watch.words)
        val now = System.currentTimeMillis()
        if (now - (pagerAlertAt[key] ?: 0L) < PAGER_ALERT_GAP_MS) return
        if (P25_CONFIDENCE < Prefs.alertThreshold(this, Category.RADIO) || com.rfsentinel.app.data.AlertMutes.isMuted(this, key, now)) return
        pagerAlertAt[key] = now
        val called = watch.name.ifEmpty { "capcode ${watch.capcode}" }
        val hit = Hit(Category.RADIO, "Pager watch: $called", P25_CONFIDENCE,
            "A page matched your watch. Read it in Tools › Pager messages.", "Pager messages")
        val off = alertsOff(hit.category) + Prefs.pagerAlertsOff(this)
        NotificationHelper.sendMapAlert(this, key, hit, com.rfsentinel.app.ui.PagerActivity::class.java, banner = AlertKind.BANNER !in off,
            shown = page.text.takeIf { it.isNotEmpty() && Prefs.pagerAlertText(this) })
        AlertPlayer.play(this, hit.tier, "Pager watch $called", off = off)
    }

    /** A call's radio heard strongly on the site's input: it is keying up close to you. */
    private fun onP25Input(call: com.rfsentinel.app.sdr.P25History.Call, hz: Long, snr: Int) {
        if (!Prefs.categoryEnabled(this, Category.RADIO)) return
        val now = System.currentTimeMillis()
        // The devices around right now (not your whitelisted ones): one that's here each time this radio is may ride with it.
        call.source?.let { radio ->
            com.rfsentinel.app.sdr.P25Link.heard(radio, DeviceRegistry.snapshot(now)
                .filter { com.rfsentinel.app.ui.LiveWindow.inRange(it, now) && !WhitelistCache.contains(it.mac) }.map { it.mac }, now)
        }
        val min = Prefs.radioMinSnr(this)
        val confidence = p25InputConfidence(snr, min)
        val name = call.tg?.let { com.rfsentinel.app.sdr.P25Log.watched(Prefs.p25Talkgroups(this))[it]?.ifEmpty { null } }
        val called = name ?: call.tg?.let { "talkgroup $it" } ?: "a private call"
        val near = when { snr >= 40 -> "very close"; snr >= 30 -> "close"; else -> "nearby" }
        val mhz = "%.4f".format(java.util.Locale.US, hz / 1e6)
        val detail = "Radio ${call.source} keyed up on $called and was heard on the site's input ($mhz MHz), $snr dB above the " +
            "noise - $near. On the input only radios transmit, so this one is near you, not just somewhere the site covers." +
            (if (call.emergency) " Marked as an emergency call." else "") + (if (call.encrypted) " Encrypted." else "")
        val hit = Hit(Category.RADIO, "P25 radio keyed up $near: $called", confidence, detail,
            "P25 control channel grant + SDR signal strength on the input (no voice)")
        radioDetection(hit, "P25 radio ${call.source}", snr, now, "p25in:${call.tg ?: call.target}",
            "Radio keyed up $near, $called", confidence >= Prefs.alertThreshold(this, Category.RADIO), talkgroup = true)
    }

    private val p25NearbyAt = HashMap<Int, Long>()

    /**
     * A radio heard calling the site on the control channel's input with nobody talking (switched on, changing talkgroup,
     * status or data): strong enough to be near you. One report per radio per [P25_NEARBY_GAP_MS].
     */
    private fun onP25Nearby(msg: com.rfsentinel.app.sdr.P25Tsbk.Inbound, snr: Int, tg: Int?) {
        if (!Prefs.categoryEnabled(this, Category.RADIO)) return
        val now = System.currentTimeMillis()
        if (now - (p25NearbyAt[msg.radio] ?: 0L) < P25_NEARBY_GAP_MS) return
        p25NearbyAt[msg.radio] = now
        if (p25NearbyAt.size > 500) p25NearbyAt.entries.removeAll { now - it.value >= P25_NEARBY_GAP_MS }
        com.rfsentinel.app.sdr.P25Link.heard(msg.radio, DeviceRegistry.snapshot(now)
            .filter { com.rfsentinel.app.ui.LiveWindow.inRange(it, now) && !WhitelistCache.contains(it.mac) }.map { it.mac }, now)
        val min = Prefs.radioMinSnr(this)
        val confidence = p25InputConfidence(snr, min)
        val name = tg?.let { com.rfsentinel.app.sdr.P25Log.watched(Prefs.p25Talkgroups(this))[it]?.ifEmpty { null } }
        val on = name ?: tg?.let { "talkgroup $it" }
        val near = when { snr >= 40 -> "very close"; snr >= 30 -> "close"; else -> "nearby" }
        val detail = "Radio ${msg.radio} sent the site a ${msg.kind}, heard on the control channel's input $snr dB above the noise - $near. " +
            "Nobody was talking: radios send these on their own (switching on, changing talkgroup, status, data). On the input " +
            "only radios transmit, so this one is near you." + (on?.let { " Its talkgroup: $it." } ?: "")
        val hit = Hit(Category.RADIO, "P25 radio $near" + (on?.let { ": $it" } ?: ""), confidence, detail,
            "P25 control channel input + SDR signal strength (no voice)")
        radioDetection(hit, "P25 radio ${msg.radio}", snr, now, "p25radio:${msg.radio}",
            "P25 radio $near", confidence >= Prefs.alertThreshold(this, Category.RADIO), talkgroup = true)
    }

    /** Tire sensors heard along your way (memory only, like [placeFollow]). */
    private val tpmsFollow = PlaceFollow()
    private var tpmsAlertAt = 0L

    /**
     * A tire-pressure sensor: the same one heard with you at three places or more (the follow settings) is a car
     * following you. The first ones ever to do that are your own car's, learned for [TPMS_LEARN_MS] and never alerted
     * (Settings › RTL-SDR radio › Forget my car's tire sensors to learn again).
     */
    private fun onTpms(r: com.rfsentinel.app.sdr.Tpms.Reading) {
        if (!Prefs.categoryEnabled(this, Category.RADIO)) return
        val loc = lastLocation ?: lastFix ?: return
        val now = System.currentTimeMillis()
        val key = "tpms:" + r.key
        tpmsFollow.heard(key, null, now, loc.latitude, loc.longitude)
        if (!Prefs.followerAlerts(this) || key in Prefs.tpmsOwn(this)) return
        val followMs = Prefs.followMinMinutes(this) * 60_000L
        val followM = Prefs.followMinMeters(this).toDouble()
        if (key !in Prefs.tpmsOwn(this) && tpmsFollow.places(key, followMs, followM) > 0)
            com.rfsentinel.app.sdr.SdrRadio.activity(com.rfsentinel.app.sdr.SdrRadio.Mode.TPMS)
        if (!tpmsFollow.follower(key, now, followMs, followM)) return
        val learning = Prefs.tpmsLearnAt(this)
        if (learning == 0L || now - learning < TPMS_LEARN_MS) {
            if (learning == 0L) Prefs.setTpmsLearnAt(this, now)
            Prefs.addTpmsOwn(this, key)
            com.rfsentinel.app.util.HwLogs.sdr.log("tire sensors: one more learned as your car's (${Prefs.tpmsOwn(this).size} in all)")
            return
        }
        val places = tpmsFollow.places(key, followMs, followM)
        val detail = "The same tire-pressure sensor (${r.protocol}, ID ${r.id}) was heard with you at $places places over the last " +
            "${Prefs.followMinMinutes(this)}+ minutes: the car it is on has been travelling with you. Every car's wheels send a fixed " +
            "ID while driving, whatever its driver's phone does. Could also be a car going the same way in traffic." +
            (r.kpa?.let { " Tire: %.0f kPa".format(java.util.Locale.US, it) + (r.tempC?.let { t -> ", %.0f °C.".format(java.util.Locale.US, t) } ?: ".") } ?: "")
        val hit = Hit(Category.RADIO, "Vehicle following you (tire sensor)", TPMS_CONFIDENCE, detail, "SDR tire-pressure sensor ID (315 / 433 MHz)")
        val alert = TPMS_CONFIDENCE >= Prefs.alertThreshold(this, Category.RADIO) && now - tpmsAlertAt >= TPMS_ALERT_GAP_MS
        if (alert) tpmsAlertAt = now
        radioDetection(hit, "TPMS ${r.protocol} ${r.id}", 0, now, key, "Vehicle following you", alert)
    }

    private var p25UnusualAt = 0L
    private val p25UnusualAlerted = HashMap<Int, Long>()

    /** Every few minutes while calls come in: talkgroups much busier than usual for the hour (Settings › RTL-SDR radio). */
    private fun checkP25Unusual() {
        val now = System.currentTimeMillis()
        if (!Prefs.p25Unusual(this) || Prefs.p25LogDays(this) == 0 || now - p25UnusualAt < P25_UNUSUAL_CHECK_MS) return
        p25UnusualAt = now
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = com.rfsentinel.app.sdr.P25History.unusual(com.rfsentinel.app.sdr.P25History.read(this@ScanForegroundService), now)
            if (found.isNotEmpty()) pipeline.post { found.forEach { onP25Unusual(it, now) } }
        }
    }

    private fun onP25Unusual(u: com.rfsentinel.app.sdr.P25History.Unusual, now: Long) {
        if (now - (p25UnusualAlerted[u.tg] ?: 0L) < P25_UNUSUAL_AGAIN_MS) return
        p25UnusualAlerted[u.tg] = now
        val name = com.rfsentinel.app.sdr.P25Log.watched(Prefs.p25Talkgroups(this))[u.tg]?.ifEmpty { null }
        val called = name ?: "talkgroup ${u.tg}"
        val detail = "P25 talkgroup ${u.tg}${name?.let { " ($it)" }.orEmpty()}: ${u.recent} calls in the last hour" +
            (if (u.new) ", never heard before in ${u.days} days of log. "
            else String.format(java.util.Locale.US, ", against %.1f on average at this hour over the last %d days. ", u.usual, u.days)) +
            "Busier than usual can mean an incident, an event or a planned operation. Heard on the site's control channel: " +
            "the radios may be anywhere the site covers."
        val hit = Hit(Category.RADIO, "Unusual activity: $called", P25_UNUSUAL_CONFIDENCE, detail, "P25 call log (signalling only, no voice)")
        radioDetection(hit, "P25 TG ${u.tg}", 0, now, "p25u:${u.tg}", "Unusual activity on $called",
            P25_UNUSUAL_CONFIDENCE >= Prefs.alertThreshold(this, Category.RADIO), talkgroup = true)
    }

    /** "154.43" for 154.430 MHz: three decimals, trailing zeros dropped, read well by the voice. */
    private fun spokenMhz(hz: Long): String =
        "%.3f".format(java.util.Locale.US, hz / 1e6).trimEnd('0').trimEnd('.')

    /** Police aircraft (ADS-B) and Waze reports: internet sources, both off by default. */
    private val onlineWatchLazy = lazy {
        com.rfsentinel.app.online.OnlineWatch(this, serviceScope, { lastLocation ?: lastFix },
            onCallout = { text -> pipeline.post {
                // Waze "getting closer" call-outs follow the category's Voice chip too.
                if (AlertKind.VOICE !in alertsOff(Category.POLICE_REPORT)) AlertPlayer.callout(this, text)
            } }) { alert ->
            pipeline.post { onOnlineHit(alert) }
        }
    }
    private val onlineWatch by onlineWatchLazy

    private fun onlineActive() = Prefs.categoryEnabled(this, Category.AIRCRAFT) ||
        (Prefs.categoryEnabled(this, Category.POLICE_REPORT) && Prefs.wazeReady(this))

    /**
     * A police aircraft overhead or a Waze report: logged, and an alert above the threshold. What the alert
     * does depends on its level (sound and voice, a notification only, or nothing beyond the log).
     */
    private fun onOnlineHit(alert: com.rfsentinel.app.online.OnlineWatch.Alert) {
        val hit = alert.hit
        if (!Prefs.categoryEnabled(this, hit.category)) return
        val now = System.currentTimeMillis()
        val tag = Prefs.gpsTaggingEnabled(this)
        // Logged where it was (the aircraft's own ADS-B position, the Waze report's spot), so its row opens the map there.
        if (alert.log) serviceScope.launch {
            AppDatabase.getInstance(this@ScanForegroundService).detectionDao().insert(
                DetectionEntity(
                    mac = alert.key.substringBefore(':').uppercase() + " " + alert.key.substringAfter(':'), label = hit.label,
                    source = "ONLINE", rssi = 0, timestamp = now,
                    latitude = if (tag) alert.lat else null, longitude = if (tag) alert.lon else null,
                    category = hit.category.name, confidence = hit.confidence, evidence = hit.evidence
                )
            )
        }
        // An aircraft or Waze report hidden from the list (swiped left): logged, no alert.
        if (alert.level != com.rfsentinel.app.online.WazePolice.Level.LOG &&
            // A circling aircraft you asked to hear about (Settings) alerts below the threshold: it's never strong.
            (hit.confidence >= Prefs.alertThreshold(this, hit.category) || hit.label == com.rfsentinel.app.online.PoliceAircraft.CIRCLING) &&
            !com.rfsentinel.app.data.AlertMutes.isMuted(this, alert.key, now)) {
            val off = alertsOff(hit.category)
            NotificationHelper.sendMapAlert(this, alert.key, hit, lat = alert.lat, lon = alert.lon, online = alert.key,
                banner = AlertKind.BANNER !in off)
            if (alert.level == com.rfsentinel.app.online.WazePolice.Level.LOUD) {
                AlertPlayer.play(this, hit.tier, alert.spoken ?: com.rfsentinel.app.util.Spoken.shortWord(hit), off = off)
            }
        }
    }

    private var gnssWatching = false
    private val gnssListener: (com.rfsentinel.app.detect.GnssAnalyzer.Anomaly) -> Unit = { a -> pipeline.post { onGnssAnomaly(a) } }

    /**
     * GPS / Galileo / GLONASS / BeiDou jamming and spoofing signs ([GnssWatch]): heard
     * whenever GPS is on (map, traces, camera warnings); it doesn't turn GPS on itself.
     */
    private fun startGnssChecks() {
        val want = ownOn() && Prefs.categoryEnabled(this, Category.GNSS) && hasFineLocation()
        if (want && !gnssWatching) { GnssWatch.start(this, gnssListener); gnssWatching = true }
        else if (!want && gnssWatching) { GnssWatch.stop(this, gnssListener); gnssWatching = false }
    }

    private fun onGnssAnomaly(a: com.rfsentinel.app.detect.GnssAnalyzer.Anomaly) {
        if (!Prefs.categoryEnabled(this, Category.GNSS)) return
        val now = System.currentTimeMillis()
        val hit = Hit(Category.GNSS, a.title, a.confidence, a.detail, "Phone satellite receiver (heuristic, not proof)")
        val loc = lastLocation
        logDetection("GNSS " + a.key, "GNSS", hit, now, loc?.latitude, loc?.longitude)
        if (a.confidence >= Prefs.alertThreshold(this, hit.category)) {
            val off = alertsOff(hit.category)
            NotificationHelper.sendMapAlert(this, "gnss:" + a.key, hit, com.rfsentinel.app.ui.GnssActivity::class.java, banner = AlertKind.BANNER !in off)
            AlertPlayer.play(this, hit.tier, a.title, off = off)
        }
    }

    /** A WiFi deauthentication flood heard by the USB WiFi adapter (Hacking tools category). */
    private fun onDeauth(a: com.rfsentinel.app.usb.DeauthWatch.Attack) {
        if (!Prefs.categoryEnabled(this, Category.HACKER)) return
        val now = System.currentTimeMillis()
        val ssid = DeviceRegistry.get(a.bssid)?.name
        val net = ssid?.let { "\"$it\"" } ?: "network ${a.bssid}"
        val channel = if (a.channel >= 5955) "6 GHz channel ${(a.channel - 5950) / 5}" else "channel ${a.channel}" // 6 GHz comes in MHz
        val detail = "${a.frames} deauthentication / disassociation frames in ${a.windowS} s for $net ($channel)" +
            (if (a.broadcast) ", sent to every device on it" else "") + ". Something is knocking devices off this WiFi: a jammer " +
            "or a hacking tool (ESP32 / ESP8266 deauther, Flipper Zero WiFi board, WiFi Pineapple). A normal network sends a " +
            "handful, never a stream. Heard by the USB WiFi adapter; nothing is sent."
        val hit = Hit(Category.HACKER, "WiFi deauth attack on $net", if (a.broadcast) 80 else 70, detail,
            "802.11 management frames (USB WiFi adapter, receive-only)")
        reportAttack(a.bssid, "WIFI", hit, ssid, "deauth:" + a.bssid, "WiFi attack nearby", now, "USB WiFi")
    }

    /** One transmitter answering under many network names (USB WiFi adapter): a Karma / PineAP fake access point. */
    private fun onKarma(a: com.rfsentinel.app.usb.KarmaWatch.Attack) {
        if (!Prefs.categoryEnabled(this, Category.HACKER)) return
        val now = System.currentTimeMillis()
        val shown = a.names.take(6).joinToString { "\"$it\"" } + if (a.names.size > 6) "..." else ""
        val channel = if (a.channel >= 5955) "6 GHz channel ${(a.channel - 5950) / 5}" else "channel ${a.channel}"
        val detail = "One access point (${a.transmitter}, $channel) answered under ${a.names.size} different network names " +
            "in ${a.windowS} s: $shown. A real access point answers under its own name only. This is how a WiFi Pineapple " +
            "or ESP32 Karma attack makes phones join it, posing as networks they know - don't join open networks here. " +
            "Heard by the USB WiFi adapter; nothing is sent."
        val hit = Hit(Category.HACKER, "Fake WiFi access point (Karma / PineAP)", 80, detail,
            "802.11 beacons and probe responses (USB WiFi adapter, receive-only); SignalSweep research (GPL-3.0; facts only)")
        reportAttack(a.transmitter, "WIFI", hit, a.names.firstOrNull(), "karma:" + a.transmitter, "WiFi attack nearby", now, "USB WiFi")
    }

    /** A flood of fake trackers on throwaway addresses (HackerWatch.tagFlood). */
    private fun onTagFlood(f: com.rfsentinel.app.detect.HackerWatch.TagFlood) {
        if (!Prefs.categoryEnabled(this, Category.HACKER)) return
        val now = System.currentTimeMillis()
        val detail = "${f.addresses} of the ${f.heard} ${f.network} trackers heard in ${f.windowS / 60} min were each heard once or twice " +
            "on an address they never used again - real tags keep theirs. Their signal strengths differ by about ${f.spreadDb} dB" +
            (if (f.spreadDb <= 4) " (alike: likely one transmitter)" else "") + "." +
            (if (f.invalidKeys > 0) " ${f.invalidKeys} sent a key no real tag can have." else "") +
            " A Flipper Zero or ESP32 tracker-spam attack makes phones show \"tracker found\" warnings that aren't real; " +
            "in a crowd, many real tags come and go too, which lowers the confidence. Real trackers are still reported as usual."
        val hit = Hit(Category.HACKER, "Fake tracker flood (${f.network})", f.score, detail,
            "Bluetooth adverts, receive-only; SignalSweep research (GPL-3.0; facts only)")
        reportAttack(f.sample, "BLE", hit, null, "tagflood:" + f.network, "Fake trackers nearby", now,
            com.rfsentinel.app.esp.HeardBy.tag(f.sample, Advert.Source.BLE))
    }

    /** An attack heard over time rather than one device: logged, and alerted like a detection. */
    private fun reportAttack(mac: String, source: String, hit: Hit, name: String?, key: String, spoken: String, now: Long, heardBy: String) {
        val loc = lastLocation
        logDetection(mac, source, hit, now, loc?.latitude, loc?.longitude, deviceName = name, heardBy = heardBy)
        if (hit.confidence >= Prefs.alertThreshold(this, hit.category)) {
            val off = alertsOff(hit.category)
            NotificationHelper.sendMapAlert(this, key, hit, com.rfsentinel.app.ui.DetectionLogActivity::class.java, banner = AlertKind.BANNER !in off)
            AlertPlayer.play(this, hit.tier, spoken, off = off)
        }
    }

    private fun onCellAnomaly(a: com.rfsentinel.app.detect.CellAnalyzer.Anomaly) {
        val now = System.currentTimeMillis()
        if (now - (lastCellAlert[a.key] ?: 0L) < CELL_REPEAT_MS) return
        lastCellAlert[a.key] = now
        CellMonitor.lastAnomaly = now to a
        val hit = Hit(Category.CELL_ANOMALY, a.title, a.confidence, a.detail, "Android cell info (heuristic, not proof)")
        val loc = lastLocation
        logDetection("CELL " + a.key.substringAfter(':'), "CELL", hit, now, loc?.latitude, loc?.longitude)
        if (a.confidence >= Prefs.alertThreshold(this, hit.category)) {
            val off = alertsOff(hit.category)
            NotificationHelper.sendMapAlert(this, "cell:" + a.key, hit, com.rfsentinel.app.ui.DetectionLogActivity::class.java, banner = AlertKind.BANNER !in off)
            AlertPlayer.play(this, hit.tier, "Cell network warning", off = off)
        }
    }

    /**
     * Radar-detector beeps: after a device alert, tick faster as the flagged device's
     * signal gets stronger and stop when it's gone. Each alerted device beeps for at
     * most [RADAR_HOLD_MS], so one parked next to you doesn't beep forever; it starts
     * again when the device re-alerts.
     */
    private fun startRadarBeep() {
        radarJob?.cancel()
        radarJob = serviceScope.launch {
            while (isActive) {
                val ctx = this@ScanForegroundService
                val now = System.currentTimeMillis()
                // Silent while Locate beeps, so the two never play over each other.
                val target = if (!Prefs.radarBeep(ctx) || com.rfsentinel.app.ui.LiveRead.locating != null) null else lastAlerted.entries
                    .filter { now - it.value in RADAR_QUIET_MS..RADAR_HOLD_MS && !WhitelistCache.contains(it.key) &&
                        !com.rfsentinel.app.data.AlertMutes.isMuted(ctx, it.key, now) }
                    .mapNotNull { DeviceRegistry.get(it.key) }
                    .filter { s -> com.rfsentinel.app.ui.LiveWindow.alerting(s, now) &&
                        AlertKind.BEEP !in (s.best?.let { alertsOff(it.category, s.mac, s.hits) } ?: OuiWatchlist.alertsOff(s.mac, s.hits)) }
                    .maxByOrNull { it.rssi }
                if (target == null) delay(1_000L)
                else {
                    AlertPlayer.tick(ctx)
                    delay(com.rfsentinel.app.util.Beeper.tickIntervalMs(target.rssi))
                }
            }
        }
    }

    /**
     * Keeps the floating threat bubble and the floating mini map in sync (hidden while
     * our own screens are visible).
     */
    private fun startBubble() {
        bubbleJob?.cancel()
        val bubble = Prefs.threatBubble(this); val miniMap = Prefs.floatingMap(this)
        if (!bubble) com.rfsentinel.app.ui.ThreatBubble.hide(this)
        if (!miniMap) com.rfsentinel.app.ui.FloatingMap.hide(this)
        if (!bubble && !miniMap) return
        bubbleJob = serviceScope.launch {
            while (isActive) {
                val ctx = this@ScanForegroundService
                if (RFSentinelApp.inForeground || !com.rfsentinel.app.ui.ThreatBubble.canShow(ctx)) {
                    com.rfsentinel.app.ui.ThreatBubble.hide(ctx)
                    com.rfsentinel.app.ui.FloatingMap.hide(ctx)
                } else {
                    val devices = DeviceRegistry.snapshot()
                    // Only devices still in range: the bubble clears as soon as a threat is gone.
                    val flagged = devices.filter { com.rfsentinel.app.ui.LiveWindow.alerting(it) }
                    val top = flagged.maxOfOrNull { it.best!!.confidence } ?: 0
                    val probable = flagged.any { it.best!!.confidence >= Prefs.alertThreshold(ctx, it.best!!.category) }
                    val cellWarning = CellMonitor.lastAnomaly?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }
                    val level = when {
                        flagged.any { it.following } || top >= 80 -> com.rfsentinel.app.ui.ThreatBubble.Level.DANGER
                        probable || cellWarning != null -> com.rfsentinel.app.ui.ThreatBubble.Level.PROBABLE
                        flagged.isNotEmpty() -> com.rfsentinel.app.ui.ThreatBubble.Level.WEAK
                        else -> com.rfsentinel.app.ui.ThreatBubble.Level.CLEAR
                    }
                    if (Prefs.threatBubble(ctx)) com.rfsentinel.app.ui.ThreatBubble.update(ctx, level, flagged.size)
                    else com.rfsentinel.app.ui.ThreatBubble.hide(ctx)
                    if (Prefs.floatingMap(ctx)) {
                        // The same dots as the full map: pinpointed, else where they were heard strongest.
                        val dots = devices.mapNotNull { s ->
                            val p = s.place ?: return@mapNotNull null
                            com.rfsentinel.app.ui.FloatingMap.Dot(p.lat, p.lon,
                                com.rfsentinel.app.ui.DeviceColors.forDevice(ctx, s), com.rfsentinel.app.ui.DeviceColors.isFlagged(s))
                        }
                        // Known cameras within ~5 km, when the map shows them.
                        val fix = lastFix
                        val cams = if (fix == null || !Prefs.showKnownAlpr(ctx)) emptyList() else
                            com.rfsentinel.app.alpr.AlprStore.cameras.asSequence()
                                .filter { kotlin.math.abs(it.lat - fix.latitude) < 0.045 && kotlin.math.abs(it.lon - fix.longitude) < 0.065 }
                                .take(400)
                                .map { com.rfsentinel.app.ui.FloatingMap.Cam(it.lat, it.lon, it.type,
                                    com.rfsentinel.app.alpr.IgnoredCameras.contains(ctx, it.osmId)) }
                                .toList()
                        com.rfsentinel.app.ui.FloatingMap.update(ctx, level, flagged.size, fix, dots, cams)
                    } else com.rfsentinel.app.ui.FloatingMap.hide(ctx)
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
        // Take the final history first, while the extra info its saved pages show (USB probes...) is still here.
        val history = runCatching { takeHistory(final = true) }.onFailure { Log.w(TAG, "History take failed", it) }.getOrNull()
        com.rfsentinel.app.ui.LiveRead.reset()
        com.rfsentinel.app.usb.DeauthWatch.onAttack = null
        com.rfsentinel.app.usb.DeauthWatch.clear()
        com.rfsentinel.app.usb.KarmaWatch.onAttack = null
        com.rfsentinel.app.usb.KarmaWatch.clear()
        com.rfsentinel.app.usb.PcapRecorder.stop()
        locatingHandler.removeCallbacks(locatingCheck)
        com.rfsentinel.app.esp.EspBoards.stop(this)
        com.rfsentinel.app.usb.UsbWifi.stop(this)
        com.rfsentinel.app.usb.UsbBluetooth.stop(this)
        com.rfsentinel.app.sdr.SdrRadio.stop(this)
        // Its hooks point into this service: cleared, so a stopped scan isn't kept in memory by them.
        com.rfsentinel.app.sdr.SdrRadio.onAircraft = null
        com.rfsentinel.app.sdr.SdrRadio.onPagerPage = null
        com.rfsentinel.app.sdr.SdrRadio.location = null
        if (gnssWatching) { GnssWatch.stop(this, gnssListener); gnssWatching = false }
        if (onlineWatchLazy.isInitialized()) onlineWatch.stop() // not built just to be stopped
        com.rfsentinel.app.esp.OuiSpyBle.onLiveChanged = null // its stop must not restart the phone's scan here
        com.rfsentinel.app.esp.OuiSpyBle.stop()
        com.rfsentinel.app.esp.MeshBle.stop()
        stopLink()
        // External hardware is gone with the scan: its "heard by" marks (External filter, badges) go too.
        com.rfsentinel.app.esp.HeardBy.esp.clear()
        com.rfsentinel.app.esp.HeardBy.usb.clear()
        com.rfsentinel.app.esp.HeardBy.usbBt.clear()
        com.rfsentinel.app.usb.ProbeIntel.clear()
        com.rfsentinel.app.data.ProbeLog.flush(this)
        nanRemoteId?.stop()
        if (audioDevicesWatched) {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).unregisterAudioDeviceCallback(audioDevices)
            audioDevicesWatched = false
        }
        bleEngine.stop()
        wifiEngine.stop()
        if (btStateReceiverRegistered) {
            try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
            btStateReceiverRegistered = false
        }
        if (locationListening) stopLocationUpdates(getSystemService(LOCATION_SERVICE) as LocationManager)
        AlertPlayer.stopIntro()
        serviceScope.cancel()
        pipelineThread.quitSafely()
        cellMonitor?.close()
        com.rfsentinel.app.ui.ThreatBubble.hide(this)
        com.rfsentinel.app.ui.FloatingMap.hide(this)
        // The final history (taken above) and trace are taken synchronously, so a scan restarted right
        // away (new session, new trace) can't clear or reuse them; written after.
        val trip = TripRecorder.detach() // stopping the scanner ends the trace recording too
        val app = application as RFSentinelApp
        app.appScope.launch {
            history?.let { runCatching { writeHistory(it) }.onFailure { e -> Log.w(TAG, "History flush failed", e) } }
            trip?.let { runCatching { TripRecorder.persist(app, it) }.onFailure { e -> Log.w(TAG, "Trace save failed", e) } }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        Prefs.setStartedByCar(this, false)
        lastFix = null // only true while scanning: kept, it would pass for your position hours later
        isRunning = false
        refreshTile()
        StatusWidget.updateAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
