package com.rfsentinel.app

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.databinding.ActivityMainBinding
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.settings.SettingsActivity
import com.rfsentinel.app.ui.DetectionLogActivity
import com.rfsentinel.app.ui.DeviceActions
import com.rfsentinel.app.ui.DeviceAdapter
import com.rfsentinel.app.ui.DeviceRow
import com.rfsentinel.app.ui.RadarView
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.ProximityUtil
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        /** Row id prefix of the live cell tower rows (not devices). */
        private const val CELL_ROW = "cell:"
        private const val WAZE_ROW = "waze:"
        private const val RADIO_ROW = "radio:"
        /** A radio row flashes while its transmitter was heard this recently. */
        private const val RADIO_LIVE_MS = 15_000L
        private const val BLUETOOTH_TAG_COLOR = 0xFF1565C0.toInt()
        private const val WIFI_TAG_COLOR = 0xFF00838F.toInt()
        private const val REFRESH_MS = 1_000L
        private const val WEAK_COLOR = 0xFFB26A00.toInt()
        private const val WHITELIST_COLOR = 0xFF6B7B80.toInt()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceAdapter
    private lateinit var panes: com.rfsentinel.app.ui.MainPanes
    private var filter = com.rfsentinel.app.ui.DeviceFilter.ALL
    private val filterChips = HashMap<com.rfsentinel.app.ui.DeviceFilter, com.google.android.material.chip.Chip>()
    private var query = ""
    private var deviceCount = 0
    private var flaggedCount = 0
    private var demoMode = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Only the required set gates scanning; a denied notification permission
        // just means alerts show in-app and via sound only.
        if (Permissions.missingRequired(this).isEmpty()) {
            startScanning()
        } else {
            binding.statusText.text = "Location and Bluetooth-scan permissions are required to scan"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        applyThemeHeader()

        adapter = DeviceAdapter(
            onClick = {
                if (it.mac.startsWith(CELL_ROW)) startActivity(Intent(this, com.rfsentinel.app.ui.CellTowersActivity::class.java))
                else if (it.mac.startsWith(RADIO_ROW)) startActivity(Intent(this, com.rfsentinel.app.ui.DetectionLogActivity::class.java))
                else if (it.mac.startsWith(WAZE_ROW)) {
                    com.rfsentinel.app.online.WazePolice.latest.firstOrNull { w -> WAZE_ROW + w.report.id == it.mac }?.let { w ->
                        startActivity(Intent(this, com.rfsentinel.app.ui.MapActivity::class.java)
                            .putExtra(com.rfsentinel.app.ui.MapActivity.EXTRA_CENTER_LAT, w.report.lat)
                            .putExtra(com.rfsentinel.app.ui.MapActivity.EXTRA_CENTER_LON, w.report.lon))
                    }
                }
                else DeviceActions.openDetails(this, it.mac)
            },
            onLongPress = { if (!it.mac.startsWith(CELL_ROW) && !it.mac.startsWith(WAZE_ROW) && !it.mac.startsWith(RADIO_ROW)) DeviceActions.showQuickActions(this, it.mac) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.newAlertPill.setOnClickListener {
            binding.recyclerView.smoothScrollToPosition(0)
            showNewAlertPill(false)
        }
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(-1)) showNewAlertPill(false)
            }
        })
        binding.wazeSwipe.setColorSchemeColors(com.rfsentinel.app.ui.ChipStyle.accent(this).first)
        binding.wazeSwipe.setOnRefreshListener {
            if (com.rfsentinel.app.ui.WazeUi.checkNow(this)) {
                refreshAskedAt = System.currentTimeMillis()
                binding.wazeSwipe.postDelayed({ binding.wazeSwipe.isRefreshing = false }, 12_000) // never spin for ever
            } else binding.wazeSwipe.isRefreshing = false
        }
        // Rows rebind every second (RSSI / "seen Xs ago"); the default change
        // crossfade would make the whole list flicker and fight the match flash.
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.radarView.onBlipClick = { DeviceActions.openDetails(this, it) }
        panes = com.rfsentinel.app.ui.MainPanes(this, binding)

        binding.startStopButton.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) fitStartButton() }
        binding.startStopButton.setOnClickListener {
            if (ScanForegroundService.isRunning) stopScanning() else requestPermissionsAndStart()
        }
        styleViewToggle()
        // Any mix of list, radar and live map: one fills the screen, more share it.
        for ((pane, id) in paneButtons) if (pane in panes.panes) binding.viewToggle.check(id)
        binding.viewToggle.addOnButtonCheckedListener { group, _, _ ->
            val chosen = paneButtons.filter { it.second in group.checkedButtonIds }.map { it.first }.toSet()
            if (chosen.isEmpty() || chosen == panes.panes) return@addOnButtonCheckedListener
            panes.setPanes(chosen)
            applyViewMode()
            render()
        }
        binding.toolsButton.setOnClickListener { v ->
            androidx.appcompat.widget.PopupMenu(this, v).apply {
                menu.add(0, 1, 0, "Cell towers")
                menu.add(0, 2, 1, "WiFi channels")
                menu.add(0, 3, 2, "WiFi spectrum")
                menu.add(0, 4, 3, "Satellites")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.CellTowersActivity::class.java))
                        2 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.WifiAnalyzerActivity::class.java))
                        4 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.GnssActivity::class.java))
                        else -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.WifiAnalyzerActivity::class.java)
                            .putExtra(com.rfsentinel.app.ui.WifiAnalyzerActivity.EXTRA_SPECTRUM, true))
                    }
                    true
                }
            }.show()
        }
        setupFilterChips()
        panes.setFilter(filter)

        // Debug builds only: made-up devices for screenshots (see DemoData).
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) {
            demoMode = true
            // So the Android Auto screens show the demo as a running scan too.
            ScanForegroundService.isRunning = true
            intent.getStringExtra("view")?.split(',')?.toSet()?.let { Prefs.setViewPanes(this, it) }
            val demoLat = intent.getFloatExtra("lat", Float.NaN)
            val demoLon = intent.getFloatExtra("lon", Float.NaN)
            if (!demoLat.isNaN() && !demoLon.isNaN()) {
                com.rfsentinel.app.ui.DemoData.fakeLocation = android.location.Location("demo").apply {
                    latitude = demoLat.toDouble(); longitude = demoLon.toDouble(); accuracy = 5f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                }
            }
            val anchor = com.rfsentinel.app.ui.DemoData.fakeLocation?.let { it.latitude to it.longitude } ?: runCatching {
                @Suppress("MissingPermission")
                (getSystemService(LOCATION_SERVICE) as android.location.LocationManager)
                    .getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?.let { it.latitude to it.longitude }
            }.getOrNull()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                com.rfsentinel.app.ui.DemoData.populate(anchor)
                com.rfsentinel.app.ui.DemoData.seedAlerts(this@MainActivity, anchor)
                anchor?.let { com.rfsentinel.app.ui.DemoData.seedHistory(this@MainActivity, it) }
            }
        }
        applyViewMode()
        observeDevices()
        updateStatus()
        lifecycleScope.launch { Favorites.load(this@MainActivity) }
        // Only on a real fresh start - a theme change recreates this screen too.
        if (savedInstanceState == null && !Prefs.onboardingDone(this)) showOnboarding()
        else if (savedInstanceState == null) com.rfsentinel.app.util.UpdateChecker.checkOnStartup(this)
        if (savedInstanceState == null) com.rfsentinel.app.util.CrashLog.offerAfterCrash(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }


    /** Styled themes: their own title, animated wordmark and the user's banner image. */
    private fun applyThemeHeader() {
        val theme = com.rfsentinel.app.ui.ThemeManager.current(this)
        theme.appTitle?.let { title = it }
        val header = theme.header
        binding.themedHeader.visibility = if (header != null) View.VISIBLE else View.GONE
        if (header == null) return
        binding.themeHeader.style = header
        val file = com.rfsentinel.app.ui.ThemeManager.bannerFile(this)
        // Bundled poster art sits beside the animated header (a picked banner replaces it).
        val useArt = theme.headerImage != null && !com.rfsentinel.app.ui.ThemeManager.hasBanner(this)
        binding.themeHeader.poster = if (useArt) android.graphics.BitmapFactory.decodeResource(resources, theme.headerImage!!,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) else null
        if (useArt) {
            binding.themeBanner.visibility = View.GONE
        } else if (com.rfsentinel.app.ui.ThemeManager.hasBanner(this)) {
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply {
                // Downsample big images so the header stays light.
                val probe = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, probe)
                inSampleSize = maxOf(1, probe.outWidth / 1200)
            })
            binding.themeBanner.setImageBitmap(bmp)
            binding.themeBanner.visibility = if (bmp != null) View.VISIBLE else View.GONE
        } else {
            binding.themeBanner.visibility = View.GONE
        }
    }

    private val paneButtons get() = listOf("list" to R.id.viewListButton, "radar" to R.id.viewRadarButton, "map" to R.id.viewMapButton)

    private fun applyViewMode() {
        panes.apply()
        // The "nothing heard" note sits over the list, or over the radar when there is no list.
        val home = if (!panes.listShown && panes.radarShown) binding.radarPane else binding.listFrame
        if (binding.emptyText.parent !== home) {
            (binding.emptyText.parent as? android.view.ViewGroup)?.removeView(binding.emptyText)
            home.addView(binding.emptyText, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER))
        }
    }

    /** Filter chips from [com.rfsentinel.app.ui.DeviceFilter] (same rules as the map), counts filled in by render(). */
    private fun setupFilterChips() {
        for (f in com.rfsentinel.app.ui.DeviceFilter.entries) {
            val chip = com.google.android.material.chip.Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = f.label
                isCheckable = true
                // No invisible 48 dp touch frame around each chip: the row is 16 dp shorter.
                setEnsureMinTouchTargetSize(false)
                com.rfsentinel.app.ui.ChipStyle.apply(this)
            }
            filterChips[f] = chip
            binding.filterChips.addView(chip)
            if (f == filter) chip.isChecked = true
        }
        binding.filterChips.setOnCheckedStateChangeListener { _, ids ->
            filter = filterChips.entries.firstOrNull { it.value.id == ids.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            panes.setFilter(filter)
            render()
        }
    }

    /** List / radar toggle: the selected half filled with the theme highlight. */
    private fun styleViewToggle() {
        val (accent, onAccent) = com.rfsentinel.app.ui.ChipStyle.accent(this)
        val text = com.rfsentinel.app.ui.ChipStyle.onSurface(this)
        val checked = intArrayOf(android.R.attr.state_checked)
        val none = intArrayOf()
        for (b in listOf(binding.viewListButton, binding.viewRadarButton, binding.viewMapButton)) {
            b.backgroundTintList = android.content.res.ColorStateList(arrayOf(checked, none), intArrayOf(accent, 0x00000000))
            b.iconTint = android.content.res.ColorStateList(arrayOf(checked, none), intArrayOf(onAccent, text))
            b.strokeColor = android.content.res.ColorStateList.valueOf(accent)
        }
        binding.toolsButton.iconTint = android.content.res.ColorStateList.valueOf(accent)
        binding.toolsButton.strokeColor = android.content.res.ColorStateList.valueOf(accent)
    }

    private var shownRunning: Boolean? = null

    private fun updateStatus() {
        val running = ScanForegroundService.isRunning || demoMode
        if (shownRunning != running) {
            shownRunning = running
            // Start: theme highlight with a play icon; Stop: red with a stop icon.
            val (accent, onAccent) = com.rfsentinel.app.ui.ChipStyle.accent(this)
            val red = com.rfsentinel.app.ui.ThemeManager.ink(this, 0xFFB3261E.toInt())
            binding.startStopButton.contentDescription = if (running) "Stop scanning" else "Start scanning"
            fitStartButton()
            binding.startStopButton.backgroundTintList = android.content.res.ColorStateList.valueOf(if (running) red else accent)
            val fg = if (running) 0xFFFFFFFF.toInt() else onAccent
            binding.startStopButton.setTextColor(fg)
            binding.startStopButton.iconTint = android.content.res.ColorStateList.valueOf(fg)
        }
        binding.statusText.text = when {
            !running -> "Idle - tap Start to listen for nearby devices"
            !bluetoothOn() && !demoMode -> "Scanning WiFi only - turn on Bluetooth for BLE (picked up automatically)"
            else -> "Scanning · $deviceCount ${plural(deviceCount, "device", "devices")} nearby" +
                (if (flaggedCount > 0) " · $flaggedCount flagged" else "") +
                (com.rfsentinel.app.service.CellMonitor.lastAnomaly
                    ?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }
                    ?.let { " · ⚠ ${it.second.title}" } ?: "") +
                (ScanForegroundService.lastWatchdogRestart
                    ?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }
                    ?.let { " · ${it.second} scan restarted " +
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it.first)) } ?: "") +
                com.rfsentinel.app.esp.OuiSpyBle.status.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                com.rfsentinel.app.esp.EspBoards.status.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                com.rfsentinel.app.usb.UsbWifi.status.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                com.rfsentinel.app.sdr.SdrRadio.status.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                com.rfsentinel.app.sdr.RadioReference.status.takeIf { it.isNotBlank() && com.rfsentinel.app.util.Prefs.radioReferenceOn(this) }
                    ?.let { "\n$it" }.orEmpty()
        }
    }

    /**
     * Start / Stop with its icon when both fit; with large text the icon goes first, then the
     * word (the colour and the icon still tell them apart), so the label is never cut off.
     */
    private var startIconPadding = -1

    private fun fitStartButton() {
        val b = binding.startStopButton
        val running = shownRunning == true
        val label = if (running) "Stop" else "Start"
        val icon = if (running) R.drawable.ic_car_stop else R.drawable.ic_car_play
        if (b.width == 0) { b.text = label; b.setIconResource(icon); return }
        val room = b.width - b.paddingStart - b.paddingEnd
        val drawn = b.transformationMethod?.getTransformation(label, b)?.toString() ?: label
        val textW = b.paint.measureText(drawn) + 2 * resources.displayMetrics.density
        if (startIconPadding < 0) startIconPadding = b.iconPadding
        val iconW = (b.iconSize.takeIf { it > 0 } ?: (24 * resources.displayMetrics.density).toInt()) + startIconPadding
        when {
            textW + iconW <= room -> { b.text = label; b.setIconResource(icon); b.iconPadding = startIconPadding }
            textW <= room -> { b.text = label; b.icon = null }
            else -> { b.text = ""; b.setIconResource(icon); b.iconPadding = 0 }
        }
    }

    /** Alerts (flagged rows) already shown, so a new one above the screen can be pointed out. */
    private var seenAlerts: Set<String> = emptySet()

    /**
     * At the top of the list, the list stays at the top: a new alert inserted above appears in
     * view instead of just off screen. Scrolled down, the rows you are reading stay put, and a
     * "New alert" pill shows instead; tap it (or scroll up) to see it.
     */
    private fun submitRows(rows: List<DeviceRow>) {
        val rv = binding.recyclerView
        val atTop = !rv.canScrollVertically(-1)
        val alerts = rows.filter { it.highlight != null || it.flashing }.map { it.mac }.toSet()
        val fresh = alerts - seenAlerts
        seenAlerts = alerts
        adapter.submitList(rows) {
            if (atTop) {
                rv.scrollToPosition(0)
                showNewAlertPill(false)
            } else if (fresh.isNotEmpty()) {
                val first = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (rows.withIndex().any { (i, r) -> r.mac in fresh && i < first }) showNewAlertPill(true)
            }
        }
    }

    private fun showNewAlertPill(show: Boolean) {
        val pill = binding.newAlertPill
        if (show == (pill.visibility == View.VISIBLE)) return
        if (show) {
            val (accent, onAccent) = com.rfsentinel.app.ui.ChipStyle.accent(this)
            pill.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * resources.displayMetrics.density; setColor(accent)
            }
            pill.setTextColor(onAccent)
            pill.alpha = 0f; pill.visibility = View.VISIBLE
            pill.animate().alpha(1f).setDuration(150).start()
        } else {
            pill.animate().alpha(0f).setDuration(150).withEndAction { pill.visibility = View.GONE }.start()
        }
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private fun bluetoothOn(): Boolean =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    /** Rebuilds the live list / radar once a second while the screen is visible. */
    private fun observeDevices() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A new or cleared Waze report redraws the list at once, not at the next tick.
                val stopWaze = com.rfsentinel.app.online.WazePolice.addListener {
                    runOnUiThread {
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@runOnUiThread
                        // The answer to a pull-down check ends the spinner.
                        if (binding.wazeSwipe.isRefreshing && com.rfsentinel.app.online.WazePolice.latestAt >= refreshAskedAt) binding.wazeSwipe.isRefreshing = false
                        render()
                    }
                }
                try {
                    while (true) {
                        render()
                        delay(REFRESH_MS)
                    }
                } finally {
                    stopWaze()
                }
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        // Ordinary devices leave the list and radar soon after they're out of range (LiveWindow).
        val bleMs = Prefs.liveBleSec(this) * 1000L
        val wifiMs = Prefs.liveWifiSec(this) * 1000L
        // Settings > Scanning: trusted (whitelisted) devices can be left out of the list and radar.
        val hideTrusted = Prefs.hideWhitelisted(this)
        val all = DeviceRegistry.snapshot(now).filter {
            com.rfsentinel.app.ui.LiveWindow.keep(it, now, bleMs, wifiMs) && !(hideTrusted && WhitelistCache.contains(it.mac))
        }
        val threshold = Prefs.alertThreshold(this)
        binding.wazeSwipe.isEnabled = com.rfsentinel.app.ui.WazeUi.on(this) && panes.listShown
        deviceCount = all.size
        // The banner and radar alert only for flagged devices still in range.
        val flagged = all.filter { com.rfsentinel.app.ui.LiveWindow.alerting(it, now) }
        flaggedCount = flagged.size
        updateBanner(flagged, threshold)
        updateStatus()

        // Live counts on the chips ("Trackers 2"); empty filters stay unlabelled.
        for ((f, chip) in filterChips) {
            val n = when (f) {
                com.rfsentinel.app.ui.DeviceFilter.CELLS -> liveCellCount(now)
                com.rfsentinel.app.ui.DeviceFilter.WAZE -> currentWaze(now).size
                com.rfsentinel.app.ui.DeviceFilter.RADIO -> if (ScanForegroundService.isRunning) com.rfsentinel.app.sdr.RadioLog.current(now).size else 0
                else -> all.count { f.matches(it) }
            }
            val label = if (f == com.rfsentinel.app.ui.DeviceFilter.ALL || n > 0) "${f.label} $n" else f.label
            if (chip.text != label) chip.text = label
        }
        val visible = all.filter { filter.matches(it) && matchesQuery(it) }
            .sortedWith(
                compareByDescending<DeviceRegistry.Snapshot> { it.best != null && !WhitelistCache.contains(it.mac) }
                    .thenByDescending { it.following }
                    .thenByDescending { it.best?.confidence ?: 0 }
                    .thenBy { bandOrder(ProximityUtil.band(it.rssi)) }
                    .thenBy { it.firstSeen }
            )

        // The map and radar frames take the top alert's colour.
        panes.update(flagged.maxByOrNull { it.best?.confidence ?: 0 }?.let { colorFor(it) })
        if (panes.radarShown) {
            binding.radarView.setBlips(visible.take(200).map { s ->
                val alert = com.rfsentinel.app.ui.LiveWindow.alerting(s, now)
                RadarView.Blip(
                    s.mac, s.rssi,
                    if (alert) colorFor(s) else binding.radarView.ordinaryColor(),
                    alert,
                    if (alert) s.best?.label else null
                )
            })
        }
        if (panes.listShown) {
            val cells = cellRows(now)
            val waze = wazeRows(now, threshold)
            val radio = radioRows(now, threshold)
            // Waze reports and radios on the air that meet the alert threshold flash at the very top;
            // the rest follow the devices.
            val (urgent, quiet) = waze.partition { it.flashing }
            val (radioLive, radioOld) = radio.partition { it.flashing }
            submitRows(radioLive + urgent + visible.map { toRow(it, now, threshold) } + radioOld + cells + quiet)
            shownCells = cells.size + waze.size + radio.size
        }
        binding.emptyText.visibility = if (visible.isEmpty() && (!panes.listShown || shownCells == 0)) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            all.isEmpty() && !ScanForegroundService.isRunning -> "Not scanning.\nTap Start scanning to begin."
            all.isEmpty() -> "Listening... no devices heard yet."
            filter == com.rfsentinel.app.ui.DeviceFilter.WAZE ->
                "No Waze reports in range.\nTurn on Waze police reports in Settings and keep scanning."
            filter == com.rfsentinel.app.ui.DeviceFilter.RADIO ->
                "No radio transmissions heard in the last 5 minutes.\nNeeds an RTL-SDR on USB and the Police radio category on in Settings."
            filter == com.rfsentinel.app.ui.DeviceFilter.CELLS ->
                "No cell towers yet - they're read every 15 s while scanning\n(needs the Fake cell tower category on in Settings)."
            else -> "No devices match this filter."
        }
    }

    /**
     * The cell towers the phone sees right now (serving first), listed after the devices
     * under the All filter. Read every 15 s while scanning; tap opens Tools > Cell towers.
     */
    /** The radio tag on ordinary rows, like the purple CELL tag on cell towers. */
    private fun radioTag(s: DeviceRegistry.Snapshot): String = when {
        Advert.Source.BLE in s.sources && Advert.Source.WIFI in s.sources -> "BLUETOOTH + WIFI"
        Advert.Source.WIFI in s.sources -> "WIFI"
        else -> "BLUETOOTH"
    }

    private fun radioColor(s: DeviceRegistry.Snapshot): Int =
        if (Advert.Source.BLE in s.sources) BLUETOOTH_TAG_COLOR else WIFI_TAG_COLOR

    private fun liveCellCount(now: Long): Int =
        if (ScanForegroundService.isRunning && now - com.rfsentinel.app.service.CellTowerStore.currentAt <= 60_000L)
            com.rfsentinel.app.service.CellTowerStore.current.size else 0

    private var shownCells = 0
    /** When the list was last pulled down to check Waze. */
    private var refreshAskedAt = 0L

    /**
     * The Waze reports inside the alert range (Settings > Waze), kept live by the checker: distance, direction
     * and score follow you as you drive. The map still shows the wider view range.
     */
    private fun currentWaze(now: Long): List<com.rfsentinel.app.online.WazePolice.Shown> {
        if (!ScanForegroundService.isRunning) return emptyList()
        val alertM = Prefs.wazeAlertM(this).toDouble()
        return com.rfsentinel.app.online.WazePolice.latest.filter { it.distanceM <= alertM }
    }

    /**
     * The Waze reports in range, nearest first, under the All and Waze filters. One that reaches the alert
     * threshold (and is not silenced for its type, nor behind you when only reports ahead count) is tinted,
     * flashes, and is pinned to the top.
     */
    private fun wazeRows(now: Long, threshold: Int): List<DeviceRow> {
        if (filter != com.rfsentinel.app.ui.DeviceFilter.ALL && filter != com.rfsentinel.app.ui.DeviceFilter.WAZE) return emptyList()
        val aheadOnly = Prefs.wazeAheadOnly(this)
        return currentWaze(now)
            .sortedBy { it.distanceM }
            .filter { query.isBlank() || it.hit.label.contains(query.trim(), ignoreCase = true) }
            .map { w ->
                val urgent = w.level != com.rfsentinel.app.online.WazePolice.Level.LOG &&
                    w.hit.confidence >= threshold && !(aheadOnly && w.where.isBehind)
                val ago = w.report.publishedMs?.let { "${((now - it) / 60_000).coerceAtLeast(0)} min ago" } ?: "just now"
                DeviceRow(
                    mac = WAZE_ROW + w.report.id,
                    title = w.hit.label,
                    subtitle = com.rfsentinel.app.online.WazePolice.relativeText(w.where, w.trend),
                    meta = "Reported $ago" + listOfNotNull(w.report.street, w.report.city).joinToString(", ").takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty() +
                        (if (w.report.thumbsUp > 0) " · ${w.report.thumbsUp} confirmed" else ""),
                    tag = "WAZE · ${w.hit.tier.label.lowercase()} ${w.hit.confidence}%",
                    tagColor = w.report.type.color,
                    highlight = if (urgent) w.report.type.color else null,
                    flashing = urgent,
                    bold = urgent
                )
            }
    }

    /**
     * RTL-SDR transmissions of the last few minutes (the radio log), under the All and Radio
     * filters: frequency, its name when known, signal and closer / farther trend. One on the air
     * right now at or above the alert threshold is tinted, flashes and is pinned to the top.
     */
    private fun radioRows(now: Long, threshold: Int): List<DeviceRow> {
        if ((filter != com.rfsentinel.app.ui.DeviceFilter.ALL && filter != com.rfsentinel.app.ui.DeviceFilter.RADIO) ||
            !ScanForegroundService.isRunning) return emptyList()
        val color = com.rfsentinel.app.ui.ThemeManager.ink(this, Category.RADIO.colorArgb)
        return com.rfsentinel.app.sdr.RadioLog.current(now)
            .filter { r -> query.isBlank() || listOfNotNull(r.mhz, r.name, r.bandLabel).any { it.contains(query.trim(), ignoreCase = true) } }
            .map { r ->
                val live = now - r.lastSeen <= RADIO_LIVE_MS
                val urgent = live && r.confidence >= threshold
                val ago = (now - r.lastSeen) / 1000
                val trend = when (r.trend) {
                    com.rfsentinel.app.sdr.RadioLog.Trend.CLOSER -> "getting closer ↑"
                    com.rfsentinel.app.sdr.RadioLog.Trend.FARTHER -> "moving away ↓"
                    else -> null
                }
                DeviceRow(
                    mac = RADIO_ROW + r.freqHz,
                    title = "Radio ${r.mhz} MHz" + (r.name?.let { " · $it" } ?: ""),
                    subtitle = listOfNotNull("${r.snrDb} dB above the noise (peak ${r.peakSnrDb})", trend, r.bandLabel).joinToString(" · "),
                    meta = (if (ago < 3) "On the air now" else "Last heard ${ago}s ago") +
                        " · first ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(r.firstSeen))}",
                    tag = "RADIO · ${r.confidence}%",
                    tagColor = color,
                    highlight = if (urgent) color else null,
                    flashing = urgent,
                    bold = live
                )
            }
    }

    private fun cellRows(now: Long): List<DeviceRow> {
        if ((filter != com.rfsentinel.app.ui.DeviceFilter.ALL && filter != com.rfsentinel.app.ui.DeviceFilter.CELLS) ||
            !ScanForegroundService.isRunning) return emptyList()
        val at = com.rfsentinel.app.service.CellTowerStore.currentAt
        if (now - at > 60_000L) return emptyList()
        val ageSec = (now - at) / 1000
        return com.rfsentinel.app.service.CellTowerStore.current
            .sortedWith(compareByDescending<com.rfsentinel.app.detect.CellAnalyzer.Cell> { it.registered }.thenByDescending { it.dbm ?: -999 })
            .filter { c ->
                query.isBlank() || listOfNotNull(c.operator, c.rat.label, c.cellId?.toString(), "${c.mcc}-${c.mnc}")
                    .any { it.contains(query.trim(), ignoreCase = true) }
            }
            .mapIndexed { i, c ->
                val area = if (c.rat == com.rfsentinel.app.detect.CellAnalyzer.Rat.LTE || c.rat == com.rfsentinel.app.detect.CellAnalyzer.Rat.NR) "TAC" else "LAC"
                DeviceRow(
                    mac = CELL_ROW + i + ":" + c.key,
                    title = "Cell tower · ${c.rat.label}" + (c.operator?.let { " · $it" } ?: ""),
                    subtitle = listOfNotNull(
                        if (c.mcc != null || c.mnc != null) "${c.mcc ?: "?"}-${c.mnc ?: "?"}" else null,
                        c.area?.let { "$area $it" }, c.cellId?.let { "Cell $it" }, c.pci?.let { "PCI $it" }
                    ).joinToString("  ·  ").ifEmpty { "No IDs reported" },
                    meta = (if (c.registered) "Serving your phone" else "Neighbour cell") +
                        (c.dbm?.let { " · $it dBm" } ?: "") + " · " + (if (ageSec < 2) "now" else "${ageSec}s ago"),
                    tag = if (c.registered) "CELL · serving" else "CELL",
                    tagColor = 0xFF5E35B1.toInt(),
                    highlight = null, flashing = false, bold = c.registered
                )
            }
    }

    private fun matchesQuery(s: DeviceRegistry.Snapshot): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return listOfNotNull(s.mac, s.name, s.vendor, s.deviceType, s.best?.label)
            .any { it.contains(q, ignoreCase = true) }
    }

    private fun colorFor(s: DeviceRegistry.Snapshot): Int {
        val best = s.best ?: return com.rfsentinel.app.ui.ThemeManager.ink(this, WHITELIST_COLOR)
        return com.rfsentinel.app.ui.ThemeManager.ink(this, if (best.tier == Tier.WEAK) WEAK_COLOR else best.category.colorArgb)
    }

    private fun toRow(s: DeviceRegistry.Snapshot, now: Long, threshold: Int): DeviceRow {
        val whitelisted = WhitelistCache.contains(s.mac)
        val best = s.best
        val alert = best != null && !whitelisted
        val ageSec = (now - s.lastSeen) / 1000
        val age = if (ageSec < 2) "now" else "${ageSec}s ago"
        val badges = buildList {
            if (s.following) add("FOLLOWING")
            if (Favorites.contains(s.mac)) add("★")
            if (s.isNew) add("NEW")
        }
        val tag = when {
            whitelisted -> "WHITELISTED"
            best != null -> (if (s.following) "FOLLOWING · " else "") +
                "${best.category.shortTag} · ${best.tier.label} ${best.confidence}%"
            else -> radioTag(s)
        }
        return DeviceRow(
            mac = s.mac,
            title = best?.label ?: s.name ?: s.deviceType,
            subtitle = s.mac + "  ·  " + (s.vendor ?: s.addressType.label),
            meta = listOf(
                if (best != null && s.name != null) "\"${s.name}\"" else s.deviceType,
                // Ordinary rows name the radio in their tag; flagged ones keep it here.
                if (tag == radioTag(s)) null else s.sources.joinToString("+") { if (it == Advert.Source.BLE) "BLE" else "WiFi" },
                "${ProximityUtil.band(s.rssi)} ${s.rssi} dBm ${DeviceIntel.formatDistance(s.distanceM)}",
                age
            ).filterNotNull().joinToString(" · ") + if (badges.isNotEmpty()) "  " + badges.joinToString(" ") else "",
            tag = tag,
            tagColor = when {
                whitelisted -> com.rfsentinel.app.ui.ThemeManager.ink(this, WHITELIST_COLOR)
                best == null -> com.rfsentinel.app.ui.ThemeManager.ink(this, radioColor(s))
                else -> colorFor(s)
            },
            highlight = if (alert) colorFor(s) else null,
            // Flashes only while still in range (like the beeps); a gone device keeps a steady tint.
            flashing = alert && com.rfsentinel.app.ui.LiveWindow.inRange(s, now) &&
                (s.following || (best!!.confidence >= threshold && best.category != Category.TRACKER)),
            bold = alert,
            heardByEsp = com.rfsentinel.app.esp.HeardBy.esp.recent(s.mac, now),
            heardByUsb = com.rfsentinel.app.esp.HeardBy.usb.recent(s.mac, now),
            heardByPhone = com.rfsentinel.app.esp.HeardBy.phone.recent(s.mac, now)
        )
    }

    private fun updateBanner(flagged: List<DeviceRegistry.Snapshot>, threshold: Int) {
        val banner = binding.threatBanner
        if (!ScanForegroundService.isRunning && flagged.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        val following = flagged.firstOrNull { it.following }
        val top = flagged.maxByOrNull { it.best!!.confidence }
        // A camera close by, a police aircraft or a Waze report, when it outranks the devices.
        val ambient = com.rfsentinel.app.online.AmbientThreats.top(this)
            ?.takeIf { following == null && it.score > (top?.best?.confidence ?: 0) }
        if (ambient != null) {
            banner.text = "⚠ ${ambient.label}"
            banner.background.mutate().setTint(when {
                ambient.score >= 85 -> 0xFFB3261E.toInt()
                ambient.score >= threshold -> 0xFFC8431A.toInt()
                else -> WEAK_COLOR
            })
            return
        }
        val (color, text) = when {
            following != null -> 0xFFB3261E.toInt() to "⚠ ${following.best!!.label} may be following you"
            top == null -> 0xFF2E7D32.toInt() to "✓ All clear - no flagged equipment nearby"
            top.best!!.tier == Tier.STRONG -> 0xFFB3261E.toInt() to "⚠ Strong match nearby: ${top.best!!.label}"
            top.best!!.confidence >= threshold -> 0xFFC8431A.toInt() to "Probable match nearby: ${top.best!!.label}"
            else -> WEAK_COLOR to "Weak match only (verify): ${top.best!!.label}"
        }
        banner.text = if (flagged.size > 1 && following == null) "$text  (+${flagged.size - 1} more)" else text
        banner.background.mutate().setTint(color)
    }

    private fun bandOrder(band: String) = when (band) {
        "Near" -> 0
        "Nearby" -> 1
        else -> 2
    }

    private fun requestPermissionsAndStart() {
        val toAsk = (Permissions.required() + Permissions.optional())
            .filterNot { Permissions.granted(this, it) }
        if (toAsk.isEmpty()) startScanning() else permissionLauncher.launch(toAsk.toTypedArray())
    }

    private fun startScanning() {
        Prefs.setStartedByCar(this, false) // started by hand: leaving the car must not stop it
        try {
            ScanForegroundService.start(this)
        } catch (e: Exception) {
            binding.statusText.text = "Could not start scanner: ${e.message}"
            return
        }
        binding.root.postDelayed({ updateStatus() }, 500)
    }

    private fun stopScanning() {
        Prefs.setStartedByCar(this, false) // a manual choice: leaving the car must not override it
        ScanForegroundService.stop(this)
        binding.root.postDelayed({ updateStatus() }, 300)
    }

    /** First launch: the setup wizard (theme, region, alerts, location, permissions). */
    private fun showOnboarding() {
        startActivity(Intent(this, com.rfsentinel.app.ui.SetupActivity::class.java))
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val wazeOn = com.rfsentinel.app.ui.WazeUi.on(this)
        menu.findItem(R.id.action_waze_now)?.isVisible = wazeOn
        menu.findItem(R.id.action_waze_status)?.isVisible = wazeOn
        val muted = Prefs.alertsMuted(this)
        menu.findItem(R.id.action_mute)?.apply {
            setIcon(if (muted) R.drawable.ic_car_volume_off else R.drawable.ic_car_volume)
            title = if (muted) "Unmute alerts" else "Mute alerts"
        }
        // The icons are white vectors; tint them to the theme's action-bar ink so they stay
        // visible on Paper and red-only in Night Drive.
        val ink = com.google.android.material.color.MaterialColors.getColor(
            supportActionBar?.themedContext ?: this, androidx.appcompat.R.attr.colorControlNormal, android.graphics.Color.WHITE
        )
        for (i in 0 until menu.size()) menu.getItem(i).icon?.mutate()?.setTint(ink)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        (menu.findItem(R.id.action_search)?.actionView as? SearchView)?.apply {
            queryHint = "Name, address, vendor, type..."
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean {
                    this@MainActivity.query = q.orEmpty()
                    render()
                    return true
                }
            })
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_waze_now -> { com.rfsentinel.app.ui.WazeUi.checkNow(this); true }
            R.id.action_waze_status -> { com.rfsentinel.app.ui.WazeUi.openStatus(this); true }
            R.id.action_match_log -> { startActivity(Intent(this, DetectionLogActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_probes -> { startActivity(Intent(this, com.rfsentinel.app.probes.ProbeListActivity::class.java)); true }
            R.id.action_export -> { Exporter.showExportMenu(this); true }
            R.id.action_traces -> { startActivity(Intent(this, com.rfsentinel.app.ui.TripsActivity::class.java)); true }
            R.id.action_history -> { startActivity(Intent(this, com.rfsentinel.app.ui.HistoryActivity::class.java)); true }
            R.id.action_mute -> {
                val muted = !Prefs.alertsMuted(this)
                Prefs.setAlertsMuted(this, muted)
                if (muted) com.rfsentinel.app.util.Voice.silence()
                invalidateOptionsMenu()
                android.widget.Toast.makeText(
                    this, if (muted) "Alert sound and voice muted" else "Alert sound on", android.widget.Toast.LENGTH_SHORT
                ).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
