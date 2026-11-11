package com.rfsentinel.app.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.databinding.ActivityWaterfallBinding
import com.rfsentinel.app.sdr.AudioDemod
import com.rfsentinel.app.sdr.FreqNames
import com.rfsentinel.app.sdr.RadioWatch
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.sdr.SdrWaterfall
import com.rfsentinel.app.util.applySystemBarInsets
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SDR's live radio waterfall. It opens on the band sweep (a row per sweep of the watched bands,
 * radio detection carries on); tapping a frequency or Tune zooms in: the SDR holds those ~2 MHz for a
 * fast live view, and detection pauses until Back to sweep or leaving the screen. Receive-only.
 */
class WaterfallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaterfallBinding
    private val queue = ConcurrentLinkedQueue<SdrWaterfall.Row>()
    private val posted = AtomicBoolean(false)
    private val drain = Runnable {
        posted.set(false)
        var any = false
        while (true) { val row = queue.poll() ?: break; binding.waterfall.add(row); any = true }
        if (any) binding.waterfall.invalidate()
    }
    /** An antenna check is waiting for its answer. */
    private var checking = false
    /** Rows show the zoomed-in view (vs the sweep), to clear the view once when it switches. */
    private var showingTuned: Boolean? = null

    /** Debug builds only: a made-up SDR ([WaterfallDemo]) instead of the real one. */
    private val demo get() = com.rfsentinel.app.BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
    private val onRow: (SdrWaterfall.Row) -> Unit = { row ->
        queue.add(row)
        while (queue.size > MAX_QUEUED) queue.poll()
        if (posted.compareAndSet(false, true)) binding.waterfall.post(drain)
    }
    private val demoFeed by lazy { WaterfallDemo(onRow) }
    private val running get() = demo || SdrRadio.running
    private val status get() = if (demo) "Demo: a made-up SDR, no radio used" else SdrRadio.status
    private var tuneHz: Long?
        get() = if (demo) demoFeed.tuneHz else SdrRadio.tuneHz
        set(v) { if (demo) demoFeed.tuneHz = v else SdrRadio.tuneHz = v }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWaterfallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Radio waterfall"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.waterfall.onTap = { hz -> zoom(hz) }
        binding.tuneButton.setOnClickListener { askFrequency() }
        binding.sweepButton.setOnClickListener { SdrRadio.listen = false; tuneHz = null; refresh() }
        binding.antennaButton.setOnClickListener { antennaCheck() }
        // Opened from a radio transmission's Locate: straight to its frequency (not again after a rotation).
        if (savedInstanceState == null) intent.getLongExtra(EXTRA_TUNE_HZ, 0L).takeIf { it > 0 }?.let { zoom(it) }
        refreshWhileStarted(1000) { refresh() }
    }

    override fun onStart() {
        super.onStart()
        if (demo) demoFeed.start() else {
            SdrWaterfall.listener = onRow
            SdrRadio.screenOpened()
            // So a log lines up with what the person saw (and screenshots of it).
            com.rfsentinel.app.util.HwLogs.sdr.log("Radio waterfall screen opened")
        }
    }

    override fun onStop() {
        super.onStop()
        if (demo) demoFeed.stop() else {
            SdrWaterfall.listener = null
            com.rfsentinel.app.util.HwLogs.sdr.log("Radio waterfall screen closed")
        }
        queue.clear()
        // Radio detection never stays paused behind a closed screen (a redraw for a new theme comes straight back).
        if (!isChangingConfigurations) { if (!demo) SdrRadio.screenClosed(); SdrRadio.listen = false; tuneHz = null }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun zoom(hz: Long) {
        if (!running) return
        tuneHz = (hz + RadioWatch.STEP_HZ / 2) / RadioWatch.STEP_HZ * RadioWatch.STEP_HZ
        refresh()
    }

    /** One reading of FM radio and the weather-radio channels: on the air around the clock, so a quick test of antenna and SDR. */
    private fun antennaCheck() {
        if (!running) { Toast.makeText(this, "No SDR running", Toast.LENGTH_SHORT).show(); return }
        // The button says it's working until the answer comes: a couple of seconds, longer with a slow SDR.
        checking = true; refresh()
        val answer: (SdrRadio.AntennaReading) -> Unit = { r ->
            runOnUiThread {
                checking = false
                if (!isDestroyed) refresh()
                if (!isFinishing && !isDestroyed) MaterialAlertDialogBuilder(this)
                    .setTitle("Antenna check")
                    .setMessage(r.verdict + "\n\nMeasured on FM radio, 87.5 to 108 MHz, and the US weather radio channels, 162.400 to 162.550 MHz.")
                    .setPositiveButton("OK", null).show()
            }
        }
        if (demo) demoFeed.antennaCheck(answer) else SdrRadio.antennaCheck = answer
    }

    /** Ready-made stations first, so nobody needs to know a frequency; typing one is the last choice. Zoomed in, Listen comes first. */
    private fun askFrequency() {
        if (!running) { Toast.makeText(this, "No SDR running", Toast.LENGTH_SHORT).show(); return }
        val listenItem = tuneHz?.takeIf { !demo }?.let { hz ->
            if (SdrRadio.listen) "Stop listening"
            else String.format(Locale.US, "Listen to %.4f MHz\n%s, at the phone's media volume", hz / 1e6,
                AudioDemod.modeFor(hz).label.replaceFirstChar { it.uppercase() })
        }
        val items = listOfNotNull(listenItem) + PRESETS.map { "${it.name}\n${it.about}" } + "Type a frequency…"
        MaterialAlertDialogBuilder(this)
            .setTitle("What to look at")
            .setItems(items.toTypedArray()) { _, picked ->
                if (listenItem != null && picked == 0) { SdrRadio.listen = !SdrRadio.listen; refresh(); return@setItems }
                val i = if (listenItem != null) picked - 1 else picked
                PRESETS.getOrNull(i)?.let { zoom(it.hz) } ?: typeFrequency()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun typeFrequency() {
        val input = EditText(this).apply {
            hint = "Frequency in MHz, e.g. 155.475"
            // Both decimal marks in every language: the number filter alone dropped a "," (155,475 became 155475).
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
            setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            tuneHz?.let { setText(String.format(Locale.US, "%.4f", it / 1e6)) }
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle("Tune to")
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(input) })
            .setPositiveButton("Tune") { _, _ ->
                val mhz = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (mhz == null || mhz < MIN_MHZ || mhz > MAX_MHZ) {
                    Toast.makeText(this, "Enter a frequency from $MIN_MHZ to $MAX_MHZ MHz", Toast.LENGTH_SHORT).show()
                } else zoom((mhz * 1e6).toLong())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        val tuned = tuneHz
        if (showingTuned != (tuned != null)) {
            showingTuned = tuned != null
            // The view carries the picture over by frequency: nothing seen is wiped.
            queue.clear()
        }
        val running = this.running
        binding.waterfall.focusHz = tuned
        binding.statusText.text = when {
            !running -> status.ifBlank { "No SDR running" }
            tuned != null -> String.format(Locale.US, "%.4f MHz", tuned / 1e6) +
                ((FreqNames.lookup(tuned, RadioWatch.STEP_HZ)?.name ?: PRESETS.firstOrNull { it.hz == tuned }?.name)?.let { " · $it" } ?: "") +
                "\n" + status
            else -> status
        }
        binding.hintText.text = when {
            !running -> "Plug in an RTL-SDR or HackRF and start scanning to see the radio bands live."
            tuned != null -> (PRESETS.firstOrNull { it.hz == tuned }?.look ?: "Zoomed in on about 2 MHz.") +
                " Pinch to zoom; tap or use the arrow keys to move; Tune › Listen plays it; Back to sweep starts the radio sweep again."
            SdrRadio.p25Hold != null -> "The SDR is holding a P25 talkgroup. Tune to look at a frequency."
            else -> "A row per sweep of your bands, newest on top; ADS-B, P25 and pagers wait while this screen is open. " +
                "Bright marks are signals: tap or pinch to look closer, tap again at 8× to tune to one, or tap Tune to pick from a list."
        }
        // Always in place, so the buttons never move; usable only while zoomed in on a frequency.
        binding.sweepButton.isEnabled = running && tuned != null
        binding.tuneButton.isEnabled = running
        binding.antennaButton.isEnabled = running && !checking
        binding.antennaButton.text = if (checking) "Checking…" else "Antenna check"
        // What the screen reader says about the waterfall itself: which view it shows.
        androidx.core.view.ViewCompat.setStateDescription(binding.waterfall, when {
            !running -> "No SDR running"
            tuned != null -> String.format(Locale.US, "Zoomed in on %.3f MHz", tuned / 1e6)
            else -> "Sweep of your watched bands"
        })
    }

    /** A ready-made view: [hz] in the middle of the ~2 MHz shown, [look] tells what a signal there looks like. */
    private class Preset(val name: String, val about: String, val hz: Long, val look: String)

    companion object {
        /** On the air in most places and harmless to watch: receive-only views of public services and hobby bands. */
        private val PRESETS = listOf(
            Preset("FM radio", "Music stations, always on: a good first test", 98_000_000L,
                "FM radio stations show as wide bright stripes that never stop."),
            Preset("Aircraft voice", "Pilots and control towers, 118 to 120 MHz", 119_000_000L,
                "Pilots and towers show as short bright marks while they talk."),
            Preset("Weather radio (US)", "NOAA forecasts, 162.400 to 162.550 MHz", 162_475_000L,
                "A weather station nearby shows as a steady stripe; none in most places outside the US."),
            Preset("Marine radio", "Boats, channel 16, 156.800 MHz", 156_800_000L,
                "Boats show as short marks while they talk; near water only."),
            Preset("Walkie-talkies", "FRS / GMRS family radios, 462.6 MHz", 462_600_000L,
                "Walkie-talkies show as short marks while someone talks."),
            Preset("Ham radio 2 m", "Amateur radio, 145 to 147 MHz", 146_000_000L,
                "Ham radio operators and their repeaters show as marks while they talk."),
            Preset("Sensors and remotes", "Weather stations, doorbells, car keys, 433.92 MHz", 433_925_000L, // on the 12.5 kHz tuning step, so the status line finds it
                "Sensors and remotes show as tiny dots each time they send."),
            Preset("Aircraft transponders", "ADS-B, 1090 MHz", 1_090_000_000L,
                "Planes' transponders show as speckles in the middle; more with planes overhead.")
        )
        /** A frequency (Hz) to zoom in on as the screen opens. */
        const val EXTRA_TUNE_HZ = "tune_hz"
        /** Rows waiting for the screen; older ones are dropped if it falls behind. */
        private const val MAX_QUEUED = 64
        private const val MIN_MHZ = 1.0
        private const val MAX_MHZ = 6000.0
    }
}
