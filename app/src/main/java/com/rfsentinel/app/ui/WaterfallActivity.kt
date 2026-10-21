package com.rfsentinel.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.databinding.ActivityWaterfallBinding
import com.rfsentinel.app.sdr.FreqNames
import com.rfsentinel.app.sdr.RadioWatch
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.sdr.SdrWaterfall
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // Opened from a radio transmission's Locate: straight to its frequency (not again after a rotation).
        if (savedInstanceState == null) intent.getLongExtra(EXTRA_TUNE_HZ, 0L).takeIf { it > 0 }?.let { zoom(it) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(1000) }
            }
        }
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

    private fun askFrequency() {
        if (!SdrRadio.running) { Toast.makeText(this, "No SDR running", Toast.LENGTH_SHORT).show(); return }
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
                (FreqNames.lookup(tuned, RadioWatch.STEP_HZ)?.let { " · ${it.name}" } ?: "") + "\n" + SdrRadio.status
            else -> SdrRadio.status
        }
        binding.hintText.text = when {
            !running -> "Plug in an RTL-SDR or HackRF and start scanning to see the radio bands live."
            tuned != null -> "Zoomed in on about 2 MHz. Radio detection is paused until you go back to the sweep. Tap to move."
            SdrRadio.adsb -> "The SDR is receiving ADS-B. Tune to look at a frequency."
            else -> "One row per sweep of your watched bands, newest at the top. Tap a signal to zoom in."
        }
        binding.sweepButton.visibility = if (tuned != null) View.VISIBLE else View.GONE
        binding.tuneButton.isEnabled = running
    }

    companion object {
        /** A frequency (Hz) to zoom in on as the screen opens. */
        const val EXTRA_TUNE_HZ = "tune_hz"
        /** Rows waiting for the screen; older ones are dropped if it falls behind. */
        private const val MAX_QUEUED = 64
        private const val MIN_MHZ = 1.0
        private const val MAX_MHZ = 6000.0
    }
}
