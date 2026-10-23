package com.rfsentinel.app.probes

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.ProbeBook
import com.rfsentinel.app.data.ProbeLog
import com.rfsentinel.app.databinding.ActivityProbeListBinding
import com.rfsentinel.app.databinding.ItemProbeBinding
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.VendorDb
import com.rfsentinel.app.oui.OuiEntry
import com.rfsentinel.app.oui.OuiWatchlist
import com.rfsentinel.app.util.MacUtil
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import java.text.DateFormat
import java.util.Date

/**
 * Requested networks: the WiFi network names devices around you asked for, newest
 * first, by network or by the device that asked. Watch one to get an alert whenever a
 * device asks for it (a "probe:" rule in the watchlist); find where a name is on WiGLE.
 */
class ProbeListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProbeListBinding
    private val adapter = Adapter()
    private val deviceAdapter = DeviceAdapter()
    private var byDevice = false
    /** Watchlist matches from the last refresh: by network name (lowercase), and by a group's first address. */
    private var networkHits: Map<String, List<Hit>> = emptyMap()
    private var groupHits: Map<String, List<Hit>> = emptyMap()
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProbeListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Requested networks"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recordSwitch.isChecked = Prefs.recordProbes(this)
        binding.recordSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setRecordProbes(this, on)
            if (on) Toast.makeText(this, "Recording while scanning with a USB WiFi adapter or an ESP32 Marauder board", Toast.LENGTH_LONG).show()
            refresh()
        }
        binding.viewToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            byDevice = id == binding.byDeviceButton.id
            binding.recyclerView.adapter = if (byDevice) deviceAdapter else adapter
            binding.filterInput.hint = if (byDevice) "Filter by name, address or model" else "Filter by name"
            binding.hideRandomChip.visibility = if (byDevice) View.VISIBLE else View.GONE
            refresh()
        }
        binding.filterInput.doAfterTextChanged { refresh() }
        binding.hideRandomChip.setOnCheckedChangeListener { _, _ -> refresh() }
        binding.clearButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear the list?")
                .setMessage("Removes every recorded network name and device. Watched names stay on the watchlist.")
                .setPositiveButton("Clear") { _, _ -> ProbeLog.clear(this); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SEARCH, 0, "Search WiGLE")
        menu.add(0, MENU_ACCOUNT, 1, "WiGLE account")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SEARCH -> { WigleUi.ask(this); true }
        MENU_ACCOUNT -> { WigleUi.account(this); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); main.post(tick) }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        ProbeLog.flush(this)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refresh() {
        val filter = binding.filterInput.text.toString().trim()
        val count: Int
        val any: Boolean
        var allRandom = false
        // Watchlist matches, by the devices that asked and by the names asked for; matches come first.
        val known = ProbeLog.devices(this).associateBy { it.mac }
        val byMac = HashMap<String, List<Hit>>()
        fun macHits(mac: String) = byMac.getOrPut(mac) { deviceHits(mac, known[mac]) }
        if (byDevice) {
            val all = ProbeLog.groups(this)
            groupHits = all.associate { g ->
                g.devices.first().mac to (g.devices.flatMap { macHits(it.mac) } + g.ssids.flatMap { nameHits(it) }).distinct()
            }
            // Hide random addresses: keeps only devices with a fixed (maker) address.
            val fixed = if (binding.hideRandomChip.isChecked) all.filter { g -> g.devices.any { !MacUtil.isRandomized(it.mac) } } else all
            val shown = (if (filter.isEmpty()) fixed else fixed.filter { g -> matches(g, filter) })
                .sortedByDescending { g -> alerting(groupHits[g.devices.first().mac]).isNotEmpty() }
            deviceAdapter.submit(shown)
            count = shown.size; any = all.isNotEmpty()
            allRandom = any && fixed.isEmpty()
        } else {
            val all = ProbeLog.all(this)
            networkHits = all.associate { n -> n.ssid.lowercase() to (nameHits(n.ssid) + n.devices.flatMap { macHits(it) }).distinct() }
            val shown = (if (filter.isEmpty()) all else all.filter { it.ssid.contains(filter, ignoreCase = true) })
                .sortedByDescending { n -> alerting(networkHits[n.ssid.lowercase()]).isNotEmpty() }
            adapter.submit(shown)
            count = shown.size; any = all.isNotEmpty()
        }
        binding.emptyText.visibility = if (count == 0) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            allRandom -> "Every device here uses a random address."
            any -> "Nothing matches the filter."
            !Prefs.recordProbes(this) -> "Recording is off. Turn it on above, then scan with a USB WiFi adapter or an ESP32 Marauder board plugged in."
            else -> "Nothing yet. Names appear while scanning with a USB WiFi adapter or an ESP32 Marauder board plugged in."
        }
    }

    private fun matches(g: ProbeBook.Group, filter: String): Boolean = g.devices.any { d ->
        d.mac.contains(filter, ignoreCase = true) || d.wpsName?.contains(filter, ignoreCase = true) == true ||
            d.wpsProduct?.contains(filter, ignoreCase = true) == true || maker(d.mac).contains(filter, ignoreCase = true)
    } || g.ssids.any { it.contains(filter, ignoreCase = true) }

    /** What on the watchlist a device matches: its address or maker, its WPS name or model, its probe fingerprint. */
    private fun deviceHits(mac: String, d: ProbeBook.Device?): List<Hit> =
        OuiWatchlist.hits(mac, d?.wpsName, listOfNotNull(VendorDb.macVendor(mac), d?.wpsProduct)) +
            OuiWatchlist.fingerprintHits(d?.fingerprint)

    /** What on the watchlist a requested name matches: a watched name, or a name rule. */
    private fun nameHits(ssid: String): List<Hit> = OuiWatchlist.probeHits(listOf(ssid)) + OuiWatchlist.hits("", ssid, emptyList())

    /** The hits whose kind is turned on: those sort first and are marked; the others only show in the details. */
    private fun alerting(hits: List<Hit>?): List<Hit> = hits.orEmpty().filter { Prefs.categoryEnabled(this, it.category) }

    /** A matched row's background: its kind's colour, as lightly as the main device list tints a flagged row. */
    private fun tint(hits: List<Hit>): Int {
        val best = hits.maxByOrNull { it.confidence } ?: return android.graphics.Color.TRANSPARENT
        return (com.rfsentinel.app.ui.ThemeManager.ink(this, best.category.colorArgb) and 0x00FFFFFF) or 0x26000000
    }

    /** A row's watchlist line: the first match, and how many more. */
    private fun watchlistLine(hits: List<Hit>): String? {
        val first = hits.firstOrNull() ?: return null
        return "WATCHLIST - ${first.label}" + if (hits.size > 1) " +${hits.size - 1} more" else ""
    }

    /** The details' watchlist part: every match with why. */
    private fun watchlistText(hits: List<Hit>?): String = if (hits.isNullOrEmpty()) "" else
        "\n\nOn the watchlist:\n" + hits.joinToString("\n") { h ->
            "• ${h.label}: ${h.evidence}" + if (Prefs.categoryEnabled(this, h.category)) "" else " · kind turned off, no alert"
        }

    private fun meta(n: ProbeBook.Network, now: Long): String {
        val watched = OuiWatchlist.isProbeWatched(n.ssid)
        // A watched name already says so; the line is for the other matches.
        val others = alerting(networkHits[n.ssid.lowercase()]) - OuiWatchlist.probeHits(listOf(n.ssid)).toSet()
        return listOf(
            if (n.devices.size == 1) "1 device" else "${n.devices.size} devices",
            if (n.encounters == 1) "seen once" else "seen ${n.encounters} times",
            "last ${ago(now - n.lastSeen)}",
            "${n.lastRssi} dBm"
        ).joinToString(" · ") +
            (if (watched) "\nWATCHED - alerts when a device asks for it" else "") +
            (watchlistLine(others)?.let { "\n$it" } ?: "")
    }

    private fun ago(ms: Long) = when {
        ms < 60_000 -> "now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} d ago"
    }

    private fun maker(mac: String) = if (MacUtil.isRandomized(mac)) "random address" else VendorDb.macVendor(mac) ?: "unknown maker"

    /** A group's title: its WPS name or model when it gave one, else its maker. */
    private fun title(g: ProbeBook.Group): String {
        val named = g.devices.firstNotNullOfOrNull { it.wpsName } ?: g.devices.firstNotNullOfOrNull { it.wpsProduct }
        return when {
            named != null -> named
            g.likelySame -> "Likely the same device"
            else -> g.devices.first().mac
        }
    }

    private fun groupMeta(g: ProbeBook.Group, now: Long): String {
        val d = g.devices.maxBy { it.lastSeen }
        val lines = mutableListOf<String>()
        if (g.likelySame) lines += "Likely the same device · ${g.devices.size} random addresses"
        else lines += listOfNotNull(if (title(g) != d.mac) d.mac else null, maker(d.mac)).joinToString(" · ")
        g.devices.firstNotNullOfOrNull { it.wpsProduct }?.takeIf { it != title(g) }?.let { lines += it }
        lines += listOf(
            if (g.ssids.size == 1) "asked for 1 network" else "asked for ${g.ssids.size} networks",
            "last ${ago(now - g.lastSeen)}",
            "${d.lastRssi} dBm"
        ).joinToString(" · ")
        lines += g.ssids.take(4).joinToString(", ") { "\"$it\"" } + if (g.ssids.size > 4) " …" else ""
        watchlistLine(alerting(groupHits[g.devices.first().mac]))?.let { lines += it }
        return lines.joinToString("\n")
    }

    /** Watch [ssid]: the user names what it is; the label is what alerts say. */
    private fun watch(ssid: String) {
        val input = EditText(this).apply { setText("Asks for \"$ssid\""); setSelectAllOnFocus(true) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Alert when a device asks for \"$ssid\"")
            .setMessage("What is it? This is what the alert says.")
            .setView(input)
            .setPositiveButton("Watch") { _, _ ->
                val label = input.text.toString().trim().ifEmpty { "Asks for \"$ssid\"" }
                OuiWatchlist.addCustomEntry(this, OuiEntry(OuiEntry.PROBE + ssid, label, "user-added", "custom"))
                Toast.makeText(this, "Watching \"$ssid\"", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unwatch(ssid: String) {
        val entry = OuiWatchlist.customEntry(OuiEntry.PROBE + ssid)
        OuiWatchlist.removeCustomEntry(this, OuiEntry.PROBE + ssid)
        refresh()
        if (entry != null) com.rfsentinel.app.util.Undo.show(this, "No longer watching \"$ssid\"") {
            OuiWatchlist.addCustomEntry(this, entry)
            refresh()
        }
    }

    private fun details(n: ProbeBook.Network) {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val devices = n.devices.reversed().joinToString("\n") { mac -> "$mac  ${maker(mac)}" }
        val watched = OuiWatchlist.isProbeWatched(n.ssid)
        val dp = resources.displayMetrics.density
        val spots = n.spots
        val where = if (spots.isEmpty()) "Where: not known (heard without a GPS fix, or before places were kept)"
            else if (spots.size == 1) "Asked for at 1 place" else "Asked for at ${spots.size} places"
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt(); setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(TextView(this@ProbeListActivity).apply {
                text = "First seen ${df.format(Date(n.firstSeen))}\nLast seen ${df.format(Date(n.lastSeen))}\n$where\n\n" +
                    "Devices that asked for it:\n$devices" + watchlistText(networkHits[n.ssid.lowercase()])
                setTextIsSelectable(true)
            })
            if (spots.isNotEmpty()) addView(com.google.android.material.button.MaterialButton(this@ProbeListActivity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Show on map"
                setOnClickListener { dialog.dismiss(); showOnMap(n) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8 * dp).toInt() })
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(n.ssid)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(if (watched) "Stop watching" else "Watch") { _, _ -> if (watched) unwatch(n.ssid) else watch(n.ssid) }
            .setNeutralButton("Remove from list") { _, _ -> ProbeLog.remove(this, n.ssid); refresh() }
            .setNegativeButton("Find on WiGLE") { _, _ -> WigleUi.search(this, n.ssid) }
            .show()
    }

    /** The map with the places [n] was asked for, on the latest one. */
    private fun showOnMap(n: ProbeBook.Network) {
        val last = n.spots.lastOrNull() ?: return
        ProbeLog.showOnMap(n.ssid)
        startActivity(android.content.Intent(this, com.rfsentinel.app.ui.MapActivity::class.java)
            .putExtra(com.rfsentinel.app.ui.MapActivity.EXTRA_CENTER_LAT, last.lat)
            .putExtra(com.rfsentinel.app.ui.MapActivity.EXTRA_CENTER_LON, last.lon))
    }

    /** A device (or a group of addresses likely to be one): what it is, and every name it asked for as chips. */
    private fun deviceDetails(g: ProbeBook.Group) {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val dp = resources.displayMetrics.density
        val text = buildString {
            if (g.likelySame) append("These random addresses have the same rare probe fingerprint and asked for a network " +
                "name in common, so they are likely one device changing its address.\n\n")
            g.devices.forEach { d ->
                append(d.mac).append("  ").append(maker(d.mac)).append('\n')
                d.wpsName?.let { append("  Name: $it\n") }
                d.wpsProduct?.let { append("  Model: $it\n") }
                d.fingerprint?.let { fp ->
                    val n = ProbeLog.sharing(this@ProbeListActivity, fp)
                    append("  Probe fingerprint: $fp" + if (n > 1) " (shared by $n addresses)\n" else "\n")
                }
                append("  First seen ${df.format(Date(d.firstSeen))}\n  Last seen ${df.format(Date(d.lastSeen))}\n")
                append("  " + (if (d.encounters == 1) "Seen once" else "Seen ${d.encounters} times") + " · ${d.lastRssi} dBm\n")
            }
            watchlistText(groupHits[g.devices.first().mac]).takeIf { it.isNotEmpty() }?.let { append(it.trimStart('\n')).append("\n") }
            append("\nNetworks it asked for (tap one):")
        }
        val chips = ChipGroup(this).apply {
            g.ssids.forEach { ssid ->
                addView(Chip(this@ProbeListActivity).apply {
                    // ★ = watched: alerts when a device asks for it.
                    this.text = if (OuiWatchlist.isProbeWatched(ssid)) "★ $ssid" else ssid
                    setOnClickListener { ssidActions(ssid) }
                })
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt(); setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(TextView(this@ProbeListActivity).apply { this.text = text; setTextIsSelectable(true) })
            addView(chips)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title(g))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun ssidActions(ssid: String) {
        val network = ProbeLog.all(this).firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }
        val items = listOfNotNull(
            network?.let { "Show this network" to { details(it) } },
            "Find on WiGLE" to { WigleUi.search(this, ssid) }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(ssid)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<ProbeBook.Network> = emptyList()

        inner class VH(val b: ItemProbeBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<ProbeBook.Network>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemProbeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            val watched = OuiWatchlist.isProbeWatched(n.ssid)
            holder.b.ssidText.text = n.ssid
            holder.b.metaText.text = meta(n, System.currentTimeMillis())
            holder.b.watchButton.text = if (watched) "Watching" else "Watch"
            holder.b.watchButton.setOnClickListener { if (watched) unwatch(n.ssid) else watch(n.ssid) }
            holder.b.findButton.setOnClickListener { WigleUi.search(this@ProbeListActivity, n.ssid) }
            holder.b.root.setBackgroundColor(tint(alerting(networkHits[n.ssid.lowercase()])))
            holder.b.root.setOnClickListener { details(n) }
        }
    }

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
        private var items: List<ProbeBook.Group> = emptyList()

        inner class VH(val b: ItemProbeBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<ProbeBook.Group>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemProbeBinding.inflate(LayoutInflater.from(parent.context), parent, false).also {
                it.watchButton.visibility = View.GONE
                it.findButton.visibility = View.GONE
            })

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val g = items[position]
            holder.b.ssidText.text = title(g)
            holder.b.metaText.text = groupMeta(g, System.currentTimeMillis())
            holder.b.root.setBackgroundColor(tint(alerting(groupHits[g.devices.first().mac])))
            holder.b.root.setOnClickListener { deviceDetails(g) }
        }
    }

    private companion object {
        const val MENU_SEARCH = 1
        const val MENU_ACCOUNT = 2
    }
}
