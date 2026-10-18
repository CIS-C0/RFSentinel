package com.rfsentinel.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.rfsentinel.app.online.MotionTracker
import com.rfsentinel.app.online.OnlineWatch
import com.rfsentinel.app.online.WazePolice
import com.rfsentinel.app.online.WazeStatus
import com.rfsentinel.app.online.wazert.WazeRtFetcher
import com.rfsentinel.app.online.wazert.WazeRtFetcher.Diagnostics
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.SecureStore
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What the Waze checks are doing and what they tell the other side: the source, the position that goes
 * out, the account, when the last and next checks are, and what came back. With Check now, Pause and
 * Forget account.
 */
class WazeStatusActivity : AppCompatActivity() {

    private lateinit var body: TextView
    private lateinit var pause: MaterialButton
    private lateinit var forget: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Waze status"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val dp = resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        body = TextView(this).apply { textSize = 14f; setLineSpacing(0f, 1.2f); setTextIsSelectable(true) }
        fun button(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick(); refresh() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad(8) }
        }
        pause = button("Pause Waze checks") { Prefs.setWazePaused(this, !Prefs.wazePaused(this)) }
        forget = button("Forget Waze account") {
            WazeRtFetcher.forgetStoredAccount(this)
            android.widget.Toast.makeText(this, "Waze account forgotten; a new one is made on the next check", android.widget.Toast.LENGTH_SHORT).show()
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(16), pad(16), pad(16), pad(16))
            addView(visual.box)
            addView(body)
            addView(button("Check now") { WazeUi.checkNow(this@WazeStatusActivity) })
            addView(pause)
            addView(forget)
            addView(button("Waze settings") {
                startActivity(android.content.Intent(this@WazeStatusActivity, com.rfsentinel.app.settings.SettingsActivity::class.java))
            })
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        scroll.applySystemBarInsets()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(1_000) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        VisualMode.addMenu(menu, this, VisualMode.Screen.WAZE); return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        VisualMode.handle(item, this, VisualMode.Screen.WAZE) { invalidateOptionsMenu(); refresh() } || super.onOptionsItemSelected(item)

    private val visual by lazy { Visual() }

    /** The visual style: state and countdown, counts, a timeline of the last checks, and what goes out. */
    private inner class Visual {
        val ctx = this@WazeStatusActivity
        val dp = resources.displayMetrics.density
        val accent = ChipStyle.accent(ctx).first
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val stateChip = TextView(ctx).apply { textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val source = TextView(ctx).apply { textSize = 13f }
        val ring = ConfidenceRingView(ctx)
        val every = TextView(ctx).apply { textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val last = TextView(ctx).apply { textSize = 12f; alpha = 0.8f }
        val tiles = listOf(VisualMode.tile(ctx, "0", "on the map", accent), VisualMode.tile(ctx, "0", "in alert range", accent),
            VisualMode.tile(ctx, "0", "checks", accent))
        val timeline = MiniBarsView(ctx)
        val timelineNote = TextView(ctx).apply { textSize = 11f; alpha = 0.7f }
        val out = TextView(ctx).apply { textSize = 13f; setLineSpacing(0f, 1.15f); setTextIsSelectable(true) }
        val answer = TextView(ctx).apply { textSize = 12f; alpha = 0.8f; setTextIsSelectable(true) }

        fun title(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(accent); letterSpacing = 0.08f
        }

        fun card(vararg views: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = VisualMode.card(ctx)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            views.forEach { addView(it) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
        }

        init {
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(title("WAZE CHECKS"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(stateChip)
            }
            val timing = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(ring, LinearLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
                addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(every); addView(last) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            box.addView(card(head, source, timing))
            box.addView(VisualMode.tileRow(ctx, tiles).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
            })
            box.addView(card(title("LAST CHECKS"), timeline, timelineNote))
            box.addView(card(title("WHAT GOES OUT"), out))
            box.addView(card(title("LAST ANSWER"), answer))
        }

        fun value(tile: View, v: String) { ((tile as LinearLayout).getChildAt(0) as TextView).text = v }
    }

    private fun refresh() {
        if (VisualMode.on(this, VisualMode.Screen.WAZE)) { refreshVisual(); return }
        visual.box.visibility = View.GONE
        body.visibility = View.VISIBLE
        refreshList()
    }

    private fun refreshVisual() {
        val v = visual
        v.box.visibility = View.VISIBLE
        body.visibility = View.GONE
        val now = System.currentTimeMillis()
        val direct = Prefs.wazeBackend(this) == Prefs.WAZE_DIRECT
        val paused = Prefs.wazePaused(this)
        pause.text = if (paused) "Resume Waze checks" else "Pause Waze checks"
        forget.visibility = if (direct) View.VISIBLE else View.GONE
        val running = WazePolice.running && !paused
        val (state, stateColor) = when {
            !WazeUi.on(this) -> "Off" to 0xFF8A8A8A.toInt()
            !ScanForegroundService.isRunning -> "Not scanning" to 0xFF8A8A8A.toInt()
            paused -> "Paused" to 0xFFE0A33A.toInt()
            WazePolice.running -> "Checking" to 0xFF2E9E5B.toInt()
            else -> "Starting" to 0xFFE0A33A.toInt()
        }
        v.stateChip.text = state
        VisualMode.chip(v.stateChip, stateColor)
        v.source.text = if (direct) "Waze direct" + (if (Diagnostics.host.isNotEmpty()) " · ${Diagnostics.host}" else "") else "OpenWeb Ninja"
        if (running && WazeStatus.everyS > 0) {
            val left = ((WazeStatus.nextCheckAt - now) / 1000).coerceAtLeast(0)
            v.ring.set((100 * left / WazeStatus.everyS.coerceAtLeast(1)).toInt(), v.accent, "next check", "$left s")
            val motion = when (WazeStatus.motion) {
                MotionTracker.State.PARKED -> "parked"
                MotionTracker.State.FAST -> "on a fast road"
                MotionTracker.State.MOVING -> "moving"
            }
            v.every.text = "Every ${Prefs.formatInterval(WazeStatus.everyS)}" +
                if (WazeStatus.everyS != WazeStatus.baseS) "\nadapted: you are $motion" else ""
        } else {
            v.ring.set(0, 0xFF8A8A8A.toInt(), "next check", "–")
            v.every.text = when {
                !WazeUi.on(this) -> "Turn on Waze reports in Settings"
                !ScanForegroundService.isRunning -> "Waze is checked while scanning"
                paused -> "Checks are paused"
                else -> "Starting…"
            }
        }
        v.last.text = "Last check ${if (WazeStatus.lastCheckAt <= 0) "never" else "${((now - WazeStatus.lastCheckAt) / 1000).coerceAtLeast(0)} s ago"}" +
            if (WazeStatus.lastCheckAt > 0) ", took ${String.format(Locale.US, "%.1f", WazeStatus.lastCheckMs / 1000.0)} s" else ""
        v.value(v.tiles[0], "${WazeStatus.onMap}")
        v.value(v.tiles[1], "${WazeStatus.inAlertRange}")
        v.value(v.tiles[2], "${WazeStatus.checks}")
        val checks = WazeStatus.recent.toList()
        val red = 0xFFD0503C.toInt()
        v.timeline.set(FloatArray(checks.size) { checks[it].reports.toFloat() },
            IntArray(checks.size) { if (checks[it].ok) v.accent else red }, WazeStatus.KEEP)
        v.timelineNote.text = if (checks.isEmpty()) "No checks yet this session."
            else "One bar per check, newest on the right; its height is the reports on the map. Red: no answer."
        v.out.text = if (direct) listOfNotNull(
            if (Diagnostics.sentLat.isNaN()) "Nothing sent yet"
            else "Your IP address and a 1 km grid square centred on " +
                "${String.format(Locale.US, "%.3f", Diagnostics.sentLat)}, ${String.format(Locale.US, "%.3f", Diagnostics.sentLon)} (never your exact position)",
            "Account: " + (if (WazeRtFetcher.hasStoredAccount(this)) "an anonymous account is stored on this phone" else "none yet; one is made on the next check") +
                " (${WazeRtFetcher.accountsMadeToday(this)} of ${WazeRtFetcher.dailyAccountLimit()} made today)",
            Diagnostics.lastError.takeIf { it.isNotEmpty() }?.let { "Last problem: $it" }
        ).joinToString("\n") else listOf(
            "Your API key and a box of about ${Prefs.formatRange(maxOf(Prefs.wazeViewKm(this) * 1000, Prefs.wazeAlertM(this)))} around your position (not rounded)",
            "API key: " + (if (SecureStore.get(this, OnlineWatch.WAZE_KEY_NAME).isNullOrBlank()) "not set" else "set")
        ).joinToString("\n")
        v.answer.text = OnlineWatch.wazeStatus.ifEmpty { "No answer yet" }
    }

    private fun refreshList() {
        val now = System.currentTimeMillis()
        val direct = Prefs.wazeBackend(this) == Prefs.WAZE_DIRECT
        val paused = Prefs.wazePaused(this)
        pause.text = if (paused) "Resume Waze checks" else "Pause Waze checks"
        forget.visibility = if (direct) View.VISIBLE else View.GONE

        fun ago(t: Long) = if (t <= 0) "never" else "${((now - t) / 1000).coerceAtLeast(0)} s ago"
        val state = when {
            !WazeUi.on(this) -> "Off: turn on Waze reports in Settings"
            !ScanForegroundService.isRunning -> "Not scanning: Waze is checked while scanning"
            paused -> "Paused"
            WazePolice.running -> "Checking"
            else -> "Starting"
        }
        val motion = when (WazeStatus.motion) {
            MotionTracker.State.PARKED -> "parked"
            MotionTracker.State.FAST -> "on a fast road"
            MotionTracker.State.MOVING -> "moving"
        }
        val lines = ArrayList<String>()
        lines += "State: $state"
        if (WazePolice.running && !paused) {
            val adapted = if (WazeStatus.everyS != WazeStatus.baseS) " (set to ${Prefs.formatInterval(WazeStatus.baseS)}, adapted: you are $motion)" else ""
            lines += "Checks every: ${Prefs.formatInterval(WazeStatus.everyS)}$adapted"
            lines += "Next check: in ${((WazeStatus.nextCheckAt - now) / 1000).coerceAtLeast(0)} s"
        }
        lines += "Last check: ${ago(WazeStatus.lastCheckAt)}" +
            (if (WazeStatus.lastCheckAt > 0) ", took ${String.format(Locale.US, "%.1f", WazeStatus.lastCheckMs / 1000.0)} s" else "") +
            " · ${WazeStatus.checks} this session"
        lines += "Reports: ${WazeStatus.onMap} on the map, ${WazeStatus.inAlertRange} in alert range"
        lines += ""
        if (direct) {
            lines += "Source: Waze direct" + (if (Diagnostics.host.isNotEmpty()) " (${Diagnostics.host})" else "")
            lines += if (Diagnostics.sentLat.isNaN()) "Sent to Waze: nothing yet"
            else "Sent to Waze: your IP address and a 1 km grid square centred on " +
                "${String.format(Locale.US, "%.3f", Diagnostics.sentLat)}, ${String.format(Locale.US, "%.3f", Diagnostics.sentLon)} (never your exact position)"
            lines += "Account: " + (if (WazeRtFetcher.hasStoredAccount(this)) "an anonymous account is stored on this phone" else "none yet; one is made on the next check") +
                " (${WazeRtFetcher.accountsMadeToday(this)} of ${WazeRtFetcher.dailyAccountLimit()} made today)"
            if (Diagnostics.lastError.isNotEmpty()) lines += "Last problem: ${Diagnostics.lastError}"
        } else {
            lines += "Source: OpenWeb Ninja (api.openwebninja.com)"
            lines += "Sent: your API key and a box of about ${Prefs.formatRange(maxOf(Prefs.wazeViewKm(this) * 1000, Prefs.wazeAlertM(this)))} around your position (not rounded)"
            lines += "API key: " + (if (SecureStore.get(this, OnlineWatch.WAZE_KEY_NAME).isNullOrBlank()) "not set" else "set")
        }
        lines += ""
        lines += OnlineWatch.wazeStatus.ifEmpty { "No answer yet" }
        body.text = lines.joinToString("\n")
    }
}
