package com.rfsentinel.app.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.FalseAlerts
import com.rfsentinel.app.data.KnownDeviceDao
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * History of logged matches (the rows the exports contain). Search by address, label, maker, name or
 * evidence, and narrow to today or one kind of equipment. Tap one for device details.
 * With [EXTRA_FALSE_ALERTS]: the alerts you marked as false instead ([FalseAlerts]); long-press one to take it off.
 */
class DetectionLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding
    private lateinit var adapter: DetectionAdapter

    /** The chip picked: everything, today, or one category (by name). */
    private var today = false
    private var category: String? = null
    private var searchJob: Job? = null
    private val falseAlerts by lazy { intent.getBooleanExtra(EXTRA_FALSE_ALERTS, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = if (falseAlerts) "False alerts" else "Match history"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = DetectionAdapter(onClick = { DeviceActions.openDetection(this, it) },
            onLongClick = if (!falseAlerts) null else { d ->
                MaterialAlertDialogBuilder(this).setTitle(d.label)
                    .setItems(arrayOf("Show details", "Remove from false alerts (alerts back on)")) { _, which ->
                        if (which == 0) DeviceActions.openDetection(this, d)
                        else DeviceActions.unmarkFalseAlert(this, d.mac) { buildChips(); search(now = true) }
                    }.show()
            })
        adapter.size = com.rfsentinel.app.util.Prefs.listSize(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.searchInput.doAfterTextChanged { search(now = false) }
        buildChips()
        search(now = true)
    }

    /** All, Today, then one chip per kind of equipment in the log. */
    private fun buildChips() = lifecycleScope.launch {
        val present = if (falseAlerts) FalseAlerts.rows(this@DetectionLogActivity).map { it.category }.toSet()
            else AppDatabase.getInstance(this@DetectionLogActivity).detectionDao().categories().toSet()
        binding.filterChips.removeAllViews()
        if (category != null && category !in present) category = null
        fun add(label: String, checked: Boolean, pick: () -> Unit) {
            binding.filterChips.addView(Chip(this@DetectionLogActivity, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId(); text = label; isCheckable = true; isCheckedIconVisible = false
                ChipStyle.apply(this)
                isChecked = checked
                setOnClickListener { pick(); search(now = true) }
            })
        }
        add("All", !today && category == null) { today = false; category = null }
        add("Today", today) { today = true; category = null }
        for (c in Category.entries) if (c.name in present) add(chipLabel(c), category == c.name) { today = false; category = c.name }
    }

    /** Collects the matches for the search box and chip, a moment after the last key so typing stays smooth. */
    private fun search(now: Boolean) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (!now) delay(250)
            val q = binding.searchInput.text?.toString()?.trim().orEmpty()
            val since = if (today) startOfToday() else 0L
            val filtered = q.isNotEmpty() || today || category != null
            if (falseAlerts) {
                val list = FalseAlerts.rows(this@DetectionLogActivity).filter { r ->
                    r.timestamp >= since && (category == null || r.category == category) && (q.isEmpty() ||
                        listOf(r.mac, r.label, r.vendor, r.deviceName, r.evidence).any { it?.contains(q, ignoreCase = true) == true })
                }
                show(list, filtered, "No false alerts logged yet. Mark one from a device's page or by long-pressing it in the list.")
                return@launch
            }
            AppDatabase.getInstance(this@DetectionLogActivity).detectionDao()
                .search(q, KnownDeviceDao.like(q), KnownDeviceDao.likeHex(q), category, since)
                .collect { list -> show(list, filtered, "No watchlist matches logged yet.") }
        }
    }

    private fun show(list: List<com.rfsentinel.app.data.DetectionEntity>, filtered: Boolean, empty: String) {
        adapter.submitList(list)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = if (filtered) "Nothing matches." else empty
        val more = if (list.size >= 1000) "+" else ""
        supportActionBar?.subtitle = "${list.size}$more ${if (filtered) "found" else "logged"}"
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Export...")
        if (!falseAlerts) menu.add(0, 2, 1, "Clear match history")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { if (falseAlerts) Exporter.showFalseAlertExport(this) else Exporter.showExportMenu(this); true }
        2 -> {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear match history?")
                .setMessage("This deletes every logged match on this device. Export first if you need a copy.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        AppDatabase.getInstance(this@DetectionLogActivity).detectionDao().clearAll()
                        com.rfsentinel.app.data.AlertLog.clear(this@DetectionLogActivity)
                        buildChips()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_FALSE_ALERTS = "false_alerts"

        /** Short chip names for the categories. */
        private fun chipLabel(c: Category) = when (c) {
            Category.BODY_CAM -> "Body cams"
            Category.ALPR -> "Plate cameras"
            Category.AUDIO_SENSOR -> "Audio sensors"
            Category.PUBLIC_SAFETY -> "Public safety"
            Category.MOBILE_PRINTER -> "Printers"
            Category.DRONE -> "Drones"
            Category.TRACKER -> "Trackers"
            Category.GLASSES -> "Glasses"
            Category.NETWORK_CAMERA -> "Cameras"
            Category.CELL_ANOMALY -> "Cell towers"
            Category.GNSS -> "GPS"
            Category.RADIO -> "Radio"
            Category.AIRCRAFT -> "Aircraft"
            Category.POLICE_REPORT -> "Waze"
            Category.OTHER_CAMERA -> "Hidden cameras"
            Category.SKIMMER -> "Skimmers"
            Category.HACKER -> "Hacking tools"
            Category.V2X -> "V2X"
            Category.CUSTOM -> "Watchlist"
            else -> c.shortTag
        }
    }
}
