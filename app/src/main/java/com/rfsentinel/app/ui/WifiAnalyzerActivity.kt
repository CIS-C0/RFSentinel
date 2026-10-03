package com.rfsentinel.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.rfsentinel.app.databinding.ActivityWifiAnalyzerBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.ui.WifiChartView.Ap
import com.rfsentinel.app.ui.WifiChartView.Band
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WiFi channel and spectrum analyzer for 2.4, 5 and 6 GHz, from the phone's own
 * WiFi scans (receive-only: scanning listens for access point beacons).
 * Android limits apps to about four scans per two minutes, so the chart refreshes
 * whenever any app's scan delivers new results.
 */
class WifiAnalyzerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiAnalyzerBinding
    private val wifi by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private var aps: List<Ap> = emptyList()
    private val bandChips = HashMap<Band, Chip>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = reload()
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Ap> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ap = items[position]
            val b = ItemDetectionBinding.bind(holder.itemView)
            b.labelText.text = ap.ssid
            b.macText.text = "${ap.bssid}  ·  ch ${WifiChartView.channelOf(ap.freq)}  ·  ${ap.widthMhz} MHz"
            b.metaText.text = "${ap.rssi} dBm  ·  ${ap.freq} MHz" +
                if (ap.centerFreq > 0 && ap.centerFreq != ap.freq) "  ·  centre ${ap.centerFreq} MHz" else ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiAnalyzerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "WiFi channels"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val spectrum = intent.getBooleanExtra(EXTRA_SPECTRUM, false)
        binding.modeGroup.check(if (spectrum) binding.modeSpectrum.id else binding.modeChannels.id)
        binding.chart.mode = if (spectrum) WifiChartView.Mode.SPECTRUM else WifiChartView.Mode.CHANNELS
        if (spectrum) title = "WiFi spectrum"
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val s = id == binding.modeSpectrum.id
            binding.chart.mode = if (s) WifiChartView.Mode.SPECTRUM else WifiChartView.Mode.CHANNELS
            title = if (s) "WiFi spectrum" else "WiFi channels"
        }
        for (band in Band.entries) {
            val chip = Chip(this).apply {
                text = band.label
                isCheckable = true
                id = android.view.View.generateViewId()
                setOnClickListener { selectBand(band) }
            }
            bandChips[band] = chip
            binding.bandGroup.addView(chip)
        }
        bandChips.getValue(Band.B24).isChecked = true

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { reload(); delay(5_000) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        requestScan(quiet = true)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Scan now").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { requestScan(quiet = false); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun requestScan(quiet: Boolean) {
        @Suppress("DEPRECATION") // no replacement API
        val ok = runCatching { wifi.startScan() }.getOrDefault(false)
        if (!quiet && !ok) {
            Toast.makeText(this, "Android limits WiFi scans to about 4 per 2 minutes - showing the latest results", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectBand(band: Band) {
        bandChips.forEach { (b, c) -> c.isChecked = b == band }
        binding.chart.band = band
        render()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked just below
    private fun reload() {
        if (!com.rfsentinel.app.util.Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            binding.summaryText.text = "WiFi scan results need the location permission. Start a scan once to grant it."
            return
        }
        val nowUs = SystemClock.elapsedRealtime() * 1000
        aps = runCatching { wifi.scanResults }.getOrNull().orEmpty()
            .filter { nowUs - it.timestamp <= MAX_AGE_US }
            .map { r ->
                @Suppress("DEPRECATION")
                val ssid = r.SSID?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: "(hidden)"
                Ap(ssid, r.BSSID ?: "?", r.frequency, centerOf(r), widthOf(r), r.level)
            }
        render()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged") // a list of a few dozen networks
    private fun render() {
        binding.chart.setAps(aps)
        val counts = aps.groupBy { WifiChartView.bandOf(it.freq) }
        bandChips.forEach { (b, c) -> c.text = "${b.label} ${counts[b]?.size ?: 0}" }
        val inBand = aps.filter { WifiChartView.bandOf(it.freq) == binding.chart.band }.sortedByDescending { it.rssi }
        val busiest = inBand.groupBy { WifiChartView.channelOf(it.freq) }.maxByOrNull { it.value.size }
        binding.summaryText.text = when {
            aps.isEmpty() -> "No WiFi scan results yet. Is WiFi (or WiFi scanning) on, and location allowed?"
            inBand.isEmpty() -> "Nothing on ${binding.chart.band.label} right now."
            else -> "${inBand.size} networks on ${binding.chart.band.label}" +
                (busiest?.let { " · busiest channel ${it.key} (${it.value.size})" } ?: "") +
                " · strongest ${inBand.first().rssi} dBm"
        }
        adapter.items = inBand
        adapter.notifyDataSetChanged()
    }

    private fun widthOf(r: ScanResult): Int = when (r.channelWidth) {
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && r.channelWidth == ScanResult.CHANNEL_WIDTH_320MHZ) 320 else 20
    }

    /** The middle of the occupied block (for 40 MHz+ the primary channel sits off-centre). */
    private fun centerOf(r: ScanResult): Int = if (widthOf(r) > 20 && r.centerFreq0 > 0) r.centerFreq0 else r.frequency

    companion object {
        const val EXTRA_SPECTRUM = "spectrum"
        /** Cached results older than this are left out (ScanResult.timestamp is µs since boot). */
        private const val MAX_AGE_US = 3 * 60_000_000L
    }
}
