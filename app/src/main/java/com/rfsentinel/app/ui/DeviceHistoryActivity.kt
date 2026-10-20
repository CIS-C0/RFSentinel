package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.KnownDeviceDao
import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.databinding.ActivityDeviceHistoryBinding
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Every device the app remembers, in range or not: search by MAC, name, vendor, type or your note,
 * and open the device's details page (its saved history) with a tap.
 */
class DeviceHistoryActivity : AppCompatActivity() {

    private enum class Filter(val label: String) { ALL("All"), FAVORITES("Favorites"), FLAGGED("Flagged before"), TODAY("Seen today") }

    private lateinit var binding: ActivityDeviceHistoryBinding
    private val adapter = Rows { DeviceActions.openDetails(this, it.mac) }
    private var filter = Filter.ALL
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Device history"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        for (f in Filter.entries) {
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId(); text = f.label; isCheckable = true; isCheckedIconVisible = false
                ChipStyle.apply(this)
                isChecked = f == filter
                setOnClickListener { if (filter != f) { filter = f; search(now = true) } }
            }
            binding.filterChips.addView(chip)
        }
        binding.searchInput.doAfterTextChanged { search(now = false) }
        if (intent.getBooleanExtra(EXTRA_IMPORT, false) && savedInstanceState == null) binding.root.post { pickFile() }
        binding.keptText.setOnClickListener {
            startActivity(Intent(this, com.rfsentinel.app.settings.SettingsActivity::class.java)
                .putExtra(com.rfsentinel.app.settings.SettingsActivity.EXTRA_SECTION, com.rfsentinel.app.settings.SettingsActivity.SECTION_DATA))
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a device's page (a note, a favorite) or Settings: up to date.
        val days = Prefs.retentionDays(this)
        binding.keptText.text = (if (days <= 0) "History is kept forever" else "History is kept for $days days") +
            " (change in Settings > Data)"
        search(now = true)
    }

    /** Runs the search, a moment after the last key so typing stays smooth. */
    private fun search(now: Boolean) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (!now) delay(250)
            val q = binding.searchInput.text?.toString()?.trim().orEmpty()
            val dao = AppDatabase.getInstance(this@DeviceHistoryActivity).knownDeviceDao()
            val since = if (filter == Filter.TODAY) startOfToday() else 0L
            val rows = dao.search(q, KnownDeviceDao.like(q), KnownDeviceDao.likeHex(q),
                filter == Filter.FAVORITES, filter == Filter.FLAGGED, since, LIMIT)
            val total = dao.count()
            adapter.submitList(rows)
            binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyText.text = when {
                total == 0 -> "No devices remembered yet. Start scanning: every device heard is kept here."
                else -> "Nothing matches."
            }
            supportActionBar?.subtitle = when {
                rows.size >= LIMIT -> "First $LIMIT of the matches · $total remembered"
                q.isEmpty() && filter == Filter.ALL -> "$total remembered"
                else -> "${rows.size} found · $total remembered"
            }
        }
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "Export history...")
        menu.add(0, 2, 1, "Import history from a file...")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        1 -> { com.rfsentinel.app.util.Exporter.showHistoryExport(this); true }
        2 -> { pickFile(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** A history file written by Export (JSON or CSV), or the "everything" export, to add to what the phone remembers. */
    private val importPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    private fun pickFile() = importPicker.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "*/*"))

    private fun importFrom(uri: android.net.Uri) {
        val progress = android.widget.TextView(this).apply { text = "Reading the file..."; setPadding(64, 32, 64, 8) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Importing device history").setView(progress).setCancelable(false).show()
        lifecycleScope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dao = com.rfsentinel.app.data.AppDatabase.getInstance(this@DeviceHistoryActivity).knownDeviceDao()
                    contentResolver.openInputStream(uri)!!.use { raw ->
                        com.rfsentinel.app.util.DeviceHistoryImport.importInto(dao, com.rfsentinel.app.util.DeviceHistoryImport.Limited(raw)) { n ->
                            runOnUiThread { if (!isDestroyed) progress.text = "$n devices read..." }
                        }
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            dialog.dismiss()
            val r = result.getOrNull()
            if (r == null) {
                val why = (result.exceptionOrNull() as? com.rfsentinel.app.util.DeviceHistoryImport.NotHistory)?.message ?: "Couldn't read that file (${result.exceptionOrNull()?.message ?: "unknown error"})"
                MaterialAlertDialogBuilder(this@DeviceHistoryActivity).setTitle("Not imported").setMessage(why).setPositiveButton("OK", null).show()
                return@launch
            }
            com.rfsentinel.app.data.Favorites.load(this@DeviceHistoryActivity)
            com.rfsentinel.app.data.DeviceNames.load(this@DeviceHistoryActivity)
            search(now = true)
            val keep = Prefs.retentionDays(this@DeviceHistoryActivity)
            MaterialAlertDialogBuilder(this@DeviceHistoryActivity).setTitle("History imported")
                .setMessage("${r.added} new devices, ${r.updated} updated, ${r.unchanged} already there" +
                    (if (r.skipped > 0) ", ${r.skipped} entries skipped (not a device)" else "") + "." +
                    (if (keep > 0) "\n\nDevices not seen for more than $keep days are removed again at the next scan unless starred (Settings > Data > Keep history)." else ""))
                .setPositiveButton("OK", null).show()
        }
    }

    companion object {
        private const val LIMIT = 500
        /** Open straight on the file picker for an import (Settings > Data). */
        const val EXTRA_IMPORT = "import"
    }

    /** One device: name (or MAC) and type, then vendor and MAC, then when it was seen. */
    private class Rows(private val open: (KnownDeviceEntity) -> Unit) :
        ListAdapter<KnownDeviceEntity, Rows.Holder>(object : DiffUtil.ItemCallback<KnownDeviceEntity>() {
            override fun areItemsTheSame(a: KnownDeviceEntity, b: KnownDeviceEntity) = a.mac == b.mac
            override fun areContentsTheSame(a: KnownDeviceEntity, b: KnownDeviceEntity) = a == b
        }) {

        class Holder(val root: LinearLayout, val title: TextView, val line: TextView, val seen: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            fun text(size: Float) = TextView(c).apply { textSize = size }
            val title = text(16f).apply { setTypeface(typeface, Typeface.BOLD) }
            val line = text(13f).apply { alpha = 0.8f }
            val seen = text(12f).apply { alpha = 0.65f }
            val root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val ripple = android.util.TypedValue()
                c.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                addView(title); addView(line); addView(seen)
            }
            return Holder(root, title, line, seen)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val k = getItem(position)
            val c = h.root.context
            h.title.text = (if (k.favorite) "★ " else "") + (k.alias?.takeIf { it.isNotBlank() } ?: k.name?.takeIf { it.isNotBlank() } ?: k.mac) +
                (k.deviceType?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            h.line.text = listOfNotNull(k.name?.takeIf { it.isNotBlank() && !k.alias.isNullOrBlank() }?.let { "\"$it\"" },
                k.vendor?.takeIf { it.isNotBlank() }, k.mac.takeIf { k.name?.isNotBlank() == true || !k.alias.isNullOrBlank() },
                k.note?.takeIf { it.isNotBlank() }?.let { "Note: $it" }).joinToString(" · ").ifEmpty { "Unknown vendor" }
            h.line.visibility = View.VISIBLE
            val last = DateUtils.getRelativeTimeSpanString(k.lastSeen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val first = DateUtils.formatDateTime(c, k.firstSeen, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            h.seen.text = "Last seen $last · first $first · ${k.sessions} session${if (k.sessions == 1) "" else "s"}"
            h.root.setOnClickListener { open(k) }
        }
    }
}
