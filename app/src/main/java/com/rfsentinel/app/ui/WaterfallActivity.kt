package com.rfsentinel.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.databinding.ActivityWaterfallBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWaterfallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Radio waterfall"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.waterfall.onTap = { hz -> zoom(hz) }
        binding.tuneButton.setOnClickListener { askFrequency() }
        binding.sweepButton.setOnClickListener { SdrRadio.tuneHz = null; refresh() }
        binding.antennaButton.setOnClickListener { antennaCheck() }
        // Opened from a radio transmission's Locate: straight to its frequency (not again after a rotation).
        if (savedInstanceState == null) intent.getLongExtra(EXTRA_TUNE_HZ, 0L).takeIf { it > 0 }?.let { zoom(it) }
        refreshWhileStarted(1000) { refresh() }
    }

    override fun onStart() {
        super.onStart()
        SdrWaterfall.listener = { row ->
            queue.add(row)
            while (queue.size > MAX_QUEUED) queue.poll()
            if (posted.compareAndSet(false, true)) binding.waterfall.post(drain)
        }
    }

    override fun onStop() {
        super.onStop()
        SdrWaterfall.listener = null
        queue.clear()
        // Radio detection never stays paused behind a closed screen (a redraw for a new theme comes straight back).
        if (!isChangingConfigurations) SdrRadio.tuneHz = null
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun zoom(hz: Long) {
        if (!SdrRadio.running) return
        SdrRadio.tuneHz = (hz + RadioWatch.STEP_HZ / 2) / RadioWatch.STEP_HZ * RadioWatch.STEP_HZ
        refresh()
    }

    /** One reading of FM radio and the weather-radio channels: on the air around the clock, so a quick test of antenna and SDR. */
    private fun antennaCheck() {
        if (!SdrRadio.running) { Toast.makeText(this, "No SDR running", Toast.LENGTH_SHORT).show(); return }
        // The button says it's working until the answer comes: a couple of seconds, longer with a slow SDR.
        checking = true; refresh()
        SdrRadio.antennaCheck = { r ->
            runOnUiThread {
                checking = false
                if (!isDestroyed) refresh()
                if (!isFinishing && !isDestroyed) MaterialAlertDialogBuilder(this)
                    .setTitle("Antenna check")
                    .setMessage(r.verdict + "\n\nMeasured on FM radio, 87.5 to 108 MHz, and the US weather radio channels, 162.400 to 162.550 MHz.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    /** Ready-made stations first, so nobody needs to know a frequency; typing one is the last choice. */
    private fun askFrequency() {
        if (!SdrRadio.running) { Toast.makeText(this, "No SDR running", Toast.LENGTH_SHORT).show(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("What to look at")
            .setItems((PRESETS.map { "${it.name}\n${it.about}" } + "Type a frequency…").toTypedArray()) { _, i ->
                PRESETS.getOrNull(i)?.let { zoom(it.hz) } ?: typeFrequency()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun typeFrequency() {
        val input = EditText(this).apply {
            hint = "Frequency in MHz, e.g. 155.475"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            SdrRadio.tuneHz?.let { setText(String.format(Locale.US, "%.4f", it / 1e6)) }
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
        val tuned = SdrRadio.tuneHz
        if (showingTuned != (tuned != null)) {
            showingTuned = tuned != null
            queue.clear()
            binding.waterfall.clear()
        }
        val running = SdrRadio.running
        binding.statusText.text = when {
            !running -> SdrRadio.status.ifBlank { "No SDR running" }
            tuned != null -> String.format(Locale.US, "%.4f MHz", tuned / 1e6) +
                ((FreqNames.lookup(tuned, RadioWatch.STEP_HZ)?.name ?: PRESETS.firstOrNull { it.hz == tuned }?.name)?.let { " · $it" } ?: "") +
                "\n" + SdrRadio.status
            else -> SdrRadio.status
        }
        binding.hintText.text = when {
            !running -> "Plug in an RTL-SDR or HackRF and start scanning to see the radio bands live."
            tuned != null -> (PRESETS.firstOrNull { it.hz == tuned }?.look ?: "Zoomed in on about 2 MHz.") +
                " Tap or use the arrow keys to move; Back to sweep starts the radio sweep again."
            SdrRadio.mode == SdrRadio.Mode.ADSB -> "The SDR is receiving ADS-B. Tune to look at a frequency."
            SdrRadio.mode == SdrRadio.Mode.P25 -> "The SDR is following a P25 control channel. Tune to look at a frequency."
            SdrRadio.mode == SdrRadio.Mode.PAGER -> "The SDR is decoding pagers. Tune to look at a frequency."
            else -> "A row per sweep of your bands, newest on top; rows pause while another scan has its turn. " +
                "Bright marks are signals: tap one to zoom in, or tap Tune to pick from a list."
        }
        binding.sweepButton.visibility = if (tuned != null) View.VISIBLE else View.GONE
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
