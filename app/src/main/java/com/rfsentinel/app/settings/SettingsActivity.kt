package com.rfsentinel.app.settings

import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.rfsentinel.app.ui.CustomThemes
import com.rfsentinel.app.ui.ThemeManager
import com.rfsentinel.app.ui.ThemeImport
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.widget.AppCompatSpinner
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rfsentinel.app.R
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.databinding.ActivitySettingsBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.usb.UsbPick
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Opens Settings on one section (expanded and scrolled to): e.g. [SECTION_MAP] from the map. */
        const val EXTRA_SECTION = "open_section"
        const val SECTION_MAP = "map"
        const val SECTION_NAV = "navigation"
        const val SECTION_DATA = "data"
        private const val PRESETS_INFO = "Adds lists of equipment known to be used by police in that region. The built-in detection " +
            "(body cams, Flock, drones, trackers, glasses) works everywhere.\n\n" +
            "Global: body cams (Axon, Zepcam, WatchGuard...), Flock, ShotSpotter, traffic cameras, radios.\n" +
            "Canada: adds Cyberkar in-car systems, Getac body cams, Genetec plate readers.\n" +
            "France: Motorola VB400 body cams of the Police nationale and Gendarmerie, Zepcam, TETRAPOL radios.\n" +
            "United Kingdom: Motorola and Axon body cams, Sepura / Motorola Airwave radios, Jenoptik ANPR cameras.\n" +
            "Portugal: Motorola SIRESP radios of PSP, GNR and INEM.\n" +
            "Germany: Motorola VB400 body cams, Motorola and Sepura BOS digital radios.\n" +
            "Spain: Axon body cams of the Policia Nacional, Teltronic radios.\n" +
            "Italy: Leonardo / Selex police radios, Selea and Elsag plate readers.\n" +
            "Netherlands: Zepcam body cams."
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
        private val SCAN_MODES = intArrayOf(
            ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_LOW_POWER
        )
    }
    private lateinit var binding: ActivitySettingsBinding
    private var stopObservingPrefetch: (() -> Unit)? = null
    private var stopObservingDeflock: (() -> Unit)? = null
    private val categorySwitches = mutableMapOf<Category, SwitchMaterial>()
    /** What follows each category switch (its alert chips, its own controls): one listener calls them all. */
    private val switchFollowers = HashMap<SwitchMaterial, MutableList<(Boolean) -> Unit>>()

    private fun SwitchMaterial.onToggle(f: (Boolean) -> Unit) {
        val list = switchFollowers.getOrPut(this) {
            mutableListOf<(Boolean) -> Unit>().also { l -> setOnCheckedChangeListener { _, on -> l.toList().forEach { it(on) } } }
        }
        list += f
    }
    private var airTagSwitch: SwitchMaterial? = null
    private var wazeKeyInput: android.widget.EditText? = null
    private var aircraftStatusText: android.widget.TextView? = null
    private var wazeStatusText: android.widget.TextView? = null

    private fun SwitchMaterial.bindLive(on: Boolean, save: (Boolean) -> Unit) {
        isChecked = on
        setOnCheckedChangeListener { _, value -> save(value) }
    }

    private fun LinearLayout.toggle(text: String, on: Boolean, details: String? = null, save: (Boolean) -> Unit) =
        SwitchMaterial(this@SettingsActivity).apply {
            this.text = text
            bindLive(on, save)
        }.also { addView(if (details == null) it else infoRow(it, details)) }

    private fun LinearLayout.label(text: String, small: Boolean = false) = TextView(this@SettingsActivity).apply {
        this.text = text
        textSize = if (small) 12f else 14f
        if (small) alpha = 0.75f
        setPadding(0, if (small) 0 else dp(10), 0, dp(2))
    }.also { addView(it) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun spinner(labels: List<String>, selected: Int, description: String, save: (Int) -> Unit) =
        AppCompatSpinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selected)
            contentDescription = description
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = save(position)
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

    private fun slider(from: Int, to: Int, current: Int, step: Int = 1, description: String,
                       format: ((Int) -> String)? = null, save: (Int) -> Unit) = Slider(this).apply {
        valueFrom = from.toFloat()
        valueTo = to.toFloat()
        stepSize = step.toFloat()
        value = current.coerceIn(from, to).toFloat()
        contentDescription = description
        if (format != null) setLabelFormatter { format(it.toInt()) }
        addOnChangeListener { _, value, fromUser -> if (fromUser) save(value.toInt()) }
    }

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
        MaterialAlertDialogBuilder(this)
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

    /** Lists the phone's music apps (those with a media service or Play button receiver) to start in the car. */
    private fun chooseMusicApp() {
        val apps = com.rfsentinel.app.receiver.CarMusic.apps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "No music app found on this phone", Toast.LENGTH_LONG).show()
            binding.carMusicSwitch.isChecked = Prefs.carMusicApp(this) != null && binding.carMusicSwitch.isChecked
            return
        }
        val current = apps.indexOfFirst { it.first == Prefs.carMusicApp(this) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Music app to play in the car")
            .setSingleChoiceItems(apps.map { it.second }.toTypedArray(), current) { d, i ->
                Prefs.setCarMusicApp(this, apps[i].first)
                binding.carMusicSwitch.isChecked = true
                if (Prefs.carDevices(this).isEmpty()) chooseCarDevices()
                updateMusicButton()
                d.dismiss()
            }
            .setNegativeButton("Cancel") { _, _ -> if (Prefs.carMusicApp(this) == null) binding.carMusicSwitch.isChecked = false }
            .show()
    }

    private fun editIgnoredSsids() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            setText(Prefs.ignoredSsidWords(this@SettingsActivity).joinToString("\n"))
            hint = "One word per line, e.g. Rogers"
            minLines = 3; maxLines = 10; gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val picked = Prefs.ignoredSsidPresets(this)
        val presets = Prefs.ISP_PRESETS.map { (region, words) ->
            region to com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                text = "$region telecoms\n" + words.joinToString(", ")
                isChecked = region in picked
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ignored network names")
            .setMessage("WiFi networks whose name contains one of these words are never flagged.")
            .setView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, 0, pad, 0)
                presets.forEach { addView(it.second) }
                addView(input)
            })
            .setPositiveButton("Save") { _, _ ->
                Prefs.setIgnoredSsidPresets(this, presets.filter { it.second.isChecked }.map { it.first }.toSet())
                Prefs.setIgnoredSsidWords(this, input.text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct())
                updateIgnoredSsidsButton()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateIgnoredSsidsButton() {
        val n = Prefs.ignoredSsidAll(this).size
        binding.ignoredSsidsButton.text = if (n == 0) "Ignored network names" else "Ignored network names · $n"
    }

    private fun updateMusicButton() {
        val app = Prefs.carMusicApp(this)
        binding.carMusicButton.text = app?.let { "Music app: " + com.rfsentinel.app.receiver.CarMusic.label(this, it) } ?: "Choose music app"
        binding.carMusicButton.shownIf(binding.carMusicSwitch.isChecked)
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
        binding.carDevicesText.shownIf(names.isNotEmpty())
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
        binding.trackerIgnoreText.shownIf(parts.isNotEmpty())
        binding.trackerIgnoreButton.shownIf(n > 0 || paused)
    }

    /** Known cameras silenced from the map (tap a camera > Ignore alerts). */
    private fun updateIgnoredCamerasText() {
        val n = com.rfsentinel.app.alpr.IgnoredCameras.count(this)
        binding.ignoredCamerasText.text = "$n camera${if (n == 1) "" else "s"} with alerts turned off · faded on the map"
        binding.ignoredCamerasText.shownIf(n > 0)
        binding.ignoredCamerasButton.shownIf(n > 0)
    }

    private fun presetSwitches() = mapOf(
        "global" to binding.presetGlobal, "canada" to binding.presetCanada, "us" to binding.presetUs,
        "france" to binding.presetFrance, "uk" to binding.presetUk, "portugal" to binding.presetPortugal,
        "germany" to binding.presetGermany, "spain" to binding.presetSpain, "italy" to binding.presetItaly,
        "netherlands" to binding.presetNetherlands
    )

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
        binding.voicePauseMusicSwitch.bindLive(Prefs.voicePausesMusic(this)) { Prefs.setVoicePausesMusic(this, it) }
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
            MaterialAlertDialogBuilder(this)
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

    /** RTL-SDR radio › Record P25 voice calls: the kept .wav files, to send. */
    private fun shareP25Recordings() {
        val files = com.rfsentinel.app.sdr.P25Record.files(this)
        if (files.isEmpty()) { Toast.makeText(this, "No P25 calls recorded yet", Toast.LENGTH_SHORT).show(); return }
        val uris = ArrayList(files.mapNotNull {
            runCatching { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }.getOrNull()
        })
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/wav")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, "RF Sentinel P25 recordings (${files.size})")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share P25 recordings"))
    }

    /** The recording's size and frame count, refreshed every second while it runs. */
    private fun updatePcap() {
        if (isFinishing || isDestroyed) return
        val rec = com.rfsentinel.app.usb.PcapRecorder
        val file = rec.file?.takeIf { it.exists() }
        val mb = String.format(java.util.Locale.US, "%.1f MB", rec.bytes / 1_048_576.0)
        binding.pcapText.text = when {
            rec.active -> "● Recording · ${rec.frames} frames · $mb"
            file != null -> "Last recording: ${rec.frames} frames · $mb" + (rec.stoppedBecause?.let { " · stopped: $it" } ?: "")
            else -> "Record what the USB WiFi adapter or V2X board hears, for Wireshark"
        }
        binding.pcapButton.text = if (rec.active) "Stop and share" else "Record frames"
        binding.pcapShareButton.shownIf(!rec.active && file != null)
        binding.pcapText.removeCallbacks(pcapTicker)
        if (rec.active) binding.pcapText.postDelayed(pcapTicker, 1_000)
    }
    private val pcapTicker = Runnable { updatePcap() }

    private fun updateLinkStatus() {
        val link = com.rfsentinel.app.link.LinkStore
        val n = link.members(this).size
        binding.linkStatusText.text = when {
            !link.hasGroup(this) -> "Not linked to other phones"
            !link.enabled(this) -> "Sharing off"
            else -> com.rfsentinel.app.link.PhoneLink.status.ifBlank {
                "$n Sentinel${if (n == 1) "" else "s"} in the group · links while scanning"
            }
        }
    }

    /** External hardware: the drop-downs picking which adapter each driver uses ([com.rfsentinel.app.usb.UsbPick]). */
    private val usbPickRows = ArrayList<View>()

    /** Rebuilt on every return to Settings, so they list what's plugged in now. */
    private fun updateUsbPickers() {
        usbPickRows.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        usbPickRows.clear()
        val all = runCatching { getSystemService(android.hardware.usb.UsbManager::class.java)?.deviceList?.values?.toList() }
            .getOrNull().orEmpty()
        fun add(kind: UsbPick.Kind, under: View, label: String, about: String) {
            val saved = Prefs.usbPick(this, kind.key)
            val ids = mutableListOf<String?>(null)
            val names = mutableListOf("Auto")
            UsbPick.candidates(kind, all).distinctBy { UsbPick.id(it) }.forEach { ids += UsbPick.id(it); names += UsbPick.label(it) }
            if (saved != null && saved !in ids) { ids += saved; names += "${Prefs.usbPickName(this, kind.key) ?: saved} · unplugged" }
            val row = choiceRow(label, names, ids.indexOf(saved)) { i ->
                if (ids[i] == Prefs.usbPick(this, kind.key)) return@choiceRow
                Prefs.setUsbPick(this, kind.key, ids[i], names[i].removeSuffix(" · unplugged"))
                if (kind == UsbPick.Kind.BT) com.rfsentinel.app.usb.UsbBluetooth.pickChanged(this)
            }
            val parent = under.parent as android.view.ViewGroup
            val at = parent.indexOfChild(under) + 1
            parent.addView(row, at)
            info(row, "$about Only one runs at a time. Two of the same model can't be told apart: the first one found is used.", label)
            usbPickRows += parent.getChildAt(at)
        }
        add(UsbPick.Kind.WIFI, binding.usbWifiText, "WiFi adapter", "Which USB WiFi adapter to use when more than one is " +
            "plugged in. Auto takes the first one found. A new pick starts the next time scanning starts.")
        add(UsbPick.Kind.BT, binding.usbBtText, "Bluetooth adapter", "Which USB Bluetooth adapter to use when more than one is " +
            "plugged in. A combo WiFi + Bluetooth adapter (like the ALFA AWUS036AXML) only has the small antenna inside its " +
            "case for Bluetooth, so Auto takes a Bluetooth-only dongle first. A new pick takes over right away while scanning.")
        add(UsbPick.Kind.SDR, binding.sdrStatusText, "SDR", "Which RTL-SDR or HackRF to use when more than one is plugged in. " +
            "Auto takes the first one found. A new pick starts the next time scanning starts.")
    }

    private fun updateEspStatus() {
        val s = com.rfsentinel.app.esp.EspBoards.status
        binding.espStatusText.text = s.ifBlank { "ESP32 board on USB: not connected" }
        binding.usbWifiText.text = com.rfsentinel.app.usb.UsbWifi.status.ifBlank { "USB WiFi adapter: not connected" }
        binding.usbBtText.text = com.rfsentinel.app.usb.UsbBluetooth.status.ifBlank {
            if (Prefs.usbBluetooth(this)) "USB Bluetooth adapter: not connected" else "USB Bluetooth adapter: off"
        }
        binding.sdrStatusText.text = com.rfsentinel.app.sdr.SdrRadio.status.ifBlank { "RTL-SDR / HackRF: not connected" }
        val board = Prefs.ouiSpyBoard(this)
        binding.ouiSpyText.text = if (board == null) "OUI-SPY over Bluetooth: not paired"
        else com.rfsentinel.app.esp.OuiSpyBle.status.ifBlank { "OUI-SPY $board: connects while scanning" }
        binding.ouiSpyButton.text = if (board == null) "Pair OUI-SPY board" else "Change or forget OUI-SPY board"
    }

    private val ouiSpyConnectPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) pairOuiSpy() }

    /**
     * Picks the OUI-SPY board among the boards nearby: a few seconds' search for the OUI-SPY service (scanning
     * needn't be running), plus any board the scan already heard by name ("OUI-SPY-xxxx").
     */
    private fun pairOuiSpy() {
        if (!com.rfsentinel.app.esp.OuiSpyBle.canConnect(this)) {
            ouiSpyConnectPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val looking = MaterialAlertDialogBuilder(this)
            .setTitle("Looking for OUI-SPY boards…")
            .setMessage("Keep the board powered and close by.")
            .setNegativeButton("Cancel", null)
            .show()
        com.rfsentinel.app.esp.OuiSpyBle.find(this) { found ->
            if (!looking.isShowing || isFinishing || isDestroyed) return@find
            looking.dismiss()
            showOuiSpyBoards(found)
        }
    }

    /** Settings > OUI-SPY engines: which of the board's detection engines run (applied to a running scan right away). */
    private fun chooseOuiSpyEngines() {
        val all = com.rfsentinel.app.esp.OuiSpyBleProtocol.ENGINE_NAMES
        val ids = all.keys.toList()
        val on = Prefs.ouiSpyEngines(this)
        val checked = BooleanArray(ids.size) { ids[it] in on }
        MaterialAlertDialogBuilder(this)
            .setTitle("OUI-SPY engines")
            .setMultiChoiceItems(all.values.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("OK") { _, _ ->
                Prefs.setOuiSpyEngines(this, ids.filterIndexed { i, _ -> checked[i] })
                if (com.rfsentinel.app.service.ScanForegroundService.isRunning) com.rfsentinel.app.service.ScanForegroundService.start(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class Board(val mac: String, val name: String, val rssi: Int)

    private fun showOuiSpyBoards(found: List<com.rfsentinel.app.esp.OuiSpyBle.Found>) {
        val heard = com.rfsentinel.app.service.DeviceRegistry.snapshot()
            .filter { it.name?.startsWith(com.rfsentinel.app.esp.OuiSpyBleProtocol.NAME_PREFIX) == true }
            .map { Board(it.mac.uppercase(), it.name!!, it.rssi) }
        val boards = (found.map { Board(it.address.uppercase(), it.name ?: "OUI-SPY board", it.rssi) } + heard)
            .distinctBy { it.mac }
            .sortedByDescending { it.rssi }
        val current = Prefs.ouiSpyBoard(this)
        val labels = boards.map { "${it.name}  (${it.mac}, ${it.rssi} dBm)" }.toMutableList()
        if (current != null) labels += "Forget the paired board"
        if (labels.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No OUI-SPY board found")
                .setMessage("Check that:\n\n" +
                    "• the board runs the Bluetooth \"App-Controlled\" firmware and has power\n" +
                    "• the OUI-SPY app isn't connected to it: a board connected to a phone stops advertising. " +
                    "Force stop that app in Android's app settings, then unplug the board for a few seconds\n" +
                    "• it isn't paired in Android's Bluetooth settings: RF Sentinel connects to it directly\n" +
                    "• Bluetooth is on and RF Sentinel may use Nearby devices")
                .setPositiveButton("Try again") { _, _ -> pairOuiSpy() }
                .setNegativeButton("OK", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
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
        val restore: () -> Unit, val expand: () -> Unit, val setOpen: (Boolean) -> Unit
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
                "All can go down to 5 s (WiFi only when \"Wi-Fi scan throttling\" is off in Developer options). " +
                "External hardware: devices heard only by an ESP32 board or a USB WiFi adapter."),
        Section("autostart", binding.headerAutoStart, binding.sectionAutoStart,
            "When the phone starts: begins scanning after a reboot.\n\n" +
                "In the car: starts scanning when the phone connects to your car's Bluetooth and stops when you leave. " +
                "Android Auto also starts it when RF Sentinel opens on the car screen.\n\n" +
                "Record a trace in the car: records a GPS trace from the moment the phone connects to your car until you leave, " +
                "then saves it with your other traces."),
        Section("detect", binding.headerDetect, binding.sectionDetect,
            "Each type of equipment can be turned on or off. Network / home cameras are off by default (many false alarms).\n\n" +
                "GPS / satellite checks cover every system the phone hears: GPS, GLONASS, Galileo, BeiDou, QZSS and NavIC.\n\n" +
                "Two-way radio needs an RTL-SDR dongle or a HackRF on USB.\n\n" +
                "Police aircraft and Waze reports are online sources, off by default. Aircraft: every minute, a position " +
                "rounded to about 1 km goes to the community ADS-B feeds adsb.fi / adsb.lol; matched against a list of " +
                "about 1,400 US and Canadian law-enforcement aircraft, plus unlisted aircraft circling low overhead. Only a strong match " +
                "within the alert distance alerts; the rest are listed and logged. With an RTL-SDR " +
                "set up as an ADS-B receiver, aircraft are also heard directly, and the online feeds can be turned off. " +
                "Waze: crowd reports (police, and any other kind you tick), at your own risk. Either through your own OpenWeb Ninja key " +
                "(Waze never sees you), or Waze direct: no key, free, but an anonymous Waze account on this phone sends Waze (Google) your " +
                "IP address and a position rounded to a 1 km grid.\n" +
                "• Alert range: how close a report must be to alert. Map range: how far the map shows them.\n" +
                "• Check every: how often Waze is asked. Slower when parked, faster on fast roads (direct), if you leave that on. " +
                "OpenWeb Ninja bills every check.\n" +
                "• Only alert for reports ahead: while you drive, only reports on your road ahead of you alert (checked against " +
                "the map's roads, from OpenFreeMap); the others stay on the list but stay quiet.\n" +
                "• Call out again: speaks at 1 km, 500 m and 200 m as a report gets closer (voice alerts on, or in the car).\n" +
                "• Each kind of report has its own level: sound and voice, notification only, or silent (list and map only).\n" +
                "• Pull the list down, or use the menu, to check right now. Waze status shows what is sent, and when.\n\n" +
                "Mobile printers: Zebra and Brother RuggedJet / PocketJet printers, the e-ticket printers in patrol cars, " +
                "but also used by couriers, parking officers and field technicians. Until you set it, it follows Public safety.\n\n" +
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
                "• Netherlands: Zepcam body cams.\n" +
                "French drone electronic IDs are decoded in every region. With a European preset on (and not United States), " +
                "Flock detection is off: Flock isn't used there."),
        Section("alerts", binding.headerAlerts, binding.sectionAlerts,
            "Threshold: \"Weak\" alerts on every match (more false alarms); \"Strong only\" on near-certain ones.\n\n" +
                "Vibration: 1 pulse weak, 2 probable, 3 strong.\n\n" +
                "Radar-detector beeps (off by default): after a device alert, keeps beeping faster as its signal " +
                "gets stronger, like a radar detector; stops when it's gone or after 2 minutes.\n\n" +
                "Radar-detector sound (off by default): radar-detector alert sounds instead of the plain beeps - " +
                "by strength (effect 1 strong, 2 probable, 3 weak, 4 following) or always the effect you pick - then " +
                "the short voice says what it is (\"Body cam\"); the proximity beeps use a pulse of the same effect. " +
                "\"GPS connected\" when the GPS locks (can be turned off); optional start beep (power-on sweep) when a scan starts, off by default.\n\n" +
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
        Section("navigation", binding.headerNav, binding.sectionNav,
            "Turn-by-turn directions: the search button on the map finds a place (or long-press the map), shows up to three " +
                "routes with the time each takes, and guides you with a banner, lane arrows and spoken directions.\n\n" +
                "Spoken directions play through the navigation voice (music dips under them, as in Maps) and follow the voice " +
                "speed and loudness chosen under Alerts. They are separate from the alert mute and snooze.\n\n" +
                "Network use: routes come from Valhalla on the OpenStreetMap Germany server and places from Photon (komoot), and OpenStreetMap's Nominatim too for a search you submit. They " +
                "see what you search for, where you start, stop and finish, and your IP address - never what you scan. Nothing is " +
                "asked until you search, long-press the map or start a route; search sends your position rounded to about 1 km.\n\n" +
                "Looking for petrol, charging, food and the like asks OpenStreetMap's Overpass servers (as the camera downloads do): " +
                "your position rounded to about 1 km, or the road ahead as points rounded to about 200 m.\n\n" +
                "Avoiding speed cameras and plate readers: the route server is asked for the route again with the road of each " +
                "camera on it left out (a few times), so it also sees those places on your route - nothing else about the cameras " +
                "or what you scan.\n\n" +
                "Not included: live traffic (the arrival time starts from the route's usual driving time and then follows how you really " +
                "drive), speed limits and offline routes.\n\n" +
                "Home, Work, starred and recent places are kept on this phone only."),
        Section("radar", binding.headerRadar, binding.sectionRadar,
            "The radar on the main screen: closer to the centre = stronger signal.\n\n" +
                "Top of the radar: the way you face (it turns with the phone, from the compass, set right by the GPS while you " +
                "drive) or north.\n\n" +
                "Dots without a direction: a phone can't tell which way a signal comes from, so most dots sit at a made-up " +
                "angle. They can stay put on screen, or turn with north like the dots whose direction is known.\n\n" +
                "Directions on the radar: alerts that broadcast their position or were pinpointed, and the device you " +
                "locate once a turn with the phone on your chest has found its side."),
        Section("map", binding.headerMap, binding.sectionMap,
            "What the map shows.\n\n" +
                "Plate, speed and red-light cameras: from OpenStreetMap (Known cameras has the downloads, warnings and CCTV).\n\n" +
                "Cell towers: placed where your phone heard each one strongest - an estimate, not the tower's real position.\n\n" +
                "The map button turns the map: north up, then with your direction of travel, then the 3D driving view - like " +
                "a car navigation app, your arrow low on the screen, closer in town and further ahead at speed.\n\n" +
                "3D view angle: how far the driving view leans back; on fast roads it leans up to 10° further (60° at most).\n\n" +
                "Speedometer: your speed (from the GPS) in the bottom left corner of the live map, and next to it the posted speed " +
                "limit as a road sign: a red ring, or a \"SPEED LIMIT\" plate where distances are in miles. Your speed turns red " +
                "above the limit. While you are guided the limits along the route come with it (one more question to the route server " +
                "for the stretch ahead, nothing about where else you go). Not navigating, the limit needs the extra switch: it asks the route " +
                "server which road you are on about every 12 seconds while you drive, so that server learns where you drive - it " +
                "is off until you turn it on. Limits are OpenStreetMap's: where none is mapped there is no sign.\n\n" +
                "The full screen map has a button that hides the toolbar, the system bars and the bottom bar (the speedometer, " +
                "alert cards and, while guiding, the turn banner stay).\n\n" +
                "Position marker: your position on the map as an arrow or a car seen from above - sedan, hatchback, wagon, SUV, pickup, " +
                "sports car, racer, convertible, mini, van, truck, taxi or motorcycle - in 16 colours, from small to very large. " +
                "It turns with your direction of travel.\n\n" +
                "Recenter: while you drive, the map goes back to following you ten seconds after you last touched it (like Google Maps); " +
                "parked it stays where you put it. The speedometer's card turns red while you are over the posted limit.\n\n" +
                "Default zoom: how close the map follows you. The 3D driving view pulls back from it as you speed up; the " +
                "north-up and heading-up views sit half a level wider. 17 is close to Waze.\n\n" +
                "Map provider: OpenFreeMap (OpenStreetMap data, free, no account) or Mapbox (Mapbox's own map: 3D buildings, " +
                "day and night light). Mapbox loads its map from Mapbox's servers, which see the area you look at, like any " +
                "online map; its usage statistics are switched off. The floating mini map always uses OpenFreeMap."),
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
                "MT7921AU (AWUS036AXML, WiFi 6E: 2.4, 5 and 6 GHz, experimental), " +
                "RTL8187 (AWUS036H), RT3070 (AWUS036NH / NEH), RT5370 / RT5372 (experimental), MT7610U (experimental), AR9271 (AWUS036NHA, experimental).\n\n" +
                "AWUS036ACS 5 GHz (experimental, off by default): the RTL8811AU / 8821AU also hops 5 GHz channels 36-48 and " +
                "149-165. Untested on every unit: if it misbehaves, turn it off and send the adapter log. Applies when the adapter is plugged in.\n\n" +
                "Adapter light: the AWUS036ACS blinks with received traffic; switch it off to keep it dark (for example in a car at night). " +
                "Only adapters whose light the app can control have this option (the RTL8811AU / 8821AU); the others keep their own behaviour. " +
                "Applies at once.\n\n" +
                "• RTL-SDR dongle or HackRF: notices two-way radios transmitting nearby (signal strength only, nothing is decoded). " +
                "The HackRF is only ever used to receive.\n\n" +
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
        Section("link", binding.headerLink, binding.sectionLink,
            "Link RF Sentinel phones near each other over Bluetooth: every linked phone shows the same devices on its list, " +
                "radar and map, whichever phone heard them first - including what each phone's ESP32 board, USB WiFi adapter " +
                "or RTL-SDR hears. Alerts sound on every phone and say which Sentinel heard the device.\n\n" +
                "Linking: tap \"Link up a Sentinel\" on one phone and \"Join a Sentinel\" on the other, then check that both " +
                "screens show the same 6 digits. Three or more phones: link each new one to any phone of the group.\n\n" +
                "Works while scanning, up to about 50-100 m between phones (Bluetooth range); a phone in reach of two others " +
                "passes on what it hears. Everything sent is encrypted for the phones of the group only. No internet, no account, " +
                "and no Android Bluetooth pairing."),
        Section("radio", binding.headerRadio, binding.sectionRadio,
            "An RTL-SDR dongle or a HackRF on USB notices two-way radios transmitting nearby, from signal strength only - nothing is " +
                "decoded or recorded. Needs Two-way radio on (What to detect).\n\n" +
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
                "then Watch this list to report its channels whenever they're active, " +
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
                "Check for updates: asks GitHub for a newer version now. \"Install now\" downloads it and installs it over this " +
                "one (Android asks you once to allow RF Sentinel to install apps, then shows its own Update card). History and " +
                "settings are kept, and scanning stops while it installs.\n\n" +
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
                    MaterialAlertDialogBuilder(this@SettingsActivity).setTitle(title).setMessage(s.info)
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
            }, setOpen = { want ->
                open = want
                ui.edit().putBoolean(s.key, open).apply()
                show()
            })
        }
    }

    // ---- ⓘ details beside a setting -----------------------------------------------------------

    /** What an ⓘ says (its tag), so the search finds a setting by its details too. */
    private class InfoText(val text: String)

    /** The ⓘ: tapping it shows [text] under [title] (the setting's own label when not given). */
    private fun infoIcon(text: String, title: () -> String): View {
        val dp = resources.displayMetrics.density
        val ripple = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }.resourceId
        return android.widget.TextView(this).apply {
            this.text = "ⓘ"
            textSize = 18f
            alpha = 0.7f
            tag = InfoText(text)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
            setBackgroundResource(ripple)
            contentDescription = "About ${title()}"
            setOnClickListener { MaterialAlertDialogBuilder(this@SettingsActivity).setTitle(title()).setMessage(text).setPositiveButton("OK", null).show() }
        }
    }

    private fun labelOf(view: View) = ((view as? android.widget.TextView)?.text ?: "").toString().removePrefix("▾  ").substringBefore(":").trim()

    /** [view] with its ⓘ at the end of the row (for a view not yet added anywhere). */
    private fun infoRow(view: View, text: String, title: String? = null): View {
        val wrap = (view.layoutParams?.width ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT) == android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(view, if (wrap) android.widget.LinearLayout.LayoutParams(-2, -2)
                else android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(infoIcon(text) { title ?: labelOf(view) })
            // A hidden setting takes its ⓘ with it.
            if (view.visibility == View.GONE) visibility = View.GONE
        }
    }

    /**
     * A What to detect row: the label with its ⓘ ([info]) right after the text, then the switch and its ▾ ([fold]).
     * A row with no ▾ keeps the slot empty, so every switch in the list lines up.
     */
    private fun detectRow(sw: SwitchMaterial, info: String? = null, title: String? = null, fold: View? = null): View {
        val slot = fold ?: foldIcon().apply { visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        if (info == null) return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(sw, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(slot)
        }
        // The label leaves the switch so the ⓘ can sit right after its text; tapping the label still flips the switch.
        val name = sw.text.toString()
        val label = android.widget.TextView(this).apply {
            text = name
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sw.textSize)
            setTextColor(sw.textColors)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { sw.toggle() }
        }
        sw.text = null
        sw.contentDescription = name
        val icon = infoIcon(info) { title ?: name }
        return object : android.widget.LinearLayout(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                // A long label wraps before the ⓘ, switch and ▾, instead of pushing them off the row.
                val free = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
                var rest = 0
                for (v in listOf(icon, sw, slot)) { measureChild(v, widthSpec, heightSpec); rest += v.measuredWidth }
                label.maxWidth = (free - rest).coerceAtLeast(0)
                super.onMeasure(widthSpec, heightSpec)
            }
        }.apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(label)
            addView(icon)
            addView(View(this@SettingsActivity), android.widget.LinearLayout.LayoutParams(0, 0, 1f))
            addView(sw)
            addView(slot)
        }
    }

    /** The ▾ that opens a category's alert chips, styled like an ⓘ. */
    private fun foldIcon() = android.widget.TextView(this).apply {
        val dp = resources.displayMetrics.density
        text = "▾"; textSize = 18f; alpha = 0.7f
        gravity = android.view.Gravity.CENTER
        minWidth = (36 * dp).toInt()
        setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
        setBackgroundResource(android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }.resourceId)
    }

    /** Puts an ⓘ at the end of [view]'s row, where it already sits. */
    private fun info(view: View, text: String, title: String? = null) {
        val parent = view.parent as? android.view.ViewGroup ?: return
        val at = parent.indexOfChild(view)
        val lp = view.layoutParams
        parent.removeViewAt(at)
        parent.addView(infoRow(view, text, title), at, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            (lp as? android.view.ViewGroup.MarginLayoutParams)?.let { setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
        })
    }

    /** The unit after a number field ("min", "s", "m", "days"), so the label doesn't have to say it. */
    private fun withUnit(field: android.widget.EditText, unit: String) {
        val parent = field.parent as? android.view.ViewGroup ?: return
        val at = parent.indexOfChild(field)
        val lp = field.layoutParams
        parent.removeViewAt(at)
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(field, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = unit; alpha = 0.75f
                setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
            })
        }
        parent.addView(row, at, lp)
    }

    /** "Probable" with a small "recommended" after it. */
    private fun tagged(main: String, tag: String): CharSequence = android.text.SpannableStringBuilder(main).apply {
        append("   ")
        val start = length
        append(tag)
        setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, 0)
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, length, 0)
    }

    /** A long title split in the label and its details: "Drone (Remote ID / drone maker)", "Hacking tools: Flipper...". */
    private fun splitTitle(t: String): Pair<String, String?> {
        val p = t.indexOf(" (")
        if (p > 0 && t.endsWith(")")) return t.substring(0, p) to t.substring(p + 2, t.length - 1).replaceFirstChar { it.uppercase() }
        val c = t.indexOf(": ")
        if (c > 0) return t.substring(0, c) to t.substring(c + 2).replaceFirstChar { it.uppercase() }
        return t to null
    }

    /** The ⓘ of the settings laid out in the layout file, and the units beside their number fields. */
    private fun addDetails() {
        info(binding.autoUpdateSwitch, "When RF Sentinel opens, it asks GitHub whether a newer release is out. Off: nothing is sent until you tap Check for updates.")
        info(binding.prereleaseSwitch, "Update checks also offer prerelease builds: test versions published on GitHub before " +
            "a full release, with the newest features and more bugs. Off: only full releases are offered.")
        info(binding.discreetSwitch, "Hides what was detected on the lock screen and in notifications.")
        info(binding.screenLabel, "Always on: the screen never turns off while RF Sentinel is open.\n\nOn while charging: stays on in the car or on a desk (recommended).\n\nNormal: turns off like any other app.")
        info(binding.rotationLabel, "Normal follows the phone's auto-rotate.\n\nAuto-rotate, upside down too: also turns upside down, which phones like the Pixel never do on their own.\n\nAlways upside down: the USB-C port at the top, for an antenna or adapter plugged in.")
        info(binding.bannerLabel, "A picture across the header of the styled themes (a logo or artwork you own). Your custom themes have their own banner, set in the theme maker.")
        info(binding.hideTrustedSwitch, "Devices on your whitelist are left off the list and radar.")
        info(binding.wifiBandLabel, "The channels a USB WiFi adapter hops. 5 and 6 GHz only gives those channels more listening time (police in-car networks are usually on 5 GHz). Adapters that only do 2.4 GHz keep scanning 2.4 GHz.\n\nThe phone's own WiFi always shows every band. An adapter picks up a change when it next starts.")
        info(binding.scanModeLabel, "Maximum finds devices fastest and uses the most battery. Battery saver notices them later.")
        info(binding.intervalLabel, "How often WiFi is scanned. Android allows an app 4 scans in 2 minutes unless WiFi scan throttling is turned off in Developer options.")
        withUnit(binding.intervalInput, "s")
        info(binding.batteryButton, "Opens Android's battery settings: set RF Sentinel to Unrestricted so scanning keeps going with the screen off.")
        info(binding.carAutoSwitch, "Starts scanning when the phone connects to your car's Bluetooth or Android Auto, and stops when you leave.")
        info(binding.carRecordSwitch, "Records a GPS trace when the phone connects to your car's Bluetooth or Android Auto, and saves it when you leave. Starts scanning too if it isn't running. A trace you started yourself is never stopped.")
        info(binding.carMusicSwitch, "When the phone connects to your car's Bluetooth, starts the music app you choose and presses Play, even if the app isn't open. Android doesn't let an app open another app's screen in the background, so its screen stays closed.")
        info(binding.presetLabel, PRESETS_INFO)
        info(binding.thresholdLabel, "Weak: every match, more false alarms.\n\nProbable: recommended.\n\nStrong only: the fewest alerts.\n\n" +
            "Probable and Strong only don't alert on a vehicle router (Cradlepoint, Sierra Wireless) you hear day after day, " +
            "like one in a shop, unless something else backs it. Weak alerts on all of them.")
        binding.thresholdMedium.text = tagged("Probable", "recommended")
        info(binding.alertVolumeLabel, "Which volume the alert sounds, Locate beeps and the alert voice follow on the phone. " +
            "Navigation is the volume Maps' directions use (on most phones the media volume), and music dips under it. " +
            "Alarm is often the loudest.\n\nSilent, vibrate and Do Not Disturb still mute alerts. In the car they always " +
            "play through the car's speakers.")
        info(binding.radarTopLabel, "The way you face: the radar turns with the phone, like a compass, so N moves. North: N stays at the top.")
        info(binding.radarDotsLabel, "Most dots have no real direction (one antenna can't tell). Stay in place keeps them still; turn with north moves them with the compass.")
        info(binding.radarBeepSwitch, "Beeps faster as a flagged device gets closer, like a radar detector.")
        info(binding.detectorSoundSwitch, "Alerts use radar-detector tones (Ka, K and X) and say \"GPS connected\" when the first GPS fix comes in.")
        info(binding.gpsVoiceSwitch, "With the radar-detector sound: says \"GPS connected\" when the GPS locks, and again when it comes back after losing the fix.")
        info(binding.dedupeLabel, "The same device won't alert again until this many minutes have passed.")
        withUnit(binding.dedupeInput, "min")
        info(binding.shortVoiceSwitch, "\"Body cam\", \"Police car\", \"Speed camera, 50\" instead of whole sentences.")
        info(binding.bubbleSwitch, "A small bubble over other apps (Waze, Google Maps...) showing the threat level. Needs \"Display over other apps\".")
        info(binding.floatingMapSwitch, "A small map over other apps, centred on you, with the same device dots as RF Sentinel's map. It always uses OpenFreeMap: a window this small has no room for Mapbox's logo and info button.")
        info(binding.followSwitch, "Warns when a tracker or flagged device keeps moving with you, for at least the time and distance below - whether it stays in range or keeps turning up again: heard at 3 or more places along your way within 2 hours.")
        withUnit(binding.followMinutesInput, "min")
        withUnit(binding.followMetersInput, "m")
        info(binding.mapProviderLabel, "OpenFreeMap: free and open, OpenStreetMap data.\n\nMapbox: Mapbox's Standard map with 3D buildings and day / night light.\n\nThe floating mini map and the Android Auto map always use OpenStreetMap maps.")
        info(binding.mapAutoRecenterSwitch, "While you drive, the map goes back to your position once you've left it alone for the time set under it (Re-center after).")
        info(binding.mapSpeedometerSwitch, "Your speed and the posted limit in a corner of the map; your speed turns red over the limit.")
        info(binding.mapSpeedLimitLiveSwitch, "Shows the posted limit with no destination too, by asking the route server (Valhalla, OpenStreetMap data) about the road you're on. Your position is sent to it.")
        info(binding.autoCamerasSwitch, "Fetches the known cameras (OpenStreetMap) for the area the map shows, as you move it.")
        info(binding.mapCctvSwitch, "Surveillance cameras mapped in OpenStreetMap.")
        info(binding.deflockButton, "Every plate-reader camera in the US and Canada, from DeFlock's OpenStreetMap snapshot. Works offline and refreshes weekly on Wi-Fi.")
        info(binding.disable2gLabel, "2G doesn't check that a tower is real. Turning it off is the best protection against fake cell towers (IMSI catchers).")
        info(binding.cellSecurityLabel, "Android 15 and newer can warn you when a network asks for your phone's identity or turns encryption off.")
        info(binding.ouiSpyRelaySwitch, "Every device the OUI-SPY board hears comes into the list, not just its own matches.")
        info(binding.ouiSpyEnginesButton, "Which of the board's detection engines run: Flock BLE and Flock WiFi (Flock Safety " +
            "cameras), Sky Spy (drones) and Detector. Where there are no Flock cameras, turn the two Flock engines off. " +
            "RF Sentinel's own checks are set under Categories.")
        info(binding.ouiSpyPhoneBleSwitch, "Off: while the OUI-SPY board is connected and live, the phone's own Bluetooth scan " +
            "pauses and the board does the Bluetooth scanning (it hears farther, and faster). It comes back on its own if the " +
            "board drops out. Turn on Relay everything too, or only the board's engine matches come in.")
        info(binding.usbBtSwitch, "Uses the Bluetooth part of a USB adapter as a second Bluetooth radio, next to the phone's own: " +
            "a Bluetooth dongle, or a combo adapter with WiFi and Bluetooth (MT7921AU, Realtek). It only listens for " +
            "advertisements, never connects. On a combo adapter WiFi and Bluetooth share the antennas. Off: only the phone's " +
            "Bluetooth scans.")
        info(binding.linkSaveSwitch, "On: what a linked Sentinel hears is saved in this phone's History too, where that " +
            "Sentinel was, tagged \"via\" and its name. Off: it still shows live here, but only the Sentinel that heard it keeps it. " +
            "Either way each phone keeps its own History: nothing is merged later.")
        info(binding.rtl5gSwitch, "For RTL8811AU / RTL8821AU adapters such as the ALFA AWUS036ACS. Experimental.")
        info(binding.usbLightSwitch, "For RTL8811AU / RTL8821AU adapters such as the ALFA AWUS036ACS: the light blinks with received traffic.")
        info(binding.traceEveryPingSwitch, "A trace normally places each device once, where its signal was strongest. On: it also " +
            "keeps every time it was heard (up to one every 3 s) with your position then, drawn as small dots on the trace map. " +
            "Uses much more storage on long drives; takes effect from the next trace.")
        info(binding.pinpointSwitch, "Works out where a flagged device is from many readings as you move (GPS runs precise every second while one is in range).")
        info(binding.pinpointAlertsSwitch, "Pinpoints and shows the live read card only for devices that alerted you, not for every flagged device (a shop's router you pass daily).")
        info(binding.liveExportSwitch, "Keeps a live copy of matches, devices and radio hits in a folder other apps can read.")
        info(binding.retentionLabel, "How long device history is kept. 0 keeps it forever.")
        withUnit(binding.retentionInput, "days")
        info(binding.exportAllButton, "CSV, JSON, KML, GPX or GeoJSON.")
        info(binding.radarSweepSwitch, "One turn takes about 5 seconds. The line passes the top each time a WiFi scan is requested; Android may still hold back the scan.")
        info(binding.forgetHistoryButton, "New / returning status, detect counts and the remembered cell towers start over. Favorites are kept.")
        info(binding.openWhitelistButton, "Trusted devices: never flagged, and hidden from the list when you choose.")
        info(binding.ignoredSsidsButton, "WiFi networks whose name contains one of these words are never flagged, " +
            "for example carriers' home routers (Rogers, Bell) that look like vehicle routers. Matches where a word starts in the name (Rogers-5G, MyRogers), any case. " +
            "A name that also says police (PD, Sheriff...) is still flagged.\n\n" +
            "Tick Canada or United States telecoms to add their usual router names in one go (off by default). " +
            "Carrier hotspots in patrol cars can carry these names too, so they won't be flagged either.")
    }

    // ---- Search ----------------------------------------------------------------------------

    /** Views tinted as search matches, with the background they had before. */
    private val highlighted = mutableListOf<Pair<View, android.graphics.drawable.Drawable?>>()
    /** Settings the search hid for not matching (shown again when it changes or is cleared). */
    private val searchHidden = mutableListOf<View>()

    /** Radio buttons listed in the order of the choices they stand for. */
    private fun android.widget.RadioGroup.checkIndex(i: Int) = check(getChildAt(i).id)
    private val android.widget.RadioGroup.checkedIndex get() = indexOfChild(findViewById(checkedRadioButtonId))

    /**
     * Shows or hides a setting for the app's own reasons (a switch turned off, nothing to say...). It
     * also takes the setting off the search's list, so clearing the search leaves it the way this set it.
     */
    private fun View.shownIf(on: Boolean) {
        setTag(R.id.search_hidden, null)
        visibility = if (on) View.VISIBLE else View.GONE
    }
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
        searchInput.doAfterTextChanged {
            val q = it?.toString().orEmpty()
            clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            filterSettings(q)
        }
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), 0)
            addView(searchInput, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear)
            addView(foldAllButton())
        }
    }

    /** Folds every section (or, when all are folded, unfolds them all) and goes back to the top. */
    private fun foldAllButton(): View {
        val dp = resources.displayMetrics.density
        val ripple = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }.resourceId
        return android.widget.TextView(this).apply {
            text = "Collapse all"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, currentTextColor))
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setBackgroundResource(ripple)
            fun label() {
                val anyOpen = sectionUi.any { it.body.visibility == View.VISIBLE }
                val want = if (anyOpen) "Collapse all" else "Expand all"
                if (text.toString() != want) { text = want; contentDescription = want } // only on a change: setting text lays out again
            }
            setOnClickListener {
                if (searchInput.text.isNotEmpty()) searchInput.setText("")
                val anyOpen = sectionUi.any { it.body.visibility == View.VISIBLE }
                sectionUi.forEach { it.setOpen(!anyOpen) }
                binding.root.scrollTo(0, 0)
                label()
            }
            // Section headers toggled by hand change what the button does.
            post { label() }
            binding.root.viewTreeObserver.addOnGlobalLayoutListener { label() }
        }
    }

    /**
     * Shows only the sections with a setting (or ⓘ text) matching [raw], unfolded, tints the
     * matching settings and scrolls to the first one. Empty: everything back as it was.
     */
    private fun filterSettings(raw: String) {
        highlighted.forEach { (v, bg) -> v.background = bg }
        highlighted.clear()
        searchHidden.forEach { if (it.getTag(R.id.search_hidden) == true) { it.setTag(R.id.search_hidden, null); it.visibility = View.VISIBLE } }
        searchHidden.clear()
        val q = raw.trim().lowercase()
        folds.values.forEach { it.searching(q.isNotEmpty()) }
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
            // Only the matching settings, unless the section's own name matches.
            if (hits.isNotEmpty() && !s.title.lowercase().contains(q)) (s.body as? android.view.ViewGroup)?.let { narrow(it, hits) }
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

    /**
     * Hides the settings in [box] with no match in [hits]. A setting is its label (or switch, check box,
     * button) with what follows it up to the next one: the choices, number field or options under it.
     * A match in the label shows the whole setting; a match further down shows the label and that part,
     * looking inside an options panel for just the matching options. Returns whether anything is left.
     */
    private fun narrow(box: android.view.ViewGroup, hits: List<View>): Boolean {
        fun holdsHit(v: View) = hits.any { h -> generateSequence(h) { it.parent as? View }.any { it === v } }
        fun startsSetting(v: View): Boolean = when (v) {
            is android.widget.RadioGroup -> false
            is android.widget.EditText -> false
            is android.widget.TextView -> true // a label, switch, check box or button
            is android.widget.LinearLayout -> v.orientation == android.widget.LinearLayout.HORIZONTAL &&
                v.childCount > 0 && startsSetting(v.getChildAt(0)) // a label or switch with its ⓘ
            else -> false
        }
        fun hide(v: View) {
            if (v.visibility == View.GONE) return
            v.visibility = View.GONE
            v.setTag(R.id.search_hidden, true)
            searchHidden += v
        }
        val groups = mutableListOf<MutableList<View>>()
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (groups.isEmpty() || startsSetting(c)) groups += mutableListOf(c) else groups.last() += c
        }
        var any = false
        for (g in groups) {
            val head = g.first().takeIf { startsSetting(it) }
            if (head != null && holdsHit(head)) { any = true; continue }
            var shown = false
            for (v in g) {
                if (v === head) continue
                val keep = holdsHit(v) && (v !is android.widget.LinearLayout || v is android.widget.RadioGroup ||
                    v.orientation == android.widget.LinearLayout.HORIZONTAL || narrow(v, hits))
                if (keep) shown = true else hide(v)
            }
            if (shown) any = true else head?.let { hide(it) }
        }
        return any
    }

    /** Visible settings under [v] whose label, text or hint contains [q]. */
    private fun collectMatches(v: View, q: String, out: MutableList<View>) {
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c.visibility == View.VISIBLE) collectMatches(c, q, out)
            }
        } else if (v.tag is InfoText) {
            // An ⓘ that matches: the setting beside it lights up.
            if ((v.tag as InfoText).text.lowercase().contains(q)) ((v.parent as? android.view.ViewGroup)?.getChildAt(0))?.let { if (it !in out) out += it }
        } else if (v is android.widget.TextView) {
            val text = v.text?.toString().orEmpty().lowercase()
            val hint = v.hint?.toString().orEmpty().lowercase()
            if (text.contains(q) || hint.contains(q)) out += v
        }
    }

    // ---- RTL-SDR radio -------------------------------------------------------------------

    /** Settings > Data > Back up settings: writes the backup where you choose. */
    private val backupPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(com.rfsentinel.app.util.SettingsBackup.create(this).toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        Toast.makeText(this, if (ok) "Settings backed up. API keys and risk acknowledgements are not in the file." else "Couldn't write the file", Toast.LENGTH_LONG).show()
    }

    /** Settings > Data > Restore settings: reads a backup, asks, applies it and restarts the app. */
    private val restorePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val backup = com.rfsentinel.app.util.SettingsBackup
        val text = runCatching { contentResolver.openInputStream(uri)!!.use { backup.read(it) } }.getOrNull()
        if (text == null) { Toast.makeText(this, "Couldn't read that file (or it is too big for a backup)", Toast.LENGTH_LONG).show(); return@registerForActivityResult }
        val found = when (val i = backup.inspect(text)) {
            is com.rfsentinel.app.util.SettingsBackup.Inspect.Bad -> {
                MaterialAlertDialogBuilder(this).setTitle("Not restored").setMessage(i.why).setPositiveButton("OK", null).show()
                return@registerForActivityResult
            }
            is com.rfsentinel.app.util.SettingsBackup.Inspect.Ok -> i
        }
        // A box per section: what is ticked is replaced by the file's, the rest is left as it is.
        val chosen = found.sections.toMutableSet()
        val labels = found.sections.map { s ->
            if (s == com.rfsentinel.app.util.SettingsBackup.Section.THEMES) "${s.label}: ${found.themes}" else s.label
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore what? Ticked parts are replaced, then RF Sentinel restarts")
            .setMultiChoiceItems(labels, BooleanArray(labels.size) { true }) { _, which, on ->
                if (on) chosen += found.sections[which] else chosen -= found.sections[which]
            }
            .setPositiveButton("Restore") { _, _ ->
                if (chosen.isEmpty()) { Toast.makeText(this, "Nothing ticked - nothing restored", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                when (val r = backup.restore(this, text, chosen)) {
                    is com.rfsentinel.app.util.SettingsBackup.Result.Refused -> MaterialAlertDialogBuilder(this)
                        .setTitle("Not restored").setMessage(r.why).setPositiveButton("OK", null).show()
                    is com.rfsentinel.app.util.SettingsBackup.Result.Restored -> {
                        restored = true
                        Toast.makeText(this, "Restored ${r.count} settings - restarting", Toast.LENGTH_LONG).show()
                        // A fresh start picks everything up (themes, filters, services read their settings at start-up).
                        packageManager.getLaunchIntentForPackage(packageName)?.let {
                            startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        }
                        Runtime.getRuntime().exit(0)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
        fun slider(from: Int, to: Int, value: Int, text: (Int) -> String, save: (Int) -> Unit) {
            val l = box.label(text(value))
            box.addView(this@SettingsActivity.slider(from, to, value, description = text(value)) {
                save(it)
                l.text = text(it)
            })
        }
        fun lines(title: String, hint: String, value: String, details: String? = null, save: (String) -> Unit): android.widget.EditText {
            box.label(title).also { if (details != null) info(it, details) }
            return android.widget.EditText(this).apply {
                setText(value); this.hint = hint
                minLines = 2; maxLines = 8; gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                doAfterTextChanged { save(it?.toString().orEmpty()) }
            }.also { box.addView(it) }
        }

        info(box.label("Network SDR"), "Uses an RTL-SDR on another device over WiFi, such as an ESP32 board or a computer running " +
            "rtl_tcp. Enter its address and port; the port is ${com.rfsentinel.app.sdr.RtlTcp.DEFAULT_PORT} if left out. A plugged-in " +
            "USB SDR is used first. Streaming takes about 5 MB/s of WiFi.", "Network SDR")
        box.addView(android.widget.EditText(this).apply {
            setText(Prefs.networkSdr(this@SettingsActivity)); hint = "192.168.4.1:1234"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    Prefs.setNetworkSdr(this@SettingsActivity, s?.toString().orEmpty().trim())
                    com.rfsentinel.app.sdr.SdrRadio.networkChanged()
                }
            })
        })

        // What the SDR does: any mix; with more than one ticked it takes turns.
        var showSdrOptions: () -> Unit = {}
        info(box.label("Scan with the SDR"), "Tick what the RTL-SDR or HackRF does while scanning. One SDR hears one thing at a time: " +
            "with more than one ticked it takes turns, and each scan hears nothing while another has its turn. " +
            "Tick only one to give it all the time.", "Scan with the SDR")
        fun scan(t: String, on: Boolean, details: String, save: (Boolean) -> Unit) = android.widget.CheckBox(this).apply {
            text = t; isChecked = on
            setOnCheckedChangeListener { _, v -> save(v); showSdrOptions() }
        }.also { box.addView(infoRow(it, details)) }
        val sweepBox = scan("Radio activity sweep", Prefs.sdrSweep(this),
            "Sweeps the radio bands below for strong two-way radio transmissions nearby: signal strength only, nothing decoded. " +
            "Needs Two-way radio turned on.") { Prefs.setSdrSweep(this, it) }
        val adsbBox = scan("ADS-B aircraft receiver", Prefs.sdrAdsb(this),
            "Listens on 1090 MHz for aircraft: police aircraft are found without internet. Needs Police aircraft turned on. " +
            "A 1090 MHz antenna helps; the stock one hears out to about 50 km.") { Prefs.setSdrAdsb(this, it) }
        val p25Box = scan("P25 control channel", Prefs.sdrP25(this),
            "Experimental. Finds the P25 trunked-radio control channel here (700 / 800 MHz, used in the United States and Canada) and " +
            "follows it: which talkgroups get a channel, and when (Tools › P25 talkgroups). Voice is only played with Listen to P25 voice, below. " +
            "Needs Two-way radio turned on. On by default when your region is the United States or Canada. Receiving radio " +
            "signalling is legal in many places but not all, and some forbid it in a vehicle or passing on what you hear: " +
            "check the law where you are.") { Prefs.setSdrP25(this, it) }
        val pagerBox = scan("Pager messages", Prefs.sdrPager(this),
            "Experimental. Finds paging channels and decodes POCSAG and FLEX pages (Tools › Pager messages). Pages stay in the " +
            "phone's memory: never saved, exported or sent, and gone when the app closes or you untick this. Pages can hold " +
            "private or medical details. Canada, the United States and many other places restrict using or passing on " +
            "messages you intercept, and some forbid listening at all: check the law where you are. " +
            "Needs Two-way radio turned on.") {
            Prefs.setSdrPager(this, it)
            if (!it) com.rfsentinel.app.sdr.SdrRadio.pagerLog.clear()
        }
        // Each ticked scan's own turn length, shown while it shares the SDR.
        val turnViews = listOf(sweepBox to com.rfsentinel.app.sdr.SdrRadio.Mode.SWEEP, adsbBox to com.rfsentinel.app.sdr.SdrRadio.Mode.ADSB,
            p25Box to com.rfsentinel.app.sdr.SdrRadio.Mode.P25, pagerBox to com.rfsentinel.app.sdr.SdrRadio.Mode.PAGER).map { (ticked, m) ->
            slider(30, 300, Prefs.sdrTurnS(this, m), { "Time for ${m.label}: $it s" }) { Prefs.setSdrTurnS(this, m, it) }
            info(box.getChildAt(box.childCount - 2), "With more than one scan ticked, the SDR gives ${m.label} this long before the next scan takes " +
                "its turn. Longer misses less of it; shorter comes back to the other scans sooner.",
                "Time for ${m.label}")
            ticked to listOf(box.getChildAt(box.childCount - 2), box.getChildAt(box.childCount - 1))
        }
        lines("Watch these talkgroups", "1234 Sheriff dispatch\n2001", Prefs.p25Talkgroups(this),
            "P25: alerts when a call starts on one of them, and names them on the P25 talkgroups screen. One per line: the " +
            "talkgroup number, with an optional name. 23* watches every talkgroup starting with 23 (2300, 2301, 2307...), and * every " +
            "talkgroup; a line with the exact number keeps its own name.") { Prefs.setP25Talkgroups(this, it) }
        info(box.label("Talkgroup alerts"), "How P25 alerts reach you: a watched talkgroup's call starting, a radio keying up nearby and " +
            "unusual talkgroup activity. Untick Voice or Sound so they don't talk or beep over P25 voice. Other radio alerts follow " +
            "the Two-way radio category in What to detect.", "Talkgroup alerts")
        box.addView(alertKindChips(Prefs.p25AlertsOff(this), false, "P25 talkgroups") { Prefs.setP25AlertsOff(this, it) })
        slider(0, 60, Prefs.p25LogDays(this), { if (it == 0) "Keep the P25 call log: off" else "Keep the P25 call log: $it days" }) {
            Prefs.setP25LogDays(this, it)
        }
        info(box.getChildAt(box.childCount - 2), "Every call the control channel grants (time, talkgroup, radio, channel) is kept on the " +
            "phone this long, for P25 talkgroups › History and Export: no internet or talkgroup list needed. About 1 MB per busy day. " +
            "0 keeps nothing and deletes the log.", "Keep the P25 call log")
        scan("Alert on unusual talkgroup activity", Prefs.p25Unusual(this),
            "Compares each talkgroup's calls in the last hour with the same hour on the days before, from the call log. Alerts when " +
            "one is at least three times busier than usual, or busy for the first time. Starts after the log has " +
            "${com.rfsentinel.app.sdr.P25History.BASELINE_DAYS} days in it, and needs the call log kept.") { Prefs.setP25Unusual(this, it) }
        scan("Listen for radios keying up nearby", Prefs.p25Inputs(this),
            "When a call starts, the SDR leaves the control channel for a fraction of a second and listens where that call's radio " +
            "transmits. A strong signal there means the radio talking is close to you, not just somewhere the site covers. At most " +
            "once every few seconds; the control channel misses a few messages each time. Uses the radio sweep's sensitivity.") {
            Prefs.setP25Inputs(this, it)
        }
        scan("Record P25 voice calls", Prefs.p25Record(this),
            "For testing P25 voice: when a clear (not encrypted) call starts, the SDR leaves the control channel for " +
            "${com.rfsentinel.app.sdr.P25Record.SECONDS} s and saves that call's radio signal to a file, about 1 MB each. Nothing " +
            "is decoded or played. At most one call every 20 s; the last ${com.rfsentinel.app.sdr.P25Record.KEEP} are kept. " +
            "Share them with the button below. Recording radio calls is not allowed everywhere: check the law where you are.") {
            Prefs.setP25Record(this, it)
        }
        box.addView(com.google.android.material.button.MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Share P25 recordings"
            setOnClickListener { shareP25Recordings() }
        })
        box.addView(infoRow(choiceRow("Listen to P25 voice", listOf("Off", "Watched talkgroups", "All clear calls"), Prefs.p25Listen(this)) {
            Prefs.setP25Listen(this, it)
        }, "Experimental. Plays clear (not encrypted) P25 calls as they happen, through the speaker, headphones or car like other audio. " +
            "The SDR leaves the control channel for the call and misses its messages meanwhile: one call at a time, at most " +
            "2 minutes each. Watched talkgroups: only those in Watch these talkgroups. Mute on the P25 talkgroups screen pauses it; Hold there (or tapping a talkgroup) plays only that talkgroup and keeps the SDR on P25 until Release. " +
            "Encrypted calls can't be played. Listening to radio calls is not allowed everywhere, and some places forbid it in " +
            "a vehicle: check the law where you are.", "Listen to P25 voice"))
        val talkgroupViews = (box.childCount - 11 until box.childCount).map { box.getChildAt(it) }
        var sweepViews = emptyList<View>() // the sweep's own settings, below
        showSdrOptions = {
            val ticked = listOf(sweepBox, adsbBox, p25Box).count { it.isChecked }
            turnViews.forEach { (scan, views) -> views.forEach { it.shownIf(ticked > 1 && scan.isChecked) } }
            talkgroupViews.forEach { it.shownIf(p25Box.isChecked) }
            sweepViews.forEach { it.shownIf(sweepBox.isChecked) }
        }
        val sweepFrom = box.childCount
        slider(com.rfsentinel.app.sdr.RadioWatch.MIN_SNR_SETTING, com.rfsentinel.app.sdr.RadioWatch.MAX_SNR_SETTING, Prefs.radioMinSnr(this),
            { "Sensitivity: $it dB above the noise" }) {
            Prefs.setRadioMinSnr(this, it)
        }
        info(box.getChildAt(box.childCount - 2), "A radio is reported once its signal is this far above the noise. Lower hears farther, with more false alarms. " +
            "Below about 10 dB the noise's own flicker sets off some alerts: good for testing how far a known radio carries " +
            "(a fire station's TETRA, say), noisy for everyday use.", "Sensitivity")
        box.label("Bands to sweep")
        val off = Prefs.radioBandsOff(this).toMutableSet()
        for ((key, band) in com.rfsentinel.app.sdr.RadioSettings.BUILT_IN) {
            box.addView(android.widget.CheckBox(this).apply {
                text = "%s · %.1f-%.1f MHz".format(java.util.Locale.US, band.label, band.startHz / 1e6, band.endHz / 1e6)
                isChecked = key !in off
                setOnCheckedChangeListener { _, on ->
                    if (on) off -= key else off += key
                    Prefs.setRadioBandsOff(this@SettingsActivity, off.toSet())
                }
            })
        }
        lines("Your own bands", "380-400 TETRA\n220-222", Prefs.radioCustomBands(this), "One per line, in MHz, with an optional name.") { Prefs.setRadioCustomBands(this, it) }
        lines("Never report these ranges", "462-469 business band\n151.8-152.0", Prefs.radioExcluded(this), "One per line, in MHz.") { Prefs.setRadioExcluded(this, it) }
        val watched = lines("Watch these frequencies", "154.4300 County fire dispatch\n460.125", Prefs.radioTargets(this),
            "Always reported when active, whatever the sensitivity. One per line, in MHz, with an optional name.") { Prefs.setRadioTargets(this, it) }
        box.toggle("Say the frequency", Prefs.radioSpeakFreq(this), "Says the frequency (and its name) instead of \"Radio transmitting nearby\".") { Prefs.setRadioSpeakFreq(this, it) }
        box.toggle("Detect cellular transmitters", Prefs.radioCellOn(this),
            "Experimental. Watches the phone / modem side of the LTE bands (uplink) for a transmitter travelling near you: a vehicle modem, a " +
            "Cradlepoint-style router, a camera with a SIM. Energy only, nothing decoded. Adds time to each sweep; " +
            "a phone counts too, so it is a weak sign on its own.") { Prefs.setRadioCellOn(this, it) }
        box.toggle("Detect TETRA radios", Prefs.radioTetraOn(this),
            "Watches 380-385 MHz, where emergency-service TETRA handsets transmit in Europe and many other countries. " +
            "A handset sends short time-slot bursts; that rhythm marks it as a TETRA radio. Energy only: TETRA is encrypted " +
            "and nothing is decoded. On by default when the phone's region is in Europe.") { Prefs.setRadioTetraOn(this, it) }
        slider(0, 20, Prefs.radioTrendDb(this), { if (it == 0) "Closer / moving away: off" else "Say \"getting closer\" / \"moving away\" when the signal changes by $it dB" }) {
            Prefs.setRadioTrendDb(this, it)
        }
        slider(1, 50, Prefs.radioMatchKhz(this), { "A hit takes a frequency's name within ± $it kHz" }) { Prefs.setRadioMatchKhz(this, it) }
        sweepViews = (sweepFrom until box.childCount).map(box::getChildAt)
        showSdrOptions()

        box.label("Frequency names")
        freqListText = box.label("", small = true)
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
        // Its own row: three buttons side by side would be cut off on a narrow screen.
        box.addView(com.google.android.material.button.MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Watch this list"
            setOnClickListener {
                val names = com.rfsentinel.app.sdr.FreqNames.importedNames
                val (merged, added, left) = com.rfsentinel.app.sdr.RadioSettings.addTargets(watched.text.toString(), names)
                if (added > 0) watched.setText(merged)
                Toast.makeText(this@SettingsActivity, when {
                    names.isEmpty() -> "Import a frequency list first"
                    left > 0 -> "$added added, $left did not fit: the limit is ${com.rfsentinel.app.sdr.RadioSettings.MAX_TARGETS} watched frequencies"
                    else -> "$added added to Watch these frequencies"
                }, Toast.LENGTH_LONG).show()
            }
        }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

        val rrSwitch = box.toggle("Name hits from RadioReference", Prefs.radioReferenceOn(this),
            "Names a hit from RadioReference's database. Needs your own RadioReference Premium login and developer key, stored encrypted on this phone.") {
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
        val rrStatus = box.label(com.rfsentinel.app.sdr.RadioReference.status.ifEmpty { "Stored encrypted on this phone." }, small = true)
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

    /** Settings > Data: the WiGLE account used by "Find on WiGLE" (requested networks, WiFi devices). */
    private fun setupWigleAccount() {
        val box = binding.sectionData
        val button = com.google.android.material.button.MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "WiGLE account"
            setOnClickListener { com.rfsentinel.app.probes.WigleUi.account(this@SettingsActivity) }
        }
        box.addView(infoRow(button, com.rfsentinel.app.probes.WigleUi.PRIVACY, "WiGLE account"),
            box.indexOfChild(binding.importHistoryButton))
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

    /** Settings > Navigation: directions, route preferences, distance units and the places kept for it. */
    private fun setupNavSection() {
        val box = binding.sectionNav

        box.toggle("Spoken directions", Prefs.navVoice(this), "Turn-by-turn directions spoken aloud. Muting them never mutes the detection alerts.") { Prefs.setNavVoice(this, it) }
        box.toggle("Scan when a route starts", Prefs.navStartScan(this),
            "Starts scanning when you start a destination. A scan already running is left alone, and the scan keeps going after you arrive.") { Prefs.setNavStartScan(this, it) }
        box.toggle("Keep the screen on while guiding", Prefs.navKeepScreenOn(this)) { Prefs.setNavKeepScreenOn(this, it) }
        box.toggle("Night map after sunset", Prefs.navAutoNight(this),
            "While guiding, the map turns dark after sunset, worked out on the phone from your position and the time. " +
            "Pressing the sun / moon button on the map keeps the look you chose instead.") { Prefs.setNavAutoNight(this, it) }
        box.addView(infoRow(box.label("Routes avoid").also { box.removeView(it) },
            "Ticking one while you plan a route is remembered here for the next one.\n\n" +
            "Speed cameras and plate readers: routes go round the cameras of the camera map (Known cameras) where there is a way round, " +
            "and each route says what is left on it. The cameras along the route are fetched first if they are not on the phone. " +
            "A camera right at the start or end, or on the only bridge, can't be avoided. A detour can be longer."))
        box.toggle("Tolls", Prefs.navAvoidTolls(this)) { Prefs.setNavAvoidTolls(this, it) }
        box.toggle("Highways", Prefs.navAvoidHighways(this)) { Prefs.setNavAvoidHighways(this, it) }
        box.toggle("Ferries", Prefs.navAvoidFerries(this)) { Prefs.setNavAvoidFerries(this, it) }
        box.toggle("Speed cameras", Prefs.navAvoidSpeedCameras(this)) { Prefs.setNavAvoidSpeedCameras(this, it) }
        box.toggle("Plate readers", Prefs.navAvoidPlateReaders(this)) { Prefs.setNavAvoidPlateReaders(this, it) }

        box.label("Places")
        val places = android.widget.TextView(this).apply { textSize = 14f; alpha = 0.8f }
        fun showPlaces() {
            places.text = "Home: ${Prefs.navHome(this@SettingsActivity)?.name ?: "not set"}\n" +
                "Work: ${Prefs.navWork(this@SettingsActivity)?.name ?: "not set"}\n" +
                "${Prefs.navFavorites(this@SettingsActivity).size} starred · ${Prefs.recentDestinations(this@SettingsActivity).size} recent"
        }
        showPlaces()
        box.addView(places)
        box.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Forget saved and recent places"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Forget your places?")
                    .setMessage("Removes Home, Work, starred and recent destinations from this phone.")
                    .setPositiveButton("Forget") { _, _ ->
                        Prefs.clearNavPlaces(this@SettingsActivity); Prefs.clearRecentDestinations(this@SettingsActivity); showPlaces()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
    }

    /** Settings > General > Units: metric or imperial for the whole app ([com.rfsentinel.app.util.Units]). */
    private fun setupDeviceUnits() {
        val box = binding.sectionGeneral
        val title = android.widget.TextView(this).apply {
            text = "Units"; textSize = 14f
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (2 * resources.displayMetrics.density).toInt())
        }
        box.addView(infoRow(title, "Metric or imperial everywhere in the app: how far a device is guessed to be, navigation " +
            "distances and the speedometer, the map's scale, cameras, Waze reports, aircraft and drones, and the car screens. " +
            "Automatic uses imperial where the phone's region drives in miles (US, UK...) and metric elsewhere. Imperial shows " +
            "feet, then miles past 1000 ft. Posted speed limits are shown as signed."))
        val group = android.widget.RadioGroup(this)
        for ((key, text) in listOf("auto" to "Automatic", "m" to "Metric · m, km", "ft" to "Imperial · ft, mi")) {
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId(); this.text = text; tag = key
                isChecked = Prefs.units(this@SettingsActivity) == key
                minHeight = (48 * resources.displayMetrics.density).toInt() // the same row height as the other choices
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            g.findViewById<View>(id)?.tag?.let { Prefs.setUnits(this, it as String); com.rfsentinel.app.util.Units.apply(this) }
        }
        box.addView(group)
    }

    /** Alerts > Snooze length, under Vibration: how long the toolbar speaker, a notification's Mute and the car's snooze last. */
    private fun setupSnoozeLength() {
        val section = binding.sectionAlerts
        val at = (0 until section.childCount).firstOrNull { i ->
            val v = section.getChildAt(i)
            v === binding.vibrateSwitch || (v as? android.view.ViewGroup)?.indexOfChild(binding.vibrateSwitch)?.let { it >= 0 } == true
        } ?: return
        val choices = Prefs.SNOOZE_CHOICES
        val row = choiceRow("Snooze length", choices.map { "$it min" }, choices.indexOf(Prefs.snoozeMinutes(this))) {
            Prefs.setSnoozeMinutes(this, choices[it])
        }
        section.addView(infoRow(row, "How long a snooze silences the alert sound and voice, like a radar detector's mute: the " +
            "speaker button at the top of the main screen (tap it), Mute on an alert notification, and the car's snooze. " +
            "Alerts still show on screen and in the list while snoozed. Long-press the speaker button for other lengths or " +
            "to mute until you turn the sound back on.", "Snooze length"), at + 1)
        // Under it: what a swipe on a flagged row of the main list does, each side.
        val actions = Prefs.SWIPE_ACTIONS
        for ((i, right) in listOf(true, false).withIndex()) {
            val label = if (right) "Swipe right on an alert" else "Swipe left on an alert"
            val swipe = choiceRow(label, listOf("Mute / unmute", "Hide", "None", "Whitelist", "Watchlist"), actions.indexOf(Prefs.swipeAction(this, right))) {
                Prefs.setSwipeAction(this, right, actions[it])
            }
            section.addView(infoRow(swipe, "What swiping a flagged row of the main list does. Both sides can do the same thing.\n\n" +
                "Mute / unmute: no alerts from it for 30 min, as its mute button; swiped again, alerts are back on.\n\n" +
                "Hide: a flagged device or radio transmission stays listed as an ordinary one, a Waze report or an aircraft " +
                "leaves the list. No alerts from it for 30 min or until the scan stops, then it comes back as it was. " +
                "Undo at the bottom puts it back at once.\n\n" +
                "Whitelist / Watchlist: asks to add the device to your whitelist (never flagged) or watchlist, as on its page. " +
                "Devices only.\n\n" +
                "None: that swipe does nothing. Aircraft and radio rows have no mute: only Hide works on them.", label), at + 2 + i)
        }
    }

    /** A setting's label with a drop-down of [items] on its right (Snooze length, the swipes); [onPick] gets the index. */
    private fun choiceRow(label: String, items: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
        }
        row.addView(android.widget.TextView(this).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(androidx.appcompat.widget.AppCompatSpinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selected.coerceAtLeast(0))
            contentDescription = label
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        })
        return row
    }

    /** Map > Re-center after, under Auto-recenter: how long after your last touch the map goes back to you. */
    private var recenterDelay: android.widget.Spinner? = null

    private fun setupRecenterDelay() {
        // Under the Auto-recenter row (with its ⓘ).
        val switchRow = binding.mapAutoRecenterSwitch.parent as? android.view.ViewGroup ?: return
        val section = switchRow.parent as? android.view.ViewGroup ?: return
        val choices = Prefs.RECENTER_CHOICES
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
        }
        row.addView(android.widget.TextView(this).apply {
            text = "Re-center after"
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        recenterDelay = androidx.appcompat.widget.AppCompatSpinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item,
                choices.map { if (it < 60) "$it s" else "${it / 60} min" }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(choices.indexOf(Prefs.mapRecenterSec(this@SettingsActivity)).coerceAtLeast(0))
            isEnabled = binding.mapAutoRecenterSwitch.isChecked
            contentDescription = "Re-center after"
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    Prefs.setMapRecenterSec(this@SettingsActivity, choices[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        row.addView(recenterDelay)
        section.addView(infoRow(row, "After you pan or zoom the map while driving, it goes back to following you once you " +
            "haven't touched it for this long. Turn Auto-recenter off to leave it where you put it.", "Re-center after"),
            section.indexOfChild(switchRow) + 1)
    }

    /** Settings > Map: what the map shows (moved here from the map's menu). Saved as you switch. */
    private fun setupMapSection() {
        setupMapProvider()
        setupMapZoom()
        setupMapTilt()
        binding.mapMarkerButton.text = com.rfsentinel.app.ui.MarkerPicker.summary(this) + "  ▾"
        binding.mapMarkerButton.setOnClickListener {
            com.rfsentinel.app.ui.MarkerPicker.show(this) { binding.mapMarkerButton.text = com.rfsentinel.app.ui.MarkerPicker.summary(this) + "  ▾" }
        }
        binding.mapKnownCamerasSwitch.isChecked = Prefs.showKnownAlpr(this)
        binding.mapKnownCamerasSwitch.setOnCheckedChangeListener { _, on -> Prefs.setShowKnownAlpr(this, on) }
        binding.mapAutoRecenterSwitch.isChecked = Prefs.mapAutoRecenter(this)
        binding.mapAutoRecenterSwitch.setOnCheckedChangeListener { _, on -> Prefs.setMapAutoRecenter(this, on); recenterDelay?.isEnabled = on }
        binding.mapSpeedometerSwitch.isChecked = Prefs.mapSpeedometer(this)
        binding.mapSpeedLimitLiveSwitch.isChecked = Prefs.mapSpeedLimitLive(this)
        binding.mapSpeedLimitLiveSwitch.isEnabled = binding.mapSpeedometerSwitch.isChecked
        binding.mapSpeedometerSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setMapSpeedometer(this, on); binding.mapSpeedLimitLiveSwitch.isEnabled = on
        }
        binding.mapSpeedLimitLiveSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setMapSpeedLimitLive(this, on)
            if (!on) com.rfsentinel.app.nav.SpeedLimits.Live.reset()
        }
        binding.mapTowersSwitch.bindLive(Prefs.showCellTowers(this)) { Prefs.setShowCellTowers(this, it) }
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
            MaterialAlertDialogBuilder(this)
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

    /** Settings > Map > 3D view angle: how far the driving view leans back (flatter = more overhead, steeper = further down the road). */
    private fun setupMapTilt() {
        fun show(t: Double) {
            val what = when { t <= 40 -> "flatter, more overhead"; t <= 55 -> "balanced, like Waze"; else -> "steep: far down the road" }
            binding.map3dTiltText.text = "3D view angle: ${t.toInt()}° · $what"
        }
        val t = Prefs.map3dTilt(this)
        binding.map3dTiltSlider.value = t.toFloat(); show(t)
        binding.map3dTiltSlider.setLabelFormatter { "${it.toInt()}°" }
        binding.map3dTiltSlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setMap3dTilt(this, v.toDouble()); show(v.toDouble())
        }
    }

    /** Settings > Map > Default zoom: how close the map follows you (the "Waze-like" button is the default). */
    private fun setupMapZoom() {
        fun show(z: Double) {
            val what = when {
                z <= 15.25 -> "wide: a neighbourhood"
                z <= 16.25 -> "a few blocks"
                z <= 17.25 -> "street level, close to Waze"
                else -> "very close: the next intersection"
            }
            binding.mapZoomText.text = "Default zoom: ${"%.2f".format(java.util.Locale.US, z).trimEnd('0').trimEnd('.')} · $what"
        }
        val z = Prefs.mapDefaultZoom(this)
        // The slider counts quarter levels (58 = 14.5 ... 74 = 18.5): whole numbers, no rounding errors.
        binding.mapZoomSlider.value = Math.round(z * 4).toFloat(); show(z)
        binding.mapZoomSlider.setLabelFormatter { "%.2f".format(java.util.Locale.US, it / 4.0).trimEnd('0').trimEnd('.') }
        binding.mapZoomSlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setMapDefaultZoom(this, v / 4.0); show(v / 4.0)
        }
        binding.mapZoomReset.setOnClickListener {
            Prefs.setMapDefaultZoom(this, Prefs.MAP_ZOOM_WAZE)
            binding.mapZoomSlider.value = (Prefs.MAP_ZOOM_WAZE * 4).toFloat(); show(Prefs.MAP_ZOOM_WAZE)
        }
    }

    /** Settings > Map > Map: OpenFreeMap or Mapbox (the open maps follow it when you're back). */
    private fun setupMapProvider() {
        val btn = binding.mapProviderButton
        fun show() { btn.text = com.rfsentinel.app.ui.MapEngine.chosen(this).label.substringBefore(" (") + "  ▾" }
        show()
        btn.setOnClickListener {
            val all = com.rfsentinel.app.ui.MapEngine.Provider.entries
            if (!com.rfsentinel.app.ui.MapEngine.mapboxAvailable) {
                Toast.makeText(this, "Mapbox isn't available in this build", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = all.indexOf(com.rfsentinel.app.ui.MapEngine.chosen(this))
            MaterialAlertDialogBuilder(this)
                .setTitle("Map provider")
                .setSingleChoiceItems(all.map { it.label }.toTypedArray(), current) { d, which ->
                    Prefs.setMapProvider(this, all[which].key)
                    show()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showNote(view: android.widget.TextView?, text: String) {
        view ?: return
        view.text = text
        view.shownIf(text.isNotBlank())
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
        MaterialAlertDialogBuilder(this)
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
        fun slider(
            label: (Int) -> String, from: Int, to: Int, value: Int,
            info: String = "How long a device stays in the list and radar after it was last heard. " +
                "Flagged, favourite and following devices always stay 3 minutes.",
            save: (Int) -> Unit
        ): Pair<com.google.android.material.slider.Slider, android.widget.TextView> {
            val text = android.widget.TextView(this).apply {
                textSize = 14f
                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
                this.text = label(value)
            }
            binding.sectionScanning.addView(infoRow(text, info, "Out of range"))
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
            return s to text
        }
        slider({ "Bluetooth: off the list after $it s" }, 5, 180, Prefs.liveBleSec(this)) {
            Prefs.setLiveBleSec(this, it)
        }
        // Below 30 s only with "Wi-Fi scan throttling" off in Developer options (see updateWifiSliderMin).
        liveWifiSlider = slider(wifiLabel, Prefs.minLiveWifiSec(this), 180, Prefs.liveWifiSec(this)) {
            Prefs.setLiveWifiSec(this, it)
        }
        slider({ "External hardware: off the list after $it s" }, 5, 180, Prefs.liveExternalSec(this),
            "How long a device heard only by an ESP32 board or a USB WiFi adapter stays in the list and radar after it " +
                "was last heard. Flagged, favourite and following devices always stay 3 minutes.") {
            Prefs.setLiveExternalSec(this, it)
        }
    }

    private val wifiLabel = { s: Int -> "WiFi: off the list after $s s" }
    private var liveWifiSlider: Pair<com.google.android.material.slider.Slider, android.widget.TextView>? = null

    /** The WiFi slider goes down to 5 s while scan throttling is off (the user may be back from Developer options). */
    private fun updateWifiSliderMin() {
        val (slider, text) = liveWifiSlider ?: return
        val min = Prefs.minLiveWifiSec(this)
        if (slider.valueFrom.toInt() == min) return
        if (slider.value < min) slider.value = min.toFloat()
        slider.valueFrom = min.toFloat()
        // Back to the chosen value when throttling goes off again ([Prefs.liveWifiSec] keeps it).
        slider.value = (Prefs.liveWifiSec(this).let { it - it % 5 }).coerceIn(min, 180).toFloat()
        text.text = wifiLabel(slider.value.toInt())
    }

    /**
     * Under a category's switch: a chip per way it alerts you (the same chips as a watchlist entry's), on unless you
     * turn it off. Opened by the category's ▾ ([fold]); saved at once.
     */
    private fun addCategoryAlertChips(c: Category, sw: SwitchMaterial, fold: android.widget.TextView) {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), 0, 0, (4 * dp).toInt())
        }
        val own = Prefs.categoryThreshold(this, c)
        val level = choiceRow("Alert from", listOf("Alerts setting", "Weak", "Probable", "Strong only"),
            when { own == null -> 0; own < 50 -> 1; own < 80 -> 2; else -> 3 }) {
            Prefs.setCategoryThreshold(this, c, listOf(null, 0, 50, 80)[it])
        }
        box.addView(level)
        info(level, "How sure a match must be before this category alerts you. \"Alerts setting\" follows Settings › Alerts; " +
            "pick Weak, Probable or Strong only to set this category on its own. Weaker matches are still listed.", "Alert from")
        box.addView(android.widget.TextView(this).apply {
            text = "Alerts you with"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, android.R.attr.textColorSecondary))
        })
        box.addView(alertKindChips(Prefs.categoryAlertsOff(this, c), Prefs.hasRadarTicks(c), "${sw.contentDescription ?: sw.text}") {
            Prefs.setCategoryAlertsOff(this, c, it)
        })
        binding.categoryContainer.addView(box)
        folds[sw] = Fold(sw, fold).also { it.add(box) }
    }

    /** A chip per way of alerting (Beep only with [beep]), ticked when it is on; [save] gets the ones turned off. */
    private fun alertKindChips(off: Set<com.rfsentinel.app.data.AlertKind>, beep: Boolean, what: String,
                               save: (Set<com.rfsentinel.app.data.AlertKind>) -> Unit) =
        com.google.android.material.chip.ChipGroup(this).apply {
            chipSpacingHorizontal = dp(6); chipSpacingVertical = 0
            var now = off
            com.rfsentinel.app.data.AlertKind.entries.forEach { kind ->
                if (kind == com.rfsentinel.app.data.AlertKind.BEEP && !beep) return@forEach
                addView(com.rfsentinel.app.ui.FitChip(this@SettingsActivity).apply {
                    text = kind.label; isCheckable = true
                    com.rfsentinel.app.ui.ChipStyle.apply(this)
                    isChecked = kind !in now
                    contentDescription = "${kind.label} alerts for $what"
                    setOnClickListener {
                        now = if (isChecked) now - kind else now + kind
                        save(now)
                    }
                })
            }
        }

    private val folds = HashMap<SwitchMaterial, Fold>()

    /** A category's ▾ and what it opens: its alert chips, then its own settings (aircraft, Waze). */
    private inner class Fold(val sw: SwitchMaterial, val arrow: android.widget.TextView) {
        private var open = false
        private val panels = mutableListOf<View>()
        init {
            arrow.setOnClickListener { open = !open; show() }
            // Turning on a category that has its own settings opens them.
            sw.onToggle { on -> if (on && panels.size > 1) open = true; show() }
        }
        fun add(panel: View) { panels += panel; show() }
        /** Search only looks at what's shown: while searching, a category's own settings show, folded or not (not its chips). */
        fun searching(on: Boolean) { show(); if (on) panels.drop(1).forEach { it.shownIf(sw.isChecked) } }
        private fun show() {
            panels.forEach { it.shownIf(open && sw.isChecked) }
            arrow.text = if (open) "▴" else "▾"
            // Off: nothing to open, but the slot stays so the switches line up.
            arrow.visibility = if (sw.isChecked) View.VISIBLE else View.INVISIBLE
            arrow.contentDescription = "Options for ${sw.contentDescription ?: sw.text}, ${if (open) "expanded" else "collapsed"}"
        }
    }

    /** Aircraft alert distance (1-20 km), search radius (10-50 km) and the feed status, under the aircraft switch. */
    private fun addAircraftControls(sw: SwitchMaterial) {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), 0, 0, (6 * dp).toInt())
        }
        box.addView(detectRow(SwitchMaterial(this).apply {
            text = "Online aircraft feeds"; isChecked = Prefs.aircraftOnline(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAircraftOnline(this@SettingsActivity, v) }
        }, "Asks adsb.fi / adsb.lol every minute for aircraft near a position rounded to about 1 km. Turn off to use only " +
            "the aircraft your RTL-SDR hears itself (Settings › RTL-SDR radio › ADS-B aircraft receiver): nothing leaves the phone."))
        box.addView(detectRow(SwitchMaterial(this).apply {
            text = "Alert for circling aircraft"; isChecked = Prefs.aircraftCircling(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAircraftCircling(this@SettingsActivity, v) }
        }, "Police and government aircraft within the alert distance always alert. Turn this on to also hear about any other aircraft " +
            "circling within it (news, training, survey and unlisted police flights orbit too). Off: they're only listed and logged."))
        fun kmSlider(label: String, about: String, km: Int, from: Int, to: Int, step: Int, save: (Int) -> Unit) {
            val text = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
            fun show(v: Int) { text.text = "$label ${com.rfsentinel.app.util.Units.km(v)}" }
            show(km)
            box.addView(text)
            box.addView(com.google.android.material.slider.Slider(this).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = step.toFloat()
                value = (km - km % step).coerceIn(from, to).toFloat()
                contentDescription = about
                setLabelFormatter { com.rfsentinel.app.util.Units.km(it.toInt()) }
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    save(v.toInt())
                    show(v.toInt())
                }
            })
        }
        kmSlider("Alert for aircraft within", "Aircraft alert distance in kilometres", Prefs.aircraftAlertKm(this), 1, 20, 1) {
            Prefs.setAircraftAlertKm(this, it)
        }
        kmSlider("Look for aircraft within", "Aircraft search radius in kilometres", Prefs.aircraftRadiusKm(this), 10, 50, 5) {
            Prefs.setAircraftRadiusKm(this, it)
        }
        aircraftStatusText = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
        box.addView(aircraftStatusText)
        showNote(aircraftStatusText, com.rfsentinel.app.online.OnlineWatch.aircraftStatus)
        binding.categoryContainer.addView(box)
        folds[sw]?.add(box)
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
        box.addView(infoRow(android.widget.TextView(this).apply { text = "Source"; textSize = 12f; alpha = 0.75f },
            "OpenWeb Ninja: your own API key; Waze never sees you. It doesn't offer traffic jams as alerts.\n\n" +
            "Waze direct: free and live, no key. Uses an anonymous Waze account on this phone and sends a position rounded to 1 km to Waze."))
        val ninjaBtn = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "OpenWeb Ninja" }
        val directBtn = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Waze direct" }
        val backendGroup = android.widget.RadioGroup(this).apply { addView(ninjaBtn); addView(directBtn) }
        box.addView(backendGroup)
        // Two independent ranges (both backends): how close a report must be to alert, and how far the map shows them.
        // The alert range steps through fixed distances, from 100 m up.
        val steps = Prefs.WAZE_ALERT_STEPS_M
        val alertText = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
        fun showAlert(m: Int) { alertText.text = "Alert when a report is within ${Prefs.formatRange(m)}" }
        val alertNow = Prefs.wazeAlertM(this)
        showAlert(alertNow)
        box.addView(alertText)
        box.addView(slider(0, steps.lastIndex, steps.indexOf(alertNow).coerceAtLeast(0), description = "Waze alert distance",
            format = { Prefs.formatRange(steps[it]) }) {
            Prefs.setWazeAlertM(this, steps[it])
            showAlert(steps[it])
        })
        val viewText = TextView(this).apply { textSize = 12f; alpha = 0.75f }
        fun showView(km: Int) { viewText.text = "Show reports on the map within ${com.rfsentinel.app.util.Units.km(km)}" }
        showView(Prefs.wazeViewKm(this))
        box.addView(viewText)
        box.addView(slider(1, 20, Prefs.wazeViewKm(this), description = "Waze map view range in kilometres",
            format = { com.rfsentinel.app.util.Units.km(it) }) {
            Prefs.setWazeViewKm(this, it)
            showView(it)
        })
        // How often Waze is asked. Each backend has its own steps; only the chosen backend's slider shows.
        fun intervalBlock(direct: Boolean): android.widget.LinearLayout {
            val steps = if (direct) Prefs.WAZE_DIRECT_INTERVALS_S else Prefs.WAZE_NINJA_INTERVALS_S
            val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val text = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.75f }
            val hint = android.widget.TextView(this).apply { textSize = 12f; alpha = 0.6f }
            fun show(sec: Int) {
                text.text = "Check for new reports every ${Prefs.formatInterval(sec)}"
                hint.text = when {
                    !direct -> "OpenWeb Ninja bills every check (about half a cent each on pay-as-you-go, check your plan): ${3600 / sec} an hour at this setting."
                    sec < 60 -> "Fresher reports, but your position goes to Waze more often."
                    sec > 90 -> "Over 90 s Waze ends the session in between, so each check logs in again (more data)."
                    else -> ""
                }
                hint.shownIf(hint.text.isNotEmpty())
            }
            val now = Prefs.wazeIntervalS(this, direct)
            show(now)
            col.addView(text)
            col.addView(slider(0, steps.lastIndex, steps.indexOf(now).coerceAtLeast(0), description = "How often to check Waze",
                format = { Prefs.formatInterval(steps[it]) }) {
                Prefs.setWazeIntervalS(this, direct, steps[it])
                show(steps[it])
            })
            col.addView(hint)
            return col
        }
        val directInterval = intervalBlock(true)
        val ninjaInterval = intervalBlock(false)
        box.addView(directInterval)
        box.addView(ninjaInterval)
        // Behaviour switches: each has a line under it saying what it does.
        box.toggle("Slower when parked, faster on fast roads", Prefs.wazeAdaptive(this),
            "Parked for 90 s: checks run 4 times less often. Over 80 km/h: twice as often (Waze direct only).") { Prefs.setWazeAdaptive(this, it) }
        box.toggle("Only alert for reports ahead of me", Prefs.wazeAheadOnly(this),
            "While you drive, only reports on the road you're on, ahead of you, alert, flash or speak - checked on the map's " +
                "roads, so one on a parallel road, a cross street, an overpass or behind you stays quiet (it stays on the list, " +
                "marked \"not on your way\" unless you hide them below). While you are being guided to a destination the route " +
                "decides: only reports on the part of it still ahead count, round corners too. Where the map can't tell yet, " +
                "reports behind you stay quiet. Parked, or before you've driven off, there is no \"ahead\" yet, so reports " +
                "stay quiet until you move.") { Prefs.setWazeAheadOnly(this, it) }
        box.toggle("Hide reports that aren't on my way", Prefs.wazeHideOffWay(this),
            "With the switch above on, a report behind you, on another street or off your route is left off the list (the map " +
                "still shows it). Off: it stays listed, quiet, marked \"not on your way\".") { Prefs.setWazeHideOffWay(this, it) }
        box.toggle("Call out again as I get closer", Prefs.wazeApproach(this),
            "Speaks at 1 km, 500 m and 200 m when voice alerts are on, or in the car.") { Prefs.setWazeApproach(this, it) }
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
            row.addView(spinner(levels.map { it.label }, Prefs.wazeLevel(this, t).ordinal, "What a ${t.label.lowercase()} alert does") {
                Prefs.setWazeLevel(this, t, levels[it])
            })
            box.addView(row)
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
            addView(forgetBtn)
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
            wazeKeyInput?.shownIf(!direct)
            directInterval.shownIf(direct)
            ninjaInterval.shownIf(!direct)
            directBox.shownIf(direct)
        }
        val directNow = Prefs.wazeBackend(this) == Prefs.WAZE_DIRECT
        backendGroup.check(if (directNow) directBtn.id else ninjaBtn.id)
        showBackend(directNow)
        backendGroup.setOnCheckedChangeListener { _, id ->
            if (id == directBtn.id) {
                if (Prefs.wazeDirectAccepted(this)) { Prefs.setWazeBackend(this, Prefs.WAZE_DIRECT); showBackend(true) }
                else {
                    backendGroup.check(ninjaBtn.id)
                    MaterialAlertDialogBuilder(this)
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
        binding.categoryContainer.addView(box)
        folds[sw]?.add(box)
        sw.onToggle { on ->
            if (on && !Prefs.wazeReady(this)) {
                sw.isChecked = false
                val direct = Prefs.wazeBackend(this) == Prefs.WAZE_DIRECT
                MaterialAlertDialogBuilder(this)
                    .setTitle("Waze reports: use at your own risk")
                    .setMessage(if (direct) WAZE_DIRECT_WARNING else WAZE_WARNING)
                    .setPositiveButton("I understand, enable") { _, _ ->
                        Prefs.setWazeAccepted(this, true)
                        if (direct) Prefs.setWazeDirectAccepted(this, true)
                        sw.isChecked = true
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun updateBannerButtons() {
        binding.bannerClearButton.isEnabled = ThemeManager.hasGlobalBanner(this)
    }

    /** The built-in themes, then your own (long-press one to edit it), then the buttons to make or import one. */
    private fun buildThemeList() {
        binding.themeGroup.removeAllViews()
        val current = ThemeManager.current(this)
        val custom = ThemeManager.custom(this)
        fun choice(title: String, checked: Boolean, selected: () -> Boolean, choose: () -> Unit) = RadioButton(this).apply {
            id = View.generateViewId()
            text = title
            isChecked = checked
            setOnCheckedChangeListener { _, checked ->
                if (checked && !selected()) {
                    resumeScroll = binding.root.scrollY
                    choose()
                    recreate()
                }
            }
        }.also { binding.themeGroup.addView(it) }
        ThemeManager.builtIn.forEach { theme ->
            choice(theme.title, theme == current, { theme == ThemeManager.current(this) }) { ThemeManager.set(this, theme) }
        }
        CustomThemes.all(this).forEach { theme ->
            choice(theme.name, current == ThemeManager.AppTheme.CUSTOM && custom?.id == theme.id,
                { ThemeManager.current(this) == ThemeManager.AppTheme.CUSTOM && ThemeManager.custom(this)?.id == theme.id }) {
                ThemeManager.setCustom(this, theme.id)
            }.setOnLongClickListener { editTheme(theme.id); true }
        }
    }

    private fun editTheme(id: String?) {
        startActivity(Intent(this, com.rfsentinel.app.ui.ThemeMakerActivity::class.java).apply { if (id != null) putExtra(com.rfsentinel.app.ui.ThemeMakerActivity.EXTRA_ID, id) })
    }

    private val themeFilePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        ThemeImport.open(this, uri) { resumeScroll = binding.root.scrollY; recreate() }
    }

    private fun setupThemeButtons() {
        val box = binding.sectionAppearance
        val at = box.indexOfChild(binding.themeGroup) + 1
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        fun btn(text: String, onClick: () -> Unit) = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text; setOnClickListener { onClick() }
        }.also { row.addView(it, android.widget.LinearLayout.LayoutParams(-1, -2)) }
        // The themes' descriptions, in the ⓘ of a "Theme" label above the list.
        val themesInfo = ThemeManager.builtIn.joinToString("\n\n") { t ->
            t.title + ": " + t.description +
                if (t == ThemeManager.AppTheme.MATERIAL_YOU && !ThemeManager.isDynamicColorAvailable) " (this phone: standard Material 3 colours)" else ""
        } + "\n\nYour own themes are listed after these: long-press one to edit it."
        box.addView(infoRow(android.widget.TextView(this).apply {
            text = "Theme"; setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0); textSize = 14f
        }, themesInfo), at - 1)
        btn("Create a custom theme...") { editTheme(null) }
        btn("Edit a theme...") {
            val all = CustomThemes.all(this)
            if (all.isEmpty()) Toast.makeText(this, "You have no custom themes yet", Toast.LENGTH_SHORT).show()
            else MaterialAlertDialogBuilder(this).setTitle("Edit which theme?")
                .setItems(all.map { it.name }.toTypedArray()) { _, i -> editTheme(all[i].id) }.show()
        }
        btn("Import a theme file...") { themeFilePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }
        info(row.getChildAt(0), "Make your own theme: colours, font, banner, header and map marker. Export it as a file to share it.")
        box.addView(row, at + 1)
        setupScreenStyle()
    }

    /** Per screen: the visual style (gauges, bars, charts) or the plain list it had before. */
    private fun setupScreenStyle() {
        val box = binding.sectionAppearance
        val dp = resources.displayMetrics.density
        box.addView(infoRow(android.widget.TextView(this).apply {
            text = "Screen style"; setPadding(0, (14 * dp).toInt(), 0, 0); textSize = 14f
        }, "On: the screen shows gauges, signal bars and charts.\n\nOff: the plain text list it had before.\n\n" +
            "Each of these screens also has a list / visual button at the top to switch it there."))
        com.rfsentinel.app.ui.VisualMode.Screen.values().forEach { s ->
            box.addView(SwitchMaterial(this).apply {
                text = s.label
                isChecked = com.rfsentinel.app.ui.VisualMode.on(this@SettingsActivity, s)
                setOnCheckedChangeListener { _, on -> com.rfsentinel.app.ui.VisualMode.set(this@SettingsActivity, s, on) }
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
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
        buildThemeList()
        setupThemeButtons()

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
        binding.wifiBandGroup.checkIndex(Prefs.wifiBand(this).ordinal)
        binding.scanModeGroup.checkIndex(SCAN_MODES.indexOf(Prefs.bleScanMode(this)).coerceAtLeast(0))
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
        binding.carRecordSwitch.isChecked = Prefs.carAutoRecord(this)
        binding.carRecordSwitch.setOnCheckedChangeListener { _, on ->
            if (on && Prefs.carDevices(this).isEmpty()) chooseCarDevices()
        }
        binding.carDevicesButton.setOnClickListener { chooseCarDevices() }
        updateCarDevicesText()
        binding.carMusicSwitch.isChecked = Prefs.carMusic(this)
        binding.carMusicSwitch.setOnCheckedChangeListener { _, on ->
            if (on && Prefs.carMusicApp(this) == null) chooseMusicApp()
            else if (on && Prefs.carDevices(this).isEmpty()) chooseCarDevices()
            updateMusicButton()
        }
        binding.carMusicButton.setOnClickListener { chooseMusicApp() }
        updateMusicButton()
        binding.batteryButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Open Android Settings > Apps > RF Sentinel > Battery", Toast.LENGTH_LONG).show() }
        }

        // Categories
        for (c in Category.entries) {
            val (short, more) = splitTitle(c.title)
            val sw = SwitchMaterial(this).apply {
                text = short
                isChecked = Prefs.categoryEnabled(this@SettingsActivity, c)
            }
            val details = listOfNotNull(more, if (c == Category.POLICE_REPORT) WAZE_WARNING_SHORT else null).joinToString("\n\n")
            val fold = foldIcon()
            binding.categoryContainer.addView(detectRow(sw, details.ifEmpty { null }, short, fold))
            categorySwitches[c] = sw
            addCategoryAlertChips(c, sw, fold)
            if (c == Category.TRACKER) {
                airTagSwitch = SwitchMaterial(this).apply {
                    text = "Exclude Apple AirTags"
                    isChecked = Prefs.excludeAirTags(this@SettingsActivity)
                    isEnabled = sw.isChecked
                }
                sw.onToggle { on -> airTagSwitch?.isEnabled = on }
                binding.categoryContainer.addView(detectRow(airTagSwitch!!).apply {
                    setPadding((24 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                })
            }
            if (c == Category.AIRCRAFT) addAircraftControls(sw)
            if (c == Category.POLICE_REPORT) addWazeControls(sw)
        }
        val enabledPresets = OuiWatchlist.getEnabledPresets(this)
        presetSwitches().forEach { (key, control) ->
            control.isChecked = key in enabledPresets
            if (key == "france" || key == "germany") control.setOnCheckedChangeListener { _, on ->
                if (on) com.rfsentinel.app.ui.RegionNotice.show(this, key)
            }
        }

        // Alerts
        val threshold = Prefs.alertThreshold(this)
        binding.thresholdGroup.checkIndex(when {
            threshold >= 80 -> 2
            threshold >= 50 -> 1
            else -> 0
        })
        binding.soundSwitch.isChecked = Prefs.soundEnabled(this)
        binding.alertVolumeGroup.checkIndex(Prefs.alertVolume(this).ordinal)
        // Each choice plays the alert sound at that volume.
        binding.alertVolumeGroup.setOnCheckedChangeListener { g, _ ->
            Prefs.setAlertVolume(this, Prefs.AlertVolume.entries[g.checkedIndex.coerceAtLeast(0)])
            com.rfsentinel.app.util.AlertPlayer.previewEffect(this, Prefs.detectorEffect(this))
        }
        binding.radarTopGroup.checkIndex(if (Prefs.radarNorthUp(this)) 1 else 0)
        binding.radarDotsGroup.checkIndex(if (Prefs.radarSpinDots(this)) 1 else 0)
        binding.radarNamesSwitch.isChecked = Prefs.radarNames(this)
        binding.radarSweepSwitch.isChecked = Prefs.radarSweepLine(this)
        binding.vibrateSwitch.isChecked = Prefs.vibrateEnabled(this)
        setupSnoozeLength()
        binding.radarBeepSwitch.isChecked = Prefs.radarBeep(this)
        binding.detectorSoundSwitch.isChecked = Prefs.detectorSound(this)
        binding.startupSweepSwitch.isChecked = Prefs.startupSweep(this)
        binding.gpsVoiceSwitch.isChecked = Prefs.gpsVoice(this)
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
        binding.rotationGroup.checkIndex(Prefs.rotation(this).ordinal)
        binding.rotationGroup.setOnCheckedChangeListener { group, _ ->
            Prefs.Rotation.entries.getOrNull(group.checkedIndex)?.let { Prefs.setRotation(this, it) }
            (application as com.rfsentinel.app.RFSentinelApp).applyOrientation(this) // turns right away
        }
        binding.floatingMapSwitch.isChecked = Prefs.floatingMap(this) && Settings.canDrawOverlays(this)
        binding.floatingMapSwitch.setOnCheckedChangeListener { sw, on ->
            if (on && !Settings.canDrawOverlays(this)) {
                sw.isChecked = false
                MaterialAlertDialogBuilder(this)
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
                MaterialAlertDialogBuilder(this)
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
        binding.ouiSpyEnginesButton.setOnClickListener { chooseOuiSpyEngines() }
        binding.linkButton.setOnClickListener { startActivity(Intent(this, com.rfsentinel.app.ui.SentinelLinkActivity::class.java)) }
        binding.linkSaveSwitch.isChecked = Prefs.saveLinked(this)
        binding.linkSaveSwitch.setOnCheckedChangeListener { _, on -> Prefs.setSaveLinked(this, on) }
        binding.usbWifiLogButton.setOnClickListener { exportUsbWifiLog() }
        // FOSS build: an adapter waiting for its chip firmware asks to download it.
        binding.usbWifiText.setOnClickListener { com.rfsentinel.app.usb.FirmwarePrompt.show(this) { updateEspStatus() } }
        binding.usbBtText.setOnClickListener { com.rfsentinel.app.usb.FirmwarePrompt.show(this) { updateEspStatus() } }
        if (intent.getBooleanExtra(com.rfsentinel.app.usb.FirmwarePrompt.EXTRA, false)) {
            intent.removeExtra(com.rfsentinel.app.usb.FirmwarePrompt.EXTRA)
            com.rfsentinel.app.usb.FirmwarePrompt.show(this) { updateEspStatus() }
        }
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
        binding.pinpointAlertsSwitch.isChecked = Prefs.pinpointAlertsOnly(this)
        binding.autoRecordSwitch.isChecked = Prefs.autoRecordTrace(this)
        binding.traceEveryPingSwitch.isChecked = Prefs.traceEveryPing(this)
        binding.ouiSpyRelaySwitch.isChecked = Prefs.ouiSpyRelayAll(this)
        binding.ouiSpyPhoneBleSwitch.isChecked = Prefs.ouiSpyPhoneBle(this)
        binding.screenModeGroup.checkIndex(Prefs.screenMode(this).ordinal)
        binding.knownAlprSwitch.isChecked = Prefs.knownAlprAlerts(this)
        binding.cellChangeSwitch.isChecked = Prefs.cellChangeAlerts(this)
        binding.speedCameraSwitch.isChecked = Prefs.speedCameraAlerts(this)
        binding.autoCamerasSwitch.isChecked = Prefs.autoCameras(this)
        setupMapSection()
        setupNavSection()
        setupDeviceUnits()
        setupRadioSection()
        setupLiveExport()
        setupWigleAccount()
        addDetails()
        setupRecenterDelay()
        binding.hideTrustedSwitch.isChecked = Prefs.hideWhitelisted(this)
        binding.hideTrustedSwitch.setOnCheckedChangeListener { _, on -> Prefs.setHideWhitelisted(this, on) }
        binding.usbLightSwitch.isChecked = Prefs.usbAdapterLight(this)
        binding.usbLightSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setUsbAdapterLight(this, on)
            com.rfsentinel.app.usb.Rtl8821auMonitor.lightEnabled = on // live, also while scanning
        }
        binding.usbBtSwitch.isChecked = Prefs.usbBluetooth(this)
        binding.usbBtSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setUsbBluetooth(this, on)
            com.rfsentinel.app.usb.UsbBluetooth.settingChanged(this) // starts or stops it live while scanning
            updateEspStatus()
        }
        binding.rtl5gSwitch.isChecked = Prefs.rtl8821au5g(this)
        binding.rtl5gSwitch.setOnCheckedChangeListener { _, on -> Prefs.setRtl8821au5g(this, on) }
        binding.autoUpdateSwitch.isChecked = Prefs.autoUpdateCheck(this)
        binding.prereleaseSwitch.isChecked = Prefs.updatePrereleases(this)
        fun showRadius(km: Int) {
            binding.cameraRadiusText.text = "Download radius: ${com.rfsentinel.app.util.Units.km(km)}"
            binding.prefetchCamerasButton.text = "Download cameras around me"
        }
        Prefs.cameraRadiusKm(this).let { km -> binding.cameraRadiusSlider.value = km.toFloat(); showRadius(km) }
        binding.cameraRadiusSlider.setLabelFormatter { com.rfsentinel.app.util.Units.km(it.toInt()) }
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
        binding.importHistoryButton.setOnClickListener {
            startActivity(Intent(this, com.rfsentinel.app.ui.DeviceHistoryActivity::class.java)
                .putExtra(com.rfsentinel.app.ui.DeviceHistoryActivity.EXTRA_IMPORT, true))
        }
        binding.backupSettingsButton.setOnClickListener { backupPicker.launch(com.rfsentinel.app.util.SettingsBackup.fileName()) }
        binding.restoreSettingsButton.setOnClickListener { restorePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }
        // Lists, tools & about (moved here from the main screen's menu).
        binding.openWatchlistButton.setOnClickListener { startActivity(Intent(this, com.rfsentinel.app.ouilist.OuiListActivity::class.java)) }
        binding.openWhitelistButton.setOnClickListener { startActivity(Intent(this, com.rfsentinel.app.whitelist.WhitelistActivity::class.java)) }
        binding.ignoredSsidsButton.setOnClickListener { editIgnoredSsids() }
        updateIgnoredSsidsButton()
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
                    AppDatabase.getInstance(this@SettingsActivity).deviceDetailsDao().pruneOrphans()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(com.rfsentinel.app.usb.FirmwarePrompt.EXTRA, false))
            com.rfsentinel.app.usb.FirmwarePrompt.show(this) { updateEspStatus() }
    }

    override fun onResume() {
        super.onResume()
        updateLinkStatus()
        // Back from the theme maker: its themes may have changed.
        if (loaded) buildThemeList()
        updatePcap()
        updateUsbPickers()
        // The user may be coming back from Developer options.
        updateWifiThrottleHint()
        showNote(aircraftStatusText, com.rfsentinel.app.online.OnlineWatch.aircraftStatus)
        showNote(wazeStatusText, com.rfsentinel.app.online.OnlineWatch.wazeStatus)
    }

    private fun updateWifiThrottleHint() {
        updateWifiSliderMin()
        val throttled = Prefs.wifiScanThrottled(this)
        binding.wifiThrottleText.text = if (throttled)
            "Android throttles WiFi scans: under 30 s is wasted unless you turn it off"
        else
            "Scan throttling is off: down to 5 s works"
        binding.wifiThrottleButton.shownIf(throttled)
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
        MaterialAlertDialogBuilder(this)
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

    private fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Clear") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Settings save themselves: whenever this screen is left (back, home, another screen). */
    /** A restored backup is in place: leaving Settings must not write the old on-screen values over it. */
    private var restored = false

    override fun onPause() {
        if (restored) { super.onPause(); return }
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
        Prefs.setWifiBand(this, Prefs.WifiBand.entries.getOrElse(binding.wifiBandGroup.checkedIndex) { Prefs.WifiBand.ALL })
        Prefs.setBleScanMode(this, SCAN_MODES.getOrElse(binding.scanModeGroup.checkedIndex) { ScanSettings.SCAN_MODE_LOW_LATENCY })
        val intervalSec = binding.intervalInput.text.toString().toLongOrNull() ?: 30L
        Prefs.setScanIntervalMs(this, intervalSec.coerceAtLeast(5) * 1000L)
        Prefs.setAutoStartOnBoot(this, binding.bootSwitch.isChecked)
        Prefs.setCarAutoStart(this, binding.carAutoSwitch.isChecked)
        Prefs.setCarAutoRecord(this, binding.carRecordSwitch.isChecked)
        Prefs.setCarMusic(this, binding.carMusicSwitch.isChecked)

        categorySwitches.forEach { (c, sw) -> Prefs.setCategoryEnabled(this, c, sw.isChecked) }
        airTagSwitch?.let { Prefs.setExcludeAirTags(this, it.isChecked) }
        wazeKeyInput?.let { input ->
            val key = input.text.toString().trim()
            val name = com.rfsentinel.app.online.OnlineWatch.WAZE_KEY_NAME
            if (key != com.rfsentinel.app.util.SecureStore.get(this, name).orEmpty()) com.rfsentinel.app.util.SecureStore.put(this, name, key)
        }
        OuiWatchlist.setEnabledPresets(this, presetSwitches().filterValues { it.isChecked }.keys)

        Prefs.setAlertThreshold(this, intArrayOf(0, 50, 80).getOrElse(binding.thresholdGroup.checkedIndex) { 50 })
        Prefs.setSoundEnabled(this, binding.soundSwitch.isChecked)
        Prefs.setRadarNorthUp(this, binding.radarTopGroup.checkedIndex == 1)
        Prefs.setRadarSpinDots(this, binding.radarDotsGroup.checkedIndex == 1)
        Prefs.setRadarNames(this, binding.radarNamesSwitch.isChecked)
        Prefs.setRadarSweepLine(this, binding.radarSweepSwitch.isChecked)
        Prefs.setRadarBeep(this, binding.radarBeepSwitch.isChecked)
        Prefs.setDetectorSound(this, binding.detectorSoundSwitch.isChecked)
        Prefs.setStartupSweep(this, binding.startupSweepSwitch.isChecked)
        Prefs.setGpsVoice(this, binding.gpsVoiceSwitch.isChecked)
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
        Prefs.setPinpointAlertsOnly(this, binding.pinpointAlertsSwitch.isChecked)
        Prefs.setAutoRecordTrace(this, binding.autoRecordSwitch.isChecked)
        Prefs.setTraceEveryPing(this, binding.traceEveryPingSwitch.isChecked)
        Prefs.setOuiSpyRelayAll(this, binding.ouiSpyRelaySwitch.isChecked)
        Prefs.setOuiSpyPhoneBle(this, binding.ouiSpyPhoneBleSwitch.isChecked)
        Prefs.setScreenMode(this, Prefs.ScreenMode.entries.getOrElse(binding.screenModeGroup.checkedIndex) { Prefs.ScreenMode.ON_WHILE_CHARGING })
        com.rfsentinel.app.ui.ScreenAwake.apply(this)
        Prefs.setKnownAlprAlerts(this, binding.knownAlprSwitch.isChecked)
        Prefs.setCellChangeAlerts(this, binding.cellChangeSwitch.isChecked)
        Prefs.setSpeedCameraAlerts(this, binding.speedCameraSwitch.isChecked)
        if (binding.autoCamerasSwitch.isChecked != Prefs.autoCameras(this)) Prefs.setCamerasAsked(this, true)
        Prefs.setAutoCameras(this, binding.autoCamerasSwitch.isChecked)
        // Turning the check on or off here answers the one-time question too.
        if (binding.autoUpdateSwitch.isChecked != Prefs.autoUpdateCheck(this)) Prefs.setUpdateCheckAsked(this, true)
        Prefs.setAutoUpdateCheck(this, binding.autoUpdateSwitch.isChecked)
        Prefs.setUpdatePrereleases(this, binding.prereleaseSwitch.isChecked)

        Prefs.setRetentionDays(this, (binding.retentionInput.text.toString().toIntOrNull() ?: 90).coerceAtLeast(0))

        // Re-deliver a start command so a running scanner picks up the changes.
        if (ScanForegroundService.isRunning) ScanForegroundService.start(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
