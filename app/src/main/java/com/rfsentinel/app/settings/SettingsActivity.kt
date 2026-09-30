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
    private lateinit var binding: ActivitySettingsBinding
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

    private fun updateBannerButtons() {
        binding.bannerClearButton.isEnabled = ThemeManager.hasBanner(this)
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
                        ThemeManager.set(this@SettingsActivity, t)
                        recreate()
                    }
                }
            })
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
        updateWifiThrottleHint()
        binding.bootSwitch.isChecked = Prefs.autoStartOnBoot(this)
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
        binding.discreetSwitch.isChecked = Prefs.discreetMode(this)
        binding.dedupeInput.setText((Prefs.dedupeWindowMs(this) / 60000).toString())

        // Location
        binding.followSwitch.isChecked = Prefs.followerAlerts(this)
        binding.followMinutesInput.setText(Prefs.followMinMinutes(this).toString())
        binding.followMetersInput.setText(Prefs.followMinMeters(this).toString())
        binding.gpsSwitch.isChecked = Prefs.gpsTaggingEnabled(this)
        binding.autoRecordSwitch.isChecked = Prefs.autoRecordTrace(this)
        binding.knownAlprSwitch.isChecked = Prefs.knownAlprAlerts(this)

        // Data
        binding.retentionInput.setText(Prefs.retentionDays(this).toString())
        binding.exportAllButton.setOnClickListener { com.rfsentinel.app.util.Exporter.showExportMenu(this) }
        binding.forgetHistoryButton.setOnClickListener {
            confirm("Forget device history?", "New/returning status and detect counts start over. Favorites are kept.") {
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@SettingsActivity).knownDeviceDao().clearNonFavorites()
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
        val dedupeMin = binding.dedupeInput.text.toString().toLongOrNull() ?: 5L
        Prefs.setDedupeWindowMs(this, dedupeMin.coerceAtLeast(1) * 60000L)

        Prefs.setFollowerAlerts(this, binding.followSwitch.isChecked)
        Prefs.setFollowMinMinutes(this, (binding.followMinutesInput.text.toString().toIntOrNull() ?: 10).coerceIn(2, 240))
        Prefs.setFollowMinMeters(this, (binding.followMetersInput.text.toString().toIntOrNull() ?: 800).coerceIn(100, 50_000))
        Prefs.setGpsTaggingEnabled(this, binding.gpsSwitch.isChecked)
        Prefs.setAutoRecordTrace(this, binding.autoRecordSwitch.isChecked)
        Prefs.setKnownAlprAlerts(this, binding.knownAlprSwitch.isChecked)

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
