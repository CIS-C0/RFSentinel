package com.rfsentinel.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.service.TripRecorder
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Recorded scan traces and saved snapshots (the device list kept as it was, [com.rfsentinel.app.data.Snapshots]),
 * one section each. Tap a trace to view it on the map, a snapshot to see its list; long-press to export, rename or delete.
 */
class TripsActivity : AppCompatActivity() {

    companion object {
        /** Opens on this section: [TripEntity.KIND_TRACE] or [TripEntity.KIND_SNAPSHOT]. */
        const val EXTRA_SECTION = "section"
    }

    private lateinit var binding: ActivityLogBinding
    private val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private var section = TripEntity.KIND_TRACE
    private var all: List<TripEntity> = emptyList()

    private fun open(t: TripEntity) {
        startActivity(
            if (t.snapshot) Intent(this, SnapshotActivity::class.java).putExtra(SnapshotActivity.EXTRA_TRIP_ID, t.id)
            else Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, t.id)
        )
    }

    private val adapter = object : ListAdapter<TripEntity, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<TripEntity>() {
            override fun areItemsTheSame(a: TripEntity, b: TripEntity) = a.id == b.id
            override fun areContentsTheSame(a: TripEntity, b: TripEntity) = a == b
        }
    ) {
        override fun getItemViewType(position: Int) = if (VisualMode.on(this@TripsActivity, VisualMode.Screen.TRIPS)) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                if (viewType == 1) TripCard() else ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val t = getItem(position)
            (holder.itemView as? TripCard)?.let { card ->
                card.bind(t)
                holder.itemView.setOnClickListener { open(t) }
                holder.itemView.setOnLongClickListener { actions(t); true }
                return
            }
            val b = ItemDetectionBinding.bind(holder.itemView)
            val live = t.id == TripRecorder.activeTripId
            b.labelText.text = (if (live) "● " else "") + t.name
            b.labelText.setTextColor(if (live) 0xFFB3261E.toInt() else if (t.flaggedCount > 0) 0xFFC8431A.toInt() else b.macText.currentTextColor)
            if (t.snapshot) {
                b.macText.text = fmt.format(Date(t.startTime))
                b.metaText.text = "${devicesLabel(t.deviceCount)} · ${t.flaggedCount} flagged"
                holder.itemView.setOnClickListener { open(t) }
                holder.itemView.setOnLongClickListener { actions(t); true }
                return
            }
            val mins = ((t.endTime ?: System.currentTimeMillis()) - t.startTime) / 60000
            b.macText.text = fmt.format(Date(t.startTime)) +
                String.format(Locale.US, "  ·  %d:%02d  ·  %s", mins / 60, mins % 60, com.rfsentinel.app.util.Units.trip(t.distanceM))
            b.metaText.text = (if (live) "Recording now · " else "") +
                "${devicesLabel(t.deviceCount)} · ${t.flaggedCount} flagged · ${t.pointCount} GPS points"
            holder.itemView.setOnClickListener { open(t) }
            holder.itemView.setOnLongClickListener { actions(t); true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        section = savedInstanceState?.getString(EXTRA_SECTION) ?: intent.getStringExtra(EXTRA_SECTION) ?: TripEntity.KIND_TRACE
        for ((kind, label) in listOf(TripEntity.KIND_TRACE to "Traces", TripEntity.KIND_SNAPSHOT to "Snapshots")) {
            binding.filterChips.addView(com.rfsentinel.app.ui.FitChip(this).apply {
                id = View.generateViewId(); text = label; isCheckable = true; isCheckedIconVisible = false
                ChipStyle.apply(this)
                isChecked = kind == section
                setOnClickListener { section = kind; show() }
            })
        }
        binding.searchInput.doAfterTextChanged { show() }

        lifecycleScope.launch {
            AppDatabase.getInstance(this@TripsActivity).tripDao().allFlow().collect { list ->
                all = list
                show()
            }
        }
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_SECTION, section)
    }

    /** The picked section, narrowed by the search box (name). */
    private fun show() {
        val snapshots = section == TripEntity.KIND_SNAPSHOT
        title = if (snapshots) "Snapshots" else "Recorded traces"
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        val mine = all.filter { it.snapshot == snapshots }
        val list = mine.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        adapter.submitList(list)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            mine.isNotEmpty() -> "Nothing matches."
            snapshots -> "No snapshots yet.\nOn the main screen, open the menu and tap Save snapshot to keep the device list as it is."
            else -> "No recorded traces yet.\nOpen the map and tap Record trace."
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        VisualMode.addMenu(menu, this, VisualMode.Screen.TRIPS); return true
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        VisualMode.handle(item, this, VisualMode.Screen.TRIPS) { invalidateOptionsMenu(); adapter.notifyDataSetChanged() } ||
            super.onOptionsItemSelected(item)

    /** A trace's route and flagged devices, loaded once per trace (again while it's still recording). */
    private class Outline(val points: Int, val route: List<Pair<Double, Double>>, val flagged: List<Pair<Double, Double>>)
    private val outlines = HashMap<Long, Outline>()

    /** Visual style: a card per trace with the route's outline and its numbers. */
    private inner class TripCard : android.widget.LinearLayout(this@TripsActivity) {
        private val dp = resources.displayMetrics.density
        private val accent = ChipStyle.accent(context).first
        private val name = android.widget.TextView(context).apply { textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        private val date = android.widget.TextView(context).apply { textSize = 12f; alpha = 0.75f }
        private val route = RouteOutlineView(context)
        private val stats = com.google.android.material.chip.ChipGroup(context).apply { chipSpacingVertical = (4 * dp).toInt() }
        private var boundId = -1L

        init {
            orientation = VERTICAL
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
            addView(name); addView(date)
            addView(route, LayoutParams(-1, (110 * dp).toInt()).apply { topMargin = (8 * dp).toInt(); bottomMargin = (8 * dp).toInt() })
            addView(stats)
        }

        fun bind(t: TripEntity) {
            val live = t.id == TripRecorder.activeTripId
            val color = if (live) 0xFFB3261E.toInt() else if (t.flaggedCount > 0) 0xFFC8431A.toInt() else accent
            background = VisualMode.card(context, color)
            name.text = (if (live) "● " else "") + t.name
            name.setTextColor(if (live || t.flaggedCount > 0) color else ChipStyle.onSurface(context))
            date.text = fmt.format(Date(t.startTime)) + if (live) " · recording now" else ""
            stats.removeAllViews()
            // A snapshot has no route: just its numbers.
            route.visibility = if (t.snapshot) View.GONE else View.VISIBLE
            if (t.snapshot) {
                stats.addView(VisualMode.chip(context, devicesLabel(t.deviceCount), accent))
                stats.addView(VisualMode.chip(context, "${t.flaggedCount} flagged", if (t.flaggedCount > 0) 0xFFD0503C.toInt() else accent))
                boundId = t.id
                return
            }
            val mins = ((t.endTime ?: System.currentTimeMillis()) - t.startTime) / 60000
            listOf(com.rfsentinel.app.util.Units.trip(t.distanceM), String.format(Locale.US, "%d:%02d", mins / 60, mins % 60),
                devicesLabel(t.deviceCount)).forEach { stats.addView(VisualMode.chip(context, it, accent)) }
            stats.addView(VisualMode.chip(context, "${t.flaggedCount} flagged", if (t.flaggedCount > 0) 0xFFD0503C.toInt() else accent))
            boundId = t.id
            val o = outlines[t.id]
            route.set(o?.route.orEmpty(), o?.flagged.orEmpty(), color)
            if (o == null || (live && o.points != t.pointCount)) {
                lifecycleScope.launch {
                    val dao = AppDatabase.getInstance(this@TripsActivity).tripDao()
                    val pts = dao.points(t.id)
                    // At most ~400 points: plenty for a small outline.
                    val step = (pts.size / 400).coerceAtLeast(1)
                    val line = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }.map { it.lat to it.lon }
                    val flagged = dao.devices(t.id).filter { it.flagged && it.lat != null && it.lon != null }.map { it.lat!! to it.lon!! }
                    outlines[t.id] = Outline(t.pointCount, line, flagged)
                    if (boundId == t.id) route.set(line, flagged, color)
                }
            }
        }
    }

    private fun actions(t: TripEntity) {
        if (t.snapshot) {
            MaterialAlertDialogBuilder(this)
                .setTitle(t.name)
                .setItems(arrayOf("View list", "View on map", "Export (GPX, KML, GeoJSON, CSV)...", "Rename", "Delete")) { _, which ->
                    when (which) {
                        0 -> open(t)
                        1 -> startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, t.id))
                        2 -> Exporter.showTripExport(this, t.id, snapshot = true)
                        3 -> rename(t)
                        4 -> delete(t)
                    }
                }
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(t.name)
            .setItems(arrayOf("View on map", "Export (GPX, KML, GeoJSON, CSV)...", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, t.id))
                    1 -> Exporter.showTripExport(this, t.id)
                    2 -> rename(t)
                    3 -> delete(t)
                }
            }
            .show()
    }

    private fun rename(t: TripEntity) {
        val input = EditText(this).apply { setText(t.name); setSelection(text.length) }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (t.snapshot) "Rename snapshot" else "Rename trace")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@TripsActivity).tripDao().rename(t.id, input.text.toString().trim().ifEmpty { if (t.snapshot) "Snapshot" else "Trace" })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(t: TripEntity) {
        if (t.id == TripRecorder.activeTripId) {
            MaterialAlertDialogBuilder(this).setMessage("Stop recording on the map before deleting this trace.")
                .setPositiveButton("OK", null).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${t.name}\"?")
            .setMessage(if (t.snapshot) "The saved device list is deleted from this phone." else "The route and its device list are deleted from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { AppDatabase.getInstance(this@TripsActivity).tripDao().delete(t.id) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
