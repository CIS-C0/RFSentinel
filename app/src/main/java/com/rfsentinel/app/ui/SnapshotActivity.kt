package com.rfsentinel.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.ProximityUtil
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One saved snapshot ([com.rfsentinel.app.data.Snapshots]): the device list as it was, searchable. */
class SnapshotActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
    }

    private lateinit var binding: ActivityLogBinding
    private var tripId = 0L
    private var trip: TripEntity? = null
    private var devices: List<TripDeviceEntity> = emptyList()
    private var onlyFlagged = false
    private val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<TripDeviceEntity> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val d = items[position]
            val b = ItemDetectionBinding.bind(holder.itemView)
            val category = Category.parse(d.category)
            val type = DeviceIntel.savedType(d.deviceType, d.vendor, d.mac)
            val label = DeviceIntel.savedLabel(d.label, d.deviceType, d.vendor, d.mac)
            b.labelText.text = label
            b.labelText.setTextColor(
                if (category != null) ThemeManager.ink(this@SnapshotActivity, category.colorArgb) else b.macText.currentTextColor
            )
            b.macText.text = d.mac + (d.vendor?.let { "  ·  $it" } ?: "")
            val parts = mutableListOf<String>()
            category?.let { parts += it.shortTag }
            if (d.confidence > 0) parts += "${Tier.of(d.confidence).label} ${d.confidence}%"
            d.name?.takeIf { it != d.label }?.let { parts += it }
            type?.takeIf { it != d.deviceType && it != label }?.let { parts += it }
            parts += "${if (d.source == "WIFI") "WiFi" else "Bluetooth"} ${ProximityUtil.band(d.bestRssi)} ${d.bestRssi} dBm"
            parts += timeFmt.format(Date(d.firstSeen)) + "–" + timeFmt.format(Date(d.lastSeen))
            b.metaText.text = parts.joinToString(" · ")
            holder.itemView.setOnClickListener { DeviceActions.openDetails(this@SnapshotActivity, d.mac) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tripId = intent.getLongExtra(EXTRA_TRIP_ID, 0L)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Snapshot"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.searchInput.doAfterTextChanged { show() }
        onlyFlagged = savedInstanceState?.getBoolean("only_flagged") ?: false
        fun chip(label: String, flagged: Boolean) = binding.filterChips.addView(
            Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId(); text = label; isCheckable = true; isCheckedIconVisible = false
                ChipStyle.apply(this)
                isChecked = flagged == onlyFlagged
                setOnClickListener { onlyFlagged = flagged; show() }
            })
        chip("All", false)
        chip("Flagged", true)
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("only_flagged", onlyFlagged)
    }

    private fun load() = lifecycleScope.launch {
        val dao = AppDatabase.getInstance(this@SnapshotActivity).tripDao()
        val t = dao.get(tripId) ?: run { finish(); return@launch }
        trip = t
        devices = dao.devices(tripId)
        title = t.name
        show()
    }

    private fun show() {
        val t = trip ?: return
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        val list = devices.filter { d ->
            (!onlyFlagged || d.flagged) && (q.isEmpty() ||
                listOfNotNull(d.mac, d.label, d.name, d.vendor, d.deviceType, DeviceIntel.savedType(d.deviceType, d.vendor, d.mac), d.evidence).any { it.contains(q, ignoreCase = true) })
        }
        adapter.items = list
        @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = if (devices.isEmpty()) "This snapshot has no devices." else "Nothing matches."
        supportActionBar?.subtitle = fmt.format(Date(t.startTime)) + " · " +
            (if (list.size != devices.size) "${list.size} of " else "") + devicesLabel(devices.size)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "View on map")
        menu.add(0, 2, 1, "Export...")
        menu.add(0, 3, 2, "Rename")
        menu.add(0, 4, 3, "Delete")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, tripId)); true }
        2 -> { Exporter.showTripExport(this, tripId, snapshot = true); true }
        3 -> { rename(); true }
        4 -> { delete(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun rename() {
        val t = trip ?: return
        val input = EditText(this).apply { setText(t.name); setSelection(text.length) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename snapshot")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Snapshot" }
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@SnapshotActivity).tripDao().rename(t.id, name)
                    trip = t.copy(name = name)
                    title = name
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete() {
        val t = trip ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${t.name}\"?")
            .setMessage("The saved device list is deleted from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@SnapshotActivity).tripDao().delete(t.id)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
