package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.sdr.PagerLog
import com.rfsentinel.app.sdr.SdrRadio
import java.util.Locale

/**
 * Pages the SDR decoded this session (Settings › RTL-SDR radio › Pager messages), newest first. No export or history,
 * on purpose: pages can hold private details, so they stay in memory ([PagerLog]).
 */
class PagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var rows: List<PagerLog.Row> = emptyList()
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
            String.format(Locale.US, "%.4f MHz", r.hz / 1e6),
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        rows = SdrRadio.pagerLog.rows().filter { r ->
            q.isEmpty() || r.page.text.contains(q, ignoreCase = true) || r.page.capcode.toString().contains(q)
        }
        header.text = SdrRadio.status.ifBlank { "No SDR running" } + "\nKept in memory only, until the app closes: at most ${PagerLog.MAX} pages."
        adapter.notifyDataSetChanged()
        val empty = when {
            rows.isNotEmpty() -> null
            q.isNotEmpty() -> "Nothing matches."
            SdrRadio.Mode.PAGER !in SdrRadio.modes -> "Pager messages is off.\nTick it in Settings › RTL-SDR radio. It runs while scanning, with an RTL-SDR or HackRF plugged in."
            else -> "No pages heard yet.\nThey show up here as a paging channel is decoded."
        }
        binding.recyclerView.visibility = if (empty == null) View.VISIBLE else View.GONE
        binding.emptyText.text = empty.orEmpty()
        binding.emptyText.visibility = if (empty != null) View.VISIBLE else View.GONE
    }
}
