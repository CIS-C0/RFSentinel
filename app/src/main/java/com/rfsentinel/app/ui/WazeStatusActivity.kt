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

    private fun refresh() {
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
