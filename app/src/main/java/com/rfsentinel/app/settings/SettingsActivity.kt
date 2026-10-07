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
        const val WAZE_WARNING_SHORT = "Use at your own risk. Reports come from OpenWeb Ninja, a third-party paid service " +
            "RF Sentinel doesn't run or endorse; your key, your account, your responsibility."
        const val WAZE_WARNING = "Waze police reports are read through OpenWeb Ninja's Waze API with your own API key.\n\n" +
            "• Use this feature at your own risk.\n" +
            "• OpenWeb Ninja and Waze are third-party services. RF Sentinel isn't affiliated with them, doesn't endorse them, " +
            "and does not grant you any right to use them or their data: you're responsible for following their terms and your local laws.\n" +
            "• Each request sends a box of about 4 km around your position to OpenWeb Ninja, and may cost you money on your plan.\n" +
            "• Reports are unverified crowd reports and can be wrong or out of date.\n\n" +
            "It stays off unless you enable it, and you can turn it off at any time."

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

    private fun sections() = listOf(
        Section("general", binding.headerGeneral, binding.sectionGeneral,
            "Setup wizard: walks through the permissions and main choices again.\n\n" +
                "Update check: asks GitHub at most every 6 hours and only speaks up when a new version is out.\n\n" +
                "Discreet mode: hides details on the lock screen and in notifications.\n\n" +
                "Screen: \"On while charging\" suits a car or a desk; \"Normal\" turns off like other apps."),
        Section("appearance", binding.headerAppearance, binding.sectionAppearance,
            "Banner image: shown above the animated header in the styled themes (Night Drive, Synthwave...); " +
                "in DedSec and fsociety it replaces the poster. The image is copied privately into the app."),
        Section("scanning", binding.headerScanning, binding.sectionScanning,
            "Bluetooth intensity: Battery saver misses short broadcasts.\n\n" +
                "WiFi interval: Android allows about one WiFi scan per 30 s unless \"Wi-Fi scan throttling\" " +
                "is turned off in Developer options; then down to 5 s works, at some battery cost.\n\n" +
                "Background scanning: lets the scan keep running with the screen off.\n\n" +
                "Remove from the list: how long an ordinary device stays in the list and radar once it stops being heard (default 30 s Bluetooth, 60 s WiFi). Flagged devices follow these too; favourite and following devices stay 3 minutes. " +
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
                "Waze: police reports within 2 km through your own OpenWeb Ninja key, at your own risk.\n\n" +
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
                "(\"Body cam\", \"Police car\", \"Speed camera, 50\")."),
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
                "• RTL-SDR dongle: notices two-way radios transmitting nearby (signal strength only, nothing is decoded).\n\n" +
                "OUI-SPY over Bluetooth needs its App-Controlled firmware. Relay mode passes on every network and device it hears " +
                "so RF Sentinel's own lists check them (the board also sends standard Wi-Fi scan probes).\n\n" +
                "Adapter not working? Export its log and send it to the developer."),
        Section("location", binding.headerLocation, binding.sectionLocation,
            "Traces: records your route while scanning, to view on the map.\n\n" +
                "Saving the GPS position with matches is privacy-sensitive: the log then shows where you were."),
        Section("data", binding.headerData, binding.sectionData,
            "Everything stays on this phone. History older than the set number of days is deleted (0 keeps it forever).\n\n" +
                "Forget device history: new / returning status, detect counts and the cell towers remembered for the " +
                "fake-cell checks start over. Favorites are kept.\n\n" +
                "Changes on this screen are saved automatically.")
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
            if (i > 0) parent.addView(View(this).apply { setBackgroundColor(divider) }, at,
                android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp.toInt())))
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
        wazeKeyInput = android.widget.EditText(this).apply {
            hint = "OpenWeb Ninja API key"
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(com.rfsentinel.app.util.SecureStore.get(this@SettingsActivity, com.rfsentinel.app.online.OnlineWatch.WAZE_KEY_NAME).orEmpty())
        }
        box.addView(wazeKeyInput)
        wazeStatusText = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
        box.addView(wazeStatusText)
        showNote(wazeStatusText, com.rfsentinel.app.online.OnlineWatch.wazeStatus)
        box.visibility = if (sw.isChecked) View.VISIBLE else View.GONE
        binding.categoryContainer.addView(box)
        sw.setOnCheckedChangeListener { _, on ->
            if (on && !Prefs.wazeAccepted(this)) {
                sw.isChecked = false
                AlertDialog.Builder(this)
                    .setTitle("Waze police reports: use at your own risk")
                    .setMessage(WAZE_WARNING)
                    .setPositiveButton("I understand, enable") { _, _ ->
                        Prefs.setWazeAccepted(this, true)
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
        setupMapSection()
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
