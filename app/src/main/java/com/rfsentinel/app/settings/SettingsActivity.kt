package com.rfsentinel.app.settings

import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import com.rfsentinel.app.ui.ThemeManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rfsentinel.app.R
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.databinding.ActivitySettingsBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        /**
         * Scroll position to restore after a theme change restyles this screen
         * (possibly twice: ours + AppCompat's night-mode switch).
         */
        private var resumeScroll: Int? = null
    }
    private lateinit var binding: ActivitySettingsBinding
    private var stopObservingPrefetch: (() -> Unit)? = null
    private var stopObservingDeflock: (() -> Unit)? = null
    private val categorySwitches = mutableMapOf<Category, SwitchMaterial>()

    private val bannerPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = ThemeManager.setBanner(this, uri)
        Toast.makeText(
            this,
            if (ok) "Banner set - it shows on the main screen in themes with an animated header" else "That file isn't a readable image (max 8 MB)",
            Toast.LENGTH_LONG
        ).show()
        updateBannerButtons()
    }

    private val connectPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) chooseCarDevices()
        else Toast.makeText(this, "Needed to recognise your car's Bluetooth", Toast.LENGTH_LONG).show()
    }

    private fun hasConnectPermission() = android.os.Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Lists paired Bluetooth devices so the user can tick their car(s). */
    @android.annotation.SuppressLint("MissingPermission") // checked by hasConnectPermission()
    private fun chooseCarDevices() {
        if (!hasConnectPermission()) {
            connectPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val paired = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
            .sortedBy { runCatching { it.name }.getOrNull() ?: it.address }
        if (paired.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices - pair your car first", Toast.LENGTH_LONG).show(); return
        }
        val chosen = Prefs.carDevices(this).toMutableSet()
        val labels = paired.map { d ->
            val car = d.bluetoothClass?.deviceClass.let {
                it == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                    it == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
            }
            (runCatching { d.name }.getOrNull() ?: d.address) + if (car) "  (car audio)" else ""
        }.toTypedArray()
        val checked = paired.map { it.address in chosen }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Which Bluetooth is your car?")
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                if (on) chosen += paired[i].address else chosen -= paired[i].address
            }
            .setPositiveButton("Save") { _, _ ->
                Prefs.setCarDevices(this, chosen)
                updateCarDevicesText()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateCarDevicesText() {
        val chosen = Prefs.carDevices(this)
        val names = if (hasConnectPermission()) {
            val bonded = runCatching {
                (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.bondedDevices
            }.getOrNull().orEmpty()
            chosen.map { a -> bonded.firstOrNull { it.address == a }?.let { runCatching { it.name }.getOrNull() } ?: a }
        } else chosen.toList()
        binding.carDevicesText.text = if (names.isEmpty())
            "No car chosen yet. Android Auto also starts it when the app opens on the car screen."
        else "Car: " + names.joinToString() + ". Android Auto also starts it."
    }

    /** Ignored trackers (long-press a tracker > Ignore this tracker) and a paused follow warning. */
    private fun updateTrackerIgnoreText() {
        val n = com.rfsentinel.app.data.TrackerMutes.count(this)
        val until = Prefs.trackerFollowPausedUntil(this)
        val paused = until > System.currentTimeMillis()
        val parts = buildList {
            add(if (n == 0) "No ignored trackers (long-press a tracker to ignore your own tag)" else "$n ignored tracker${if (n == 1) "" else "s"}")
            if (paused) add("tracker follow warnings paused until " +
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until)))
        }
        binding.trackerIgnoreText.text = parts.joinToString("; ")
        binding.trackerIgnoreButton.visibility = if (n > 0 || paused) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Known cameras silenced from the map (tap a camera > Ignore alerts). */
    private fun updateIgnoredCamerasText() {
        val n = com.rfsentinel.app.alpr.IgnoredCameras.count(this)
        binding.ignoredCamerasText.text = if (n == 0) "No ignored cameras (tap a camera on the map to turn off its alerts)"
            else "$n camera${if (n == 1) "" else "s"} with alerts turned off (shown faded on the map)"
        binding.ignoredCamerasButton.visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** ESP32 boards on USB (OUI-Spy / GhostESP) and an OUI-SPY board over Bluetooth. */
    /** Speed, voice and a test button for spoken alerts; changes apply right away. */
    private fun setupVoiceControls() {
        val voice = com.rfsentinel.app.util.Voice
        val rates = mapOf(R.id.voiceRateSlow to 0.8f, R.id.voiceRateNormal to 1.0f, R.id.voiceRateFast to 1.25f)
        val current = Prefs.voiceRate(this)
        binding.voiceRateGroup.check(rates.minByOrNull { kotlin.math.abs(it.value - current) }!!.key)
        binding.voiceRateGroup.setOnCheckedChangeListener { _, id -> rates[id]?.let { Prefs.setVoiceRate(this, it) } }
        binding.voiceTestButton.setOnClickListener { com.rfsentinel.app.util.AlertPlayer.testVoice(this) }

        fun label(v: android.speech.tts.Voice, i: Int) =
            "${v.locale.getDisplayCountry(java.util.Locale.ENGLISH).ifEmpty { "English" }} · voice ${i + 1}" +
                (if (v.isNetworkConnectionRequired) " · online" else "") +
                (if (v.quality >= android.speech.tts.Voice.QUALITY_HIGH) " · high quality" else "")
        fun showVoice() {
            val list = voice.voices
            val chosen = Prefs.voiceName(this)?.let { n -> list.indexOfFirst { it.name == n } }?.takeIf { it >= 0 }
            binding.voiceText.text = when {
                list.isEmpty() -> "Uses the phone's speech engine (Google's when installed), in English."
                chosen != null -> "Voice: ${label(list[chosen], chosen)}"
                else -> "Voice: automatic (${label(list[0], 0)})"
            }
        }
        voice.warmUp(this) { runOnUiThread { if (!isDestroyed) showVoice() } }
        showVoice()
        binding.voicePickButton.setOnClickListener {
            val list = voice.voices
            if (list.isEmpty()) {
                Toast.makeText(this, "The speech engine is still starting - try again in a moment", Toast.LENGTH_SHORT).show()
                voice.warmUp(this); return@setOnClickListener
            }
            val names = listOf("Automatic (best English voice)") + list.mapIndexed { i, v -> label(v, i) }
            val sel = Prefs.voiceName(this)?.let { n -> list.indexOfFirst { it.name == n } + 1 } ?: 0
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Voice for spoken alerts")
                .setSingleChoiceItems(names.toTypedArray(), sel) { _, which ->
                    // Each pick is spoken right away so you can compare.
                    Prefs.setVoiceName(this, if (which == 0) null else list[which - 1].name)
                    voice.voiceChanged(this)
                    com.rfsentinel.app.util.AlertPlayer.testVoice(this)
                    showVoice()
                }
                .setPositiveButton("Done", null)
                .setNeutralButton("Speech engine settings") { _, _ ->
                    runCatching { startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
                }
                .show()
        }
    }

    private fun updateEspStatus() {
        val s = com.rfsentinel.app.esp.EspBoards.status
        binding.espStatusText.text = "ESP32 on USB (OUI-Spy or GhostESP): " +
            s.ifBlank { "plug one in with an OTG cable while scanning to add its detections" }
        binding.usbWifiText.text = "USB WiFi adapter in monitor mode (RTL8811AU / 8821AU, e.g. ALFA AWUS036ACS): " +
            com.rfsentinel.app.usb.UsbWifi.status.ifBlank { "plug one in with an OTG cable while scanning - longer range, and it hears devices connected to networks" }
        val board = Prefs.ouiSpyBoard(this)
        binding.ouiSpyText.text = if (board == null)
            "OUI-SPY over Bluetooth (App-Controlled firmware): not paired"
        else "OUI-SPY board $board: " + com.rfsentinel.app.esp.OuiSpyBle.status.ifBlank { "connects while scanning" }
        binding.ouiSpyButton.text = if (board == null) "Pair OUI-SPY board (Bluetooth)" else "Change or forget OUI-SPY board"
    }

    private val ouiSpyConnectPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) pairOuiSpy() }

    /**
     * Picks the OUI-SPY board among nearby devices RF Sentinel hears (they
     * advertise as "OUI-SPY-xxxx"); scanning must be running to see it.
     */
    private fun pairOuiSpy() {
        if (!com.rfsentinel.app.esp.OuiSpyBle.canConnect(this)) {
            ouiSpyConnectPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val boards = com.rfsentinel.app.service.DeviceRegistry.snapshot()
            .filter { it.name?.startsWith(com.rfsentinel.app.esp.OuiSpyBleProtocol.NAME_PREFIX) == true }
            .sortedByDescending { it.rssi }
        val current = Prefs.ouiSpyBoard(this)
        val labels = boards.map { "${it.name}  (${it.mac}, ${it.rssi} dBm)" }.toMutableList()
        if (current != null) labels += "Forget the paired board"
        if (labels.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No OUI-SPY board heard")
                .setMessage("Power the board (Bluetooth / App-Controlled firmware) and start scanning, " +
                    "then come back here: it shows up as \"OUI-SPY-xxxx\".")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Which OUI-SPY board?")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < boards.size) {
                    Prefs.setOuiSpyBoard(this, boards[i].mac)
                    Toast.makeText(this, "Paired - RF Sentinel connects to it while scanning", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setOuiSpyBoard(this, null)
                    com.rfsentinel.app.esp.OuiSpyBle.stop()
                }
                updateEspStatus()
                // Apply to a running scan right away.
                if (com.rfsentinel.app.service.ScanForegroundService.isRunning) {
                    com.rfsentinel.app.service.ScanForegroundService.start(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBannerButtons() {
        binding.bannerClearButton.isEnabled = ThemeManager.hasBanner(this)
    }

    override fun onDestroy() {
        com.rfsentinel.app.esp.EspBoards.onStatusChanged = null
        com.rfsentinel.app.esp.OuiSpyBle.onStatusChanged = null
        stopObservingPrefetch?.invoke()
        stopObservingDeflock?.invoke()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Appearance: applied immediately (the screen restyles itself).
        val current = ThemeManager.current(this)
        ThemeManager.AppTheme.entries.forEach { t ->
            val unavailable = t == ThemeManager.AppTheme.MATERIAL_YOU && !ThemeManager.isDynamicColorAvailable
            binding.themeGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = t.title + "  \u2014  " + t.description +
                    if (unavailable) " (this phone: standard Material 3 colours)" else ""
                isChecked = t == current
                setOnCheckedChangeListener { _, checked ->
                    if (checked && t != ThemeManager.current(this@SettingsActivity)) {
                        resumeScroll = binding.root.scrollY
                        ThemeManager.set(this@SettingsActivity, t)
                        recreate()
                    }
                }
            })
        }

        resumeScroll?.let { y ->
            binding.root.post {
                if (isFinishing || isDestroyed) return@post
                binding.root.scrollTo(0, y)
                resumeScroll = null
            }
        }

        binding.runSetupButton.setOnClickListener {
            startActivity(android.content.Intent(this, com.rfsentinel.app.ui.SetupActivity::class.java))
            finish()
        }
        binding.bannerPickButton.setOnClickListener { bannerPicker.launch("image/*") }
        binding.bannerClearButton.setOnClickListener {
            ThemeManager.clearBanner(this)
            updateBannerButtons()
            Toast.makeText(this, "Banner removed", Toast.LENGTH_SHORT).show()
        }
        updateBannerButtons()

        // Scanning
        binding.bleSwitch.isChecked = Prefs.bleEnabled(this)
        binding.wifiSwitch.isChecked = Prefs.wifiEnabled(this)
        binding.scanModeGroup.check(
            when (Prefs.bleScanMode(this)) {
                ScanSettings.SCAN_MODE_BALANCED -> R.id.scanModeBalanced
                ScanSettings.SCAN_MODE_LOW_POWER -> R.id.scanModeLowPower
                else -> R.id.scanModeLowLatency
            }
        )
        binding.intervalInput.setText((Prefs.scanIntervalMs(this) / 1000).toString())
        binding.wifiThrottleButton.setOnClickListener { openWifiThrottleSetting() }
        binding.disable2gButton.setOnClickListener {
            val opened = runCatching { startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)) }.isSuccess ||
                runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.isSuccess
            Toast.makeText(
                this,
                if (opened) "Look for \"Allow 2G\" and turn it off (on some phones: SIMs > your SIM)"
                else "Open Settings > Network > SIMs and turn off \"Allow 2G\"",
                Toast.LENGTH_LONG
            ).show()
        }
        binding.cellSecurityButton.setOnClickListener {
            // The Cellular security page (Android 15+); else the general security page.
            val opened = runCatching { startActivity(Intent("android.settings.CELLULAR_NETWORK_SECURITY")) }.isSuccess ||
                runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }.isSuccess
            Toast.makeText(
                this,
                if (opened) "Look for Cellular security > Network notifications (Android 15+ only)"
                else "Open Settings > Security & privacy > More security & privacy > Cellular security",
                Toast.LENGTH_LONG
            ).show()
        }
        updateWifiThrottleHint()
        binding.bootSwitch.isChecked = Prefs.autoStartOnBoot(this)
        binding.carAutoSwitch.isChecked = Prefs.carAutoStart(this)
        binding.carAutoSwitch.setOnCheckedChangeListener { _, on ->
            if (on && Prefs.carDevices(this).isEmpty()) chooseCarDevices()
        }
        binding.carDevicesButton.setOnClickListener { chooseCarDevices() }
        updateCarDevicesText()
        binding.batteryButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Open Android Settings > Apps > RF Sentinel > Battery", Toast.LENGTH_LONG).show() }
        }

        // Categories
        for (c in Category.entries) {
            val sw = SwitchMaterial(this).apply {
                text = c.title
                isChecked = Prefs.categoryEnabled(this@SettingsActivity, c)
            }
            binding.categoryContainer.addView(sw)
            categorySwitches[c] = sw
        }
        val enabledPresets = OuiWatchlist.getEnabledPresets(this)
        binding.presetGlobal.isChecked = "global" in enabledPresets
        binding.presetCanada.isChecked = "canada" in enabledPresets
        binding.presetUs.isChecked = "us" in enabledPresets

        // Alerts
        val threshold = Prefs.alertThreshold(this)
        binding.thresholdGroup.check(
            when {
                threshold >= 80 -> R.id.thresholdStrong
                threshold >= 50 -> R.id.thresholdMedium
                else -> R.id.thresholdWeak
            }
        )
        binding.soundSwitch.isChecked = Prefs.soundEnabled(this)
        binding.vibrateSwitch.isChecked = Prefs.vibrateEnabled(this)
        binding.voiceSwitch.isChecked = Prefs.voiceEnabled(this)
        setupVoiceControls()
        binding.discreetSwitch.isChecked = Prefs.discreetMode(this)
        binding.bubbleSwitch.isChecked = Prefs.threatBubble(this) && Settings.canDrawOverlays(this)
        binding.bubbleSwitch.setOnCheckedChangeListener { sw, on ->
            if (on && !Settings.canDrawOverlays(this)) {
                sw.isChecked = false
                AlertDialog.Builder(this)
                    .setTitle("Allow the floating bubble")
                    .setMessage("Android needs the \"Display over other apps\" permission for the bubble. " +
                        "Turn it on for RF Sentinel on the next screen, then come back and switch the bubble on.")
                    .setPositiveButton("Open settings") { _, _ ->
                        runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        binding.dedupeInput.setText((Prefs.dedupeWindowMs(this) / 60000).toString())

        // Location
        binding.followSwitch.isChecked = Prefs.followerAlerts(this)
        updateTrackerIgnoreText()
        updateEspStatus()
        com.rfsentinel.app.esp.EspBoards.onStatusChanged = { runOnUiThread { updateEspStatus() } }
        com.rfsentinel.app.esp.OuiSpyBle.onStatusChanged = { runOnUiThread { updateEspStatus() } }
        binding.ouiSpyButton.setOnClickListener { pairOuiSpy() }
        binding.usbWifiLogButton.setOnClickListener {
            val log = com.rfsentinel.app.usb.UsbWifi.logText().ifBlank { "No USB WiFi adapter activity yet." }
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "RF Sentinel USB WiFi adapter log")
                    .putExtra(android.content.Intent.EXTRA_TEXT, log), "Share adapter log"))
        }
        binding.trackerIgnoreButton.setOnClickListener {
            com.rfsentinel.app.data.TrackerMutes.clear(this)
            Prefs.setTrackerFollowPausedUntil(this, 0L)
            updateTrackerIgnoreText()
            Toast.makeText(this, "Tracker warnings back to normal", Toast.LENGTH_SHORT).show()
        }
        binding.followMinutesInput.setText(Prefs.followMinMinutes(this).toString())
        binding.followMetersInput.setText(Prefs.followMinMeters(this).toString())
        binding.gpsSwitch.isChecked = Prefs.gpsTaggingEnabled(this)
        binding.autoRecordSwitch.isChecked = Prefs.autoRecordTrace(this)
        binding.ouiSpyRelaySwitch.isChecked = Prefs.ouiSpyRelayAll(this)
        binding.screenModeGroup.check(when (Prefs.screenMode(this)) {
            Prefs.ScreenMode.ALWAYS_ON -> R.id.screenAlwaysOn
            Prefs.ScreenMode.ON_WHILE_CHARGING -> R.id.screenOnCharging
            Prefs.ScreenMode.NORMAL -> R.id.screenNormal
        })
        binding.knownAlprSwitch.isChecked = Prefs.knownAlprAlerts(this)
        binding.cellChangeSwitch.isChecked = Prefs.cellChangeAlerts(this)
        binding.speedCameraSwitch.isChecked = Prefs.speedCameraAlerts(this)
        binding.autoCamerasSwitch.isChecked = Prefs.autoCameras(this)
        fun showRadius(km: Int) {
            binding.cameraRadiusText.text = "Download radius: $km km" +
                if (km > 100) " (larger areas take longer and use more data)" else ""
            binding.prefetchCamerasButton.text = "Download cameras around me (~$km km)"
        }
        Prefs.cameraRadiusKm(this).let { km -> binding.cameraRadiusSlider.value = km.toFloat(); showRadius(km) }
        binding.cameraRadiusSlider.setLabelFormatter { "${it.toInt()} km" }
        binding.cameraRadiusSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setCameraRadiusKm(this, value.toInt())
            showRadius(value.toInt())
        }
        binding.prefetchCamerasButton.setOnClickListener { com.rfsentinel.app.alpr.CameraPrefetch.start(this) }
        updateIgnoredCamerasText()
        binding.ignoredCamerasButton.setOnClickListener {
            com.rfsentinel.app.alpr.IgnoredCameras.clear(this)
            updateIgnoredCamerasText()
            Toast.makeText(this, "Every camera alerts again", Toast.LENGTH_SHORT).show()
        }
        stopObservingPrefetch = com.rfsentinel.app.alpr.CameraPrefetch.observe { s ->
            val located = com.rfsentinel.app.alpr.CameraPrefetch.canRun(this)
            binding.prefetchCamerasButton.isEnabled = located &&
                s !is com.rfsentinel.app.alpr.CameraPrefetch.State.Locating && s !is com.rfsentinel.app.alpr.CameraPrefetch.State.Downloading
            binding.prefetchCamerasText.text = com.rfsentinel.app.ui.cameraPrefetchText(s, located)
        }
        binding.deflockButton.setOnClickListener { com.rfsentinel.app.alpr.DeflockBulk.start(this) }
        stopObservingDeflock = com.rfsentinel.app.alpr.DeflockBulk.observe { s ->
            binding.deflockButton.isEnabled = !com.rfsentinel.app.alpr.DeflockBulk.isRunning
            val last = Prefs.deflockUpdated(this)
            binding.deflockText.text = when (s) {
                is com.rfsentinel.app.alpr.DeflockBulk.State.Downloading ->
                    "Downloading · ${s.detail.ifEmpty { "${s.done}/${s.total} regions" }} · ${s.found} plate cameras so far"
                is com.rfsentinel.app.alpr.DeflockBulk.State.CheckingExtras ->
                    "${s.cameras} plate cameras saved · checking OpenStreetMap for more (up to 45 s)..."
                is com.rfsentinel.app.alpr.DeflockBulk.State.Done ->
                    "${s.cameras} plate cameras saved - they work offline and refresh weekly on Wi-Fi."
                is com.rfsentinel.app.alpr.DeflockBulk.State.Failed -> s.reason
                else -> if (last > 0) "Last downloaded " +
                    android.text.format.DateUtils.getRelativeTimeSpanString(last) + "; refreshes weekly on Wi-Fi."
                else "Every plate reader mapped in OpenStreetMap, from DeFlock's hourly snapshot (cdn.deflock.me sees only the download, not your position)."
            }
        }

        // Data
        binding.retentionInput.setText(Prefs.retentionDays(this).toString())
        binding.exportAllButton.setOnClickListener { com.rfsentinel.app.util.Exporter.showExportMenu(this) }
        binding.forgetHistoryButton.setOnClickListener {
            confirm("Forget device history?", "New/returning status, detect counts and the cell towers remembered for the fake-cell checks start over. Favorites are kept.") {
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@SettingsActivity).knownDeviceDao().clearNonFavorites()
                    com.rfsentinel.app.service.CellMonitor.forget(this@SettingsActivity)
                    com.rfsentinel.app.service.CellTowerStore.forget(this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "Device history cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.clearLogButton.setOnClickListener {
            confirm("Clear match history?", "Deletes every logged match. Export first if you need a copy.") {
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@SettingsActivity).detectionDao().clearAll()
                    Toast.makeText(this@SettingsActivity, "Match history cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.saveButton.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        // The user may be coming back from Developer options.
        updateWifiThrottleHint()
    }

    private fun updateWifiThrottleHint() {
        val throttled = wifiScanThrottled()
        binding.wifiThrottleText.text = if (throttled)
            "Android limits apps to 4 WiFi scans per 2 minutes, so values under 30 s are wasted. " +
                "To scan faster, turn off \"Wi-Fi scan throttling\" in Developer options."
        else
            "Wi-Fi scan throttling is off: intervals down to 5 s work, at some battery cost."
        binding.wifiThrottleButton.visibility = if (throttled) View.VISIBLE else View.GONE
    }

    /**
     * Opens Developer options, where "Wi-Fi scan throttling" lives (Networking
     * section). If they aren't unlocked yet, opens About phone instead and says how.
     */
    private fun openWifiThrottleSetting() {
        val devEnabled = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        }.getOrDefault(false)
        if (devEnabled && runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }.isSuccess
        ) {
            Toast.makeText(this, "Scroll to Networking and turn off \"Wi-Fi scan throttling\"", Toast.LENGTH_LONG).show()
            return
        }
        // Explain first, then open About phone: a dialog shown behind it would go unseen.
        AlertDialog.Builder(this)
            .setTitle("Unlock Developer options first")
            .setMessage(
                "1. In About phone, tap \"Build number\" 7 times (enter your PIN if asked).\n" +
                    "2. Come back here and tap \"Open Developer options\" again.\n" +
                    "3. Under Networking, turn off \"Wi-Fi scan throttling\"."
            )
            .setPositiveButton("Open About phone") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                    .onFailure { Toast.makeText(this, "Open Settings → About phone", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Android's WiFi scan throttle (Developer options), on unless the user disabled it. */
    private fun wifiScanThrottled(): Boolean =
        runCatching { Settings.Global.getInt(contentResolver, "wifi_scan_throttle_enabled", 1) != 0 }.getOrDefault(true)

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Clear") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        Prefs.setBleEnabled(this, binding.bleSwitch.isChecked)
        Prefs.setWifiEnabled(this, binding.wifiSwitch.isChecked)
        Prefs.setBleScanMode(
            this,
            when (binding.scanModeGroup.checkedRadioButtonId) {
                R.id.scanModeBalanced -> ScanSettings.SCAN_MODE_BALANCED
                R.id.scanModeLowPower -> ScanSettings.SCAN_MODE_LOW_POWER
                else -> ScanSettings.SCAN_MODE_LOW_LATENCY
            }
        )
        val intervalSec = binding.intervalInput.text.toString().toLongOrNull() ?: 30L
        Prefs.setScanIntervalMs(this, intervalSec.coerceAtLeast(5) * 1000L)
        Prefs.setAutoStartOnBoot(this, binding.bootSwitch.isChecked)
        Prefs.setCarAutoStart(this, binding.carAutoSwitch.isChecked)

        categorySwitches.forEach { (c, sw) -> Prefs.setCategoryEnabled(this, c, sw.isChecked) }
        val presets = mutableSetOf<String>()
        if (binding.presetGlobal.isChecked) presets.add("global")
        if (binding.presetCanada.isChecked) presets.add("canada")
        if (binding.presetUs.isChecked) presets.add("us")
        OuiWatchlist.setEnabledPresets(this, presets)

        Prefs.setAlertThreshold(
            this,
            when (binding.thresholdGroup.checkedRadioButtonId) {
                R.id.thresholdWeak -> 0
                R.id.thresholdStrong -> 80
                else -> 50
            }
        )
        Prefs.setSoundEnabled(this, binding.soundSwitch.isChecked)
        Prefs.setVibrateEnabled(this, binding.vibrateSwitch.isChecked)
        Prefs.setVoiceEnabled(this, binding.voiceSwitch.isChecked)
        Prefs.setDiscreetMode(this, binding.discreetSwitch.isChecked)
        Prefs.setThreatBubble(this, binding.bubbleSwitch.isChecked)
        if (!binding.bubbleSwitch.isChecked) com.rfsentinel.app.ui.ThreatBubble.hide(this)
        val dedupeMin = binding.dedupeInput.text.toString().toLongOrNull() ?: 5L
        Prefs.setDedupeWindowMs(this, dedupeMin.coerceAtLeast(1) * 60000L)

        Prefs.setFollowerAlerts(this, binding.followSwitch.isChecked)
        Prefs.setFollowMinMinutes(this, (binding.followMinutesInput.text.toString().toIntOrNull() ?: 10).coerceIn(2, 240))
        Prefs.setFollowMinMeters(this, (binding.followMetersInput.text.toString().toIntOrNull() ?: 800).coerceIn(100, 50_000))
        Prefs.setGpsTaggingEnabled(this, binding.gpsSwitch.isChecked)
        Prefs.setAutoRecordTrace(this, binding.autoRecordSwitch.isChecked)
        Prefs.setOuiSpyRelayAll(this, binding.ouiSpyRelaySwitch.isChecked)
        Prefs.setScreenMode(this, when (binding.screenModeGroup.checkedRadioButtonId) {
            R.id.screenAlwaysOn -> Prefs.ScreenMode.ALWAYS_ON
            R.id.screenNormal -> Prefs.ScreenMode.NORMAL
            else -> Prefs.ScreenMode.ON_WHILE_CHARGING
        })
        com.rfsentinel.app.ui.ScreenAwake.apply(this)
        Prefs.setKnownAlprAlerts(this, binding.knownAlprSwitch.isChecked)
        Prefs.setCellChangeAlerts(this, binding.cellChangeSwitch.isChecked)
        Prefs.setSpeedCameraAlerts(this, binding.speedCameraSwitch.isChecked)
        Prefs.setAutoCameras(this, binding.autoCamerasSwitch.isChecked)

        Prefs.setRetentionDays(this, (binding.retentionInput.text.toString().toIntOrNull() ?: 90).coerceAtLeast(0))

        // Re-deliver a start command so a running scanner picks up the changes.
        if (ScanForegroundService.isRunning) ScanForegroundService.start(this)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
