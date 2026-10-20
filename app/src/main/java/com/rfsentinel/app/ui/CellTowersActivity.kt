package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.detect.CellAnalyzer
import com.rfsentinel.app.service.CellMonitor
import com.rfsentinel.app.service.CellTowerStore
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Permissions
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The cell towers around you (serving cell and neighbours, live) and every tower
 * seen while scanning, with radio type, network, IDs, channel and signal.
 * Read passively from what the modem already reports; nothing is transmitted.
 */
class CellTowersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val fmt get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private var reader: CellMonitor? = null

    private sealed interface Row {
        data class Header(val text: String) : Row
        data class Live(val cell: CellAnalyzer.Cell) : Row
        data class Seen(val tower: CellTowerStore.Tower) : Row
        /** Visual style: the serving cell as a card with its signal over the last minutes. */
        data class Serving(val cell: CellAnalyzer.Cell) : Row
    }

    private val visualOn get() = VisualMode.on(this, VisualMode.Screen.CELLS)

    /** The serving cell's signal at each refresh (time, dBm), and when the phone last moved to another tower. */
    private val servingHistory = ArrayDeque<Pair<Long, Int>>()
    private var servingKey: String? = null
    private var servingChangedAt = 0L

    private var rows: List<Row> = emptyList()

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Serving -> 3
            else -> if (visualOn) 2 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = when (viewType) {
                0 -> TextView(parent.context).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 16f
                    setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
                }
                3 -> ServingCard()
                // Visual rows: signal bars beside the usual three lines.
                2 -> android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(SignalBarsView(parent.context),
                        android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (4 * resources.displayMetrics.density).toInt() })
                    addView(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), this, false).root,
                        android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
                else -> ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val bars = (v as? android.widget.LinearLayout)?.getChildAt(0) as? SignalBarsView
            val item = if (bars != null) (v as android.widget.LinearLayout).getChildAt(1) else v
            when (val r = rows[position]) {
                is Row.Header -> (v as TextView).text = r.text
                is Row.Serving -> (v as ServingCard).bind(r.cell)
                is Row.Live -> { bindLive(ItemDetectionBinding.bind(item), r.cell); bars?.set(r.cell.dbm?.let { it + CELL_OFFSET }) }
                is Row.Seen -> { bindSeen(ItemDetectionBinding.bind(item), r.tower); bars?.set(r.tower.bestDbm?.let { it + CELL_OFFSET }) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Cell towers"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    override fun onDestroy() {
        reader?.release()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        VisualMode.addMenu(menu, this, VisualMode.Screen.CELLS); return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        VisualMode.handle(item, this, VisualMode.Screen.CELLS) {
            invalidateOptionsMenu(); lifecycleScope.launch { refresh() }
        } || super.onOptionsItemSelected(item)

    /** The serving cell, big: its radio and network, signal now with bars, and a line of its signal over the last 5 minutes. */
    private inner class ServingCard : android.widget.LinearLayout(this@CellTowersActivity) {
        private val dp = resources.displayMetrics.density
        private val accent = ChipStyle.accent(context).first
        private val ratChip = TextView(context).apply { textSize = 12f; setTypeface(typeface, Typeface.BOLD) }
        private val title = TextView(context).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
        private val dbm = TextView(context).apply { textSize = 34f; setTypeface(typeface, Typeface.BOLD) }
        private val unit = TextView(context).apply { textSize = 14f; alpha = 0.75f; text = " dBm" }
        private val bars = SignalBarsView(context)
        private val spark = SparklineView(context)
        private val sparkLabel = TextView(context).apply { textSize = 12f; alpha = 0.7f }
        private val idText = TextView(context).apply { textSize = 12f; typeface = Typeface.MONOSPACE; alpha = 0.85f }

        init {
            orientation = VERTICAL
            background = VisualMode.card(context)
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
            addView(android.widget.LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "SERVING CELL"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent); letterSpacing = 0.08f
                }, LayoutParams(0, -2, 1f))
                addView(ratChip)
            })
            addView(title, LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })
            addView(android.widget.LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = android.view.Gravity.BOTTOM
                addView(dbm); addView(unit, LayoutParams(0, -2, 1f).apply { bottomMargin = (7 * dp).toInt() })
                addView(bars, LayoutParams((40 * dp).toInt(), (30 * dp).toInt()).apply { bottomMargin = (8 * dp).toInt() })
            })
            addView(spark, LayoutParams(-1, (44 * dp).toInt()).apply { topMargin = (4 * dp).toInt() })
            addView(sparkLabel)
            addView(idText, LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        }

        fun bind(c: CellAnalyzer.Cell) {
            val level = VisualMode.dbmLevel(c.dbm?.let { it + CELL_OFFSET })
            val color = VisualMode.levelColor(level)
            ratChip.text = c.rat.label
            VisualMode.chip(ratChip, accent)
            title.text = network(c.mcc, c.mnc, c.operator).ifEmpty { "Unknown network" }
            dbm.text = c.dbm?.toString() ?: "--"
            dbm.setTextColor(color)
            bars.set(c.dbm?.let { it + CELL_OFFSET }, color)
            val now = System.currentTimeMillis()
            // One slot per refresh over the last 5 minutes, NaN where none.
            val slots = (HISTORY_MS / REFRESH_MS).toInt()
            val values = FloatArray(slots) { Float.NaN }
            servingHistory.filter { now - it.first <= HISTORY_MS }.forEach { (t, v) ->
                values[(slots - 1 - ((now - t) / REFRESH_MS).toInt()).coerceIn(0, slots - 1)] = v.toFloat()
            }
            spark.set(values, color, 6f)
            sparkLabel.text = "Last 5 minutes" + if (servingChangedAt > 0 && now - servingChangedAt < HISTORY_MS)
                " · moved to this tower at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(servingChangedAt))}" else ""
            idText.text = ids(c.rat, c.area, c.cellId, c.pci, c.channel)
        }
    }

    private suspend fun refresh() {
        if (!Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            show(emptyList(), "Cell information needs the location permission.\nStart a scan once to grant it.")
            return
        }
        // While scanning, the scanner's own snapshot (every 15 s); otherwise read here.
        val fresh = System.currentTimeMillis() - CellTowerStore.currentAt < 30_000L
        val live = if (ScanForegroundService.isRunning && fresh) CellTowerStore.current
            else (reader ?: CellMonitor(this).also { reader = it }).read()
        val seen = CellTowerStore.all(this).sortedByDescending { it.lastSeen }

        val out = ArrayList<Row>()
        val sorted = live.sortedWith(compareByDescending<CellAnalyzer.Cell> { it.registered }.thenByDescending { it.dbm ?: -999 })
        val serving = sorted.firstOrNull { it.registered }
        val now = System.currentTimeMillis()
        if (serving != null) {
            if (servingKey != null && servingKey != serving.key) servingChangedAt = now
            servingKey = serving.key
            serving.dbm?.let { servingHistory.addLast(now to it) }
            while (servingHistory.isNotEmpty() && now - servingHistory.first().first > HISTORY_MS) servingHistory.removeFirst()
        }
        if (visualOn && serving != null) {
            out += Row.Serving(serving)
            if (sorted.size > 1) out += Row.Header("Neighbours · ${sorted.size - 1}")
            sorted.filter { it !== serving }.forEach { out += Row.Live(it) }
        } else {
            out += Row.Header("Now · ${live.size} ${if (live.size == 1) "cell" else "cells"}")
            sorted.forEach { out += Row.Live(it) }
        }
        out += Row.Header("Seen while scanning · ${seen.size}")
        seen.forEach { out += Row.Seen(it) }
        val empty = if (live.isEmpty() && seen.isEmpty())
            "No cell information.\nIs the SIM active and airplane mode off? While scanning, towers are recorded " +
                "when the Fake cell tower category is on (Settings)."
            else null
        show(out, empty)
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged") // a few dozen rows, rebuilt every 5 s
    private fun show(list: List<Row>, emptyText: String?) {
        rows = list
        adapter.notifyDataSetChanged()
        binding.emptyText.text = emptyText.orEmpty()
        binding.emptyText.visibility = if (emptyText != null) View.VISIBLE else View.GONE
    }

    private fun network(mcc: String?, mnc: String?, operator: String?) =
        listOfNotNull(operator, if (mcc != null || mnc != null) "${mcc ?: "?"}-${mnc ?: "?"}" else null).joinToString(" · ")

    private fun ids(rat: CellAnalyzer.Rat?, area: Int?, cellId: Long?, pci: Int?, channel: Int?): String {
        val areaName = if (rat == CellAnalyzer.Rat.LTE || rat == CellAnalyzer.Rat.NR) "TAC" else "LAC"
        val chName = when (rat) {
            CellAnalyzer.Rat.LTE -> "EARFCN"; CellAnalyzer.Rat.NR -> "NR-ARFCN"
            CellAnalyzer.Rat.WCDMA, CellAnalyzer.Rat.TDSCDMA -> "UARFCN"; else -> "ARFCN"
        }
        val pciName = when (rat) {
            CellAnalyzer.Rat.GSM -> "BSIC"; CellAnalyzer.Rat.WCDMA -> "PSC"; CellAnalyzer.Rat.TDSCDMA -> "CPID"; else -> "PCI"
        }
        return listOfNotNull(
            area?.let { "$areaName $it" }, cellId?.let { "Cell $it" },
            pci?.let { "$pciName $it" }, channel?.let { "$chName $it" }
        ).joinToString(" · ").ifEmpty { "No IDs reported" }
    }

    private fun bindLive(b: ItemDetectionBinding, c: CellAnalyzer.Cell) {
        b.labelText.text = c.rat.label + (if (c.registered) "  ·  serving" else "  ·  neighbour")
        b.labelText.setTextColor(if (c.registered) ThemeManager.ink(this, 0xFF0B7A3E.toInt()) else b.macText.currentTextColor)
        b.macText.text = ids(c.rat, c.area, c.cellId, c.pci, c.channel)
        b.metaText.text = listOfNotNull(network(c.mcc, c.mnc, c.operator).ifEmpty { null }, c.dbm?.let { "$it dBm" })
            .joinToString(" · ")
        b.root.setOnClickListener(null)
    }

    private fun bindSeen(b: ItemDetectionBinding, t: CellTowerStore.Tower) {
        val rat = runCatching { CellAnalyzer.Rat.valueOf(t.rat) }.getOrNull()
        b.labelText.text = t.ratLabel + (if (t.servedYou) "  ·  served you" else "")
        b.labelText.setTextColor(b.macText.currentTextColor)
        b.macText.text = ids(rat, t.area, t.cellId, t.pci, t.channel)
        b.metaText.text = listOfNotNull(
            network(t.mcc, t.mnc, t.operator).ifEmpty { null },
            t.bestDbm?.let { "best $it dBm" },
            "seen ${t.timesSeen}×, last ${fmt.format(Date(t.lastSeen))}",
            if (t.bestLat != null) "on map" else null
        ).joinToString(" · ")
        b.root.setOnClickListener { details(t) }
    }

    private fun details(t: CellTowerStore.Tower) {
        val rat = runCatching { CellAnalyzer.Rat.valueOf(t.rat) }.getOrNull()
        val text = buildString {
            append(network(t.mcc, t.mnc, t.operator).ifEmpty { "Unknown network" }).append('\n')
            append(ids(rat, t.area, t.cellId, t.pci, t.channel)).append("\n\n")
            append("First seen ${fmt.format(Date(t.firstSeen))}\nLast seen ${fmt.format(Date(t.lastSeen))}\n")
            append("Seen ${t.timesSeen} times").append(if (t.servedYou) ", served your phone" else ", as a neighbour only").append('\n')
            t.bestDbm?.let { append("Strongest signal: $it dBm\n") }
            append("\nThe map position is where your phone was when this tower's signal was strongest - ")
            append("an estimate, usually within a few hundred metres to a few km of the real tower.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(t.ratLabel)
            .setMessage(text)
            .apply {
                if (t.bestLat != null) setPositiveButton("Show on map") { _, _ ->
                    startActivity(Intent(this@CellTowersActivity, MapActivity::class.java)
                        .putExtra(MapActivity.EXTRA_CENTER_LAT, t.bestLat!!)
                        .putExtra(MapActivity.EXTRA_CENTER_LON, t.bestLon!!)
                        .putExtra(MapActivity.EXTRA_SHOW_TOWERS, true))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    companion object {
        private const val REFRESH_MS = 5_000L
        private const val HISTORY_MS = 5 * 60_000L
        /** Cell signals run about 10 dB lower than WiFi and Bluetooth for the same bars. */
        private const val CELL_OFFSET = 10
    }
}
