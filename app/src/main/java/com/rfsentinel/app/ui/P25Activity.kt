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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ActivityLogBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.sdr.P25History
import com.rfsentinel.app.sdr.P25Log
import com.rfsentinel.app.sdr.P25Tsbk
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The P25 control channel the SDR follows (Settings > RTL-SDR radio > P25 control channel): its site, and the
 * talkgroups and private calls granted a channel there, most recent first. Watched talkgroups are named and
 * marked. History: the kept call log ([P25History]) per talkgroup over the last day and week, and its busiest hour.
 * With Settings › Listen to P25 voice on, the call playing is marked, and Mute pauses listening.
 * Hold (or tapping a talkgroup) keeps the SDR on that talkgroup's calls alone until Release ([SdrRadio.p25Hold]).
 */
class P25Activity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var rows: List<P25Log.Row> = emptyList()
    /** History mode: the kept log per talkgroup, read every [HISTORY_MS] (null: showing the live control channel). */
    private var history: List<P25History.Summary>? = null
    private var historyAt = 0L
    private var watched = P25Log.Watched()
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
            holder.itemView.setOnClickListener(null)
            history?.let { bindHistory(ItemDetectionBinding.bind(holder.itemView), it[position]); return }
            val r = rows[position]
            ItemDetectionBinding.bind(holder.itemView).apply {
                val name = r.tg?.let { watched[it] }
                val playing = SdrRadio.p25Playing?.let { if (r.tg != null) it.tg == r.tg else it.tg == null && it.target == r.target } == true
                r.tg?.let { tg -> root.setOnClickListener { askHold(tg, r.encrypted, r.phase2) } }
                labelText.text = listOfNotNull(title(r), "playing".takeIf { playing }, "held".takeIf { r.tg != null && r.tg == SdrRadio.p25Hold }, name?.ifEmpty { "watched" }, "EMERGENCY".takeIf { r.emergency },
                    "encrypted".takeIf { r.encrypted }, "Phase 2".takeIf { r.phase2 }).joinToString("  ·  ")
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
        binding = logScreen("P25 talkgroups", adapter)
        binding.searchInput.doAfterTextChanged { refresh() }
        binding.root.addView(header, 2)
        refreshWhileStarted(1_000) { refresh() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_HISTORY, 0, if (history == null) "History" else "Live")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_EXPORT, 1, "Export call log")
        menu.add(0, MENU_CALLS, 2, "Played calls")
        if (Prefs.p25Listen(this) != 0) menu.add(0, MENU_MUTE, 0, if (Prefs.p25Mute(this)) "Unmute" else "Mute")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        heldInMenu = SdrRadio.p25Hold
        if (Prefs.p25Listen(this) != 0 || heldInMenu != null) menu.add(0, MENU_HOLD, 0, if (heldInMenu != null) "Release" else "Hold")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    /** The talkgroup held when the menu was built: the menu is rebuilt when that changes (Release, or scanning stopped). */
    private var heldInMenu: Int? = null

    private fun hold(tg: Int?) {
        SdrRadio.p25Hold = tg
        invalidateOptionsMenu(); refresh()
    }

    /** A talkgroup tapped: hold it, or release it when it is the one held. */
    private fun askHold(tg: Int, encrypted: Boolean, phase2: Boolean) {
        if (SdrRadio.p25Hold == tg) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Release talkgroup $tg?")
                .setMessage("The SDR goes back to all its scans in Settings › RTL-SDR radio.")
                .setPositiveButton("Release") { _, _ -> hold(null) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Hold talkgroup $tg?")
            .setMessage("Plays only this talkgroup's clear calls, with no time limit, and keeps the SDR on P25: the radio sweep, " +
                "cellular check and ADS-B pause, and so do recordings. Listening for radios keying up nearby stays on for its calls. " +
                "Until you tap Release or scanning stops." +
                (if (encrypted) "\n\nIts last call was encrypted: encrypted calls can't be played." else "") +
                (if (phase2) "\n\nIts last call was on a Phase 2 (TDMA) channel: those calls can't be played yet." else ""))
            .setPositiveButton("Hold") { _, _ -> hold(tg) }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_HISTORY -> {
            history = if (history == null) emptyList() else null
            historyAt = 0L
            title = if (history == null) "P25 talkgroups" else "P25 history"
            invalidateOptionsMenu(); refresh(); true
        }
        MENU_EXPORT -> { exportLog(); true }
        MENU_CALLS -> { showPlayedCalls(); true }
        MENU_HOLD -> {
            val tg = SdrRadio.p25Playing?.tg
            when {
                SdrRadio.p25Hold != null -> hold(null)
                tg != null -> hold(tg)
                else -> android.widget.Toast.makeText(this, "Tap a talkgroup to hold it, or tap Hold while a call plays", android.widget.Toast.LENGTH_LONG).show()
            }
            true
        }
        MENU_MUTE -> {
            val mute = !Prefs.p25Mute(this)
            Prefs.setP25Mute(this, mute); SdrRadio.p25Mute = mute
            invalidateOptionsMenu(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private var player: android.media.MediaPlayer? = null

    private fun stopPlayer() { player?.release(); player = null }

    override fun onStop() { stopPlayer(); super.onStop() }

    /** The saved played calls ([com.rfsentinel.app.sdr.P25Record.saveSpeech]), newest first: tap one to hear it. */
    private fun showPlayedCalls() = lifecycleScope.launch {
        val calls = withContext(Dispatchers.IO) {
            com.rfsentinel.app.sdr.P25Record.speechFiles(this@P25Activity).mapNotNull { com.rfsentinel.app.sdr.P25Record.saved(it) }
        }
        if (calls.isEmpty()) {
            android.widget.Toast.makeText(this@P25Activity, "No calls saved yet: each P25 call played is kept (Settings › " +
                "RTL-SDR radio › Save played calls)", android.widget.Toast.LENGTH_LONG).show()
            return@launch
        }
        val labels = calls.map { c ->
            val who = c.tg?.let { tg -> "Talkgroup $tg" + (watched[tg]?.ifEmpty { null }?.let { " ($it)" } ?: "") }
                ?: c.hz?.let { String.format(Locale.US, "Radio %.4f MHz", it / 1e6) } ?: "Private call to radio ${c.target}"
            DateUtils.formatDateTime(this@P25Activity, c.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_ABBREV_MONTH) + " · $who · " + String.format(Locale.US, "%.1f s", c.seconds)
        }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@P25Activity)
            .setTitle("Played calls")
            .setSingleChoiceItems(labels, -1) { _, i -> play(calls[i].file) }
            .setNeutralButton("Share") { _, _ ->
                com.rfsentinel.app.util.Exporter.shareFiles(this@P25Activity, calls.map { it.file }, "audio/wav",
                    "RF Sentinel P25 calls (${calls.size})", "Share played calls")
            }
            .setPositiveButton("Close", null)
            .setOnDismissListener { stopPlayer() }
            .show()
    }

    private fun play(f: java.io.File) {
        stopPlayer()
        player = runCatching {
            android.media.MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.path); prepare(); start()
            }
        }.onFailure { android.widget.Toast.makeText(this, "This call can't be played", android.widget.Toast.LENGTH_SHORT).show() }.getOrNull()
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
        if (SdrRadio.p25Hold != heldInMenu) invalidateOptionsMenu()
        watched = P25Log.watched(Prefs.p25Talkgroups(this))
        val q = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (history != null) { refreshHistory(q); return }
        rows = SdrRadio.p25Log.rows().filter { r ->
            q.isEmpty() || listOfNotNull(title(r), r.tg?.let { watched[it] }, r.lastSource?.toString()).any { it.contains(q, ignoreCase = true) }
        }
        val s = SdrRadio.p25Log.summary()
        header.text = listOfNotNull(
            SdrRadio.p25Hold?.let { "Holding talkgroup $it · other radio uses paused" },
            SdrRadio.status.ifBlank { "No SDR running" },
            s.site?.let { site ->
                listOfNotNull(site.wacn?.let { "WACN %05X".format(it) }, "system %03X".format(site.sysId),
                    site.rfss?.let { rfss -> "site $rfss-${site.site}" }).joinToString(" · ")
            },
            if (s.messages > 0) "${s.messages} messages · ${s.registrations} radios registered" else null
        ).joinToString("\n")
        adapter.notifyDataSetChanged()
        val p25Off = SdrRadio.Mode.P25 !in SdrRadio.modes
        val empty = when {
            rows.isNotEmpty() -> null
            q.isNotEmpty() -> "Nothing matches."
            p25Off -> "P25 is off.\nTap here to open Settings › RTL-SDR radio and tick P25 control channel. It runs while scanning, with an RTL-SDR plugged in."
            else -> "No calls heard yet.\nTalkgroups show up here as the control channel grants them a channel."
        }
        binding.recyclerView.visibility = if (empty == null) View.VISIBLE else View.GONE
        binding.emptyText.text = empty.orEmpty()
        binding.emptyText.visibility = if (empty != null) View.VISIBLE else View.GONE
        settingsLink(binding.emptyText, empty != null && q.isEmpty() && p25Off)
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
        settingsLink(binding.emptyText, false)
    }

    companion object {
        private const val MENU_HISTORY = 1
        private const val MENU_EXPORT = 2
        private const val MENU_MUTE = 3
        private const val MENU_HOLD = 4
        private const val MENU_CALLS = 5
        private const val HISTORY_MS = 30_000L
        private val EMERGENCY = 0xFFD0503C.toInt()

        /** "Talkgroup 1234", or "Private call to radio 123456". */
        fun title(r: P25Log.Row) = r.tg?.let { "Talkgroup $it" } ?: "Private call to radio ${r.target}"
    }
}
