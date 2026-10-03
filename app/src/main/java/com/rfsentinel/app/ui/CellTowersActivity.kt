package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    }

    private var rows: List<Row> = emptyList()

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
                is Row.Live -> bindLive(ItemDetectionBinding.bind(holder.itemView), r.cell)
                is Row.Seen -> bindSeen(ItemDetectionBinding.bind(holder.itemView), r.tower)
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
        out += Row.Header("Now · ${live.size} ${if (live.size == 1) "cell" else "cells"}")
        live.sortedWith(compareByDescending<CellAnalyzer.Cell> { it.registered }.thenByDescending { it.dbm ?: -999 })
            .forEach { out += Row.Live(it) }
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
        AlertDialog.Builder(this)
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
    }
}
