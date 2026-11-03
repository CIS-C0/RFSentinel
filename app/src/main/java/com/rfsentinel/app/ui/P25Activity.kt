package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import com.rfsentinel.app.sdr.P25History
import com.rfsentinel.app.sdr.P25Log
import com.rfsentinel.app.sdr.P25Tsbk
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The P25 control channel the SDR follows (Settings > RTL-SDR radio > P25 control channel): its site, and the
 * talkgroups and private calls granted a channel there, most recent first. Watched talkgroups are named and
 * marked. History: the kept call log ([P25History]) per talkgroup over the last day and week, and its busiest hour.
 * With Settings › Listen to P25 voice on, the call playing is marked, and Mute pauses listening.
 */
class P25Activity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var rows: List<P25Log.Row> = emptyList()
    /** History mode: the kept log per talkgroup, read every [HISTORY_MS] (null: showing the live control channel). */
    private var history: List<P25History.Summary>? = null
    private var historyAt = 0L
    private var watched: Map<Int, String> = emptyMap()
    private val header by lazy {
        TextView(this).apply {
            textSize = 12f; alpha = 0.8f
            val p = (8 * resources.displayMetrics.density).toInt(); setPadding(0, p, 0, p)
        }
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = history?.size ?: rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            history?.let { bindHistory(ItemDetectionBinding.bind(holder.itemView), it[position]); return }
            val r = rows[position]
            ItemDetectionBinding.bind(holder.itemView).apply {
                val name = r.tg?.let { watched[it] }
                val playing = SdrRadio.p25Playing?.let { if (r.tg != null) it.tg == r.tg else it.tg == null && it.target == r.target } == true
                labelText.text = listOfNotNull(title(r), "playing".takeIf { playing }, name?.ifEmpty { "watched" }, "EMERGENCY".takeIf { r.emergency },
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_HISTORY, 0, if (history == null) "History" else "Live")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_EXPORT, 1, "Export call log")
        if (Prefs.p25Listen(this) != 0) menu.add(0, MENU_MUTE, 0, if (Prefs.p25Mute(this)) "Unmute" else "Mute")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_HISTORY -> {
            history = if (history == null) emptyList() else null
            historyAt = 0L
            title = if (history == null) "P25 talkgroups" else "P25 history"
            invalidateOptionsMenu(); refresh(); true
        }
        MENU_EXPORT -> { exportLog(); true }
        MENU_MUTE -> {
            val mute = !Prefs.p25Mute(this)
            Prefs.setP25Mute(this, mute); SdrRadio.p25Mute = mute
            invalidateOptionsMenu(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun exportLog() = lifecycleScope.launch {
        val text = withContext(Dispatchers.IO) { P25History.file(this@P25Activity).takeIf { it.exists() }?.readText() }
        if (text == null || text.lines().size <= 2) {
            android.widget.Toast.makeText(this@P25Activity, "No calls logged yet", android.widget.Toast.LENGTH_LONG).show()
            return@launch
        }
        com.rfsentinel.app.util.Exporter.share(this@P25Activity, "rf_sentinel_p25_calls_${com.rfsentinel.app.util.Exporter.stamp()}.csv",
            text, "text/csv", "Export P25 call log")
    }

    private fun bindHistory(b: ItemDetectionBinding, h: P25History.Summary) {
        val name = h.tg?.let { watched[it] }
        b.labelText.text = listOfNotNull(h.tg?.let { "Talkgroup $it" } ?: "Private call to radio ${h.target}", name?.ifEmpty { "watched" },
            "EMERGENCY".takeIf { h.emergency }, "encrypted".takeIf { h.encrypted }).joinToString("  ·  ")
        b.labelText.setTextColor(when {
            h.emergency -> EMERGENCY
            name != null -> ChipStyle.accent(this).first
            else -> b.macText.currentTextColor
        })
        b.macText.text = "${h.lastDay} today · ${h.lastWeek} this week · ${h.all} kept"
        b.metaText.text = listOfNotNull(
            h.busiestHour?.let { String.format(Locale.US, "busiest %02d:00", it) },
            "${h.radios} radio${if (h.radios == 1) "" else "s"}",
            DateUtils.getRelativeTimeSpanString(h.lastAt, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
        ).joinToString(" · ")
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        watched = P25Log.watched(Prefs.p25Talkgroups(this))
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (history != null) { refreshHistory(q); return }
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

    private var all: List<P25History.Summary> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshHistory(q: String) {
        val now = System.currentTimeMillis()
        if (now - historyAt >= HISTORY_MS) {
            historyAt = now
            lifecycleScope.launch {
                all = withContext(Dispatchers.IO) { P25History.summarize(P25History.read(this@P25Activity), System.currentTimeMillis()) }
                if (history != null) refreshHistory(binding.searchInput.text?.toString()?.trim().orEmpty())
            }
        }
        history = all.filter { h ->
            q.isEmpty() || listOfNotNull(h.tg?.toString(), h.target?.toString(), h.tg?.let { watched[it] }).any { it.contains(q, ignoreCase = true) }
        }
        val days = Prefs.p25LogDays(this)
        header.text = if (days == 0) "The call log is off (Settings › RTL-SDR radio › Keep the P25 call log)."
            else "Calls kept for $days days: ${all.sumOf { it.all }} calls, ${all.size} talkgroups / private calls."
        adapter.notifyDataSetChanged()
        val empty = when {
            history!!.isNotEmpty() -> null
            q.isNotEmpty() && all.isNotEmpty() -> "Nothing matches."
            else -> "No calls logged yet.\nCalls are kept as the control channel grants them while P25 runs."
        }
        binding.recyclerView.visibility = if (empty == null) View.VISIBLE else View.GONE
        binding.emptyText.text = empty.orEmpty()
        binding.emptyText.visibility = if (empty != null) View.VISIBLE else View.GONE
    }

    companion object {
        private const val MENU_HISTORY = 1
        private const val MENU_EXPORT = 2
        private const val MENU_MUTE = 3
        private const val HISTORY_MS = 30_000L
        private val EMERGENCY = 0xFFD0503C.toInt()

        /** "Talkgroup 1234", or "Private call to radio 123456". */
        fun title(r: P25Log.Row) = r.tg?.let { "Talkgroup $it" } ?: "Private call to radio ${r.target}"
    }
}
