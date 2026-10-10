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
        /** Opens Settings on one section (expanded and scrolled to): e.g. [SECTION_MAP] from the map. */
        const val EXTRA_SECTION = "open_section"
        const val SECTION_MAP = "map"
        const val WAZE_WARNING_SHORT = "Use at your own risk. Reports come from third-party services RF Sentinel doesn't run or endorse " +
            "(OpenWeb Ninja with your own key, or Waze itself); your account, your responsibility."
        const val WAZE_WARNING = "Waze police reports are read through OpenWeb Ninja's Waze API with your own API key.\n\n" +
            "• Use this feature at your own risk.\n" +
            "• OpenWeb Ninja and Waze are third-party services. RF Sentinel isn't affiliated with them, doesn't endorse them, " +
            "and does not grant you any right to use them or their data: you're responsible for following their terms and your local laws.\n" +
            "• Each request sends a box of about 4 km around your position to OpenWeb Ninja, and may cost you money on your plan.\n" +
            "• Reports are unverified crowd reports and can be wrong or out of date.\n\n" +
            "It stays off unless you enable it, and you can turn it off at any time."

        const val WAZE_DIRECT_WARNING = "Direct reads Waze police reports from Waze itself, using the Waze app's own protocol.\n\n" +
            "• Use this feature at your own risk.\n" +
            "• RF Sentinel registers an anonymous Waze account on this phone (stored encrypted) and presents itself to Waze as the Waze app. " +
            "Each update sends Waze, a Google service, your IP address and your position rounded to a 1 km grid (about 500 m off at most), as often as you set below (every minute by default).\n" +
            "• Waze isn't affiliated with RF Sentinel and may change or block this at any time; you're responsible for following their terms and your local laws.\n" +
            "• Reports are unverified crowd reports and can be wrong or out of date. Only police reports are used.\n\n" +
            "It stays off unless you enable it, and you can switch back to OpenWeb Ninja or turn it off at any time."

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
    private var airTagSwitch: SwitchMaterial? = null
    private var wazeKeyInput: android.widget.EditText? = null
    private var aircraftStatusText: android.widget.TextView? = null
    private var wazeStatusText: android.widget.TextView? = null

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
        binding.carDevicesText.text = "Car: " + names.joinToString()
        binding.carDevicesText.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Ignored trackers (long-press a tracker > Ignore this tracker) and a paused follow warning. */
    private fun updateTrackerIgnoreText() {
        val n = com.rfsentinel.app.data.TrackerMutes.count(this)
        val until = Prefs.trackerFollowPausedUntil(this)
        val paused = until > System.currentTimeMillis()
        val parts = buildList {
            if (n > 0) add("$n ignored tracker${if (n == 1) "" else "s"}")
            if (paused) add("follow warnings paused until " +
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until)))
        }
        binding.trackerIgnoreText.text = parts.joinToString("; ")
        binding.trackerIgnoreText.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        binding.trackerIgnoreButton.visibility = if (n > 0 || paused) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Known cameras silenced from the map (tap a camera > Ignore alerts). */
    private fun updateIgnoredCamerasText() {
        val n = com.rfsentinel.app.alpr.IgnoredCameras.count(this)
        binding.ignoredCamerasText.text = "$n camera${if (n == 1) "" else "s"} with alerts turned off (faded on the map)"
        binding.ignoredCamerasText.visibility = if (n > 0) View.VISIBLE else View.GONE
        binding.ignoredCamerasButton.visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** ESP32 boards on USB (OUI-Spy / GhostESP / Marauder) and an OUI-SPY board over Bluetooth. */
    /** Speed, voice and a test button for spoken alerts; changes apply right away. */
    private fun setupVoiceControls() {
        val voice = com.rfsentinel.app.util.Voice
        val rates = mapOf(R.id.voiceRateSlow to 0.8f, R.id.voiceRateNormal to 1.0f, R.id.voiceRateFast to 1.25f)
        val current = Prefs.voiceRate(this)
        binding.voiceRateGroup.check(rates.minByOrNull { kotlin.math.abs(it.value - current) }!!.key)
        binding.voiceRateGroup.setOnCheckedChangeListener { _, id -> rates[id]?.let { Prefs.setVoiceRate(this, it) } }
        // Louder than the phone's volume alone allows: music in the car often drowns the voice.
        fun boostLabel(db: Int) = "Voice loudness: " + if (db == 0) "normal" else "+$db dB"
        binding.voiceBoostSlider.value = Prefs.voiceBoostDb(this).toFloat()
        binding.voiceBoostLabel.text = boostLabel(Prefs.voiceBoostDb(this))
        binding.voiceBoostSlider.setLabelFormatter { if (it == 0f) "normal" else "+${it.toInt()} dB" }
        binding.voiceBoostSlider.addOnChangeListener { _, v, fromUser ->
            binding.voiceBoostLabel.text = boostLabel(v.toInt())
            if (fromUser) Prefs.setVoiceBoostDb(this, v.toInt())
        }
        binding.voiceBoostSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                com.rfsentinel.app.util.AlertPlayer.testVoice(this@SettingsActivity, binding.shortVoiceSwitch.isChecked)
            }
        })
        binding.voicePauseMusicSwitch.isChecked = Prefs.voicePausesMusic(this)
        binding.voicePauseMusicSwitch.setOnCheckedChangeListener { _, on -> Prefs.setVoicePausesMusic(this, on) }
        // Tests what is on screen (Settings save when you leave), so the short switch counts right away.
        binding.voiceTestButton.setOnClickListener {
            com.rfsentinel.app.util.AlertPlayer.testVoice(this, binding.shortVoiceSwitch.isChecked)
        }

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
                    com.rfsentinel.app.util.AlertPlayer.testVoice(this, binding.shortVoiceSwitch.isChecked)
                    showVoice()
                }
                .setPositiveButton("Done", null)
                .setNeutralButton("Speech engine settings") { _, _ ->
                    runCatching { startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) }
                }
                .show()
        }
    }

    /**
     * The USB WiFi adapter log as a .txt file to send (email, messages, Drive...): app and
     * phone, the USB devices plugged in, and the driver log - for testers without adb.
     */
    private fun exportUsbWifiLog() {
        lifecycleScope.launch {
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
                    val file = java.io.File(dir, "rfsentinel-usb-wifi-log-${com.rfsentinel.app.util.Exporter.stamp()}.txt")
                    file.writeText(com.rfsentinel.app.usb.UsbWifi.report(this@SettingsActivity))
                    androidx.core.content.FileProvider.getUriForFile(this@SettingsActivity, "$packageName.fileprovider", file)
                }.getOrNull()
            }
            if (uri == null) { Toast.makeText(this@SettingsActivity, "Couldn't create the log file", Toast.LENGTH_SHORT).show(); return@launch }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "RF Sentinel USB WiFi adapter log")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export adapter log"))
        }
    }

    /** Starts or stops the .pcap recording of what the USB WiFi adapter / V2X board hears; stopping offers to share it. */
    private fun togglePcap() {
        val rec = com.rfsentinel.app.usb.PcapRecorder
        if (rec.active) {
            rec.stop()
            updatePcap()
            sharePcap()
            return
        }
        if (!com.rfsentinel.app.service.ScanForegroundService.isRunning) {
            Toast.makeText(this, "Start scanning with the adapter or V2X board plugged in, then record", Toast.LENGTH_LONG).show()
            return
        }
        val dir = java.io.File(cacheDir, "exports")
        // Only the newest recording is kept: they get big.
        dir.listFiles { f -> f.name.endsWith(".pcap") }?.forEach { it.delete() }
        val file = java.io.File(dir, "rfsentinel-${com.rfsentinel.app.util.Exporter.stamp()}.pcap")
        if (!rec.start(file)) { Toast.makeText(this, "Couldn't create the recording file", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Recording - frames are saved as they're heard; it stops when scanning does", Toast.LENGTH_LONG).show()
        updatePcap()
    }

    private fun sharePcap() {
        val file = com.rfsentinel.app.usb.PcapRecorder.file?.takeIf { it.exists() && it.length() > 24 } ?: run {
            Toast.makeText(this, "Nothing was recorded (no frames heard)", Toast.LENGTH_SHORT).show(); return
        }
        val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.tcpdump.pcap")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "RF Sentinel capture ${file.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share .pcap recording"))
    }

    /** The recording's size and frame count, refreshed every second while it runs. */
    private fun updatePcap() {
        if (isFinishing || isDestroyed) return
        val rec = com.rfsentinel.app.usb.PcapRecorder
        val file = rec.file?.takeIf { it.exists() }
        val mb = String.format(java.util.Locale.US, "%.1f MB", rec.bytes / 1_048_576.0)
        binding.pcapText.text = when {
            rec.active -> "● Recording · ${rec.frames} frames · $mb"
            file != null -> "Last recording: ${rec.frames} frames · $mb" + (rec.stoppedBecause?.let { " (stopped: $it)" } ?: "")
            else -> "Record what the USB WiFi adapter or V2X board hears, for Wireshark"
        }
        binding.pcapButton.text = if (rec.active) "Stop and share" else "Record frames (.pcap)"
        binding.pcapShareButton.visibility = if (!rec.active && file != null) View.VISIBLE else View.GONE
        binding.pcapText.removeCallbacks(pcapTicker)
        if (rec.active) binding.pcapText.postDelayed(pcapTicker, 1_000)
    }
    private val pcapTicker = Runnable { updatePcap() }

    private fun updateEspStatus() {
        val s = com.rfsentinel.app.esp.EspBoards.status
        binding.espStatusText.text = s.ifBlank { "ESP32 board on USB: not connected" }
        binding.usbWifiText.text = com.rfsentinel.app.usb.UsbWifi.status.ifBlank { "USB WiFi adapter: not connected" }
        binding.sdrStatusText.text = com.rfsentinel.app.sdr.SdrRadio.status.ifBlank { "RTL-SDR: not connected" }
        val board = Prefs.ouiSpyBoard(this)
        binding.ouiSpyText.text = if (board == null) "OUI-SPY over Bluetooth: not paired"
        else com.rfsentinel.app.esp.OuiSpyBle.status.ifBlank { "OUI-SPY $board: connects while scanning" }
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

    /** A foldable settings section: its header, its content and what the ⓘ button explains. */
    private class Section(val key: String, val header: android.widget.TextView, val body: View, val info: String)

    /** A folded section on screen, for the search: its header row, divider, body and fold state. */
    private class SectionUi(
        val title: String, val info: String, val row: View, val divider: View?, val body: View,
        val restore: () -> Unit, val expand: () -> Unit
    )

    private val sectionUi = mutableListOf<SectionUi>()

    private fun sections() = listOf(
        Section("general", binding.headerGeneral, binding.sectionGeneral,
            "Setup wizard: walks through the permissions and main choices again.\n\n" +
                "Update check: asks GitHub at most every 6 hours and only speaks up when a new version is out.\n\n" +
                "Discreet mode: hides details on the lock screen and in notifications.\n\n" +
                "Screen: \"On while charging\" suits a car or a desk; \"Normal\" turns off like other apps.\n\n" +
                "Screen rotation: phones like the Pixel never auto-rotate upside down. \"Auto-rotate, upside down too\" turns " +
                "the app whichever way the phone is held, upside down included (rotation lock still applies); \"Always upside down\" " +
                "keeps the USB-C port at the top (an antenna, a WiFi adapter or an ESP32 board plugged in). The status bar and " +
                "Android's own navigation buttons or gestures turn with it and work normally. Only while the app is on screen: " +
                "other apps and the home screen stay as usual."),
        Section("appearance", binding.headerAppearance, binding.sectionAppearance,
            "Banner image: shown above the animated header in the styled themes (Night Drive, Synthwave...); " +
                "in DedSec and fsociety it replaces the poster. The image is copied privately into the app."),
        Section("scanning", binding.headerScanning, binding.sectionScanning,
            "Bluetooth intensity: Battery saver misses short broadcasts.\n\n" +
                "WiFi interval: Android allows about one WiFi scan per 30 s unless \"Wi-Fi scan throttling\" " +
                "is turned off in Developer options; then down to 5 s works, at some battery cost.\n\n" +
                "Background scanning: lets the scan keep running with the screen off.\n\n" +
                "Remove from the list: how long an ordinary device stays in the list and radar once it stops being heard (default 30 s Bluetooth, 60 s WiFi). Flagged devices follow these too; favourite and following devices stay 3 minutes. " +
                "Hide trusted devices: whitelisted devices are left out of the list and radar (they stay in the history). " +
                "Both can go down to 5 s (WiFi only when Developer options are on and the WiFi interval is under 30 s)."),
        Section("autostart", binding.headerAutoStart, binding.sectionAutoStart,
            "When the phone starts: begins scanning after a reboot.\n\n" +
                "In the car: starts scanning when the phone connects to your car's Bluetooth and stops when you leave. " +
                "Android Auto also starts it when RF Sentinel opens on the car screen."),
        Section("detect", binding.headerDetect, binding.sectionDetect,
            "Each type of equipment can be turned on or off. Network / home cameras are off by default (many false alarms).\n\n" +
                "GPS / satellite checks cover every system the phone hears: GPS, GLONASS, Galileo, BeiDou, QZSS and NavIC.\n\n" +
                "Two-way radio needs an RTL-SDR dongle on USB.\n\n" +
                "Police aircraft and Waze reports are online sources, off by default. Aircraft: every minute, a position " +
                "rounded to about 1 km goes to the community ADS-B feeds adsb.fi / adsb.lol; matched against a list of " +
                "about 1,400 US and Canadian law-enforcement aircraft, plus unlisted aircraft circling low overhead. " +
                "Waze: crowd reports (police, and any other kind you tick), at your own risk. Either through your own OpenWeb Ninja key " +
                "(Waze never sees you), or Waze direct: no key, free, but an anonymous Waze account on this phone sends Waze (Google) your " +
                "IP address and a position rounded to a 1 km grid.\n" +
                "• Alert range: how close a report must be to alert. Map range: how far the map shows them.\n" +
                "• Check every: how often Waze is asked. Slower when parked, faster on fast roads (direct), if you leave that on. " +
                "OpenWeb Ninja bills every check.\n" +
                "• Only alert for reports ahead: while you drive, reports behind you stay on the list but stay quiet.\n" +
                "• Call out again: speaks at 1 km, 500 m and 200 m as a report gets closer (voice alerts on, or in the car).\n" +
                "• Each kind of report has its own level: sound and voice, notification only, or silent (list and map only).\n" +
                "• Pull the list down, or use the menu, to check right now. Waze status shows what is sent, and when.\n\n" +
                "Card skimmers: the Bluetooth modules built into gas-pump and ATM skimmers (HC-05 / HC-06 style names). " +
                "Hacking tools (off by default): Flipper Zero, Pwnagotchi, WiFi Pineapple, deauthers, evil twin WiFi " +
                "networks (one network name, two makers, one open) and Bluetooth pairing-pop-up spam floods.\n\n" +
                "Watchlist presets are lists of vendor MAC prefixes:\n" +
                "• Global: Axon, Flock, Zepcam, WatchGuard, Digital Ally, ShotSpotter, traffic cameras...\n" +
                "• Canada: Axon, Cyberkar, Getac, Genetec, Motorola, ticket printers...\n" +
                "• United States\n" +
                "• France: Motorola VB400 body cams (Police nationale, Gendarmerie), Zepcam, Axon, TETRAPOL radios, Idemia. " +
                "• United Kingdom: Motorola VB400 / VB300 and Axon body cams, Sepura and Motorola Airwave radios, " +
                "Jenoptik SPECS / VECTOR ANPR and speed cameras.\n" +
                "• Portugal: Motorola SIRESP radios of PSP, GNR and INEM; PSP / GNR body cams are still being bought.\n" +
                "• Germany: Motorola VB400 body cams of several state police forces, Motorola and Sepura BOS digital radios.\n" +
                "• Spain: Axon body cams of the Policia Nacional, Teltronic TETRA radios.\n" +
                "• Italy: Leonardo / Selex TETRA radios of the Polizia di Stato, Selea and Elsag plate readers.\n" +
                "French drone electronic IDs are decoded in every region. With a European preset on (and not US / Canada), " +
                "the weak Flock clues are ignored: Flock isn't used there."),
        Section("alerts", binding.headerAlerts, binding.sectionAlerts,
            "Threshold: \"Weak\" alerts on every match (more false alarms); \"Strong only\" on near-certain ones.\n\n" +
                "Vibration: 1 pulse weak, 2 probable, 3 strong.\n\n" +
                "Radar-detector beeps (off by default): after a device alert, keeps beeping faster as its signal " +
                "gets stronger, like a radar detector; stops when it's gone or after 2 minutes.\n\n" +
                "Radar-detector sound (off by default): radar-detector alert sounds instead of the plain beeps - " +
                "by strength (effect 1 strong, 2 probable, 3 weak, 4 following) or always the effect you pick - then " +
                "the short voice says what it is (\"Body cam\"); the proximity beeps use a pulse of the same effect. " +
                "\"GPS connected\" when the GPS locks; optional start beep (power-on sweep) when a scan starts, off by default.\n\n" +
                "Intro sound (off by default): plays when a scan starts.\n\n" +
                "Re-alert: how long before the same device can alert again."),
        Section("voice", binding.headerVoice, binding.sectionVoice,
            "Speaks each alert, for example while driving. Short alerts say just the type " +
                "(\"Body cam\", \"Police car\", \"Speed camera, 50\").\n\n" +
                "Voice loudness: makes the voice louder than the volume alone allows (up to +15 dB, without distorting), " +
                "for when music drowns it out - in the car too. Release the slider to hear it.\n\n" +
                "Pause music while an alert is spoken: music stops for the alert and carries on after it, " +
                "instead of only getting quieter."),
        Section("overlay", binding.headerOverlay, binding.sectionOverlay,
            "Shown over other apps (Waze, Maps...) while scanning. Needs Android's \"Display over other apps\" permission.\n\n" +
                "Bubble: each new alert shows what was detected in a small card beside it for a few seconds.\n\n" +
                "Mini map: the devices around you, like the app's map. Drag to move, pinch to zoom, " +
                "corner handle to resize, tap to open the full map."),
        Section("follow", binding.headerFollow, binding.sectionFollow,
            "Warns when a tracker or flagged device keeps moving with you for at least the time and distance set here.\n\n" +
                "Your own tag: long-press it in the list > Ignore this tracker."),
        Section("map", binding.headerMap, binding.sectionMap,
            "What the map shows.\n\n" +
                "Plate, speed and red-light cameras: from OpenStreetMap (Known cameras has the downloads, warnings and CCTV).\n\n" +
                "Cell towers: placed where your phone heard each one strongest - an estimate, not the tower's real position."),
        Section("cameras", binding.headerCameras, binding.sectionCameras,
            "Plate-reader and speed / red-light camera positions come from OpenStreetMap.\n\n" +
                "Download automatically: fetches the cameras of the map area you look at (the Overpass server sees that area, like map tiles).\n\n" +
                "Download around me: saves the cameras within the radius for offline use. Larger areas take longer and use more data.\n\n" +
                "US & Canada: every plate reader in DeFlock's hourly OpenStreetMap snapshot " +
                "(cdn.deflock.me sees only the download, not your position); refreshes weekly on Wi-Fi.\n\n" +
                "To silence one camera: tap it on the map > Ignore alerts.\n\n" +
                "CCTV cameras: ordinary surveillance cameras volunteers mapped in OpenStreetMap (street, shop, building cameras). " +
                "Everything within the download radius around you is fetched in the background; they show from city zoom, with a " +
                "shaded area where their direction is mapped when you zoom in close. Most are wired, so the scan can't detect them. " +
                "Public ones by default; private outdoor and indoor ones on request. Map only, no alerts.\n\n" +
                "Delete downloaded cameras: removes the saved plate, speed, red-light and CCTV cameras (they download again as you use the map)."),
        Section("cell", binding.headerCell, binding.sectionCell,
            "Tower change alerts are frequent while driving; most useful when parked.\n\n" +
                "Turning off \"Allow 2G\" (Android 12+) is the strongest protection against fake towers: " +
                "2G has no network authentication, which is what IMSI catchers exploit.\n\n" +
                "Android 15+ (e.g. Pixel 8+) can itself warn you when a network asks for your SIM's identity or turns " +
                "encryption off: Security & privacy > More security & privacy > Cellular security > Network notifications."),
        Section("hardware", binding.headerHardware, binding.sectionHardware,
            "Plug these in with an OTG cable while scanning:\n\n" +
                "• ESP32 boards with OUI-Spy, GhostESP or Marauder firmware (also through a Flipper Zero), FREE-WiLi 2.\n\n" +
                "• USB WiFi adapters in monitor mode: longer range, and they hear devices connected to networks. " +
                "RTL8811AU / 8821AU (ALFA AWUS036ACS), RTL8812BU / 8822BU, RTL8814AU (AWUS1900), MT7612U (AWUS036ACM), " +
                "RTL8187 (AWUS036H), RT3070 (AWUS036NH / NEH), AR9271 (AWUS036NHA, experimental).\n\n" +
                "AWUS036ACS 5 GHz (experimental, off by default): the RTL8811AU / 8821AU also hops 5 GHz channels 36-48 and " +
                "149-165. Untested on every unit: if it misbehaves, turn it off and send the adapter log. Applies when the adapter is plugged in.\n\n" +
                "• RTL-SDR dongle: notices two-way radios transmitting nearby (signal strength only, nothing is decoded).\n\n" +
                "OUI-SPY over Bluetooth needs its App-Controlled firmware. Relay mode passes on every network and device it hears " +
                "so RF Sentinel's own lists check them (the board also sends standard Wi-Fi scan probes).\n\n" +
                "Adapter not working? Export its log and send it to the developer.\n\n" +
                "WiFi deauth attacks (with Hacking tools on): the USB WiFi adapter counts the frames that knock devices off a " +
                "network; a flood (a jammer, an ESP32 deauther, a Flipper WiFi board, a WiFi Pineapple) raises an alert. " +
                "Protected (802.11w) frames are ignored, and a normal network never sends enough.\n\n" +
                "Record frames (.pcap): saves every frame the USB WiFi adapter (or V2X board) hears to a file for Wireshark, " +
                "with each frame's channel and signal, until you stop it, scanning stops, or it reaches 200 MB. It holds " +
                "the addresses and names of everything nearby: share it with care. Only the newest recording is kept.\n\n" +
                "• V2X (Europe): an ESP32-C5 flashed with the V2X2MAP firmware (github.com/pit711/V2X2MAP) hears the 5.9 GHz " +
                "car-to-car radio (ITS-G5). Emergency vehicles that broadcast it show on the list and map at their own GPS " +
                "position, with an alert when their light bar or siren is on, or when they warn that they're approaching. " +
                "Ordinary cars are counted, not listed. North America uses a different V2X radio (C-V2X) that this board can't hear. " +
                "Receiving V2X may fall under telecom or privacy law where you are."),
        Section("radio", binding.headerRadio, binding.sectionRadio,
            "An RTL-SDR dongle on USB notices two-way radios transmitting nearby, from signal strength only - nothing is " +
                "decoded or recorded. Needs the Police radio category on (What to detect).\n\n" +
                "Sensitivity: how far above the noise a burst must be to count. Lower hears farther radios, and more false alarms.\n\n" +
                "Bands: the built-in North American public-safety bands, your own bands (one per line, \"380-400 TETRA\"), " +
                "ranges never to report (\"462-469 business band\"), and frequencies to watch (\"154.4300 County fire\"), " +
                "which are reported whenever they're active.\n\n" +
                "Hits show in the main list (Radio filter). Closer / moving away: called out when the signal changes by the set " +
                "amount - a rough guide only.\n\n" +
                "Cellular transmitters (LTE uplink, off by default): also watches the phone / modem side of the in-range LTE " +
                "bands (600 / 700 / 850 / 1700 MHz, including Band 14, the US public-safety broadband band FirstNet uses) for a transmitter close to you. Energy only, nothing decoded or identified. " +
                "Few things drive around with a mobile router always transmitting, so a cellular signal that travels with you - or " +
                "that lines up with a device flagged over Wi-Fi / Bluetooth - is a useful sign; a phone alone is not. It adds time to " +
                "each sweep while it's on.\n\n" +
                "Frequency names: import a CSV (RadioReference's export, CHIRP, or any file with a frequency and a name column), " +
                "or let RadioReference name the FCC licences near you (US): needs your own RadioReference Premium login and a " +
                "RadioReference developer key; your position rounded to ~1 km is sent to RadioReference, at most once a day per area.\n\n" +
                "Changes apply from the next sweep while scanning."),
        Section("location", binding.headerLocation, binding.sectionLocation,
            "Traces: records your route while scanning, to view on the map.\n\n" +
                "Saving the GPS position with matches is privacy-sensitive: the log then shows where you were. It also lets the map remember where it last looked, so it can open there before the GPS answers.\n\n" +
                "Pinpoint flagged devices: while a flagged device is in range, the GPS reads your position every second and " +
                "its signal is measured at every spot you pass. Once you've passed it from more than one side (around the block), " +
                "the map puts it where it most likely is, with a circle it is very likely in. Driving one straight road can't tell " +
                "which side it's on: the circle then covers both. Works for devices that stay put; one that moves with you isn't " +
                "pinned. Kept in memory, not in the history. Uses more battery while a flagged device is around."),
        Section("data", binding.headerData, binding.sectionData,
            "Everything stays on this phone. History older than the set number of days is deleted (0 keeps it forever).\n\n" +
                "Live export (off by default): while scanning, rewrites three files every 30 s in the folder you choose - " +
                "rfsentinel_live_matches.csv, rfsentinel_live_matches.kml and rfsentinel_live.geojson (devices on the map and " +
                "your position) - so another mapping app can show them close to live. Anything that can read that folder can read them.\n\n" +
                "Forget device history: new / returning status, detect counts and the cell towers remembered for the " +
                "fake-cell checks start over. Favorites are kept.\n\n" +
                "Changes on this screen are saved automatically."),
        Section("more", binding.headerMore, binding.sectionMore,
            "Watchlist & rules: the vendor prefixes and rules that flag a device; add your own.\n\n" +
                "Whitelist: devices you trust, never flagged.\n\n" +
                "Check for updates: asks GitHub for a newer version now.\n\n" +
                "About & sources: version, licence, where the signatures come from, and the app's limits.\n\n" +
                "Crash log: if RF Sentinel ever closes unexpectedly, what went wrong is saved on this phone (never sent " +
                "automatically). Export it to send to the developer; the app also offers this the next time it opens.")
    )

    /** Folds each section under a tappable header (remembered) with an ⓘ button for the details. */
    private fun setupSections() {
        val ui = getSharedPreferences("settings_sections", MODE_PRIVATE)
        val defaultOpen = setOf("general", "detect", "alerts")
        val dp = resources.displayMetrics.density
        val ripple = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        val divider = androidx.core.graphics.ColorUtils.setAlphaComponent(
            com.google.android.material.color.MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface), 0x22)
        sections().forEachIndexed { i, s ->
            val parent = s.header.parent as android.widget.LinearLayout
            val at = parent.indexOfChild(s.header)
            parent.removeView(s.header)
            val title = s.header.text.toString()
            s.header.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            s.header.setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            val info = android.widget.TextView(this).apply {
                text = "ⓘ"
                textSize = 20f
                setTextColor(s.header.currentTextColor)
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
                setBackgroundResource(ripple)
                contentDescription = "About $title"
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity).setTitle(title).setMessage(s.info)
                        .setPositiveButton("OK", null).show()
                }
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(ripple)
                addView(s.header)
                addView(info)
            }
            parent.addView(row, at, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            val dividerView = if (i == 0) null else View(this).apply { setBackgroundColor(divider) }
            dividerView?.let { parent.addView(it, at,
                android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp.toInt()))) }
            s.body.setPadding(0, 0, 0, (10 * dp).toInt())
            val requested = intent.getStringExtra(EXTRA_SECTION) == s.key
            if (requested) ui.edit().putBoolean(s.key, true).apply()
            var open = ui.getBoolean(s.key, s.key in defaultOpen)
            fun show() {
                s.body.visibility = if (open) View.VISIBLE else View.GONE
                s.header.text = (if (open) "▾  " else "▸  ") + title
                row.contentDescription = "$title, ${if (open) "expanded" else "collapsed"}"
            }
            show()
            // After the sections above have laid out (expanded / collapsed), so the header lands on top.
            if (requested) binding.root.postDelayed({ if (!isFinishing) binding.root.smoothScrollTo(0, row.top) }, 250)
            row.setOnClickListener {
                open = !open
                ui.edit().putBoolean(s.key, open).apply()
                show()
            }
            sectionUi += SectionUi(title, s.info, row, dividerView, s.body, restore = { show() }, expand = {
                s.body.visibility = View.VISIBLE
                s.header.text = "▾  $title"
            })
        }
    }

    // ---- Search ----------------------------------------------------------------------------

    /** Views tinted as search matches, with the background they had before. */
    private val highlighted = mutableListOf<Pair<View, android.graphics.drawable.Drawable?>>()
    private lateinit var searchInput: android.widget.EditText

    /** The search field pinned above the scrolling settings (with a clear button). */
    private fun searchBar(): View {
        val dp = resources.displayMetrics.density
        searchInput = android.widget.EditText(this).apply {
            hint = "Search settings"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = (6 * dp).toInt()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val clear = android.widget.TextView(this).apply {
            text = "✕"
            textSize = 18f
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            contentDescription = "Clear search"
            visibility = View.GONE
            setOnClickListener { searchInput.setText("") }
        }
        // Back only puts the keyboard away (the search stays); the keyboard's search key does too.
        searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                filterSettings(q)
            }
        })
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), 0)
            addView(searchInput, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear)
        }
    }

    /**
     * Shows only the sections with a setting (or ⓘ text) matching [raw], unfolded, tints the
     * matching settings and scrolls to the first one. Empty: everything back as it was.
     */
    private fun filterSettings(raw: String) {
        highlighted.forEach { (v, bg) -> v.background = bg }
        highlighted.clear()
        val q = raw.trim().lowercase()
        if (q.isEmpty()) {
            sectionUi.forEach { it.row.visibility = View.VISIBLE; it.divider?.visibility = View.VISIBLE; it.restore() }
            return
        }
        val tint = androidx.core.graphics.ColorUtils.setAlphaComponent(
            com.google.android.material.color.MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary), 0x40)
        var first: View? = null
        for (s in sectionUi) {
            val hits = mutableListOf<View>()
            collectMatches(s.body, q, hits)
            val show = hits.isNotEmpty() || s.title.lowercase().contains(q) || s.info.lowercase().contains(q)
            s.row.visibility = if (show) View.VISIBLE else View.GONE
            s.divider?.visibility = s.row.visibility
            if (!show) { s.body.visibility = View.GONE; continue }
            s.expand()
            for (v in hits) {
                highlighted += v to v.background
                v.background = android.graphics.drawable.ColorDrawable(tint)
            }
            if (first == null) first = hits.firstOrNull() ?: s.row
        }
        val target = first ?: return
        binding.root.post {
            if (isFinishing) return@post
            val content = binding.root.getChildAt(0) as? android.view.ViewGroup ?: return@post
            val r = android.graphics.Rect()
            target.getDrawingRect(r)
            runCatching { content.offsetDescendantRectToMyCoords(target, r) }.onSuccess {
                binding.root.scrollTo(0, maxOf(0, r.top - (24 * resources.displayMetrics.density).toInt()))
            }
        }
    }

    /** Visible settings under [v] whose label, text or hint contains [q]. */
    private fun collectMatches(v: View, q: String, out: MutableList<View>) {
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c.visibility == View.VISIBLE) collectMatches(c, q, out)
            }
        } else if (v is android.widget.TextView) {
            val text = v.text?.toString().orEmpty().lowercase()
            val hint = v.hint?.toString().orEmpty().lowercase()
            if (text.contains(q) || hint.contains(q)) out += v
        }
    }

    // ---- RTL-SDR radio -------------------------------------------------------------------

    private val freqListPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { com.rfsentinel.app.sdr.FreqNames.readLimited(it) }.orEmpty()
                    com.rfsentinel.app.sdr.FreqNames.importCsv(this@SettingsActivity, text, "your list")
                }.getOrDefault(-1)
            }
            Toast.makeText(this@SettingsActivity,
                if (n > 0) "$n frequencies imported" else "No frequencies found in that file (needs a frequency column in MHz)",
                Toast.LENGTH_LONG).show()
            updateFreqListText()
        }
    }

    private var freqListText: android.widget.TextView? = null

    private fun updateFreqListText() {
        val n = com.rfsentinel.app.sdr.FreqNames.importedCount
        freqListText?.text = if (n == 0) "No frequency list imported" else "$n named frequencies imported"
    }

    /** Settings > RTL-SDR radio, built in code; everything is saved as you change it. */
    private fun setupRadioSection() {
        val box = binding.sectionRadio
        val dp = resources.displayMetrics.density
        fun label(t: String, small: Boolean = false) = android.widget.TextView(this).apply {
            text = t; textSize = if (small) 12f else 14f
            if (small) alpha = 0.75f
            setPadding(0, (if (small) 0 else 10 * dp).toInt(), 0, (2 * dp).toInt())
        }.also { box.addView(it) }
        fun slider(from: Int, to: Int, value: Int, text: (Int) -> String, save: (Int) -> Unit) {
            val l = label(text(value))
            box.addView(com.google.android.material.slider.Slider(this).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 1f
                this.value = value.coerceIn(from, to).toFloat()
                contentDescription = text(value)
                addOnChangeListener { _, v, fromUser -> if (fromUser) { save(v.toInt()); l.text = text(v.toInt()) } }
            })
        }
        fun lines(title: String, hint: String, value: String, save: (String) -> Unit) {
            label(title)
            box.addView(android.widget.EditText(this).apply {
                setText(value); this.hint = hint
                minLines = 2; maxLines = 8; gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { save(s?.toString().orEmpty()) }
                })
            })
        }
        fun switch(t: String, on: Boolean, save: (Boolean) -> Unit) = SwitchMaterial(this).apply {
            text = t; isChecked = on
            setOnCheckedChangeListener { _, v -> save(v) }
        }.also { box.addView(it) }

        slider(15, 50, Prefs.radioMinSnr(this), { "Sensitivity: report a radio from $it dB above the noise (lower = hears farther, more false alarms)" }) {
            Prefs.setRadioMinSnr(this, it)
        }
        label("Bands to sweep")
        val off = Prefs.radioBandsOff(this).toMutableSet()
        for ((key, band) in com.rfsentinel.app.sdr.RadioSettings.BUILT_IN) {
            box.addView(android.widget.CheckBox(this).apply {
                text = "%s (%.1f-%.1f MHz)".format(java.util.Locale.US, band.label, band.startHz / 1e6, band.endHz / 1e6)
                isChecked = key !in off
                setOnCheckedChangeListener { _, on ->
                    if (on) off -= key else off += key
                    Prefs.setRadioBandsOff(this@SettingsActivity, off.toSet())
                }
            })
        }
        lines("Your own bands (one per line, in MHz)", "380-400 TETRA\n220-222", Prefs.radioCustomBands(this)) { Prefs.setRadioCustomBands(this, it) }
        lines("Never report these ranges", "462-469 business band\n151.8-152.0", Prefs.radioExcluded(this)) { Prefs.setRadioExcluded(this, it) }
        lines("Watch these frequencies (always reported when active)", "154.4300 County fire dispatch\n460.125", Prefs.radioTargets(this)) { Prefs.setRadioTargets(this, it) }
        switch("Say the frequency (and its name) instead of \"Radio transmitting nearby\"", Prefs.radioSpeakFreq(this)) { Prefs.setRadioSpeakFreq(this, it) }
        switch("Also detect cellular transmitters (LTE uplink, experimental)", Prefs.radioCellOn(this)) { Prefs.setRadioCellOn(this, it) }
        label("Watches the phone / modem side of the LTE bands for a transmitter travelling near you (a vehicle modem, a " +
            "Cradlepoint-style router, a camera with a SIM). Energy only, nothing decoded. Adds time to each sweep; " +
            "a phone counts too, so it is a weak sign on its own.", small = true)
        slider(0, 20, Prefs.radioTrendDb(this), { if (it == 0) "Closer / moving away: off" else "Say \"getting closer\" / \"moving away\" when the signal changes by $it dB" }) {
            Prefs.setRadioTrendDb(this, it)
        }
        slider(1, 50, Prefs.radioMatchKhz(this), { "A hit takes a frequency's name within ± $it kHz" }) { Prefs.setRadioMatchKhz(this, it) }

        label("Frequency names")
        freqListText = label("", small = true)
        com.rfsentinel.app.sdr.FreqNames.load(this)
        updateFreqListText()
        box.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(com.google.android.material.button.MaterialButton(this@SettingsActivity, null,
                android.R.attr.borderlessButtonStyle).apply {
                text = "Import CSV"
                setOnClickListener { freqListPicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) }
            })
            addView(com.google.android.material.button.MaterialButton(this@SettingsActivity, null,
                android.R.attr.borderlessButtonStyle).apply {
                text = "Clear list"
                setOnClickListener { com.rfsentinel.app.sdr.FreqNames.clearImported(this@SettingsActivity); updateFreqListText() }
            })
        })

        val rrSwitch = switch("Name hits from RadioReference (your own Premium login)", Prefs.radioReferenceOn(this)) {
            Prefs.setRadioReferenceOn(this, it)
        }
        fun secret(title: String, key: String, password: Boolean) {
            box.addView(android.widget.EditText(this).apply {
                hint = title
                setText(com.rfsentinel.app.util.SecureStore.get(this@SettingsActivity, key).orEmpty())
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    (if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setOnFocusChangeListener { v, has ->
                    if (!has) com.rfsentinel.app.util.SecureStore.put(this@SettingsActivity, key, (v as android.widget.EditText).text.toString().trim())
                }
                secretFields += this to key
            })
        }
        secret("RadioReference username (not your email)", com.rfsentinel.app.sdr.RadioReference.USER_KEY, false)
        secret("RadioReference password", com.rfsentinel.app.sdr.RadioReference.PASS_KEY, true)
        secret("RadioReference developer key", com.rfsentinel.app.sdr.RadioReference.APPKEY_KEY, true)
        val rrStatus = label(com.rfsentinel.app.sdr.RadioReference.status.ifEmpty { "Stored encrypted on this phone." }, small = true)
        box.addView(com.google.android.material.button.MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Test login"
            setOnClickListener {
                saveSecrets()
                rrStatus.text = "Checking…"
                lifecycleScope.launch { rrStatus.text = com.rfsentinel.app.sdr.RadioReference.test(this@SettingsActivity) }
            }
        })
        rrSwitch.isChecked = Prefs.radioReferenceOn(this)
    }

    /** The RadioReference fields, saved (encrypted) when they lose focus and when leaving Settings. */
    private val secretFields = mutableListOf<Pair<android.widget.EditText, String>>()

    private fun saveSecrets() {
        for ((field, key) in secretFields) com.rfsentinel.app.util.SecureStore.put(this, key, field.text.toString().trim())
    }

    // ---- Live export (Settings > Data) ---------------------------------------------------

    private val liveExportFolder = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { if (Prefs.liveExportTree(this) == null) binding.liveExportSwitch.isChecked = false; return@registerForActivityResult }
        runCatching {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        Prefs.setLiveExportTree(this, uri.toString())
        com.rfsentinel.app.util.LiveExport.reset()
        updateLiveExportText()
    }

    private fun setupLiveExport() {
        binding.liveExportSwitch.isChecked = Prefs.liveExport(this)
        binding.liveExportSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setLiveExport(this, on)
            if (on && Prefs.liveExportTree(this) == null) liveExportFolder.launch(null)
            updateLiveExportText()
        }
        binding.liveExportFolderButton.setOnClickListener { liveExportFolder.launch(null) }
        updateLiveExportText()
    }

    private fun updateLiveExportText() {
        val tree = Prefs.liveExportTree(this)?.let { android.net.Uri.parse(it) }
        val folder = tree?.let { runCatching { android.provider.DocumentsContract.getTreeDocumentId(it).substringAfter(':') }.getOrNull() }
        binding.liveExportText.text = when {
            !Prefs.liveExport(this) -> "Off"
            tree == null -> "Choose a folder"
            else -> "Folder: ${folder?.ifEmpty { "storage root" } ?: tree}" +
                com.rfsentinel.app.util.LiveExport.status.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
        }
    }

    /** Settings > Map: what the map shows (moved here from the map's menu). Saved as you switch. */
    private fun setupMapSection() {
        binding.mapKnownCamerasSwitch.isChecked = Prefs.showKnownAlpr(this)
        binding.mapKnownCamerasSwitch.setOnCheckedChangeListener { _, on -> Prefs.setShowKnownAlpr(this, on) }
        binding.mapTowersSwitch.isChecked = Prefs.showCellTowers(this)
        binding.mapTowersSwitch.setOnCheckedChangeListener { _, on -> Prefs.setShowCellTowers(this, on) }
        binding.mapCctvSwitch.isChecked = Prefs.showCctv(this)
        binding.mapCctvPrivateSwitch.isChecked = Prefs.cctvPrivate(this)
        binding.mapCctvPrivateSwitch.isEnabled = binding.mapCctvSwitch.isChecked
        binding.mapCctvSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setShowCctv(this, on)
            binding.mapCctvPrivateSwitch.isEnabled = on
        }
        binding.mapCctvPrivateSwitch.setOnCheckedChangeListener { _, on -> Prefs.setCctvPrivate(this, on) }
        binding.mapTracesButton.setOnClickListener {
            startActivity(Intent(this, com.rfsentinel.app.ui.TripsActivity::class.java))
        }
        binding.mapDeleteCamerasButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete downloaded cameras?")
                .setMessage("Removes the saved plate, speed, red-light and CCTV cameras. They download again as you use the map.")
                .setPositiveButton("Delete") { _, _ ->
                    // Stop a running download first, or it would put cameras back afterwards.
                    com.rfsentinel.app.alpr.DeflockBulk.cancel()
                    com.rfsentinel.app.alpr.AlprStore.clear(this)
                    com.rfsentinel.app.alpr.CctvStore.clear(this)
                    // A running scan drops the deleted cameras from its warnings too.
                    if (com.rfsentinel.app.service.ScanForegroundService.isRunning) runCatching {
                        startService(Intent(this, com.rfsentinel.app.service.ScanForegroundService::class.java)
                            .setAction(com.rfsentinel.app.service.ScanForegroundService.ACTION_REFRESH_LOCATION))
                    }
                    Toast.makeText(this, "Downloaded cameras deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** A small grey line under a category switch, indented like the AirTag option. */
    private fun indentedNote(text: String) = android.widget.TextView(this).apply {
        this.text = text
        textSize = 12f
        alpha = 0.75f
        setPadding((24 * resources.displayMetrics.density).toInt(), 0, 0, (6 * resources.displayMetrics.density).toInt())
        visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showNote(view: android.widget.TextView?, text: String) {
        view ?: return
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * How long ordinary devices stay in the list and radar after they're last heard
     * (flagged, favourite and following ones always stay 3 minutes). Saved as you slide.
     */
    /** True while the "Test intro" button's intro plays. */
    private var introTesting = false

    private val detectorEffectNames = arrayOf(
        "By strength (1 strong, 2 probable, 3 weak, 4 following)",
        "Effect 1", "Effect 2", "Effect 3", "Effect 4"
    )

    private fun updateDetectorEffectButton() {
        binding.detectorEffectButton.text = "Detector sound effect: " +
            detectorEffectNames[Prefs.detectorEffect(this)].substringBefore(" (")
    }

    /** Pick the radar-detector sound effect; each choice plays as a preview. */
    private fun chooseDetectorEffect() {
        var picked = Prefs.detectorEffect(this)
        AlertDialog.Builder(this)
            .setTitle("Detector sound effect")
            .setSingleChoiceItems(detectorEffectNames, picked) { _, which ->
                picked = which
                com.rfsentinel.app.util.AlertPlayer.previewEffect(this, which)
            }
            .setPositiveButton("OK") { _, _ ->
                Prefs.setDetectorEffect(this, picked)
                updateDetectorEffectButton()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { com.rfsentinel.app.util.AlertPlayer.stopPreview(false) }
            .show()
    }

    private fun addLiveWindowSliders() {
        fun slider(label: (Int) -> String, from: Int, to: Int, value: Int, save: (Int) -> Unit): com.google.android.material.slider.Slider {
            val text = android.widget.TextView(this).apply {
                textSize = 13f
                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
                this.text = label(value)
            }
            binding.sectionScanning.addView(text)
            val s = com.google.android.material.slider.Slider(this).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 5f
                this.value = (value - value % 5).coerceIn(from, to).toFloat()
                setLabelFormatter { "${it.toInt()} s" }
                contentDescription = label(value)
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    save(v.toInt()); text.text = label(v.toInt())
                }
            }
            binding.sectionScanning.addView(s)
            return s
        }
        slider({ "Remove Bluetooth devices from the list after $it s out of range" }, 5, 180, Prefs.liveBleSec(this)) {
            Prefs.setLiveBleSec(this, it)
        }
        // Below 30 s only with Developer options on and a WiFi scan interval under 30 s.
        val wifiLabel = { s: Int -> "Remove WiFi devices from the list after $s s out of range" }
        val wifiSlider = slider(wifiLabel, Prefs.minLiveWifiSec(this), 180, Prefs.liveWifiSec(this)) {
            Prefs.setLiveWifiSec(this, it)
        }
        // Follow the WiFi interval as it's typed.
        binding.intervalInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val sec = s?.toString()?.toLongOrNull() ?: return
                val min = Prefs.minLiveWifiSec(this@SettingsActivity, sec.coerceAtLeast(5) * 1000L)
                if (wifiSlider.valueFrom.toInt() == min) return
                if (wifiSlider.value < min) {
                    wifiSlider.value = min.toFloat()
                    Prefs.setLiveWifiSec(this@SettingsActivity, min)
                    (binding.sectionScanning.getChildAt(binding.sectionScanning.indexOfChild(wifiSlider) - 1)
                        as? android.widget.TextView)?.text = wifiLabel(min)
                }
                wifiSlider.valueFrom = min.toFloat()
            }
        })
    }

    /** Aircraft search radius (10-50 km) and the feed status, under the aircraft switch. */
    private fun addAircraftControls(sw: SwitchMaterial) {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), 0, 0, (6 * dp).toInt())
        }
        val radiusText = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
        fun showRadius(km: Int) { radiusText.text = "Look for aircraft within $km km" }
        val km = Prefs.aircraftRadiusKm(this)
        showRadius(km)
        box.addView(radiusText)
        box.addView(com.google.android.material.slider.Slider(this).apply {
            valueFrom = 10f; valueTo = 50f; stepSize = 5f
            value = (km - km % 5).coerceIn(10, 50).toFloat()
            contentDescription = "Aircraft search radius in kilometres"
            setLabelFormatter { "${it.toInt()} km" }
            addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                Prefs.setAircraftRadiusKm(this@SettingsActivity, v.toInt())
                showRadius(v.toInt())
            }
        })
        aircraftStatusText = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
        box.addView(aircraftStatusText)
        showNote(aircraftStatusText, com.rfsentinel.app.online.OnlineWatch.aircraftStatus)
        box.visibility = if (sw.isChecked) View.VISIBLE else View.GONE
        binding.categoryContainer.addView(box)
        sw.setOnCheckedChangeListener { _, on -> box.visibility = if (on) View.VISIBLE else View.GONE }
    }

    /**
     * Waze police reports: off by default, enabled only after the use-at-your-own-risk
     * warning is accepted; the user's OpenWeb Ninja key is kept encrypted (SecureStore).
     */
    private fun addWazeControls(sw: SwitchMaterial) {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), 0, 0, (8 * dp).toInt())
        }
        box.addView(android.widget.TextView(this).apply {
            text = WAZE_WARNING_SHORT
            textSize = 12f
            alpha = 0.75f
        })
        val ninjaBtn = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "OpenWeb Ninja (your own API key; Waze never sees you)" }
        val directBtn = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Waze direct (no key; sends a position rounded to 1 km to Waze)" }
        val backendGroup = android.widget.RadioGroup(this).apply { addView(ninjaBtn); addView(directBtn) }
        box.addView(backendGroup)
        // Two independent ranges (both backends): how close a report must be to alert, and how far the map shows them.
        fun rangeSlider(label: String, from: Float, to: Float, km: Int, desc: String, save: (Int) -> Unit) {
            val text = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
            fun show(v: Int) { text.text = "$label $v km" }
            show(km)
            box.addView(text)
            box.addView(com.google.android.material.slider.Slider(this).apply {
                valueFrom = from; valueTo = to; stepSize = 1f
                value = km.toFloat().coerceIn(from, to)
                contentDescription = desc
                setLabelFormatter { "${it.toInt()} km" }
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    save(v.toInt()); show(v.toInt())
                }
            })
        }
        // The alert range steps through fixed distances, from 100 m up.
        val steps = Prefs.WAZE_ALERT_STEPS_M
        val alertText = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
        fun showAlert(m: Int) { alertText.text = "Alert when a report is within ${Prefs.formatRange(m)}" }
        val alertNow = Prefs.wazeAlertM(this)
        showAlert(alertNow)
        box.addView(alertText)
        box.addView(com.google.android.material.slider.Slider(this).apply {
            valueFrom = 0f; valueTo = (steps.size - 1).toFloat(); stepSize = 1f
            value = steps.indexOf(alertNow).coerceAtLeast(0).toFloat()
            contentDescription = "Waze alert distance"
            setLabelFormatter { Prefs.formatRange(steps[it.toInt().coerceIn(0, steps.size - 1)]) }
            addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val m = steps[v.toInt().coerceIn(0, steps.size - 1)]
                Prefs.setWazeAlertM(this@SettingsActivity, m); showAlert(m)
            }
        })
        rangeSlider("Show reports on the map within", 1f, 20f, Prefs.wazeViewKm(this), "Waze map view range in kilometres") { Prefs.setWazeViewKm(this, it) }
        // How often Waze is asked. Each backend has its own steps; only the chosen backend's slider shows.
        fun intervalBlock(direct: Boolean): android.widget.LinearLayout {
            val steps = if (direct) Prefs.WAZE_DIRECT_INTERVALS_S else Prefs.WAZE_NINJA_INTERVALS_S
            val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val text = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
            val hint = android.widget.TextView(this).apply { textSize = 11f; alpha = 0.6f }
            fun show(sec: Int) {
                text.text = "Check for new reports every ${Prefs.formatInterval(sec)}"
                hint.text = when {
                    !direct -> "OpenWeb Ninja bills every check (about half a cent each on pay-as-you-go, check your plan): ${3600 / sec} an hour at this setting."
                    sec < 60 -> "Fresher reports, but your position goes to Waze more often."
                    sec > 90 -> "Over 90 s Waze ends the session in between, so each check logs in again (more data)."
                    else -> ""
                }
                hint.visibility = if (hint.text.isEmpty()) View.GONE else View.VISIBLE
            }
            val now = Prefs.wazeIntervalS(this, direct)
            show(now)
            col.addView(text)
            col.addView(com.google.android.material.slider.Slider(this).apply {
                valueFrom = 0f; valueTo = (steps.size - 1).toFloat(); stepSize = 1f
                value = steps.indexOf(now).coerceAtLeast(0).toFloat()
                contentDescription = "How often to check Waze"
                setLabelFormatter { Prefs.formatInterval(steps[it.toInt().coerceIn(0, steps.size - 1)]) }
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    val sec = steps[v.toInt().coerceIn(0, steps.size - 1)]
                    Prefs.setWazeIntervalS(this@SettingsActivity, direct, sec); show(sec)
                }
            })
            col.addView(hint)
            return col
        }
        val directInterval = intervalBlock(true)
        val ninjaInterval = intervalBlock(false)
        box.addView(directInterval)
        box.addView(ninjaInterval)
        // Behaviour switches: each has a line under it saying what it does.
        fun switchRow(label: String, hint: String, checked: Boolean, save: (Boolean) -> Unit) {
            box.addView(SwitchMaterial(this).apply {
                text = label
                isChecked = checked
                setOnCheckedChangeListener { _, on -> save(on) }
            })
            box.addView(android.widget.TextView(this).apply { text = hint; textSize = 11f; alpha = 0.6f })
        }
        switchRow("Slower when parked, faster on fast roads",
            "Parked for 90 s: checks run 4 times less often. Over 80 km/h: twice as often (Waze direct only).",
            Prefs.wazeAdaptive(this)) { Prefs.setWazeAdaptive(this, it) }
        switchRow("Only alert for reports ahead of me",
            "While you drive, reports behind you stay on the list but don't alert, flash or speak.",
            Prefs.wazeAheadOnly(this)) { Prefs.setWazeAheadOnly(this, it) }
        switchRow("Call out again as I get closer",
            "Speaks at 1 km, 500 m and 200 m when voice alerts are on, or in the car.",
            Prefs.wazeApproach(this)) { Prefs.setWazeApproach(this, it) }
        // What to watch (ticked) and what an alert of that kind does (the menu beside it).
        box.addView(android.widget.TextView(this).apply { text = "What to alert on, and how loud"; textSize = 12f; alpha = 0.75f })
        val levels = com.rfsentinel.app.online.WazePolice.Level.entries
        for (t in com.rfsentinel.app.online.WazePolice.Type.entries) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = t.label
                isChecked = t.name in Prefs.wazeTypes(this@SettingsActivity)
                setOnCheckedChangeListener { _, on ->
                    val now = Prefs.wazeTypes(this@SettingsActivity).toMutableSet()
                    if (on) now.add(t.name) else now.remove(t.name)
                    if (now.isEmpty()) { isChecked = true; return@setOnCheckedChangeListener } // keep at least one
                    Prefs.setWazeTypes(this@SettingsActivity, now)
                }
            })
            row.addView(androidx.appcompat.widget.AppCompatSpinner(this).apply {
                adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, levels.map { it.label }).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(Prefs.wazeLevel(this@SettingsActivity, t).ordinal)
                contentDescription = "What a ${t.label.lowercase()} alert does"
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        Prefs.setWazeLevel(this@SettingsActivity, t, levels[position])
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            })
            box.addView(row)
        }
        val jamNote = android.widget.TextView(this).apply {
            text = "OpenWeb Ninja doesn't offer traffic jams as alerts; they only come through Waze direct."
            textSize = 11f; alpha = 0.6f
        }
        box.addView(jamNote)
        val directNote = android.widget.TextView(this).apply {
            text = "Direct: free and live. Uses an anonymous Waze account on this phone."
            textSize = 12f; alpha = 0.75f
        }
        val forgetBtn = android.widget.Button(this).apply {
            text = "Forget Waze account"
            setOnClickListener {
                com.rfsentinel.app.online.wazert.WazeRtFetcher.forgetStoredAccount(this@SettingsActivity)
                android.widget.Toast.makeText(this@SettingsActivity, "Waze account forgotten; a new one is made on the next poll", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val directBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(directNote); addView(forgetBtn)
        }
        box.addView(directBox)
        wazeKeyInput = android.widget.EditText(this).apply {
            hint = "OpenWeb Ninja API key"
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(com.rfsentinel.app.util.SecureStore.get(this@SettingsActivity, com.rfsentinel.app.online.OnlineWatch.WAZE_KEY_NAME).orEmpty())
        }
        box.addView(wazeKeyInput)
        fun showBackend(direct: Boolean) {
            wazeKeyInput?.visibility = if (direct) View.GONE else View.VISIBLE
            directInterval.visibility = if (direct) View.VISIBLE else View.GONE
            jamNote.visibility = if (direct) View.GONE else View.VISIBLE
            ninjaInterval.visibility = if (direct) View.GONE else View.VISIBLE
            directBox.visibility = if (direct) View.VISIBLE else View.GONE
        }
        val directNow = Prefs.wazeBackend(this) == Prefs.WAZE_DIRECT
        backendGroup.check(if (directNow) directBtn.id else ninjaBtn.id)
        showBackend(directNow)
        backendGroup.setOnCheckedChangeListener { _, id ->
            if (id == directBtn.id) {
                if (Prefs.wazeDirectAccepted(this)) { Prefs.setWazeBackend(this, Prefs.WAZE_DIRECT); showBackend(true) }
                else {
                    backendGroup.check(ninjaBtn.id)
                    AlertDialog.Builder(this)
                        .setTitle("Waze direct: use at your own risk")
                        .setMessage(WAZE_DIRECT_WARNING)
                        .setPositiveButton("I understand, use direct") { _, _ ->
                            Prefs.setWazeDirectAccepted(this, true)
                            Prefs.setWazeBackend(this, Prefs.WAZE_DIRECT)
                            backendGroup.check(directBtn.id)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else { Prefs.setWazeBackend(this, Prefs.WAZE_NINJA); showBackend(false) }
        }
        wazeStatusText = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
        box.addView(wazeStatusText)
        showNote(wazeStatusText, com.rfsentinel.app.online.OnlineWatch.wazeStatus)
        box.addView(android.widget.Button(this).apply {
            text = "Waze status"
            setOnClickListener { com.rfsentinel.app.ui.WazeUi.openStatus(this@SettingsActivity) }
        })
        box.visibility = if (sw.isChecked) View.VISIBLE else View.GONE
        binding.categoryContainer.addView(box)
        sw.setOnCheckedChangeListener { _, on ->
            if (on && !Prefs.wazeReady(this)) {
                sw.isChecked = false
                val direct = Prefs.wazeBackend(this) == Prefs.WAZE_DIRECT
                AlertDialog.Builder(this)
                    .setTitle("Waze reports: use at your own risk")
                    .setMessage(if (direct) WAZE_DIRECT_WARNING else WAZE_WARNING)
                    .setPositiveButton("I understand, enable") { _, _ ->
                        Prefs.setWazeAccepted(this, true)
                        if (direct) Prefs.setWazeDirectAccepted(this, true)
                        sw.isChecked = true
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnCheckedChangeListener
            }
            box.visibility = if (on) View.VISIBLE else View.GONE
        }
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

    /** True once the screen shows the saved values, so leaving it can save them back. */
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        // The search field stays pinned above the scrolling settings.
        val page = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // The page holds focus, so opening Settings doesn't pop the keyboard up.
            isFocusableInTouchMode = true
            addView(searchBar())
            addView(binding.root, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(page)
        page.applySystemBarInsets()
        page.requestFocus()
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
        setupSections()

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
        addLiveWindowSliders()
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
            if (c == Category.TRACKER) {
                airTagSwitch = SwitchMaterial(this).apply {
                    text = "Exclude Apple AirTags"
                    isChecked = Prefs.excludeAirTags(this@SettingsActivity)
                    setPadding((24 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                    isEnabled = sw.isChecked
                }
                sw.setOnCheckedChangeListener { _, on -> airTagSwitch?.isEnabled = on }
                binding.categoryContainer.addView(airTagSwitch)
            }
            if (c == Category.AIRCRAFT) addAircraftControls(sw)
            if (c == Category.POLICE_REPORT) addWazeControls(sw)
        }
        val enabledPresets = OuiWatchlist.getEnabledPresets(this)
        binding.presetGlobal.isChecked = "global" in enabledPresets
        binding.presetCanada.isChecked = "canada" in enabledPresets
        binding.presetUs.isChecked = "us" in enabledPresets
        binding.presetFrance.isChecked = "france" in enabledPresets
        binding.presetFrance.setOnCheckedChangeListener { _, on -> if (on) com.rfsentinel.app.ui.RegionNotice.show(this, "france") }
        binding.presetUk.isChecked = "uk" in enabledPresets
        binding.presetPortugal.isChecked = "portugal" in enabledPresets
        binding.presetGermany.isChecked = "germany" in enabledPresets
        binding.presetGermany.setOnCheckedChangeListener { _, on -> if (on) com.rfsentinel.app.ui.RegionNotice.show(this, "germany") }
        binding.presetSpain.isChecked = "spain" in enabledPresets
        binding.presetItaly.isChecked = "italy" in enabledPresets

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
        binding.radarBeepSwitch.isChecked = Prefs.radarBeep(this)
        binding.detectorSoundSwitch.isChecked = Prefs.detectorSound(this)
        binding.startupSweepSwitch.isChecked = Prefs.startupSweep(this)
        binding.scanIntroSwitch.isChecked = Prefs.scanIntro(this)
        updateDetectorEffectButton()
        binding.detectorEffectButton.isEnabled = binding.detectorSoundSwitch.isChecked
        binding.detectorSoundSwitch.setOnCheckedChangeListener { _, on -> binding.detectorEffectButton.isEnabled = on }
        binding.detectorEffectButton.setOnClickListener { chooseDetectorEffect() }
        binding.testIntroButton.setOnClickListener {
            introTesting = com.rfsentinel.app.util.AlertPlayer.testIntro(this) {
                introTesting = false
                binding.testIntroButton.text = "Test intro"
            }
            binding.testIntroButton.text = if (introTesting) "Stop intro" else "Test intro"
        }
        binding.voiceSwitch.isChecked = Prefs.voiceEnabled(this)
        binding.shortVoiceSwitch.isChecked = Prefs.shortVoice(this)
        setupVoiceControls()
        binding.discreetSwitch.isChecked = Prefs.discreetMode(this)
        val rotations = mapOf(R.id.rotationNormal to Prefs.Rotation.NORMAL, R.id.rotationAllWays to Prefs.Rotation.ALL_WAYS,
            R.id.rotationUpsideDown to Prefs.Rotation.UPSIDE_DOWN)
        binding.rotationGroup.check(rotations.entries.first { it.value == Prefs.rotation(this) }.key)
        binding.rotationGroup.setOnCheckedChangeListener { _, id ->
            rotations[id]?.let { Prefs.setRotation(this, it) }
            (application as com.rfsentinel.app.RFSentinelApp).applyOrientation(this) // turns right away
        }
        binding.floatingMapSwitch.isChecked = Prefs.floatingMap(this) && Settings.canDrawOverlays(this)
        binding.floatingMapSwitch.setOnCheckedChangeListener { sw, on ->
            if (on && !Settings.canDrawOverlays(this)) {
                sw.isChecked = false
                AlertDialog.Builder(this)
                    .setTitle("Allow the floating map")
                    .setMessage("Android needs the \"Display over other apps\" permission for the floating map. " +
                        "Turn it on for RF Sentinel on the next screen, then come back and switch the map on.")
                    .setPositiveButton("Open settings") { _, _ ->
                        runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
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
        binding.usbWifiLogButton.setOnClickListener { exportUsbWifiLog() }
        binding.pcapButton.setOnClickListener { togglePcap() }
        binding.pcapShareButton.setOnClickListener { sharePcap() }
        updatePcap()
        binding.trackerIgnoreButton.setOnClickListener {
            com.rfsentinel.app.data.TrackerMutes.clear(this)
            Prefs.setTrackerFollowPausedUntil(this, 0L)
            updateTrackerIgnoreText()
            Toast.makeText(this, "Tracker warnings back to normal", Toast.LENGTH_SHORT).show()
        }
        binding.followMinutesInput.setText(Prefs.followMinMinutes(this).toString())
        binding.followMetersInput.setText(Prefs.followMinMeters(this).toString())
        binding.gpsSwitch.isChecked = Prefs.gpsTaggingEnabled(this)
        binding.pinpointSwitch.isChecked = Prefs.pinpointFlagged(this)
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
        setupMapSection()
        setupRadioSection()
        setupLiveExport()
        binding.hideTrustedSwitch.isChecked = Prefs.hideWhitelisted(this)
        binding.hideTrustedSwitch.setOnCheckedChangeListener { _, on -> Prefs.setHideWhitelisted(this, on) }
        binding.rtl5gSwitch.isChecked = Prefs.rtl8821au5g(this)
        binding.rtl5gSwitch.setOnCheckedChangeListener { _, on -> Prefs.setRtl8821au5g(this, on) }
        binding.autoUpdateSwitch.isChecked = Prefs.autoUpdateCheck(this)
        fun showRadius(km: Int) {
            binding.cameraRadiusText.text = "Download radius: $km km"
            binding.prefetchCamerasButton.text = "Download cameras around me"
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
                else "From DeFlock's OpenStreetMap snapshot"
            }
        }

        // Data
        binding.retentionInput.setText(Prefs.retentionDays(this).toString())
        binding.exportAllButton.setOnClickListener { com.rfsentinel.app.util.Exporter.showExportMenu(this) }
        // Lists, tools & about (moved here from the main screen's menu).
        binding.openWatchlistButton.setOnClickListener { startActivity(Intent(this, com.rfsentinel.app.ouilist.OuiListActivity::class.java)) }
        binding.openWhitelistButton.setOnClickListener { startActivity(Intent(this, com.rfsentinel.app.whitelist.WhitelistActivity::class.java)) }
        binding.checkUpdateButton.setOnClickListener { com.rfsentinel.app.util.UpdateChecker.check(this) }
        binding.aboutButton.setOnClickListener { com.rfsentinel.app.ui.AboutDialog.show(this) }
        binding.exportCrashLogButton.setOnClickListener { com.rfsentinel.app.util.CrashLog.share(this) }
        binding.clearCrashLogButton.setOnClickListener {
            com.rfsentinel.app.util.CrashLog.clear(this)
            Toast.makeText(this, "Crash log cleared", Toast.LENGTH_SHORT).show()
        }
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
                    com.rfsentinel.app.data.AlertLog.clear(this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "Match history cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        loaded = true
    }

    override fun onResume() {
        super.onResume()
        updatePcap()
        // The user may be coming back from Developer options.
        updateWifiThrottleHint()
        showNote(aircraftStatusText, com.rfsentinel.app.online.OnlineWatch.aircraftStatus)
        showNote(wazeStatusText, com.rfsentinel.app.online.OnlineWatch.wazeStatus)
    }

    private fun updateWifiThrottleHint() {
        val throttled = wifiScanThrottled()
        binding.wifiThrottleText.text = if (throttled)
            "Android throttles WiFi scans: under 30 s is wasted unless you turn it off"
        else
            "Scan throttling is off: down to 5 s works"
        binding.wifiThrottleButton.visibility = if (throttled) View.VISIBLE else View.GONE
    }

    /**
     * Opens Developer options scrolled to "Wi-Fi scan throttling", highlighted (Settings'
     * fragment-args key; ignored on phones that don't support it, which just open the
     * list). If they aren't unlocked yet, opens About phone instead and says how.
     */
    private fun openWifiThrottleSetting() {
        val devEnabled = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        }.getOrDefault(false)
        if (devEnabled && runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "wifi_scan_throttling")
                    .putExtra(":settings:show_fragment_args",
                        android.os.Bundle().apply { putString(":settings:fragment_args_key", "wifi_scan_throttling") }))
            }.isSuccess
        ) {
            Toast.makeText(this, "Turn off the highlighted \"Wi-Fi scan throttling\"", Toast.LENGTH_LONG).show()
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
    /** Android 11+ keeps the developer toggle in the WiFi service; older versions in a global setting. */
    private fun wifiScanThrottled(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30)
            runCatching { getSystemService(android.net.wifi.WifiManager::class.java).isScanThrottleEnabled }.getOrDefault(true)
        else runCatching { Settings.Global.getInt(contentResolver, "wifi_scan_throttle_enabled", 1) != 0 }.getOrDefault(true)

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Clear") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Settings save themselves: whenever this screen is left (back, home, another screen). */
    override fun onPause() {
        saveSecrets()
        // Leaving Settings ends any sound preview (not an intro played by a starting scan).
        com.rfsentinel.app.util.AlertPlayer.stopPreview(stopIntro = introTesting)
        if (introTesting) { introTesting = false; binding.testIntroButton.text = "Test intro" }
        super.onPause()
        if (loaded) save()
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
        airTagSwitch?.let { Prefs.setExcludeAirTags(this, it.isChecked) }
        wazeKeyInput?.let { input ->
            val key = input.text.toString().trim()
            val name = com.rfsentinel.app.online.OnlineWatch.WAZE_KEY_NAME
            if (key != com.rfsentinel.app.util.SecureStore.get(this, name).orEmpty()) com.rfsentinel.app.util.SecureStore.put(this, name, key)
        }
        val presets = mutableSetOf<String>()
        if (binding.presetGlobal.isChecked) presets.add("global")
        if (binding.presetCanada.isChecked) presets.add("canada")
        if (binding.presetUs.isChecked) presets.add("us")
        if (binding.presetFrance.isChecked) presets.add("france")
        if (binding.presetUk.isChecked) presets.add("uk")
        if (binding.presetPortugal.isChecked) presets.add("portugal")
        if (binding.presetGermany.isChecked) presets.add("germany")
        if (binding.presetSpain.isChecked) presets.add("spain")
        if (binding.presetItaly.isChecked) presets.add("italy")
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
        Prefs.setRadarBeep(this, binding.radarBeepSwitch.isChecked)
        Prefs.setDetectorSound(this, binding.detectorSoundSwitch.isChecked)
        Prefs.setStartupSweep(this, binding.startupSweepSwitch.isChecked)
        Prefs.setScanIntro(this, binding.scanIntroSwitch.isChecked)
        Prefs.setVibrateEnabled(this, binding.vibrateSwitch.isChecked)
        Prefs.setVoiceEnabled(this, binding.voiceSwitch.isChecked)
        Prefs.setShortVoice(this, binding.shortVoiceSwitch.isChecked)
        Prefs.setDiscreetMode(this, binding.discreetSwitch.isChecked)
        Prefs.setThreatBubble(this, binding.bubbleSwitch.isChecked)
        if (!binding.bubbleSwitch.isChecked) com.rfsentinel.app.ui.ThreatBubble.hide(this)
        Prefs.setFloatingMap(this, binding.floatingMapSwitch.isChecked)
        if (!binding.floatingMapSwitch.isChecked) com.rfsentinel.app.ui.FloatingMap.hide(this)
        val dedupeMin = binding.dedupeInput.text.toString().toLongOrNull() ?: 5L
        Prefs.setDedupeWindowMs(this, dedupeMin.coerceAtLeast(1) * 60000L)

        Prefs.setFollowerAlerts(this, binding.followSwitch.isChecked)
        Prefs.setFollowMinMinutes(this, (binding.followMinutesInput.text.toString().toIntOrNull() ?: 10).coerceIn(2, 240))
        Prefs.setFollowMinMeters(this, (binding.followMetersInput.text.toString().toIntOrNull() ?: 800).coerceIn(100, 50_000))
        Prefs.setGpsTaggingEnabled(this, binding.gpsSwitch.isChecked)
        Prefs.setPinpointFlagged(this, binding.pinpointSwitch.isChecked)
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
        Prefs.setAutoUpdateCheck(this, binding.autoUpdateSwitch.isChecked)

        Prefs.setRetentionDays(this, (binding.retentionInput.text.toString().toIntOrNull() ?: 90).coerceAtLeast(0))

        // Re-deliver a start command so a running scanner picks up the changes.
        if (ScanForegroundService.isRunning) ScanForegroundService.start(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
