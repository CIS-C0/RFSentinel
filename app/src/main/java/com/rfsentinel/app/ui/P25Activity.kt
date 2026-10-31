package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.sdr.P25Log
import com.rfsentinel.app.sdr.P25Tsbk
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The P25 control channel the SDR follows (Settings > RTL-SDR radio > P25 control channel): its site, and the
 * talkgroups and private calls granted a channel there, most recent first. Watched talkgroups are named and
 * marked. Signalling only: nothing here is voice.
 */
class P25Activity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var rows: List<P25Log.Row> = emptyList()
    private var watched: Map<Int, String> = emptyMap()
    private val header by lazy {
        TextView(this).apply {
            textSize = 12f; alpha = 0.8f
            val p = (8 * resources.displayMetrics.density).toInt(); setPadding(0, p, 0, p)
        }
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            ItemDetectionBinding.bind(holder.itemView).apply {
                val name = r.tg?.let { watched[it] }
                labelText.text = listOfNotNull(title(r), name?.ifEmpty { "watched" }, "EMERGENCY".takeIf { r.emergency },
                    "encrypted".takeIf { r.encrypted }).joinToString("  ·  ")
                labelText.setTextColor(when {
                    r.emergency -> EMERGENCY
                    name != null -> ChipStyle.accent(this@P25Activity).first
                    else -> macText.currentTextColor
                })
                macText.text = listOfNotNull(
                    r.lastHz?.let { String.format(Locale.US, "%.4f MHz", it / 1e6) } ?: r.lastChannel?.let { "channel ${P25Tsbk.channelName(it)}" },
                    r.lastSource?.let { "last radio $it" }
                ).joinToString(" · ").ifEmpty { "No call yet: radios affiliated" }
                metaText.text = listOfNotNull(
                    "${r.calls} call${if (r.calls == 1) "" else "s"}",
                    "${r.radios} radio${if (r.radios == 1) "" else "s"}",
                    r.lastAt.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS) }
                ).joinToString(" · ")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "P25 talkgroups"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.searchInput.doAfterTextChanged { refresh() }
        binding.root.addView(header, 2)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(1_000) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        watched = P25Log.watched(Prefs.p25Talkgroups(this))
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        rows = SdrRadio.p25Log.rows().filter { r ->
            q.isEmpty() || listOfNotNull(title(r), r.tg?.let { watched[it] }, r.lastSource?.toString()).any { it.contains(q, ignoreCase = true) }
        }
        val s = SdrRadio.p25Log.summary()
        header.text = listOfNotNull(
            SdrRadio.status.ifBlank { "No SDR running" },
            s.site?.let { site ->
                listOfNotNull(site.wacn?.let { "WACN %05X".format(it) }, "system %03X".format(site.sysId),
                    site.rfss?.let { rfss -> "site $rfss-${site.site}" }).joinToString(" · ")
            },
            if (s.messages > 0) "${s.messages} messages · ${s.registrations} radios registered" else null
        ).joinToString("\n")
        adapter.notifyDataSetChanged()
        val empty = when {
            rows.isNotEmpty() -> null
            q.isNotEmpty() -> "Nothing matches."
            SdrRadio.Mode.P25 !in SdrRadio.modes -> "P25 is off.\nTick P25 control channel in Settings › RTL-SDR radio. It runs while scanning, with an RTL-SDR plugged in."
            else -> "No calls heard yet.\nTalkgroups show up here as the control channel grants them a channel."
        }
        binding.recyclerView.visibility = if (empty == null) View.VISIBLE else View.GONE
        binding.emptyText.text = empty.orEmpty()
        binding.emptyText.visibility = if (empty != null) View.VISIBLE else View.GONE
    }

    companion object {
        private val EMERGENCY = 0xFFD0503C.toInt()

        /** "Talkgroup 1234", or "Private call to radio 123456". */
        fun title(r: P25Log.Row) = r.tg?.let { "Talkgroup $it" } ?: "Private call to radio ${r.target}"
    }
}
