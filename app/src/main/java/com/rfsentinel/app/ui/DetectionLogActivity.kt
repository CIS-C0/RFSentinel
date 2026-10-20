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
 */
class DetectionLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding
    private lateinit var adapter: DetectionAdapter

    /** The chip picked: everything, today, or one category (by name). */
    private var today = false
    private var category: String? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Match history"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = DetectionAdapter(onClick = { DeviceActions.openDetection(this, it) })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.searchInput.doAfterTextChanged { search(now = false) }
        buildChips()
        search(now = true)
    }

    /** All, Today, then one chip per kind of equipment in the log. */
    private fun buildChips() = lifecycleScope.launch {
        val present = AppDatabase.getInstance(this@DetectionLogActivity).detectionDao().categories().toSet()
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
            AppDatabase.getInstance(this@DetectionLogActivity).detectionDao()
                .search(q, KnownDeviceDao.like(q), KnownDeviceDao.likeHex(q), category, since)
                .collect { list ->
                    adapter.submitList(list)
                    binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.emptyText.text = if (filtered) "Nothing matches." else "No watchlist matches logged yet."
                    val more = if (list.size >= 1000) "+" else ""
                    supportActionBar?.subtitle = "${list.size}$more ${if (filtered) "found" else "logged"}"
                }
        }
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Export...")
        menu.add(0, 2, 1, "Clear match history")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { Exporter.showExportMenu(this); true }
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

    private companion object {
        /** Short chip names for the categories. */
        fun chipLabel(c: Category) = when (c) {
            Category.BODY_CAM -> "Body cams"
            Category.ALPR -> "Plate cameras"
            Category.AUDIO_SENSOR -> "Audio sensors"
            Category.PUBLIC_SAFETY -> "Public safety"
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
