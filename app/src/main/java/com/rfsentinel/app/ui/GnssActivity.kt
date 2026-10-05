package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.detect.GnssAnalyzer
import com.rfsentinel.app.service.GnssWatch
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The satellites the phone hears, for every system (GPS, GLONASS, Galileo, BeiDou, QZSS,
 * NavIC, SBAS): signal strength, height in the sky, whether the fix uses it, and the
 * jamming / spoofing check. GPS is switched on while this screen is open.
 */
class GnssActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private sealed interface Row {
        data class Header(val text: String) : Row
        data class Line(val title: String, val sub: String, val meta: String, val highlight: Boolean = false) : Row
    }

    private var rows: List<Row> = emptyList()
    private val gpsOn = LocationListener { }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = if (viewType == 0) TextView(parent.context).apply {
                setTypeface(typeface, Typeface.BOLD)
                textSize = 15f
                setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
            } else ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = r.text
                is Row.Line -> ItemDetectionBinding.bind(holder.itemView).apply {
                    labelText.text = r.title
                    labelText.setTextColor(if (r.highlight) ThemeManager.ink(this@GnssActivity, 0xFF0B7A3E.toInt()) else macText.currentTextColor)
                    macText.text = r.sub
                    metaText.text = r.meta
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Satellites"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(1_000) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()
        if (!Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) return
        GnssWatch.start(this)
        runCatching {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsOn, Looper.getMainLooper())
        }
    }

    override fun onStop() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(gpsOn) }
        if (Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) GnssWatch.stop(this)
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        if (!Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            show(emptyList(), "The satellite view needs the location permission.\nStart a scan once to grant it."); return
        }
        val snap = GnssWatch.latest?.takeIf { System.currentTimeMillis() - GnssWatch.latestAt < 10_000L }
        if (snap == null) {
            show(emptyList(), "Listening for satellites…\nIt can take up to a minute; outdoors or near a window works best."); return
        }
        val tracked = snap.sats.filter { it.cn0 > 0f }
        val out = ArrayList<Row>()
        out += Row.Header("Check")
        out += Row.Line(GnssWatch.analyzer.verdict,
            "${tracked.size} satellites heard, ${snap.sats.count { it.usedInFix }} used for the position",
            if (GnssWatch.hasAgc) "Receiver noise (AGC) ${snap.agcDb?.let { "%.1f dB".format(it) } ?: "-"} · jamming check on"
            else "This phone doesn't report receiver noise: jamming is only suspected on a total loss",
            highlight = GnssWatch.analyzer.verdict.startsWith("No "))
        out += Row.Header("Systems")
        snap.sats.groupBy { it.system }.toSortedMap(compareBy { it.ordinal }).forEach { (sys, list) ->
            val heard = list.filter { it.cn0 > 0f }
            out += Row.Line(sys.label, "${heard.size} heard · ${list.count { it.usedInFix }} used",
                if (heard.isEmpty()) "no signal" else "strongest %.0f dB-Hz · average %.0f dB-Hz".format(heard.maxOf { it.cn0 }, heard.map { it.cn0 }.average()))
        }
        out += Row.Header("Satellites")
        snap.sats.sortedWith(compareBy<GnssAnalyzer.Sat> { it.system.ordinal }.thenByDescending { it.cn0 }).forEach { s ->
            out += Row.Line("${s.system.label} ${s.svid}${band(s)}" + if (s.usedInFix) "  ·  used" else "",
                if (s.cn0 > 0f) "${bars(s.cn0)}  %.0f dB-Hz".format(s.cn0) else "not heard",
                "%.0f° above the horizon".format(s.elevation), highlight = s.usedInFix)
        }
        show(out, null)
    }

    private fun band(s: GnssAnalyzer.Sat): String {
        val f = s.carrierMhz ?: return ""
        return when {
            f > 1500f -> when (s.system) { GnssAnalyzer.System.GALILEO -> " E1"; GnssAnalyzer.System.BEIDOU -> " B1"; GnssAnalyzer.System.GLONASS -> " G1"; else -> " L1" }
            f > 1250f -> " (L6 / E6 / B3)"
            f > 1200f -> " (L2 / B2)"
            else -> when (s.system) { GnssAnalyzer.System.GALILEO -> " E5a"; GnssAnalyzer.System.BEIDOU -> " B2a"; else -> " L5" }
        }
    }

    private fun bars(cn0: Float): String { val n = ((cn0 - 15) / 7).toInt().coerceIn(0, 5); return "▮".repeat(n) + "▯".repeat(5 - n) }

    @SuppressLint("NotifyDataSetChanged")
    private fun show(list: List<Row>, emptyText: String?) {
        rows = list
        adapter.notifyDataSetChanged()
        binding.emptyText.text = emptyText.orEmpty()
        binding.emptyText.visibility = if (emptyText != null) View.VISIBLE else View.GONE
    }
}
