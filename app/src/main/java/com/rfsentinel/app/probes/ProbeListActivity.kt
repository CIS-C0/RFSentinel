package com.rfsentinel.app.probes

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.data.ProbeBook
import com.rfsentinel.app.data.ProbeLog
import com.rfsentinel.app.databinding.ActivityProbeListBinding
import com.rfsentinel.app.databinding.ItemProbeBinding
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
 * first. Watch one to get an alert whenever a device asks for it (a "probe:" rule in
 * the watchlist).
 */
class ProbeListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProbeListBinding
    private val adapter = Adapter()
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
        binding.filterInput.doAfterTextChanged { refresh() }
        binding.clearButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear the list?")
                .setMessage("Removes every recorded network name. Watched names stay on the watchlist.")
                .setPositiveButton("Clear") { _, _ -> ProbeLog.clear(this); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }
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
        val all = ProbeLog.all(this)
        val shown = if (filter.isEmpty()) all else all.filter { it.ssid.contains(filter, ignoreCase = true) }
        adapter.submit(shown)
        binding.emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            all.isNotEmpty() -> "No name matches the filter."
            !Prefs.recordProbes(this) -> "Recording is off. Turn it on above, then scan with a USB WiFi adapter or an ESP32 Marauder board plugged in."
            else -> "Nothing yet. Names appear while scanning with a USB WiFi adapter or an ESP32 Marauder board plugged in."
        }
    }

    private fun meta(n: ProbeBook.Network, now: Long): String = listOf(
        if (n.devices.size == 1) "1 device" else "${n.devices.size} devices",
        if (n.encounters == 1) "seen once" else "seen ${n.encounters} times",
        "last ${ago(now - n.lastSeen)}",
        "${n.lastRssi} dBm"
    ).joinToString(" · ") + if (OuiWatchlist.isProbeWatched(n.ssid)) "\nWATCHED - alerts when a device asks for it" else ""

    private fun ago(ms: Long) = when {
        ms < 60_000 -> "now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} d ago"
    }

    /** Watch [ssid]: the user names what it is; the label is what alerts say. */
    private fun watch(ssid: String) {
        val input = EditText(this).apply { setText("Asks for \"$ssid\""); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this)
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
        OuiWatchlist.removeCustomEntry(this, OuiEntry.PROBE + ssid)
        refresh()
    }

    private fun details(n: ProbeBook.Network) {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val devices = n.devices.reversed().joinToString("\n") { mac ->
            mac + "  " + (if (MacUtil.isRandomized(mac)) "random address" else VendorDb.macVendor(mac) ?: "unknown maker")
        }
        val watched = OuiWatchlist.isProbeWatched(n.ssid)
        AlertDialog.Builder(this)
            .setTitle(n.ssid)
            .setMessage("First seen ${df.format(Date(n.firstSeen))}\nLast seen ${df.format(Date(n.lastSeen))}\n\nDevices that asked for it:\n$devices")
            .setPositiveButton(if (watched) "Stop watching" else "Watch") { _, _ -> if (watched) unwatch(n.ssid) else watch(n.ssid) }
            .setNeutralButton("Remove from list") { _, _ -> ProbeLog.remove(this, n.ssid); refresh() }
            .setNegativeButton("Close", null)
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
            holder.b.root.setOnClickListener { details(n) }
        }
    }
}
