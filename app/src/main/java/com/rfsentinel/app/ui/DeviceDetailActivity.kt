package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.DeviceNames
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.databinding.ActivityDeviceDetailBinding
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.MacUtil
import com.rfsentinel.app.util.ProximityUtil
import com.rfsentinel.app.util.applySystemBarInsets
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything RF Sentinel knows about one device, from passive observation
 * only: why it was flagged, decoded identifiers, live signal with a locate
 * mode, long-term history, drone Remote ID, and the raw advertisement.
 */
class DeviceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAC = "mac"
        /** Two radio frequencies this close are the same channel (half the sweep's 12.5 kHz step). */
        private const val RADIO_SAME_HZ = 6_250L
        private val RADIO_KEY = Regex("""^RADIO (\d+(?:\.\d+)?) MHz$""")

        /** The frequency in a radio transmission's log key ("RADIO 154.4300 MHz"), else null. */
        fun radioHz(key: String): Long? =
            RADIO_KEY.find(key)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.round(it * 1_000_000) }
    }

    private lateinit var binding: ActivityDeviceDetailBinding
    private lateinit var mac: String
    private var sectionsKey = ""
    private var known: KnownDeviceEntity? = null
    /** The saved record's type as this version names it. */
    private val knownType get() = known?.let { DeviceIntel.savedType(it.deviceType, it.vendor, it.mac) }
    private var pastMatches: List<DetectionEntity> = emptyList()
    /** The newest saved match of each kind, as hits: what the page shows while the device isn't heard. */
    private var pastHits: List<com.rfsentinel.app.detect.Hit> = emptyList()
    /** Its page as it was when last heard: what the page shows while the device isn't heard. */
    private var saved: com.rfsentinel.app.data.LastHeard.Saved? = null
    /** The saved page, while the device isn't heard here now. */
    private fun savedNow(s: DeviceRegistry.Snapshot?) = if (s == null) saved else null
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private val timeFmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        mac = intent.getStringExtra(EXTRA_MAC) ?: run { finish(); return }
        title = "Device details"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        radioHz = radioHz(mac)
        binding.locateButton.setOnClickListener { if (radioHz != null) locateRadio() else toggleLocate() }
        // The main action in the theme's highlight colour (faded while the device isn't heard).
        ChipStyle.accent(this).let { (accent, onAccent) ->
            val off = intArrayOf(-android.R.attr.state_enabled)
            val on = intArrayOf()
            binding.locateButton.backgroundTintList = android.content.res.ColorStateList(arrayOf(off, on), intArrayOf((accent and 0x00FFFFFF) or 0x55000000, accent))
            binding.locateButton.setTextColor(android.content.res.ColorStateList(arrayOf(off, on), intArrayOf((onAccent and 0x00FFFFFF) or 0x99000000.toInt(), onAccent)))
        }
        binding.actionWatchlist.setOnClickListener { if (radioHz != null) toggleWatchedFrequency() else onWatchlistAction() }
        binding.actionWhitelist.setOnClickListener {
            val s = DeviceRegistry.get(mac)
            when {
                com.rfsentinel.app.data.TrackerMutes.isMuted(mac) -> {
                    com.rfsentinel.app.data.TrackerMutes.unmute(this, mac)
                    Toast.makeText(this, "Tracker no longer ignored", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                WhitelistCache.inTable(mac) -> DeviceActions.unwhitelist(this, mac)
                com.rfsentinel.app.data.TrustedNetworks.contains(mac) -> { DeviceActions.untrustNetwork(this, DeviceActions.ssidOf(s)); refresh() }
                isRotatingTracker(s) -> DeviceActions.ignoreTracker(this, mac)
                else -> DeviceActions.whitelist(this, mac, s?.let { it.best?.label ?: it.name } ?: "", DeviceActions.ssidOf(s))
            }
        }
        binding.actionFavorite.setOnClickListener {
            val s = DeviceRegistry.get(mac)
            val fav = Favorites.contains(mac)
            lifecycleScope.launch {
                Favorites.set(this@DeviceDetailActivity, mac, !fav, s?.name ?: known?.name, s?.vendor ?: known?.vendor, s?.deviceType)
                refresh()
            }
        }
        binding.actionRename.setOnClickListener { rename() }
        binding.actionNote.setOnClickListener { editNote() }
        binding.noteText.setOnClickListener { editNote() }
        binding.actionSightings.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra(HistoryActivity.EXTRA_MAC, mac))
        }
        binding.actionCopy.setOnClickListener { DeviceActions.copy(this, mac) }
        binding.actionShare.setOnClickListener { share() }
        binding.actionLearn.setOnClickListener { DeviceActions.learn(this, mac) }

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@DeviceDetailActivity)
            known = db.knownDeviceDao().get(mac)
            pastMatches = db.detectionDao().forMac(mac)
            saved = db.deviceDetailsDao().get(mac)?.let { com.rfsentinel.app.data.LastHeard.fromJson(it.json) }
            pastHits = pastMatches.distinctBy { it.label }.take(5).map { m ->
                com.rfsentinel.app.detect.Hit(com.rfsentinel.app.detect.Category.parse(m.category) ?: com.rfsentinel.app.detect.Category.CUSTOM,
                    m.label, m.confidence, m.evidence, "")
            }
            sectionsKey = ""
            refresh()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refresh()
                    delay(1000)
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        VisualMode.addMenu(menu, this, VisualMode.Screen.DEVICE_DETAIL); return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        VisualMode.handle(item, this, VisualMode.Screen.DEVICE_DETAIL) { invalidateOptionsMenu(); refresh() } ||
            super.onOptionsItemSelected(item)

    private val visual get() = VisualMode.on(this, VisualMode.Screen.DEVICE_DETAIL)

    // ---- Rendering ------------------------------------------------------------

    private fun isRotatingTracker(s: DeviceRegistry.Snapshot?) =
        s?.best?.category == com.rfsentinel.app.detect.Category.TRACKER &&
            s.addressType != com.rfsentinel.app.detect.AddressType.PUBLIC

    private fun refresh() {
        val s = DeviceRegistry.get(mac)
        // The same device under a newer address (it rotated): its live signal is heard there now.
        val liveMac = DeviceRegistry.currentAddress(mac)
        val live = if (liveMac != mac) DeviceRegistry.get(liveMac) ?: s else s
        val now = System.currentTimeMillis()
        val whitelisted = WhitelistCache.contains(mac)
        val best = s?.best
        val last = savedNow(s)
        // Not heard now: its strongest match as last heard, else from the match history, like the list showed it.
        val pastBest = last?.best ?: pastHits.maxByOrNull { it.confidence }

        // Header
        if (whitelisted) {
            binding.tagText.visibility = View.VISIBLE
            binding.tagText.text = "WHITELISTED - trusted"
            binding.tagText.setBackgroundColor(0xFF6B7B80.toInt())
        } else if (best != null) {
            binding.tagText.visibility = View.VISIBLE
            binding.tagText.text = "${best.category.shortTag} · ${best.tier.label} ${best.confidence}%"
            binding.tagText.setBackgroundColor(ThemeManager.ink(this, if (best.tier == Tier.WEAK) 0xFFB26A00.toInt() else best.category.colorArgb))
        } else if (s == null && pastBest != null) {
            val h = pastBest
            binding.tagText.visibility = View.VISIBLE
            binding.tagText.text = "${h.category.shortTag} · ${h.tier.label} ${h.confidence}%"
            binding.tagText.setBackgroundColor(ThemeManager.ink(this, if (h.tier == Tier.WEAK) 0xFFB26A00.toInt() else h.category.colorArgb))
        } else {
            binding.tagText.visibility = View.GONE
        }
        val heardAs = best?.label ?: s?.name ?: pastBest?.label ?: known?.name ?: s?.deviceType ?: knownType ?: "Device"
        val alias = DeviceNames.of(mac)
        // Your own name in place of what it's heard as; that stays on the line below.
        binding.titleText.text = alias ?: heardAs
        binding.subtitleText.text = (if (alias != null) "$heardAs\n" else "") +
            mac + ((s?.vendor ?: known?.vendor ?: VendorDb.macVendor(mac))?.let { "\n$it" } ?: "")
        val note = known?.note?.takeIf { it.isNotBlank() }
        binding.noteText.visibility = if (note != null) View.VISIBLE else View.GONE
        binding.noteText.text = note?.let { "\uD83D\uDCDD $it" }
        binding.presenceText.text = when {
            liveMac != mac && live != null -> "In range under its new address $liveMac (it changed its address) · heard ${ago(now - live.lastSeen)}"
            s == null -> "Not in range right now" + (known?.let { " · last heard ${dateFmt.format(Date(it.lastSeen))}" } ?: "") +
                (last?.takeIf { it.following }?.let { "\n⚠ Was moving with you" } ?: "")
            s.following -> "⚠ Has been moving with you"
            else -> "In range · heard ${ago(now - s.lastSeen)} · ${s.sightings} packets received"
        }

        // Visual style: the dial and ring take the place of the big number.
        val visual = this.visual
        binding.visualTop.visibility = if (visual) View.VISIBLE else View.GONE
        binding.rssiText.visibility = if (visual) View.GONE else View.VISIBLE
        val ringHit = (best ?: last?.best)?.takeIf { !whitelisted }
        binding.ring.visibility = if (ringHit != null) View.VISIBLE else View.GONE
        if (ringHit != null) binding.ring.set(ringHit.confidence,
            ThemeManager.ink(this, if (ringHit.tier == Tier.WEAK) 0xFFB26A00.toInt() else ringHit.category.colorArgb),
            ringHit.tier.label.lowercase() + " match")

        // Live signal (from the newest address when it rotated)
        if (live != null) {
            binding.rssiText.text = "${live.rssi}"
            val trendDir = LiveRead.trend(live)
            val trend = if (live.history.size < 2) "" else when (trendDir) {
                1 -> "  ↑ getting closer"; -1 -> "  ↓ moving away"; else -> "  → steady"
            }
            binding.gauge.set(live.rssi, live.bestRssi, trendDir)
            binding.rssiDetail.text = (if (visual) "${ProximityUtil.band(live.rssi)}\n" else "dBm · ${ProximityUtil.band(live.rssi)} $trend\n") +
                "${DeviceIntel.formatDistance(live.distanceM)} (rough) · best ${live.bestRssi} dBm" +
                (if (liveMac != mac) "\nNow heard as $liveMac" else "")
            binding.graph.setSamples(live.history)
            binding.locateButton.isEnabled = true
            binding.locateButton.text = if (locatingThis()) "Stop" else "Locate"
        } else {
            binding.rssiText.text = "--"
            binding.gauge.set(null, null, 0)
            // As last heard: its last reading, best and the signal graph then.
            binding.rssiDetail.text = "Not currently heard" + (last?.let {
                "\nLast ${it.rssi} dBm · ${ProximityUtil.band(it.rssi)}\n" +
                    "${DeviceIntel.formatDistance(it.distanceM)} (rough) · best ${it.bestRssi} dBm"
            } ?: "")
            binding.graph.setSamples(last?.samples.orEmpty())
            binding.locateButton.isEnabled = false
            stopLocate()
        }

        // Action labels
        val fp = com.rfsentinel.app.usb.ProbeIntel.of(mac)?.fingerprint
        val custom = OuiWatchlist.customAddressEntry(mac)
            ?: fp?.let { OuiWatchlist.customEntry(com.rfsentinel.app.oui.OuiEntry.FINGERPRINT + it) }
        binding.actionWatchlist.text = if (custom != null) "Remove from watchlist" else "Add to watchlist"
        radioHz?.let { hz ->
            // A radio transmission (RTL-SDR / HackRF): found with the waterfall, watched as a frequency.
            binding.locateButton.isEnabled = true // without an SDR it says what it needs
            binding.actionWatchlist.text = if (watchedTarget(hz) != null) "Stop watching frequency" else "Watch this frequency"
        }
        binding.actionWhitelist.text = when {
            com.rfsentinel.app.data.TrackerMutes.isMuted(mac) -> "Stop ignoring"
            whitelisted -> "Un-whitelist"
            isRotatingTracker(s) -> "Ignore tracker"
            else -> "Whitelist"
        }
        binding.actionFavorite.text = if (Favorites.contains(mac)) "★ Favorite" else "☆ Favorite"
        binding.actionNote.text = if (known?.note.isNullOrBlank()) "Add note" else "Edit note"

        // Sections: rebuilt only when their content changes (keeps scroll & selection stable).
        val content = buildSections(s)
        val key = visual.toString() + content.joinToString("|") { it.first + it.second.joinToString { r -> r.first + r.second } }
        if (key != sectionsKey) {
            sectionsKey = key
            renderSections(content)
        }
    }

    private fun buildSections(s: DeviceRegistry.Snapshot?): List<DeviceSection> {
        val last = savedNow(s) ?: return DeviceSections.build(this, mac, s, known, pastMatches, pastHits)
        // Not heard now: its page as last heard, with the saved record and match history as they are now.
        return last.sections.map { (title, rows) ->
            when (title) {
                DeviceSections.FLAGGED -> DeviceSections.WAS_FLAGGED to
                    if (last.hits.isEmpty() && pastHits.isNotEmpty()) DeviceSections.pastMatchRows(pastMatches, pastHits) else rows
                DeviceSections.HISTORY -> title to listOf(
                    "Last heard" to dateFmt.format(Date(last.firstSeen)) + " - " + timeFmt.format(Date(last.at)) +
                        " · ${last.sightings} packets received"
                ) + DeviceSections.savedRows(known, pastMatches) + rows
                else -> title to rows
            }
        }
    }

    private fun renderSections(sections: List<DeviceSection>) {
        val container = binding.sections
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val hits = DeviceRegistry.get(mac)?.hits ?: saved?.hits.orEmpty()
        for ((title, rows) in sections) {
            container.addView(TextView(this).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, (18 * dp).toInt(), 0, (4 * dp).toInt())
            })
            if (visual && (title == DeviceSections.FLAGGED || title == DeviceSections.WAS_FLAGGED) && hits.isNotEmpty()) {
                hits.forEach { container.addView(hitCard(it)) }
                continue
            }
            for ((k, v) in rows) {
                container.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
                    addView(TextView(context).apply {
                        text = k
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(0xFF888888.toInt())
                    })
                    addView(TextView(context).apply {
                        text = v
                        setTextIsSelectable(true)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        if (title == DeviceSections.RAW) typeface = Typeface.MONOSPACE
                    })
                })
            }
            if (title.startsWith("Drone")) addDroneButtons(container)
        }
        val tagged = pastMatches.firstOrNull { it.latitude != null }
        if (tagged != null) {
            container.addView(mapButton("Show last logged location on map", tagged.latitude!!, tagged.longitude!!, "Logged match"))
        }
    }

    /** Visual style: one card per match, with its confidence as a bar and what it rests on as chips. */
    private fun hitCard(h: com.rfsentinel.app.detect.Hit): View {
        val dp = resources.displayMetrics.density
        val color = ThemeManager.ink(this, if (h.tier == Tier.WEAK) 0xFFB26A00.toInt() else h.category.colorArgb)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = VisualMode.card(this@DeviceDetailActivity, color, 0x18, 0x77, 14f)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt(); bottomMargin = (4 * dp).toInt() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = h.label; setTypeface(typeface, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply {
                    text = "${h.confidence}%"; setTypeface(typeface, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(color)
                })
            })
            addView(LevelBarView(context).apply { set(h.confidence / 100f, color) },
                LinearLayout.LayoutParams(-1, (6 * dp).toInt()).apply { topMargin = (6 * dp).toInt(); bottomMargin = (8 * dp).toInt() })
            addView(com.google.android.material.chip.ChipGroup(context).apply {
                chipSpacingVertical = (4 * dp).toInt()
                addView(VisualMode.chip(context, "${h.category.title} · ${h.tier.label}", color))
                evidenceKinds(h.evidence).forEach { addView(VisualMode.chip(context, it, ChipStyle.accent(context).first)) }
            })
            addView(TextView(context).apply {
                text = h.evidence; setTextIsSelectable(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            addView(TextView(context).apply {
                text = "Source: ${h.source}"; setTextIsSelectable(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.7f
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        }
    }

    /** What a match's evidence rests on, as short chip labels. */
    private fun evidenceKinds(evidence: String): List<String> {
        val e = evidence.lowercase()
        return buildList {
            if ("service uuid" in e || "uuid" in e) add("Service UUID")
            if ("company id" in e || "manufacturer data" in e) add("Company ID")
            if ("payload" in e || "tag" in e) add("Payload")
            if ("name" in e) add("Name")
            if ("oui" in e || "address prefix" in e || "mac prefix" in e || "registrant" in e) add("Address prefix")
            if ("probe" in e || "fingerprint" in e) add("Probe")
            if ("remote id" in e) add("Remote ID")
            if ("following" in e || "moving with you" in e || "with you" in e) add("Follows you")
            if ("corroborated" in e) add("Corroborated")
            if ("ssid" in e || "network" in e) add("WiFi")
        }.distinct()
    }

    private fun addDroneButtons(container: LinearLayout) {
        val r = DeviceRegistry.get(mac)?.remoteId
        if (r == null) {
            // Not heard now: where it and its operator were when last heard.
            val last = saved ?: return
            last.drone?.let { (lat, lon) -> container.addView(mapButton("Drone on map", lat, lon, "Drone ${last.uasId ?: ""}")) }
            last.operator?.let { (lat, lon) -> container.addView(mapButton("Operator / takeoff on map", lat, lon, "Drone operator")) }
            return
        }
        if (r.hasPosition) container.addView(mapButton("Drone on map", r.latitude!!, r.longitude!!, "Drone ${r.uasId ?: ""}"))
        if (r.hasOperatorPosition) container.addView(mapButton("Operator / takeoff on map", r.operatorLatitude!!, r.operatorLongitude!!, "Drone operator"))
    }

    /** Hands the point to the user's own maps app. */
    private fun mapButton(label: String, lat: Double, lon: Double, pinName: String) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setOnClickListener {
                val uri = Uri.parse(String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)", lat, lon, lat, lon, Uri.encode(pinName)))
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    Toast.makeText(this@DeviceDetailActivity, "No maps app installed", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** Your own name for the device, shown in place of the name it broadcasts. */
    private fun rename() {
        val s = DeviceRegistry.get(mac)
        val heardAs = s?.best?.label ?: s?.name ?: known?.name ?: s?.deviceType ?: knownType
        ask("Name this device", DeviceNames.of(mac).orEmpty(), heardAs ?: "Name", multiLine = false,
            clear = DeviceNames.of(mac)?.let { "Use its own name" }) { text ->
            lifecycleScope.launch {
                DeviceNames.set(this@DeviceDetailActivity, mac, text, s?.name ?: known?.name, s?.vendor ?: known?.vendor, s?.deviceType,
                    DeviceRegistry.fingerprintOf(mac))
                known = AppDatabase.getInstance(this@DeviceDetailActivity).knownDeviceDao().get(mac)
                refresh()
            }
        }
    }

    /** Your own note on the device: kept with its history, searchable there. */
    private fun editNote() {
        val s = DeviceRegistry.get(mac)
        val old = known?.note.orEmpty()
        ask(if (old.isBlank()) "Add a note" else "Edit note", old, "Where you saw it, what it is…", multiLine = true,
            clear = old.takeIf { it.isNotBlank() }?.let { "Delete note" }) { text ->
            lifecycleScope.launch {
                DeviceNames.setNote(this@DeviceDetailActivity, mac, text, s?.name ?: known?.name, s?.vendor ?: known?.vendor, s?.deviceType)
                known = AppDatabase.getInstance(this@DeviceDetailActivity).knownDeviceDao().get(mac)
                refresh()
            }
        }
    }

    /** A text box with Save, Cancel and (when there is something to clear) [clear]; [done] gets the text, blank when cleared. */
    private fun ask(title: String, value: String, hint: String, multiLine: Boolean, clear: String?, done: (String) -> Unit) {
        val dp = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            setText(value); this.hint = hint
            if (multiLine) { isSingleLine = false; minLines = 2; maxLines = 6 } else { isSingleLine = true; setSelectAllOnFocus(true) }
            setSelection(text.length)
        }
        val box = android.widget.FrameLayout(this).apply {
            val side = (20 * dp).toInt(); setPadding(side, (8 * dp).toInt(), side, 0); addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> done(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .apply { if (clear != null) setNeutralButton(clear) { _, _ -> done("") } }
            .show()
            .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }

    private fun ago(ms: Long) = if (ms < 2000) "just now" else "${ms / 1000}s ago"

    // ---- Actions --------------------------------------------------------------

    private fun onWatchlistAction() {
        val custom = OuiWatchlist.customAddressEntry(mac)
        if (custom != null) {
            OuiWatchlist.removeCustomEntry(this, custom.prefix)
            com.rfsentinel.app.util.Undo.show(this, "Removed ${custom.prefix} from the watchlist") {
                OuiWatchlist.addCustomEntry(this, custom)
            }
        } else {
            DeviceActions.addToWatchlist(this, mac, DeviceRegistry.get(mac)?.name ?: known?.name,
                DeviceRegistry.get(mac)?.advert?.isWifi == true)
        }
    }

    private fun share() {
        val s = DeviceRegistry.get(mac)
        if (s?.advert != null && s.best == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Share")
                .setItems(arrayOf("Full device report", "Report unknown device (signature only)")) { _, which ->
                    if (which == 0) shareFull(s) else DeviceActions.reportUnknown(this, mac)
                }
                .show()
            return
        }
        shareFull(s)
    }

    private fun shareFull(s: DeviceRegistry.Snapshot?) {
        val text = buildString {
            appendLine("RF Sentinel device report")
            appendLine(binding.titleText.text)
            appendLine(binding.subtitleText.text)
            buildSections(s).forEach { (title, rows) ->
                appendLine()
                appendLine("== $title ==")
                rows.forEach { (k, v) -> appendLine("$k: $v") }
            }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share device report"))
    }

    // ---- Radio transmissions (logged as "RADIO 154.4300 MHz") -----------------------

    /** The frequency of a radio transmission's page, null for a Wi-Fi / Bluetooth device. */
    private var radioHz: Long? = null

    /** Locate for a radio: the waterfall zoomed in on its frequency, live, to walk towards the strongest trace. */
    private fun locateRadio() {
        val hz = radioHz ?: return
        if (!com.rfsentinel.app.sdr.SdrRadio.running) {
            Toast.makeText(this, "Plug in an RTL-SDR or HackRF and start scanning to follow this frequency", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, WaterfallActivity::class.java).putExtra(WaterfallActivity.EXTRA_TUNE_HZ, hz))
    }

    /** The watched-frequency line for [hz] (Settings > RTL-SDR radio > Watch these frequencies), if any. */
    private fun watchedTarget(hz: Long): String? = Prefs.radioTargets(this).lines().firstOrNull { line ->
        com.rfsentinel.app.sdr.RadioSettings.targets(line).firstOrNull()?.let { kotlin.math.abs(it.freqHz - hz) < RADIO_SAME_HZ } == true
    }

    /** Adds the frequency to the watched ones (always reported when active, whatever the sensitivity), or takes it off. */
    private fun toggleWatchedFrequency() {
        val hz = radioHz ?: return
        val before = Prefs.radioTargets(this)
        val line = watchedTarget(hz)
        val after = if (line != null) before.lines().filterNot { it == line }.joinToString("\n")
        else {
            val name = com.rfsentinel.app.sdr.FreqNames.lookup(hz, Prefs.radioMatchKhz(this) * 1000L)?.name ?: DeviceNames.of(mac)
            val add = String.format(java.util.Locale.US, "%.4f", hz / 1e6) + (name?.let { " $it" } ?: "")
            before.trimEnd().let { if (it.isEmpty()) add else "$it\n$add" }
        }
        applyRadioTargets(after)
        com.rfsentinel.app.util.Undo.show(this,
            if (line != null) "No longer watching this frequency" else "Watching this frequency: always reported when active") {
            applyRadioTargets(before)
        }
    }

    private fun applyRadioTargets(text: String) {
        Prefs.setRadioTargets(this, text)
        // A running SDR picks it up at once (the scanner reads the settings again only on its next refresh).
        if (com.rfsentinel.app.sdr.SdrRadio.running) com.rfsentinel.app.sdr.SdrRadio.config = com.rfsentinel.app.sdr.RadioSettings.config(this)
        refresh()
    }

    // ---- Locate (Geiger-counter beeps) ------------------------------------------

    // Locate runs in LiveRead, so it keeps going while you look at the map (its live read card shows there).
    private fun locatingThis() = LiveRead.locating == mac

    private fun toggleLocate() {
        if (locatingThis()) stopLocate() else startLocate()
    }

    private fun startLocate() {
        LiveRead.startLocate(this, mac)
        binding.locateButton.text = "Stop"
        Toast.makeText(this, "Walk around: beeps speed up as the signal gets stronger. It keeps going on the map", Toast.LENGTH_LONG).show()
    }

    private fun stopLocate() {
        if (locatingThis()) LiveRead.stopLocate()
        if (::binding.isInitialized) binding.locateButton.text = "Locate"
    }
}
