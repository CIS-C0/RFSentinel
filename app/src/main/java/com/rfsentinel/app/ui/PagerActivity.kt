package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.sdr.PagerLog
import com.rfsentinel.app.sdr.RadioSettings
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.util.Prefs
import java.util.Locale

/**
 * Pages the SDR decoded this session (Settings › RTL-SDR radio › Pager messages), newest first. No export or history,
 * on purpose: pages can hold private details, so they stay in memory ([PagerLog]).
 */
class PagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var rows: List<PagerLog.Row> = emptyList()
    private var channels: Map<Long, String> = emptyMap()
    private val header by lazy {
        TextView(this).apply {
            textSize = 12f; alpha = 0.8f
            val p = (8 * resources.displayMetrics.density).toInt(); setPadding(0, p, 0, p)
        }
    }

    private val adapter = DetectionRows({ rows }) { r ->
        labelText.text = "Capcode ${r.page.capcode}  ·  ${r.page.protocol}"
        macText.text = r.page.text.ifEmpty { "Tone only: no message" }
        metaText.text = listOfNotNull(
            channels[r.hz] ?: String.format(Locale.US, "%.4f MHz", r.hz / 1e6),
            "cut off".takeIf { r.page.partial },
            DateUtils.getRelativeTimeSpanString(r.at, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
        ).joinToString(" · ")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = logScreen("Pager messages", adapter)
        binding.searchInput.doAfterTextChanged { refresh() }
        binding.root.addView(header, 2)
        refreshWhileStarted(1_000) { refresh() }
    }

    override fun onStart() {
        super.onStart()
        buildChips()
    }

    /** One tap to stay on a channel typed in Settings; All goes through them in turn. */
    private fun buildChips() {
        val list = RadioSettings.targets(Prefs.pagerFreqs(this))
        channels = list.associate { it.freqHz to it.label }
        binding.filterChips.removeAllViews()
        if (list.size < 2) return
        val on = Prefs.pagerChannel(this).takeIf { it in channels } ?: 0L
        fun add(label: String, hz: Long) {
            binding.filterChips.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId(); text = label; isCheckable = true; isCheckedIconVisible = false
                ChipStyle.apply(this)
                isChecked = hz == on
                setOnClickListener { Prefs.setPagerChannel(context, hz); SdrRadio.pagerChannel = hz }
            })
        }
        add("All", 0L)
        list.forEach { add(it.label, it.freqHz) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_HIDE_TONE, Menu.NONE, "Hide tone-only pages").apply {
            isCheckable = true; isChecked = Prefs.pagerHideTone(this@PagerActivity)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_HIDE_TONE) return super.onOptionsItemSelected(item)
        item.isChecked = !item.isChecked
        Prefs.setPagerHideTone(this, item.isChecked)
        refresh()
        return true
    }

    private companion object { const val MENU_HIDE_TONE = 1 }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        val all = SdrRadio.pagerLog.rows()
        // Hidden, still counted: they show the channel is decoding.
        val hideTone = Prefs.pagerHideTone(this)
        val tones = if (hideTone) all.count { it.page.text.isBlank() } else 0
        rows = all.filter { r ->
            !(hideTone && r.page.text.isBlank()) &&
                (q.isEmpty() || r.page.text.contains(q, ignoreCase = true) || r.page.capcode.toString().contains(q))
        }
        header.text = SdrRadio.status.ifBlank { "No SDR running" } +
            (if (tones > 0) "\n$tones tone-only page${if (tones == 1) "" else "s"} hidden" else "") +
            "\nKept in memory only, until the app closes: at most ${PagerLog.MAX} pages."
        adapter.notifyDataSetChanged()
        val pagerOff = SdrRadio.Mode.PAGER !in SdrRadio.modes
        val empty = when {
            rows.isNotEmpty() -> null
            q.isNotEmpty() -> "Nothing matches."
            tones > 0 -> "Only tone-only pages so far: the channel is decoding."
            pagerOff -> "Pager messages is off.\nTap here to open Settings › RTL-SDR radio and tick it. It runs while scanning, with an RTL-SDR or HackRF plugged in."
            else -> "No pages heard yet.\nThey show up here as a paging channel is decoded."
        }
        binding.recyclerView.visibility = if (empty == null) View.VISIBLE else View.GONE
        binding.emptyText.text = empty.orEmpty()
        binding.emptyText.visibility = if (empty != null) View.VISIBLE else View.GONE
        settingsLink(binding.emptyText, empty != null && q.isEmpty() && tones == 0 && pagerOff)
    }
}
