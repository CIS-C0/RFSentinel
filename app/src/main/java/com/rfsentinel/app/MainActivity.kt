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
        private const val AIRCRAFT_ROW = "air:"
        // The speaker's long-press menu: after the snooze lengths (their indexes).
        private const val MUTE_ITEM = 1000
        private const val SOUND_ITEM = 1001
        /** A radio row flashes while its transmitter was heard this recently. */
        private const val RADIO_LIVE_MS = 15_000L
        /** Radio transmissions stay on the radar this long after they were last heard (the list keeps 5 minutes). */
        private const val RADIO_RADAR_MS = 60_000L
        private const val BLUETOOTH_TAG_COLOR = 0xFF1565C0.toInt()
        /** Location off: Android gives the app no Bluetooth or WiFi results. Tapping the warning opens the setting. */
        private const val LOCATION_OFF = "Location is off - no devices can be seen. Tap to turn it on"
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
    /** The devices the list shows right now (filter and search applied), for Save snapshot. */
    private var shownDevices: List<DeviceRegistry.Snapshot> = emptyList()
    private var flaggedCount = 0
    /** The visual style's status: warning chip and hardware strip (Settings > Appearance). */
    private val statusPanel by lazy {
        // Its ✕ on a restarted-scan note forgets the restart, so the text status and the car screen drop it too.
        com.rfsentinel.app.ui.StatusPanel(this).apply { onWarningHidden = { ScanForegroundService.forgetWatchdogRestart() } }
    }
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
            // Denied for good: Android no longer shows its prompt, so Start alone would do nothing.
            if (Permissions.missingRequired(this).any { !shouldShowRequestPermissionRationale(it) }) askForAppSettings()
        }
    }

    /** The only way left to grant a refused permission: the app's page in Android's settings. */
    private fun askForAppSettings() {
        val what = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            "Location (precise) and Nearby devices" else "Location (precise)"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Permissions needed to scan")
            .setMessage("RF Sentinel needs $what to scan, and Android won't ask again. " +
                "Open the app's settings, then Permissions, and allow them.")
            .setPositiveButton("Open app settings") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)))
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Styled themes with a banner: the title bar sits over the banner's top, so the banner takes less room.
        val tucked = hasThemeHeader()
        if (tucked) supportRequestWindowFeature(androidx.appcompat.app.AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR_OVERLAY)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        if (tucked) supportActionBar?.apply {
            // A soft dark fade keeps the title and icons readable over the art.
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD9000000.toInt(), 0x80000000.toInt(), 0x00000000)))
            elevation = 0f
        }
        if (tucked) {
            // The content is still laid out below the title bar: pull the banner up under it.
            val tv = android.util.TypedValue()
            val bar = if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true))
                android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics) else 0
            binding.root.clipToPadding = false
            (binding.themedHeader.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin = -bar
        }
        applyThemeHeader()

        adapter = DeviceAdapter(
            onClick = {
                if (it.mac.startsWith(CELL_ROW)) startActivity(Intent(this, com.rfsentinel.app.ui.CellTowersActivity::class.java))
                else if (it.mac.startsWith(RADIO_ROW)) startActivity(Intent(this, com.rfsentinel.app.ui.DetectionLogActivity::class.java))
                else if (it.mac.startsWith(AIRCRAFT_ROW)) {
                    val hex = it.mac.removePrefix(AIRCRAFT_ROW)
                    currentAircraft(System.currentTimeMillis()).firstOrNull { a -> a.plane.hex == hex }?.let { a ->
                        com.rfsentinel.app.ui.MapActivity.onlineIntent(this, AIRCRAFT_ROW + hex, a.plane.lat, a.plane.lon)?.let { i -> startActivity(i) }
                    }
                }
                else if (it.mac.startsWith(WAZE_ROW)) {
                    com.rfsentinel.app.online.WazePolice.latest.firstOrNull { w -> WAZE_ROW + w.report.id == it.mac }?.let { w ->
                        startActivity(Intent(this, com.rfsentinel.app.ui.MapActivity::class.java)
                            .putExtra(com.rfsentinel.app.ui.MapActivity.EXTRA_CENTER_LAT, w.report.lat)
                            .putExtra(com.rfsentinel.app.ui.MapActivity.EXTRA_CENTER_LON, w.report.lon))
                    }
                }
                else DeviceActions.openDetails(this, it.mac)
            },
            onLongPress = { if (listOf(CELL_ROW, WAZE_ROW, RADIO_ROW, AIRCRAFT_ROW).none { p -> it.mac.startsWith(p) }) DeviceActions.showQuickActions(this, it.mac) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        (binding.statusText.parent as android.view.ViewGroup).let { p ->
            p.addView(statusPanel.root, p.indexOfChild(binding.statusText) + 1)
        }
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
        binding.radarView.onBlipClick = {
            if (it.startsWith(RADIO_ROW)) startActivity(Intent(this, com.rfsentinel.app.ui.DetectionLogActivity::class.java))
            else DeviceActions.openDetails(this, it)
        }
        // The app closed while the map was starting last time: switch it to OpenGL, or leave it off the main screen (and say so).
        val mapRecovery = com.rfsentinel.app.ui.MapSafety.recover(this)
        panes = com.rfsentinel.app.ui.MainPanes(this, binding)
        if (mapRecovery != com.rfsentinel.app.ui.MapSafety.Recovery.NONE) binding.root.post { if (!isFinishing && !isDestroyed) com.rfsentinel.app.ui.MapSafetyUi.tell(this, mapRecovery) }

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
                menu.add(0, 5, 4, "Antenna check")
                menu.add(0, 6, 5, "Aircraft")
                menu.add(0, 7, 6, "Radio waterfall")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        7 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.WaterfallActivity::class.java))
                        6 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.AircraftActivity::class.java)
                            .putExtra("demo", demoMode))
                        5 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.AntennaCheckActivity::class.java)
                            .putExtra("demo", demoMode).putExtra("single", intent.getBooleanExtra("single", false)))
                        1 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.CellTowersActivity::class.java))
                        2 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.WifiAnalyzerActivity::class.java))
                        4 -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.GnssActivity::class.java).putExtra("demo", demoMode))
                        else -> startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.WifiAnalyzerActivity::class.java)
                            .putExtra(com.rfsentinel.app.ui.WifiAnalyzerActivity.EXTRA_SPECTRUM, true))
                    }
                    true
                }
            }.show()
        }
        // One filter for this screen and the full screen map: the last one picked on either.
        filter = com.rfsentinel.app.ui.DeviceFilter.parse(Prefs.mapFilter(this))
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
            // Made-up aircraft drifting around, for the list, the map and its Aircraft filter.
            lifecycleScope.launch {
                while (true) {
                    com.rfsentinel.app.online.PoliceAircraft.latest = com.rfsentinel.app.ui.DemoData.aircraft(anchor ?: (0.0 to 0.0))
                    com.rfsentinel.app.online.PoliceAircraft.latestAt = System.currentTimeMillis()
                    kotlinx.coroutines.delay(2_000L)
                }
            }
            // --ez demo_alert true: a made-up alert every 12 s, for the alert card on the full screen map.
            if (intent.getBooleanExtra("demo_alert", false)) lifecycleScope.launch {
                while (true) { kotlinx.coroutines.delay(12_000L); com.rfsentinel.app.ui.DemoData.publishSampleAlert(com.rfsentinel.app.ui.DemoData.fakeLocation?.let { it.latitude to it.longitude }) }
            }
        }
        applyViewMode()
        observeDevices()
        updateStatus()
        lifecycleScope.launch { Favorites.load(this@MainActivity); com.rfsentinel.app.data.DeviceNames.load(this@MainActivity) }
        // Only on a real fresh start - a theme change recreates this screen too.
        if (savedInstanceState == null && !Prefs.onboardingDone(this)) showOnboarding()

        if (savedInstanceState == null) com.rfsentinel.app.util.CrashLog.offerAfterCrash(this)
    }

    override fun onResume() {
        super.onResume()
        // Back from the full screen map: show the filter picked there.
        com.rfsentinel.app.ui.DeviceFilter.parse(Prefs.mapFilter(this)).let { f ->
            if (f != filter) filterChips[f]?.let { it.isChecked = true; scrollToChip(it) }
        }
        updateStatus()
        // Every time the app comes to the front (this screen can stay alive for days): Settings > Check for updates on app open.
        if (Prefs.onboardingDone(this)) com.rfsentinel.app.util.UpdateChecker.checkOnStartup(this)
    }


    /** True when [applyThemeHeader] shows a header: a styled theme's wordmark or a custom theme's banner. */
    private fun hasThemeHeader(): Boolean {
        val theme = com.rfsentinel.app.ui.ThemeManager.current(this)
        return com.rfsentinel.app.ui.ThemeManager.headerStyle(this) != null ||
            (theme == com.rfsentinel.app.ui.ThemeManager.AppTheme.CUSTOM && com.rfsentinel.app.ui.ThemeManager.hasBanner(this))
    }

    /** Styled themes: their own title, animated wordmark and the user's banner image. */
    private fun applyThemeHeader() {
        val theme = com.rfsentinel.app.ui.ThemeManager.current(this)
        com.rfsentinel.app.ui.ThemeManager.appTitle(this)?.let { title = it }
        val header = com.rfsentinel.app.ui.ThemeManager.headerStyle(this)
        val ownBanner = theme == com.rfsentinel.app.ui.ThemeManager.AppTheme.CUSTOM && com.rfsentinel.app.ui.ThemeManager.hasBanner(this)
        // A custom theme can have just a banner and no animated header.
        binding.themedHeader.visibility = if (header != null || ownBanner) View.VISIBLE else View.GONE
        binding.themeHeader.visibility = if (header != null) View.VISIBLE else View.GONE
        // The styled headers draw the app's name themselves: no second title over it in the title bar.
        supportActionBar?.setDisplayShowTitleEnabled(header == null)
        if (header == null && !ownBanner) return
        if (header != null) binding.themeHeader.style = header
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
        if (binding.emptyState.parent !== home) {
            (binding.emptyState.parent as? android.view.ViewGroup)?.removeView(binding.emptyState)
            home.addView(binding.emptyState, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER))
        }
    }

    /** Filter chips from [com.rfsentinel.app.ui.DeviceFilter] (same rules as the map), counts filled in by render(). */
    private fun setupFilterChips() {
        for (f in com.rfsentinel.app.ui.DeviceFilter.MAIN_ORDER) {
            val chip = com.google.android.material.chip.Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = f.label
                isCheckable = true
                // No invisible 48 dp touch frame around each chip: the row is 16 dp shorter.
                setEnsureMinTouchTargetSize(false)
                com.rfsentinel.app.ui.ChipStyle.apply(this, minTouchTarget = false)
            }
            filterChips[f] = chip
            binding.filterChips.addView(chip)
            if (f == filter) { chip.isChecked = true; scrollToChip(chip) }
        }
        binding.showAllButton.setOnClickListener {
            filterChips[com.rfsentinel.app.ui.DeviceFilter.ALL]?.let { it.isChecked = true; scrollToChip(it) }
        }
        binding.filterChips.setOnCheckedStateChangeListener { _, ids ->
            filter = filterChips.entries.firstOrNull { it.value.id == ids.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            Prefs.setMapFilter(this, filter.name)
            panes.setFilter(filter)
            render()
        }
    }

    /** Brings [chip] into view in the sideways-scrolling row (a filter picked on the map may be past the edge). */
    private fun scrollToChip(chip: com.google.android.material.chip.Chip) {
        val row = binding.filterChips.parent as? android.widget.HorizontalScrollView ?: return
        row.post { if (chip.right > row.scrollX + row.width || chip.left < row.scrollX) row.smoothScrollTo((chip.left - row.width / 4).coerceAtLeast(0), 0) }
    }

    /** The stock chip's side paddings, to put back in the list style. */
    private var chipPadding: FloatArray? = null

    /**
     * Visual style: the filter chips are tinted pills, shorter than the stock ones, [color] when set (the
     * flagged count in red). The list style keeps the outlined chips.
     */
    private fun styleFilterChip(chip: com.google.android.material.chip.Chip, visual: Boolean, color: Int?) {
        val key = if (visual) "v${color ?: 0}" else "list"
        val was = chip.getTag(R.id.visual_chip_color) as? String
        if (was == key) return
        chip.setTag(R.id.visual_chip_color, key)
        // Only the colour changed (the flagged count came or went): recolour, touch nothing that sizes the chip.
        if (visual && was?.startsWith("v") == true) { tintVisualChip(chip, color); return }
        val dp = resources.displayMetrics.density
        com.rfsentinel.app.ui.ChipStyle.apply(chip, minTouchTarget = false)
        val stock = chipPadding ?: floatArrayOf(chip.chipStartPadding, chip.textStartPadding, chip.textEndPadding, chip.chipEndPadding)
            .also { chipPadding = it }
        if (!visual) {
            chip.chipMinHeight = 32 * dp
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            chip.chipStartPadding = stock[0]; chip.textStartPadding = stock[1]; chip.textEndPadding = stock[2]; chip.chipEndPadding = stock[3]
            sameSize(chip)
            return
        }
        // Tighter sides, so All, Flagged, Bluetooth, WiFi and Cells with their counts fit a phone's width.
        chip.chipStartPadding = 2 * dp; chip.textStartPadding = 6 * dp; chip.textEndPadding = 6 * dp; chip.chipEndPadding = 2 * dp
        tintVisualChip(chip, color)
        chip.chipStrokeWidth = 1.2f * dp
        chip.chipMinHeight = 28 * dp
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        sameSize(chip)
    }

    /** The visual style's tinted pill in [color] (the theme highlight when null). */
    private fun tintVisualChip(chip: com.google.android.material.chip.Chip, color: Int?) {
        val (accent, onAccent) = com.rfsentinel.app.ui.ChipStyle.accent(this)
        val c = color ?: accent
        val checked = intArrayOf(android.R.attr.state_checked)
        val none = intArrayOf()
        chip.chipBackgroundColor = android.content.res.ColorStateList(arrayOf(checked, none),
            intArrayOf(c, com.rfsentinel.app.ui.VisualMode.tint(c, 0x26)))
        chip.chipStrokeColor = android.content.res.ColorStateList(arrayOf(checked, none), intArrayOf(c, com.rfsentinel.app.ui.VisualMode.tint(c, 0x99)))
        chip.setTextColor(android.content.res.ColorStateList(arrayOf(checked, none), intArrayOf(onAccent, c)))
    }

    /**
     * Every chip the same height, however often it was restyled: Flagged is restyled each time its colour changes,
     * and the touch frame switched on and off on the way left it a different size from the rest.
     */
    private fun sameSize(chip: com.google.android.material.chip.Chip) {
        chip.setEnsureMinTouchTargetSize(false) // no invisible 48 dp frame (see setupFilterChips)
        chip.minHeight = chip.chipMinHeight.toInt()
        chip.requestLayout()
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
        val visual = com.rfsentinel.app.ui.VisualMode.on(this, com.rfsentinel.app.ui.VisualMode.Screen.STATUS)
        binding.statusText.visibility = if (visual) View.GONE else View.VISIBLE
        statusPanel.root.visibility = if (visual) View.VISIBLE else View.GONE
        if (visual) {
            val now = System.currentTimeMillis()
            val locationOff = running && !locationOn() && !demoMode
            val warning = when {
                locationOff -> LOCATION_OFF
                running && !bluetoothOn() && !demoMode -> "Scanning WiFi only - turn on Bluetooth for BLE (picked up automatically)"
                else -> listOfNotNull(
                    com.rfsentinel.app.service.CellMonitor.lastAnomaly?.takeIf { now - it.first < 15 * 60_000L }?.second?.title,
                    ScanForegroundService.lastWatchdogRestart?.takeIf { now - it.first < 10 * 60_000L }?.let {
                        "${it.second} scan restarted " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it.first))
                    }
                ).joinToString(" · ").ifEmpty { null }
            }
            val hardware = if (!running) emptyList() else listOfNotNull(
                com.rfsentinel.app.esp.OuiSpyBle.status.takeIf { it.isNotBlank() }?.let { com.rfsentinel.app.ui.StatusPanel.Hardware(it) },
                com.rfsentinel.app.esp.EspBoards.status.takeIf { it.isNotBlank() }?.let { com.rfsentinel.app.ui.StatusPanel.Hardware(it) },
                com.rfsentinel.app.usb.UsbWifi.status.takeIf { it.isNotBlank() }?.let {
                    com.rfsentinel.app.ui.StatusPanel.Hardware(it, { com.rfsentinel.app.usb.MonitorFrames.frameCount.get() }, " frames/s")
                },
                com.rfsentinel.app.sdr.SdrRadio.status.takeIf { it.isNotBlank() }?.let {
                    com.rfsentinel.app.ui.StatusPanel.Hardware(it,
                        if (it.contains("ADS-B")) ({ com.rfsentinel.app.sdr.SdrRadio.adsbMessages }) else null, " msg/s")
                },
                com.rfsentinel.app.sdr.RadioReference.status.takeIf { it.isNotBlank() && com.rfsentinel.app.util.Prefs.radioReferenceOn(this) }
                    ?.let { com.rfsentinel.app.ui.StatusPanel.Hardware(it) },
                com.rfsentinel.app.link.PhoneLink.status.takeIf { it.isNotBlank() }?.let { com.rfsentinel.app.ui.StatusPanel.Hardware(it) },
                com.rfsentinel.app.link.MeshNode.status.takeIf { it.isNotBlank() }?.let { com.rfsentinel.app.ui.StatusPanel.Hardware(it) }
            )
            statusPanel.update(running, warning, hardware, if (locationOff) ::openLocationSettings else null)
            return
        }
        val locationOff = running && !locationOn() && !demoMode
        if (locationOff) binding.statusText.setOnClickListener { openLocationSettings() }
        else { binding.statusText.setOnClickListener(null); binding.statusText.isClickable = false }
        binding.statusText.text = when {
            !running -> "Idle - tap Start to listen for nearby devices"
            locationOff -> "⚠ $LOCATION_OFF"
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
                    ?.let { "\n$it" }.orEmpty() +
                com.rfsentinel.app.link.PhoneLink.status.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                com.rfsentinel.app.link.MeshNode.status.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        }
    }

    /**
     * "Start" / "Stop" with its icon when both fit; with less room the icon goes and the word stays. When
     * even the word would be cut off (large text, a narrow window, the view buttons beside it) the word goes
     * and the icon stays on its own: a play triangle to start, a stop square to stop (the colour and the
     * description say the same), centred in the button with no side padding so the icon is never clipped.
     */
    private var startIconPadding = -1
    private var startPadStart = 0
    private var startPadEnd = 0
    private var startMinWidth = 0
    private var startIconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_START

    private fun fitStartButton() {
        val b = binding.startStopButton
        val d = resources.displayMetrics.density
        val running = shownRunning == true
        val label = if (running) "Stop" else "Start"
        val icon = if (running) R.drawable.ic_car_stop else R.drawable.ic_car_play
        if (startIconPadding < 0) { // the button as the layout made it
            startIconPadding = b.iconPadding; startPadStart = b.paddingStart; startPadEnd = b.paddingEnd; startMinWidth = b.minWidth
            startIconGravity = b.iconGravity
        }
        fun withWord() {
            b.text = label; b.setIconResource(icon); b.iconPadding = startIconPadding; b.iconGravity = startIconGravity
            b.setPaddingRelative(startPadStart, b.paddingTop, startPadEnd, b.paddingBottom); b.minWidth = startMinWidth
        }
        if (b.width == 0) { withWord(); return }
        val room = b.width - startPadStart - startPadEnd
        val drawn = b.transformationMethod?.getTransformation(label, b)?.toString() ?: label
        // The measured width runs a little short of what the button really needs (letter spacing, its own insets): a
        // word that only just fits by the measure showed cut off ("ST"), so it must fit with some room to spare.
        val textW = b.paint.measureText(drawn) * 1.1f + 6 * d
        val iconW = (b.iconSize.takeIf { it > 0 } ?: (24 * d).toInt()) + startIconPadding
        if (textW + iconW <= room) withWord()
        else if (textW <= room) { withWord(); b.icon = null }
        else {
            // The icon alone, in the middle of the button: "text start" gravity centres the icon with its text, here an
            // invisible zero-width one (with no text at all the button keeps the icon at its start).
            b.text = "​"; b.setIconResource(icon); b.iconPadding = 0
            b.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            b.setPaddingRelative(startPadStart, b.paddingTop, startPadEnd, b.paddingBottom); b.minWidth = (48 * d).toInt()
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

    /** Where the pill is going (not where it is): a show during the hide's fade must still show it. */
    private var newAlertPillShown = false

    private fun showNewAlertPill(show: Boolean) {
        val pill = binding.newAlertPill
        if (show == newAlertPillShown) return
        newAlertPillShown = show
        pill.animate().cancel()
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

    /** Android hides Bluetooth and WiFi scan results from the app while Location is off: nothing shows up at all. */
    private fun locationOn(): Boolean =
        (getSystemService(LOCATION_SERVICE) as? android.location.LocationManager)
            ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } != false

    private fun openLocationSettings() {
        try { startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "Turn Location on in the phone's quick settings", android.widget.Toast.LENGTH_LONG).show()
        }
    }

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
        val externalMs = Prefs.liveExternalSec(this) * 1000L
        // Settings > Scanning: trusted (whitelisted) devices can be left out of the list and radar.
        val hideTrusted = Prefs.hideWhitelisted(this)
        val all = DeviceRegistry.snapshot(now).filter {
            com.rfsentinel.app.ui.LiveWindow.keep(it, now, bleMs, wifiMs, externalMs) && !(hideTrusted && WhitelistCache.contains(it.mac))
        }
        val threshold = Prefs.alertThreshold(this)
        binding.wazeSwipe.isEnabled = com.rfsentinel.app.ui.WazeUi.on(this) && panes.listShown
        deviceCount = all.size
        adapter.visual = com.rfsentinel.app.ui.VisualMode.on(this, com.rfsentinel.app.ui.VisualMode.Screen.DEVICE_LIST)
        // The banner and radar alert only for flagged devices still in range.
        val flagged = all.filter { com.rfsentinel.app.ui.LiveWindow.alerting(it, now) }
        flaggedCount = flagged.size
        updateBanner(flagged, threshold)
        updateStatus()

        // Live counts on the chips ("Trackers 2"); empty filters stay unlabelled.
        val visualChips = com.rfsentinel.app.ui.VisualMode.on(this, com.rfsentinel.app.ui.VisualMode.Screen.STATUS)
        for ((f, chip) in filterChips) {
            val n = when (f) {
                com.rfsentinel.app.ui.DeviceFilter.CELLS -> liveCellCount(now)
                com.rfsentinel.app.ui.DeviceFilter.WAZE -> currentWaze(now).size
                com.rfsentinel.app.ui.DeviceFilter.AIRCRAFT -> currentAircraft(now).size
                com.rfsentinel.app.ui.DeviceFilter.RADIO -> if (ScanForegroundService.isRunning) com.rfsentinel.app.sdr.RadioLog.current(now).size else 0
                else -> all.count { f.matches(it) }
            }
            val label = if (f == com.rfsentinel.app.ui.DeviceFilter.ALL || n > 0) "${f.label} $n" else f.label
            if (chip.text != label) chip.text = label
            styleFilterChip(chip, visualChips, if (f == com.rfsentinel.app.ui.DeviceFilter.FLAGGED && n > 0) 0xFFD0503C.toInt() else null)
        }
        val visible = all.filter { filter.matches(it) && matchesQuery(it) }
            .sortedWith(
                compareByDescending<DeviceRegistry.Snapshot> { it.best != null && !WhitelistCache.contains(it.mac) }
                    .thenByDescending { it.following }
                    .thenByDescending { it.best?.confidence ?: 0 }
                    .thenBy { bandOrder(ProximityUtil.band(it.rssi)) }
                    .thenBy { it.firstSeen }
            )
        shownDevices = visible

        refreshMuteButton()
        // The map and radar frames take the top alert's colour.
        panes.update(flagged.maxByOrNull { it.best?.confidence ?: 0 }?.let { colorFor(it) })
        var radioBlips = 0
        if (panes.radarShown) {
            val radio = radioBlips(now, threshold)
            radioBlips = radio.size
            binding.radarView.setBlips(visible.take(200).map { s ->
                val alert = com.rfsentinel.app.ui.LiveWindow.alerting(s, now)
                RadarView.Blip(
                    s.mac, s.rssi,
                    if (alert) colorFor(s) else binding.radarView.ordinaryColor(),
                    alert,
                    if (alert) s.best?.label else null
                )
            } + radio)
        }
        if (panes.listShown) {
            val cells = cellRows(now)
            val waze = wazeRows(now, threshold)
            val radio = radioRows(now, threshold)
            val air = aircraftRows(now, threshold)
            // Waze reports, radios on the air and aircraft that meet the alert threshold flash at the very top;
            // the rest follow the devices.
            val (urgent, quiet) = waze.partition { it.flashing }
            val (radioLive, radioOld) = radio.partition { it.flashing }
            val (airUrgent, airQuiet) = air.partition { it.flashing }
            submitRows(airUrgent + radioLive + urgent + visible.map { toRow(it, now, threshold) } + airQuiet + radioOld + cells + quiet)
            shownCells = cells.size + waze.size + radio.size + air.size
        }
        binding.emptyState.visibility = if (visible.isEmpty() && (!panes.listShown || shownCells == 0) &&
            (!panes.radarShown || radioBlips == 0)) View.VISIBLE else View.GONE
        // A filter that hides everything heard: one tap back to the full list.
        binding.showAllButton.visibility =
            if (filter != com.rfsentinel.app.ui.DeviceFilter.ALL && all.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            all.isEmpty() && !ScanForegroundService.isRunning -> "Not scanning.\nTap Start scanning to begin."
            all.isEmpty() -> "Listening... no devices heard yet."
            filter == com.rfsentinel.app.ui.DeviceFilter.WAZE ->
                "No Waze reports in range.\nTurn on Waze police reports in Settings and keep scanning."
            filter == com.rfsentinel.app.ui.DeviceFilter.RADIO ->
                "No radio transmissions heard in the last 5 minutes.\nNeeds an RTL-SDR on USB and the Police radio category on in Settings."
            filter == com.rfsentinel.app.ui.DeviceFilter.AIRCRAFT ->
                "No aircraft yet.\nThey come from an RTL-SDR on USB set to ADS-B, or from the online feeds when that's on in Settings."
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
        return com.rfsentinel.app.online.WazePolice.forList(com.rfsentinel.app.online.WazePolice.latest, alertM,
            Prefs.wazeAheadOnly(this), Prefs.wazeHideOffWay(this))
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
            // With "only ahead" on, the ones on your way come first; the others stay listed, quiet, below them.
            .sortedWith(compareBy<com.rfsentinel.app.online.WazePolice.Shown> { aheadOnly && !it.onMyWay }.thenBy { it.distanceM })
            .filter { query.isBlank() || it.hit.label.contains(query.trim(), ignoreCase = true) }
            .map { w ->
                val off = aheadOnly && !w.onMyWay // behind you, another street, off your route
                val urgent = w.level != com.rfsentinel.app.online.WazePolice.Level.LOG &&
                    w.hit.confidence >= threshold && !off
                val ago = w.report.publishedMs?.let { "${((now - it) / 60_000).coerceAtLeast(0)} min ago" } ?: "just now"
                DeviceRow(
                    mac = WAZE_ROW + w.report.id,
                    title = w.hit.label,
                    subtitle = com.rfsentinel.app.online.WazePolice.relativeText(w.where, w.trend) + if (off) " · not on your way" else "",
                    meta = "Reported $ago" + listOfNotNull(w.report.street, w.report.city).joinToString(", ").takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty() +
                        (if (w.report.thumbsUp > 0) " · ${w.report.thumbsUp} confirmed" else ""),
                    tag = "WAZE · ${w.hit.tier.label.lowercase()} ${w.hit.confidence}%",
                    tagColor = w.report.type.color,
                    highlight = if (urgent) w.report.type.color else null,
                    flashing = urgent,
                    bold = urgent,
                    confidence = w.hit.confidence
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
                    bold = live,
                    confidence = r.confidence
                )
            }
    }

    /**
     * RTL-SDR / HackRF transmissions of the last minute on the radar, under the All and Radio filters: closer to the
     * centre the stronger the signal. One on the air right now at or above the alert threshold pulses, with its frequency.
     */
    private fun radioBlips(now: Long, threshold: Int): List<RadarView.Blip> {
        if ((filter != com.rfsentinel.app.ui.DeviceFilter.ALL && filter != com.rfsentinel.app.ui.DeviceFilter.RADIO) ||
            !ScanForegroundService.isRunning) return emptyList()
        val color = com.rfsentinel.app.ui.ThemeManager.ink(this, Category.RADIO.colorArgb)
        return com.rfsentinel.app.sdr.RadioLog.current(now)
            .filter { now - it.lastSeen <= RADIO_RADAR_MS }
            .map { r ->
                val alert = now - r.lastSeen <= RADIO_LIVE_MS && r.confidence >= threshold
                // Signal above the noise as a stand-in RSSI: 40 dB lands in Near, 30 dB in Nearby (as the alert wording).
                RadarView.Blip(RADIO_ROW + r.freqHz, (-115 + r.snrDb * 3 / 2).coerceIn(-100, -30), color, alert,
                    if (alert) "${r.mhz} MHz" + (r.name?.let { " $it" } ?: "") else null)
            }
    }

    /** The aircraft heard right now (RTL-SDR ADS-B and the online feeds). */
    private fun currentAircraft(now: Long): List<com.rfsentinel.app.online.PoliceAircraft.Seen> = when {
        !ScanForegroundService.isRunning -> emptyList()
        else -> com.rfsentinel.app.online.PoliceAircraft.all(now)
    }

    /**
     * Aircraft as rows: under All only the law-enforcement ones (so airliners don't fill the list), under
     * Aircraft every one, police first, then nearest. A police one at or above the alert threshold is
     * tinted, flashes and is pinned to the top. Tap: the aircraft on the map.
     */
    private fun aircraftRows(now: Long, threshold: Int): List<DeviceRow> {
        if (filter != com.rfsentinel.app.ui.DeviceFilter.ALL && filter != com.rfsentinel.app.ui.DeviceFilter.AIRCRAFT) return emptyList()
        val all = filter == com.rfsentinel.app.ui.DeviceFilter.AIRCRAFT
        val me = (if (demoMode) com.rfsentinel.app.ui.DemoData.fakeLocation else ScanForegroundService.lastFix)?.let { it.latitude to it.longitude }
        val color = com.rfsentinel.app.ui.ThemeManager.ink(this, Category.AIRCRAFT.colorArgb)
        val air = com.rfsentinel.app.ui.AircraftActivity
        return currentAircraft(now)
            .filter { all || it.hit != null }
            .filter { s -> query.isBlank() || listOfNotNull(air.name(s.plane), s.plane.type, s.plane.owner, s.plane.registration, s.hit?.label)
                .any { it.contains(query.trim(), ignoreCase = true) } }
            .map { s -> s to me?.let { com.rfsentinel.app.online.PoliceAircraft.distance(s.plane, it.first, it.second) } }
            .sortedWith(compareByDescending<Pair<com.rfsentinel.app.online.PoliceAircraft.Seen, Double?>> { it.first.hit != null }
                .thenBy { it.second ?: Double.MAX_VALUE })
            .map { (s, d) ->
                val p = s.plane
                val hit = s.hit
                val urgent = hit != null && hit.confidence >= threshold
                val where = d?.let { m -> com.rfsentinel.app.util.Units.distance(m) +
                    me?.let { " " + air.compass(air.bearing(it.first, it.second, p.lat, p.lon)) }.orEmpty() }
                DeviceRow(
                    mac = AIRCRAFT_ROW + p.hex,
                    title = "Aircraft " + air.name(p) + (hit?.let { " · ${it.label}" } ?: ""),
                    subtitle = listOfNotNull(where, air.altitude(p), p.speedKt?.let { String.format(java.util.Locale.US, "%.0f kt", it) })
                        .joinToString(" · "),
                    meta = listOfNotNull(p.type, p.owner, p.registration?.takeIf { it != air.name(p) }).joinToString(" · ").ifEmpty { p.hex.uppercase() },
                    tag = if (hit != null) "AIRCRAFT · ${hit.confidence}%" else "AIRCRAFT",
                    tagColor = color,
                    highlight = if (urgent) color else null,
                    flashing = urgent,
                    bold = hit != null,
                    confidence = hit?.confidence
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
                    highlight = null, flashing = false, bold = c.registered,
                    rssi = c.dbm, barBoost = 10
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
        val alias = com.rfsentinel.app.data.DeviceNames.of(s.mac)
        val lookalike = com.rfsentinel.app.data.DeviceNames.lookalike(s.mac, DeviceRegistry.fingerprintOf(s.mac))
        return DeviceRow(
            mac = s.mac,
            title = alias ?: best?.label ?: s.name ?: s.deviceType,
            subtitle = s.mac + "  ·  " + (s.vendor ?: s.addressType.label),
            meta = listOf(
                // Under your own name, what it's heard as comes first.
                if (alias != null) best?.label ?: s.name?.let { "\"$it\"" } ?: s.deviceType
                else if (best != null && s.name != null) "\"${s.name}\"" else s.deviceType,
                lookalike?.let { "like your \"$it\"" },
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
            heardByPhone = com.rfsentinel.app.esp.HeardBy.phone.recent(s.mac, now),
            heardByPeer = com.rfsentinel.app.esp.HeardBy.peer.via(s.mac, now),
            rssi = s.rssi,
            confidence = best?.confidence?.takeIf { !whitelisted }
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
            slimBanner(false)
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
        slimBanner(top == null && following == null)
    }

    /** Visual style: "All clear" is a slim bar, so the map and list get the room; an alert gets the full size. */
    private fun slimBanner(allClear: Boolean) {
        val slim = allClear && com.rfsentinel.app.ui.VisualMode.on(this, com.rfsentinel.app.ui.VisualMode.Screen.STATUS)
        val banner = binding.threatBanner
        if (banner.getTag(R.id.visual_chip_color) == slim) return
        banner.setTag(R.id.visual_chip_color, slim)
        val dp = resources.displayMetrics.density
        val v = ((if (slim) 2 else 6) * dp).toInt()
        banner.setPadding((12 * dp).toInt(), v, (12 * dp).toInt(), v)
        banner.textSize = if (slim) 12f else 14f
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
        val left = snoozeMinutesLeft()
        menu.findItem(R.id.action_mute)?.apply {
            setIcon(when { muted -> R.drawable.ic_car_volume_off; left > 0 -> R.drawable.ic_car_snooze; else -> R.drawable.ic_car_volume })
            title = when {
                muted -> "Alerts muted - tap for sound"
                left > 0 -> "Alerts snoozed, $left min left - tap for sound"
                else -> "Snooze alerts ${Prefs.snoozeMinutes(this@MainActivity)} min"
            }
        }
        // The icons are white vectors; tint them to the theme's action-bar ink so they stay
        // visible on Paper and red-only in Night Drive.
        val ink = com.google.android.material.color.MaterialColors.getColor(
            supportActionBar?.themedContext ?: this, androidx.appcompat.R.attr.colorControlNormal, android.graphics.Color.WHITE
        )
        for (i in 0 until menu.size()) menu.getItem(i).icon?.mutate()?.setTint(ink)
        // Snoozed: the minutes left on the speaker, like a radar detector's mute.
        menu.findItem(R.id.action_mute)?.let { item ->
            if (left > 0 && !muted) item.icon?.let { item.icon = MinutesBadge(it, left, ink) }
        }
        // Long-press: the other lengths, mute until turned back on, sound on. (After the toolbar has laid the button out.)
        binding.root.post { findViewById<View>(R.id.action_mute)?.setOnLongClickListener { muteMenu(it); true } }
        return super.onPrepareOptionsMenu(menu)
    }

    /** Whole minutes of snooze left, rounded up; 0 when not snoozed. */
    private fun snoozeMinutesLeft(now: Long = System.currentTimeMillis()): Long {
        val until = Prefs.alertsSnoozedUntil(this)
        return if (until > now) (until - now + 59_999) / 60_000 else 0
    }

    /** What the speaker shows (muted, minutes snoozed, sound on): the menu is redrawn when it changes, wherever it was changed from. */
    private var muteShown: Pair<Boolean, Long>? = null
    private fun refreshMuteButton() {
        val now = Prefs.alertsMuted(this) to snoozeMinutesLeft()
        if (now != muteShown) { muteShown = now; invalidateOptionsMenu() }
    }

    private fun soundOn() {
        Prefs.setAlertsMuted(this, false)
        Prefs.setAlertsSnoozedUntil(this, 0L)
        invalidateOptionsMenu()
        android.widget.Toast.makeText(this, "Alert sound on", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun snoozeFor(minutes: Int) {
        Prefs.setAlertsMuted(this, false)
        com.rfsentinel.app.receiver.AlertActionReceiver.snooze(this, minutes = minutes)
        invalidateOptionsMenu()
        android.widget.Toast.makeText(this, "Alerts snoozed for $minutes min · long-press for more", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun muteMenu(anchor: View) {
        val silenced = Prefs.alertsSilenced(this)
        val popup = android.widget.PopupMenu(this, anchor)
        Prefs.SNOOZE_CHOICES.forEachIndexed { i, m -> popup.menu.add(0, i, i, "Snooze $m min") }
        popup.menu.add(0, MUTE_ITEM, 100, "Mute until I turn it back on")
        if (silenced) popup.menu.add(0, SOUND_ITEM, 101, "Sound on")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MUTE_ITEM -> {
                    Prefs.setAlertsMuted(this, true)
                    Prefs.setAlertsSnoozedUntil(this, 0L)
                    com.rfsentinel.app.util.Voice.silence()
                    invalidateOptionsMenu()
                    android.widget.Toast.makeText(this, "Alert sound and voice muted", android.widget.Toast.LENGTH_SHORT).show()
                }
                SOUND_ITEM -> soundOn()
                else -> snoozeFor(Prefs.SNOOZE_CHOICES[item.itemId])
            }
            true
        }
        popup.show()
    }

    /** The speaker icon with the snooze's minutes left in its corner. */
    private class MinutesBadge(private val icon: android.graphics.drawable.Drawable, minutes: Long, color: Int) : android.graphics.drawable.Drawable() {
        private val text = if (minutes > 99) "99+" else minutes.toString()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.RIGHT
        }
        override fun getIntrinsicWidth() = icon.intrinsicWidth
        override fun getIntrinsicHeight() = icon.intrinsicHeight
        override fun onBoundsChange(bounds: android.graphics.Rect) { icon.bounds = bounds }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            // The icon a little smaller, up and left, so the number fits bottom right without covering it.
            canvas.save()
            canvas.scale(0.78f, 0.78f, b.left.toFloat(), b.top.toFloat())
            icon.draw(canvas)
            canvas.restore()
            paint.textSize = b.height() * 0.48f
            canvas.drawText(text, b.right.toFloat(), b.bottom.toFloat() - b.height() * 0.02f, paint)
        }
        override fun setAlpha(alpha: Int) { icon.alpha = alpha; paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { icon.colorFilter = cf; paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
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

    /** Keeps the device list as shown now (filter and search applied) under a name, to look at later (History > Snapshots). */
    private fun saveSnapshot() {
        val devices = shownDevices
        if (devices.isEmpty()) {
            android.widget.Toast.makeText(this, "The list is empty: nothing to save", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            setText(com.rfsentinel.app.data.Snapshots.defaultName()); selectAll()
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Save snapshot")
            .setMessage("Keeps the ${devices.size} device${if (devices.size == 1) "" else "s"} in the list now, to look at later in History > Snapshots.")
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(input) })
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { com.rfsentinel.app.data.Snapshots.defaultName() }
                val app = applicationContext
                lifecycleScope.launch {
                    val id = runCatching { com.rfsentinel.app.data.Snapshots.save(app, name, devices) }.getOrNull()
                    if (id == null) {
                        android.widget.Toast.makeText(this@MainActivity, "Couldn't save the snapshot", android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    com.google.android.material.snackbar.Snackbar.make(binding.root, "Snapshot saved", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .setAction("View") {
                            startActivity(Intent(this@MainActivity, com.rfsentinel.app.ui.SnapshotActivity::class.java)
                                .putExtra(com.rfsentinel.app.ui.SnapshotActivity.EXTRA_TRIP_ID, id))
                        }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_waze_now -> { com.rfsentinel.app.ui.WazeUi.checkNow(this); true }
            R.id.action_waze_status -> { com.rfsentinel.app.ui.WazeUi.openStatus(this); true }
            R.id.action_device_history -> { startActivity(Intent(this, com.rfsentinel.app.ui.DeviceHistoryActivity::class.java)); true }
            R.id.action_match_log -> { startActivity(Intent(this, DetectionLogActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_probes -> { startActivity(Intent(this, com.rfsentinel.app.probes.ProbeListActivity::class.java)); true }
            R.id.action_export -> { Exporter.showExportMenu(this); true }
            R.id.action_traces -> { startActivity(Intent(this, com.rfsentinel.app.ui.TripsActivity::class.java)); true }
            R.id.action_snapshots -> {
                startActivity(Intent(this, com.rfsentinel.app.ui.TripsActivity::class.java)
                    .putExtra(com.rfsentinel.app.ui.TripsActivity.EXTRA_SECTION, com.rfsentinel.app.data.TripEntity.KIND_SNAPSHOT))
                true
            }
            R.id.action_save_snapshot -> { saveSnapshot(); true }
            R.id.action_history -> { startActivity(Intent(this, com.rfsentinel.app.ui.HistoryActivity::class.java)); true }
            // Like a radar detector's mute: a tap snoozes for the set length; muted or snoozed, a tap brings the sound back.
            R.id.action_mute -> {
                if (Prefs.alertsSilenced(this)) soundOn() else snoozeFor(Prefs.snoozeMinutes(this))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
