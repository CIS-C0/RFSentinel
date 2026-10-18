package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
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
import com.rfsentinel.app.util.AlertPlayer
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.DetectionEntity
import com.rfsentinel.app.data.DeviceNames
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.databinding.ActivityDeviceDetailBinding
import com.rfsentinel.app.detect.AdStructure
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Bytes
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.MacUtil
import com.rfsentinel.app.util.ProximityUtil
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.Job
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
    }

    private lateinit var binding: ActivityDeviceDetailBinding
    private lateinit var mac: String
    private var sectionsKey = ""
    private var known: KnownDeviceEntity? = null
    private var pastMatches: List<DetectionEntity> = emptyList()
    private var locateJob: Job? = null
    private var tone: ToneGenerator? = null
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

        binding.locateButton.setOnClickListener { toggleLocate() }
        binding.actionWatchlist.setOnClickListener { onWatchlistAction() }
        binding.actionWhitelist.setOnClickListener {
            val s = DeviceRegistry.get(mac)
            when {
                com.rfsentinel.app.data.TrackerMutes.isMuted(mac) -> {
                    com.rfsentinel.app.data.TrackerMutes.unmute(this, mac)
                    Toast.makeText(this, "Tracker no longer ignored", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                WhitelistCache.inTable(mac) -> DeviceActions.unwhitelist(this, mac)
                isRotatingTracker(s) -> DeviceActions.ignoreTracker(this, mac)
                else -> DeviceActions.whitelist(this, mac, s?.let { it.best?.label ?: it.name } ?: "")
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
        binding.actionCopy.setOnClickListener { DeviceActions.copy(this, mac) }
        binding.actionShare.setOnClickListener { share() }

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@DeviceDetailActivity)
            known = db.knownDeviceDao().get(mac)
            pastMatches = db.detectionDao().forMac(mac)
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

    override fun onPause() {
        stopLocate()
        super.onPause()
    }

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

        // Header
        if (whitelisted) {
            binding.tagText.visibility = View.VISIBLE
            binding.tagText.text = "WHITELISTED - trusted"
            binding.tagText.setBackgroundColor(0xFF6B7B80.toInt())
        } else if (best != null) {
            binding.tagText.visibility = View.VISIBLE
            binding.tagText.text = "${best.category.shortTag} · ${best.tier.label} ${best.confidence}%"
            binding.tagText.setBackgroundColor(ThemeManager.ink(this, if (best.tier == Tier.WEAK) 0xFFB26A00.toInt() else best.category.colorArgb))
        } else {
            binding.tagText.visibility = View.GONE
        }
        val heardAs = best?.label ?: s?.name ?: known?.name ?: s?.deviceType ?: known?.deviceType ?: "Device"
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
            s == null -> "Not in range right now" + (known?.let { " · last heard ${dateFmt.format(Date(it.lastSeen))}" } ?: "")
            s.following -> "⚠ Has been moving with you"
            else -> "In range · heard ${ago(now - s.lastSeen)} · ${s.sightings} packets received"
        }

        // Visual style: the dial and ring take the place of the big number.
        val visual = this.visual
        binding.visualTop.visibility = if (visual) View.VISIBLE else View.GONE
        binding.rssiText.visibility = if (visual) View.GONE else View.VISIBLE
        val ringHit = best?.takeIf { !whitelisted }
        binding.ring.visibility = if (ringHit != null) View.VISIBLE else View.GONE
        if (ringHit != null) binding.ring.set(ringHit.confidence,
            ThemeManager.ink(this, if (ringHit.tier == Tier.WEAK) 0xFFB26A00.toInt() else ringHit.category.colorArgb),
            ringHit.tier.label.lowercase() + " match")

        // Live signal (from the newest address when it rotated)
        if (live != null) {
            binding.rssiText.text = "${live.rssi}"
            val recent = live.history.takeLast(6).dropLast(1)
            val trendDir = if (recent.isEmpty()) 0 else {
                val avg = recent.map { it.rssi }.average()
                when {
                    live.rssi > avg + 3 -> 1
                    live.rssi < avg - 3 -> -1
                    else -> 0
                }
            }
            val trend = if (recent.isEmpty()) "" else when (trendDir) {
                1 -> "  ↑ getting closer"; -1 -> "  ↓ moving away"; else -> "  → steady"
            }
            binding.gauge.set(live.rssi, live.bestRssi, trendDir)
            binding.rssiDetail.text = (if (visual) "${ProximityUtil.band(live.rssi)}\n" else "dBm · ${ProximityUtil.band(live.rssi)} $trend\n") +
                "${DeviceIntel.formatDistance(live.distanceM)} (rough) · best ${live.bestRssi} dBm" +
                (if (liveMac != mac) "\nNow heard as $liveMac" else "")
            binding.graph.setSamples(live.history)
            binding.locateButton.isEnabled = true
        } else {
            binding.rssiText.text = "--"
            binding.gauge.set(null, null, 0)
            binding.rssiDetail.text = "Not currently heard"
            binding.graph.setSamples(emptyList())
            binding.locateButton.isEnabled = false
            stopLocate()
        }

        // Action labels
        val fp = com.rfsentinel.app.usb.ProbeIntel.of(mac)?.fingerprint
        val custom = OuiWatchlist.customEntry(mac) ?: OuiWatchlist.customEntry(MacUtil.oui(mac))
            ?: fp?.let { OuiWatchlist.customEntry(com.rfsentinel.app.oui.OuiEntry.FINGERPRINT + it) }
        binding.actionWatchlist.text = if (custom != null) "Remove from watchlist" else "Add to watchlist"
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

    private fun buildSections(s: DeviceRegistry.Snapshot?): List<Pair<String, List<Pair<String, String>>>> {
        val out = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        val a = s?.advert

        // Why flagged
        if (s != null) {
            out += "Why it's flagged" to if (s.hits.isEmpty()) {
                listOf("Result" to "Not flagged - no watchlist entry or equipment signature matched.")
            } else {
                s.hits.flatMap { h ->
                    listOf(
                        "● ${h.label}" to "${h.category.title} · ${h.tier.label} (${h.confidence}%)",
                        "   Evidence" to h.evidence,
                        "   Source" to h.source
                    )
                }
            }
        }

        // Identity
        val id = mutableListOf<Pair<String, String>>()
        (s?.deviceType ?: known?.deviceType)?.let { id += "Device type" to it }
        (s?.name ?: known?.name)?.let { id += (if (a?.isWifi == true) "Network name (SSID)" else "Advertised name") to it }
        VendorDb.macVendor(mac)?.let { id += "Address registrant (IEEE)" to it }
        a?.manufacturerData?.keys?.forEach { cid ->
            id += "Bluetooth company" to (VendorDb.company(cid) ?: "Unknown") + String.format(" (0x%04X)", cid)
        }
        if (s != null) {
            id += "Address type" to s.addressType.label
            id += "Trackability" to s.addressType.trackability(known?.firstSeen, known?.lastSeen, known?.sessions ?: 0,
                com.rfsentinel.app.service.DeviceRegistry.linkedFrom(mac) != null)
            id += "Radio" to s.sources.joinToString(" + ") { if (it == Advert.Source.BLE) "Bluetooth LE" else "WiFi" }
        }
        a?.connectable?.let { id += "Connectable" to if (it) "Yes (accepts connections)" else "No (broadcast only)" }
        a?.phy?.let { id += "PHY" to it }
        a?.txPower?.let { id += "Advertised TX power" to "$it dBm" }
        s?.facts?.forEach { id += it }
        // Heard by a USB WiFi adapter / Marauder board: its WPS block, fingerprint, network requests.
        com.rfsentinel.app.usb.ProbeIntel.of(mac)?.let { r ->
            if (r.hidden) id += "Hidden network" to (s?.name?.let { "Its name, \"$it\", was revealed by a device joining or asking for it" }
                ?: "It hides its name - shown once a device joins or asks for it (USB WiFi adapter)")
            r.wps?.deviceName?.let { id += "Device name (WPS)" to it }
            r.wps?.product?.let { id += "Maker / model (WPS)" to it }
            r.fingerprint?.let { fp ->
                id += "Probe fingerprint" to fp + if (OuiWatchlist.isFingerprintWatched(fp)) " · on your watchlist" else
                    " (same for every device of this model / OS - Watchlist can watch it)"
            }
            r.skeleton?.let { sk ->
                id += "Probe shape" to "%08x".format(sk) + (com.rfsentinel.app.detect.SignatureEngine.probeShapeNote(sk)?.let { " · $it" } ?: "") +
                    if (OuiWatchlist.isFingerprintWatched(com.rfsentinel.app.usb.ProbeIntel.shapeKey(sk))) " · on your watchlist" else ""
            }
            if (r.probed.isNotEmpty()) id += "Networks it asked for" to r.probed.reversed().joinToString(", ")
        }
        if (id.isNotEmpty()) out += "Identity" to id

        // Drone Remote ID
        s?.remoteId?.let { r ->
            val rows = mutableListOf<Pair<String, String>>()
            r.uasId?.let { rows += "UAS ID" to it }
            r.idType?.let { rows += "ID type" to it }
            r.uaType?.let { rows += "Aircraft type" to it }
            r.status?.let { rows += "Status" to it }
            if (r.hasPosition) rows += "Drone position" to String.format(Locale.US, "%.6f, %.6f", r.latitude, r.longitude)
            r.altitudeGeoM?.let { rows += "Altitude (WGS84)" to String.format(Locale.US, "%.0f m", it) }
            r.heightM?.let { rows += "Height above takeoff/ground" to String.format(Locale.US, "%.0f m", it) }
            r.speedMs?.let { rows += "Ground speed" to String.format(Locale.US, "%.1f m/s (%.0f km/h)", it, it * 3.6) }
            r.verticalSpeedMs?.let { rows += "Vertical speed" to String.format(Locale.US, "%.1f m/s", it) }
            r.directionDeg?.let { rows += "Heading" to "$it°" }
            if (r.hasOperatorPosition) rows += "Operator position" to String.format(Locale.US, "%.6f, %.6f", r.operatorLatitude, r.operatorLongitude)
            r.operatorId?.let { rows += "Operator ID" to it }
            r.selfIdDescription?.let { rows += "Description" to it }
            out += "Drone Remote ID (ASTM F3411)" to rows
        }

        // Session & history
        val hist = mutableListOf<Pair<String, String>>()
        if (s != null) {
            hist += "First heard this session" to timeFmt.format(Date(s.firstSeen))
        }
        known?.let {
            hist += "First ever seen" to dateFmt.format(Date(it.firstSeen))
            hist += "Last seen (saved)" to dateFmt.format(Date(it.lastSeen))
            hist += "Scanning sessions seen in" to "${it.sessions}"
            hist += "Detect count" to "${it.detectCount} (at most one per minute)"
        } ?: run { hist += "History" to "First time this address has been heard" }
        if (pastMatches.isNotEmpty()) {
            hist += "Logged matches" to "${pastMatches.size}" + (if (pastMatches.size >= 200) "+" else "")
            pastMatches.take(5).forEach { m ->
                hist += dateFmt.format(Date(m.timestamp)) to "${m.label} · ${m.rssi} dBm" +
                    (if (m.latitude != null) String.format(Locale.US, " · %.5f, %.5f", m.latitude, m.longitude) else "")
            }
        }
        if (s != null && s.path.size >= 2) {
            val first = s.path.first()
            val moved = s.path.maxOf { DeviceRegistry.metersBetween(first.lat, first.lon, it.lat, it.lon) }
            hist += "Travelled with you" to String.format(Locale.US, "%.0f m while in range", moved)
        }
        s?.broadcast?.let { b ->
            hist += "Position (its own GPS, over V2X)" to String.format(Locale.US, "%.6f, %.6f", b.lat, b.lon)
        } ?: s?.located?.let { e ->
            hist += "Pinpointed position" to String.format(Locale.US, "%.6f, %.6f · ", e.lat, e.lon) +
                com.rfsentinel.app.detect.Locator.describe(e)
        } ?: s?.takeIf { it.best != null && !it.following && com.rfsentinel.app.util.Prefs.pinpointFlagged(this) }?.let {
            hist += "Pinpointing" to "Not yet: pass it from more than one side (around the block) and it is placed on the map " +
                "with the circle it is very likely in"
        }
        out += "History" to hist

        // Raw advertisement
        if (a != null) {
            val raw = mutableListOf<Pair<String, String>>()
            a.manufacturerData.forEach { (cid, data) ->
                raw += String.format("Manufacturer 0x%04X", cid) to Bytes.hex(data)
            }
            a.serviceUuids.forEach { u ->
                val short = Advert.shortOf(u)
                raw += "Service UUID" to (if (short != null) String.format("0x%04X", short) + (VendorDb.uuid16(short)?.let { " - $it" } ?: "") else u.toString())
            }
            a.serviceData.forEach { (u, data) ->
                val short = Advert.shortOf(u)
                val label = if (short != null) String.format("0x%04X", short) + (VendorDb.uuid16(short)?.let { " $it" } ?: "") else u.toString()
                raw += "Service data $label" to Bytes.hex(data)
            }
            AdStructure.parse(a.rawBytes).forEach { ad ->
                raw += String.format("AD 0x%02X %s", ad.type, ad.typeName) to Bytes.hex(ad.data).ifEmpty { "(empty)" }
            }
            a.wifi?.let { w ->
                raw += "Capabilities" to w.capabilities.ifEmpty { "(none)" }
                raw += "Information elements" to "${w.infoElements.size}" +
                    if (w.infoElements.isNotEmpty()) " (IDs " + w.infoElements.map { it.first }.distinct().joinToString() + ")" else ""
            }
            if (raw.isNotEmpty()) out += "Raw advertisement" to raw
        }
        return out
    }

    private fun renderSections(sections: List<Pair<String, List<Pair<String, String>>>>) {
        val container = binding.sections
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val hits = DeviceRegistry.get(mac)?.hits.orEmpty()
        for ((title, rows) in sections) {
            container.addView(TextView(this).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, (18 * dp).toInt(), 0, (4 * dp).toInt())
            })
            if (visual && title == "Why it's flagged" && hits.isNotEmpty()) {
                hits.forEach { container.addView(hitCard(it)) }
                continue
            }
            for ((k, v) in rows) {
                container.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
                    addView(TextView(context).apply {
                        text = k
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(0xFF888888.toInt())
                    })
                    addView(TextView(context).apply {
                        text = v
                        setTextIsSelectable(true)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        if (title == "Raw advertisement") typeface = Typeface.MONOSPACE
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
                    text = h.label; setTypeface(typeface, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply {
                    text = "${h.confidence}%"; setTypeface(typeface, Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(color)
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
                text = h.evidence; setTextIsSelectable(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            addView(TextView(context).apply {
                text = "Source: ${h.source}"; setTextIsSelectable(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); alpha = 0.7f
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
        val r = DeviceRegistry.get(mac)?.remoteId ?: return
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
        val heardAs = s?.best?.label ?: s?.name ?: known?.name ?: s?.deviceType ?: known?.deviceType
        ask("Name this device", DeviceNames.of(mac).orEmpty(), heardAs ?: "Name", multiLine = false,
            clear = DeviceNames.of(mac)?.let { "Use its own name" }) { text ->
            lifecycleScope.launch {
                DeviceNames.set(this@DeviceDetailActivity, mac, text, s?.name ?: known?.name, s?.vendor ?: known?.vendor, s?.deviceType)
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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
        val custom = OuiWatchlist.customEntry(mac) ?: OuiWatchlist.customEntry(MacUtil.oui(mac))
        if (custom != null) {
            OuiWatchlist.removeCustomEntry(this, custom.prefix)
            Toast.makeText(this, "Removed ${custom.prefix} from the watchlist", Toast.LENGTH_SHORT).show()
        } else {
            DeviceActions.addToWatchlist(this, mac, DeviceRegistry.get(mac)?.name ?: known?.name)
        }
    }

    private fun share() {
        val s = DeviceRegistry.get(mac)
        if (s?.advert != null && s.best == null) {
            androidx.appcompat.app.AlertDialog.Builder(this)
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

    // ---- Locate (Geiger-counter beeps) ------------------------------------------

    private fun toggleLocate() {
        if (locateJob != null) stopLocate() else startLocate()
    }

    /** The address Locate is following (changes when the device rotates its address). */
    private var followed: String? = null

    private fun startLocate() {
        followed = null
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        binding.locateButton.text = "Stop"
        Toast.makeText(this, "Walk around: beeps speed up as the signal gets stronger", Toast.LENGTH_LONG).show()
        locateJob = lifecycleScope.launch {
            while (isActive) {
                // Keeps following it when it changes its address (a tag rotating its random address).
                val liveMac = DeviceRegistry.currentAddress(mac)
                if (liveMac != followed) {
                    if (followed != null) Toast.makeText(this@DeviceDetailActivity, "It changed its address - still following it", Toast.LENGTH_SHORT).show()
                    followed = liveMac
                }
                val rssi = DeviceRegistry.get(liveMac)?.rssi ?: -100
                // Faster as the signal gets stronger (same curve as the radar beeps).
                val interval = com.rfsentinel.app.util.Beeper.tickIntervalMs(rssi)
                if (!AlertPlayer.locateTick(this@DeviceDetailActivity)) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                delay(interval)
            }
        }
    }

    private fun stopLocate() {
        locateJob?.cancel()
        locateJob = null
        tone?.release()
        tone = null
        if (::binding.isInitialized) binding.locateButton.text = "Locate"
    }
}
