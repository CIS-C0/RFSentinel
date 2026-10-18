package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.online.PoliceAircraft
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Units
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The aircraft around you, from the RTL-SDR's ADS-B receiver and the online feeds (when on): as a
 * radar seen from above, or as a list, nearest first. Law-enforcement ones are marked. Tap one to
 * see it on the map.
 */
class AircraftActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    /** One aircraft with where it is from you. */
    private class Row(val seen: PoliceAircraft.Seen, val distanceKm: Double?, val bearingDeg: Double?)

    private var rows: List<Row> = emptyList()
    private val demo get() = com.rfsentinel.app.BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            val p = r.seen.plane
            ItemDetectionBinding.bind(holder.itemView).apply {
                labelText.text = name(p) + if (r.seen.hit != null) "  ·  ${r.seen.hit.label}" else ""
                labelText.setTextColor(if (r.seen.hit != null) POLICE else macText.currentTextColor)
                macText.text = listOfNotNull(p.type, p.owner, p.registration?.takeIf { it != name(p) }).joinToString(" · ").ifEmpty { p.hex.uppercase() }
                metaText.text = listOfNotNull(
                    r.distanceKm?.let { Units.distance(it * 1000) + (r.bearingDeg?.let { b -> " ${compass(b)}" } ?: "") },
                    altitude(p), p.speedKt?.let { String.format(Locale.US, "%.0f kt", it) }, p.trackDeg?.let { "heading ${it.toInt()}°" }
                ).joinToString(" · ")
            }
            holder.itemView.setOnClickListener { openOnMap(p) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Aircraft"
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        VisualMode.addMenu(menu, this, VisualMode.Screen.AIRCRAFT); return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        VisualMode.handle(item, this, VisualMode.Screen.AIRCRAFT) { invalidateOptionsMenu(); refresh() } || super.onOptionsItemSelected(item)

    private val visual by lazy { Visual() }

    /** The visual style: counts, the radar, and the RTL-SDR's messages per second. */
    private inner class Visual {
        val ctx = this@AircraftActivity
        val dp = resources.displayMetrics.density
        val accent = ChipStyle.accent(ctx).first
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val root = android.widget.ScrollView(ctx).apply { addView(box); visibility = View.GONE }
        val tiles = listOf(VisualMode.tile(ctx, "0", "aircraft", accent), VisualMode.tile(ctx, "0", "law enforcement", POLICE),
            VisualMode.tile(ctx, "–", "nearest", accent))
        val radar = AircraftRadarView(ctx)
        val sources = TextView(ctx).apply { textSize = 12f; alpha = 0.8f }
        val rateCard: LinearLayout
        val rate = SparklineView(ctx)
        val rateText = TextView(ctx).apply { textSize = 12f; alpha = 0.8f }
        val history = ArrayDeque<Float>()
        var lastCount = -1L
        var lastAt = 0L

        fun title(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent); letterSpacing = 0.08f
        }

        fun card(vararg views: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = VisualMode.card(ctx)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            views.forEach { addView(it) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
        }

        init {
            box.addView(VisualMode.tileRow(ctx, tiles).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
            })
            box.addView(card(title("RADAR · NORTH UP"), radar, sources))
            rateCard = card(title("RTL-SDR · ADS-B MESSAGES PER SECOND"), rate, rateText)
            box.addView(rateCard)
        }

        fun value(tile: View, v: String) { ((tile as LinearLayout).getChildAt(0) as TextView).text = v }

        fun show(list: List<Row>) {
            value(tiles[0], "${list.size}")
            value(tiles[1], "${list.count { it.seen.hit != null }}")
            value(tiles[2], list.firstNotNullOfOrNull { it.distanceKm }?.let { Units.distance(it * 1000) } ?: "–")
            val far = list.mapNotNull { it.distanceKm }.maxOrNull() ?: 0.0
            val range = listOf(10.0, 25.0, 50.0, 100.0, 200.0).firstOrNull { far <= it } ?: 200.0
            radar.set(list.mapNotNull { r ->
                val d = r.distanceKm ?: return@mapNotNull null
                val b = r.bearingDeg ?: return@mapNotNull null
                AircraftRadarView.Blip(b, d, r.seen.plane.trackDeg, name(r.seen.plane), altitude(r.seen.plane), r.seen.hit != null)
            }, range)
            val now = System.currentTimeMillis()
            val mine = if (now - PoliceAircraft.localAt <= 30_000L) PoliceAircraft.local.size else 0
            val online = if (now - PoliceAircraft.latestAt <= 3 * 60_000L) PoliceAircraft.latest.size else 0
            sources.text = if (demo) "Demo aircraft" else "RTL-SDR: $mine · online feeds: $online" +
                if (list.any { it.distanceKm == null }) " · ${list.count { it.distanceKm == null }} not placed (no position yet)" else ""
            // The RTL-SDR's receiver: messages decoded per second over the last minute.
            val adsb = SdrRadio.status.contains("ADS-B")
            rateCard.visibility = if (adsb) View.VISIBLE else View.GONE
            if (adsb) {
                val n = SdrRadio.adsbMessages
                if (lastCount >= 0 && now - lastAt >= 900) {
                    history.addLast((n - lastCount).coerceAtLeast(0) * 1000f / (now - lastAt))
                    while (history.size > 60) history.removeFirst()
                }
                if (lastCount < 0 || now - lastAt >= 900) { lastCount = n; lastAt = now }
                rate.set(history.toFloatArray(), accent, 5f)
                rateText.text = history.lastOrNull()?.let { String.format(Locale.US, "%.0f messages/s now", it) } ?: "Counting…"
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        val now = System.currentTimeMillis()
        val me = if (demo) (DemoData.fakeLocation?.let { it.latitude to it.longitude } ?: (0.0 to 0.0))
            else ScanForegroundService.lastFix?.let { it.latitude to it.longitude }
        val seen = if (demo) demoAircraft(me!!) else PoliceAircraft.all(now)
        rows = seen.map { s ->
            val p = s.plane
            if (me == null) Row(s, null, null)
            else Row(s, DeviceRegistry.metersBetween(me.first, me.second, p.lat, p.lon) / 1000, bearing(me.first, me.second, p.lat, p.lon))
        }.sortedWith(compareByDescending<Row> { it.seen.hit != null }.thenBy { it.distanceKm ?: Double.MAX_VALUE })
        val visualOn = VisualMode.on(this, VisualMode.Screen.AIRCRAFT)
        val empty = when {
            rows.isNotEmpty() -> null
            !ScanForegroundService.isRunning && !demo -> "Not scanning.\nAircraft come from an RTL-SDR on USB (ADS-B) or the online feeds, while scanning."
            else -> "No aircraft heard yet.\nThey come from an RTL-SDR on USB set to ADS-B, or from the online feeds when that's on in Settings."
        }
        visual.root.visibility = if (visualOn && empty == null) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (!visualOn && empty == null) View.VISIBLE else View.GONE
        if (visualOn) visual.show(rows) else adapter.notifyDataSetChanged()
        binding.emptyText.text = empty.orEmpty()
        binding.emptyText.visibility = if (empty != null) View.VISIBLE else View.GONE
    }

    private fun openOnMap(p: PoliceAircraft.Plane) {
        startActivity(Intent(this, MapActivity::class.java)
            .putExtra(MapActivity.EXTRA_CENTER_LAT, p.lat)
            .putExtra(MapActivity.EXTRA_CENTER_LON, p.lon))
    }

    private fun name(p: PoliceAircraft.Plane) = p.callsign?.trim()?.ifEmpty { null } ?: p.registration ?: p.hex.uppercase()

    private fun altitude(p: PoliceAircraft.Plane): String? = p.altitudeFt?.let {
        if (Units.imperial) String.format(Locale.US, "%,d ft", it) else String.format(Locale.US, "%,d m", (it * 0.3048).toInt())
    }

    private fun compass(deg: Double) = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg + 22.5) / 45).toInt() % 8]

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Debug builds' demo: a few made-up aircraft around [me], drifting along their headings. */
    private fun demoAircraft(me: Pair<Double, Double>): List<PoliceAircraft.Seen> {
        val t = (System.currentTimeMillis() / 1000 % 600) / 3600.0
        fun plane(hex: String, call: String, km: Double, brg: Double, trk: Double, ft: Int, kt: Double, police: Boolean): PoliceAircraft.Seen {
            val moved = km + kt * 1.852 * t * cos(Math.toRadians(trk - brg))
            val lat = me.first + moved / 111.0 * cos(Math.toRadians(brg))
            val lon = me.second + moved / (111.0 * cos(Math.toRadians(me.first)).coerceAtLeast(0.1)) * sin(Math.toRadians(brg))
            val p = PoliceAircraft.Plane(hex, call, null, if (police) "Helicopter" else "Airliner", if (police) "Demo police air unit" else null,
                lat, lon, ft, kt, trk, false)
            val hit = if (police) com.rfsentinel.app.detect.Hit(com.rfsentinel.app.detect.Category.AIRCRAFT, "Police helicopter", 85,
                "Demo data", "Demo") else null
            return PoliceAircraft.Seen(p, hit)
        }
        return listOf(
            plane("demo01", "POLICE1", 6.0, 40.0, 120.0, 1500, 90.0, true),
            plane("demo02", "DEMO123", 28.0, 200.0, 20.0, 31000, 450.0, false),
            plane("demo03", "DEMO456", 15.0, 300.0, 250.0, 12000, 280.0, false),
            plane("demo04", "DEMO789", 40.0, 110.0, 300.0, 36000, 470.0, false)
        )
    }

    companion object {
        private val POLICE = 0xFFD0503C.toInt()
    }
}
