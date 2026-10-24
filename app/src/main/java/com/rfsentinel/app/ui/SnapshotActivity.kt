package com.rfsentinel.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.util.Exporter
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
    /** The main screen's filters (those that work on saved devices), starting on its choice. */
    private var filter = DeviceFilter.ALL
    private val filterChips = LinkedHashMap<DeviceFilter, Chip>()
    private val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    /** The main list's own rows and adapter, so a snapshot reads exactly like the live list. */
    private val adapter = DeviceAdapter(
        onClick = { DeviceActions.openDetails(this, it.mac) },
        onLongPress = { DeviceActions.showQuickActions(this, it.mac) }
    )

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
        // As the main list: room under the last row.
        binding.recyclerView.clipToPadding = false
        binding.recyclerView.setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        binding.searchInput.doAfterTextChanged { show() }
        filter = savedInstanceState?.getString("filter")?.let(DeviceFilter::parse) ?: DeviceFilter.savedStart(this)
        for (f in DeviceFilter.SAVED_ORDER) {
            val chip = FitChip(this).apply {
                id = View.generateViewId(); text = f.label; isCheckable = true; isCheckedIconVisible = false
                ChipStyle.apply(this)
            }
            filterChips[f] = chip
            binding.filterChips.addView(chip)
            if (f == filter) chip.isChecked = true
        }
        binding.filterChips.setOnCheckedStateChangeListener { _, ids ->
            filter = filterChips.entries.firstOrNull { it.value.id == ids.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            show()
        }
        // The main screen's choice may sit off screen: bring it into view.
        filterChips[filter]?.let { chip ->
            val row = binding.filterChips.parent as? android.widget.HorizontalScrollView
            row?.post { if (chip.right > row.width) row.scrollTo((chip.left - row.width / 4).coerceAtLeast(0), 0) }
        }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("filter", filter.name)
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
            filter.matches(d) && (q.isEmpty() ||
                listOfNotNull(d.mac, d.label, d.name, d.vendor, d.deviceType, DeviceIntel.savedType(d.deviceType, d.vendor, d.mac), d.evidence).any { it.contains(q, ignoreCase = true) })
        }
        // Sort by / Group by, as on the main screen; by default matches first, then the strongest signal.
        val sections = DeviceOrder.arrange(
            list.map { d ->
                DeviceOrder.Item(d, DeviceFilter.seen(d), DeviceIntel.savedLabel(d.label, d.deviceType, d.vendor, d.mac),
                    d.vendor, d.bestRssi, d.firstSeen, d.lastSeen)
            },
            DeviceOrder.sort(this), DeviceOrder.group(this),
            compareBy<DeviceOrder.Item<TripDeviceEntity>> { it.value.category == null }.thenByDescending { it.value.confidence }
                .thenByDescending { it.rssi }
        )
        adapter.visual = VisualMode.on(this, VisualMode.Screen.DEVICE_LIST)
        adapter.submitList(sections.flatMap { sec ->
            listOfNotNull(sec.header?.let { DeviceRow.header(it, DeviceOrder.headerLabel(sec)) }) + sec.items.map { DeviceRows.saved(this, it) }
        })
        for ((f, chip) in filterChips) {
            val n = devices.count { f.matches(it) }
            chip.text = if (f == DeviceFilter.ALL || n > 0) "${f.label} $n" else f.label
        }
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
        DeviceOrder.addMenu(menu, this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = if (DeviceOrder.onMenuItem(item, this)) { show(); true } else when (item.itemId) {
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
