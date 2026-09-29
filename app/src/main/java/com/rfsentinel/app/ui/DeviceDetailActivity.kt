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
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.DetectionEntity
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
            if (WhitelistCache.contains(mac)) DeviceActions.unwhitelist(this, mac)
            else DeviceActions.whitelist(this, mac, DeviceRegistry.get(mac)?.let { it.best?.label ?: it.name } ?: "")
        }
        binding.actionFavorite.setOnClickListener {
            val s = DeviceRegistry.get(mac)
            val fav = Favorites.contains(mac)
            lifecycleScope.launch {
                Favorites.set(this@DeviceDetailActivity, mac, !fav, s?.name ?: known?.name, s?.vendor ?: known?.vendor, s?.deviceType)
                refresh()
            }
        }
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

    override fun onPause() {
        stopLocate()
        super.onPause()
    }

    // ---- Rendering ------------------------------------------------------------

    private fun refresh() {
        val s = DeviceRegistry.get(mac)
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
        binding.titleText.text = best?.label ?: s?.name ?: known?.name ?: s?.deviceType ?: known?.deviceType ?: "Device"
        binding.subtitleText.text = mac + ((s?.vendor ?: known?.vendor ?: VendorDb.macVendor(mac))?.let { "\n$it" } ?: "")
        binding.presenceText.text = when {
            s == null -> "Not in range right now" + (known?.let { " · last heard ${dateFmt.format(Date(it.lastSeen))}" } ?: "")
            s.following -> "⚠ Has been moving with you"
            else -> "In range · heard ${ago(now - s.lastSeen)} · ${s.sightings} packets received"
        }

        // Live signal
        if (s != null) {
            binding.rssiText.text = "${s.rssi}"
            val recent = s.history.takeLast(6).dropLast(1)
            val trend = if (recent.isEmpty()) "" else {
                val avg = recent.map { it.rssi }.average()
                when {
                    s.rssi > avg + 3 -> "  ↑ getting closer"
                    s.rssi < avg - 3 -> "  ↓ moving away"
                    else -> "  → steady"
                }
            }
            binding.rssiDetail.text = "dBm · ${ProximityUtil.band(s.rssi)} $trend\n" +
                "${DeviceIntel.formatDistance(s.distanceM)} (rough) · best ${s.bestRssi} dBm"
            binding.graph.setSamples(s.history)
            binding.locateButton.isEnabled = true
        } else {
            binding.rssiText.text = "--"
            binding.rssiDetail.text = "Not currently heard"
            binding.graph.setSamples(emptyList())
            binding.locateButton.isEnabled = false
            stopLocate()
        }

        // Action labels
        val custom = OuiWatchlist.customEntry(mac) ?: OuiWatchlist.customEntry(MacUtil.oui(mac))
        binding.actionWatchlist.text = if (custom != null) "Remove from watchlist" else "Add to watchlist"
        binding.actionWhitelist.text = if (whitelisted) "Un-whitelist" else "Whitelist"
        binding.actionFavorite.text = if (Favorites.contains(mac)) "★ Favorite" else "☆ Favorite"

        // Sections: rebuilt only when their content changes (keeps scroll & selection stable).
        val content = buildSections(s)
        val key = content.joinToString("|") { it.first + it.second.joinToString { r -> r.first + r.second } }
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
            id += "Trackability" to s.addressType.trackable
            id += "Radio" to s.sources.joinToString(" + ") { if (it == Advert.Source.BLE) "Bluetooth LE" else "WiFi" }
        }
        a?.connectable?.let { id += "Connectable" to if (it) "Yes (accepts connections)" else "No (broadcast only)" }
        a?.phy?.let { id += "PHY" to it }
        a?.txPower?.let { id += "Advertised TX power" to "$it dBm" }
        s?.facts?.forEach { id += it }
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
        for ((title, rows) in sections) {
            container.addView(TextView(this).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, (18 * dp).toInt(), 0, (4 * dp).toInt())
            })
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

    private fun startLocate() {
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        binding.locateButton.text = "Stop"
        Toast.makeText(this, "Walk around: beeps speed up as the signal gets stronger", Toast.LENGTH_LONG).show()
        locateJob = lifecycleScope.launch {
            while (isActive) {
                val rssi = DeviceRegistry.get(mac)?.rssi ?: -100
                // -95 dBm -> ~1.6 s between beeps, -40 dBm -> ~0.1 s.
                val interval = (1600 - ((rssi + 95).coerceIn(0, 55) / 55.0) * 1500).toLong()
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
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
