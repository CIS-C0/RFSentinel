package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import androidx.core.location.LocationListenerCompat
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
    private val gpsOn = LocationListenerCompat { }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = if (viewType == 0) TextView(parent.context).apply {
                setTypeface(typeface, Typeface.BOLD)
                textSize = 16f
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
        binding.root.addView(visual.root, 0)
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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        VisualMode.addMenu(menu, this, VisualMode.Screen.SATELLITES); return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        VisualMode.handle(item, this, VisualMode.Screen.SATELLITES) { invalidateOptionsMenu(); refresh() } || super.onOptionsItemSelected(item)

    private val demo get() = com.rfsentinel.app.BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)

    /** The visual style: the check, counts, the sky plot and a bar per satellite. */
    private val visual by lazy { Visual() }

    private inner class Visual {
        val dp = resources.displayMetrics.density
        val accent = ChipStyle.accent(this@GnssActivity).first
        val box = android.widget.LinearLayout(this@GnssActivity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val root = android.widget.ScrollView(this@GnssActivity).apply { addView(box); visibility = View.GONE }
        val verdict = TextView(this@GnssActivity).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
        val verdictSub = TextView(this@GnssActivity).apply { textSize = 12f; alpha = 0.8f }
        val tiles = android.widget.FrameLayout(this@GnssActivity)
        val sky = SkyPlotView(this@GnssActivity)
        val legend = com.google.android.material.chip.ChipGroup(this@GnssActivity).apply { chipSpacingVertical = (4 * dp).toInt() }
        val bars = SatBarsView(this@GnssActivity)
        var legendKey = ""

        fun title(t: String) = TextView(this@GnssActivity).apply {
            text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent); letterSpacing = 0.08f
        }

        fun card(vararg views: View) = android.widget.LinearLayout(this@GnssActivity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = VisualMode.card(this@GnssActivity)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            views.forEach { addView(it) }
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
        }

        init {
            box.addView(card(title("CHECK"), verdict, verdictSub))
            box.addView(tiles, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() })
            box.addView(card(title("SKY · NORTH UP"), sky, legend))
            box.addView(card(title("SIGNAL PER SATELLITE · dB-Hz"), bars,
                TextView(this@GnssActivity).apply { text = "Filled: used for your position. Outlined: heard only."; textSize = 12f; alpha = 0.7f }))
        }

        fun show(snap: GnssAnalyzer.Snapshot, verdictText: String, sub: String, good: Boolean) {
            val heard = snap.sats.filter { it.cn0 > 0f }
            verdict.text = verdictText
            verdict.setTextColor(if (good) ThemeManager.ink(this@GnssActivity, 0xFF0B7A3E.toInt()) else 0xFFE0A33A.toInt())
            verdictSub.text = sub
            tiles.removeAllViews()
            tiles.addView(VisualMode.tileRow(this@GnssActivity, listOf(
                VisualMode.tile(this@GnssActivity, "${heard.size}", "heard", accent),
                VisualMode.tile(this@GnssActivity, "${snap.sats.count { it.usedInFix }}", "used", accent),
                VisualMode.tile(this@GnssActivity, heard.maxOfOrNull { it.cn0 }?.let { "%.0f".format(it) } ?: "-", "best dB-Hz", accent))))
            sky.set(snap.sats)
            bars.set(snap.sats)
            val systems = snap.sats.map { it.system }.distinct().sortedBy { it.ordinal }
            val key = systems.joinToString()
            if (key != legendKey) {
                legendKey = key
                legend.removeAllViews()
                systems.forEach { legend.addView(VisualMode.chip(this@GnssActivity, it.label, GnssColors.of(it))) }
            }
        }
    }

    /** Debug builds' demo: a made-up sky for checking the screen indoors or on an emulator. */
    private fun demoSnapshot(): GnssAnalyzer.Snapshot {
        val t = System.currentTimeMillis() / 1000 % 360
        val sats = listOf(GnssAnalyzer.System.GPS to 9, GnssAnalyzer.System.GALILEO to 6, GnssAnalyzer.System.GLONASS to 5,
            GnssAnalyzer.System.BEIDOU to 6).flatMap { (sys, n) ->
            (1..n).map { i ->
                val el = (10 + (i * 37 + sys.ordinal * 11) % 75).toFloat()
                GnssAnalyzer.Sat(sys, i * 3 + sys.ordinal, 18f + el / 2.2f + (i % 3) * 2, el, i % 4 != 0, 1575.42f,
                    ((i * 71 + sys.ordinal * 47 + t / 30f) % 360).toFloat())
            }
        }
        return GnssAnalyzer.Snapshot(System.currentTimeMillis(), sats, 22.5)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        if (!Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            show(emptyList(), "The satellite view needs the location permission.\nStart a scan once to grant it."); return
        }
        val snap = if (demo) demoSnapshot() else GnssWatch.latest?.takeIf { System.currentTimeMillis() - GnssWatch.latestAt < 10_000L }
        if (snap == null) {
            show(emptyList(), "Listening for satellites…\nIt can take up to a minute; outdoors or near a window works best."); return
        }
        val tracked = snap.sats.filter { it.cn0 > 0f }
        if (VisualMode.on(this, VisualMode.Screen.SATELLITES)) {
            visual.show(snap, GnssWatch.analyzer.verdict,
                if (GnssWatch.hasAgc) "Receiver noise (AGC) ${snap.agcDb?.let { "%.1f dB".format(it) } ?: "-"} · jamming check on"
                else "This phone doesn't report receiver noise: jamming is only suspected on a total loss",
                GnssWatch.analyzer.verdict.startsWith("No "))
            show(emptyList(), null, visualShown = true)
            return
        }
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
    private fun show(list: List<Row>, emptyText: String?, visualShown: Boolean = false) {
        visual.root.visibility = if (visualShown) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (visualShown) View.GONE else View.VISIBLE
        rows = list
        adapter.notifyDataSetChanged()
        binding.emptyText.text = emptyText.orEmpty()
        binding.emptyText.visibility = if (emptyText != null) View.VISIBLE else View.GONE
    }
}
