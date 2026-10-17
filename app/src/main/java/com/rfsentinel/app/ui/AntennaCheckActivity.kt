package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfsentinel.app.databinding.ActivityAntennaCheckBinding
import com.rfsentinel.app.databinding.ItemDetectionBinding
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.usb.AntennaCheck
import com.rfsentinel.app.usb.UsbWifi
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Antenna check for the USB WiFi adapter (after Wardrive Go's): pick a nearby access point as
 * the reference, the adapter holds its channel, and its frames are compared per antenna (chain 0
 * vs chain 1, with Zero and Identify) and between captures with antenna A and antenna B.
 * Signal only, not SWR. Scanning goes back to hopping when the screen closes.
 */
class AntennaCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAntennaCheckBinding
    private var aps: List<AntennaCheck.Ap> = emptyList()

    private var ref: AntennaCheck.Ap? = null
    private var lockedAt = 0L
    /** Chain 0 minus chain 1 with matched antennas, subtracted from then on. */
    private var zero: Double? = null
    private var identifyAt = 0L
    private var identifyBefore: AntennaCheck.Stats? = null
    private var capA: AntennaCheck.Stats? = null
    private var capB: AntennaCheck.Stats? = null
    /** Debug builds, `--ez demo true` (`--ez single true`: one signal per frame): made-up access points and frames. */
    private var demo = false

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) } // rows stay put across refreshes, so a tap isn't lost to a rebind
        override fun getItemCount() = aps.size
        override fun getItemId(position: Int) = aps[position].bssid.hashCode().toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ap = aps[position]
            ItemDetectionBinding.bind(holder.itemView).apply {
                labelText.text = ap.ssid ?: "Hidden network"
                macText.text = ap.bssid
                metaText.text = "${AntennaCheck.channelName(ap.channel)} · ${ap.rssi} dBm"
            }
            holder.itemView.setOnClickListener { pick(ap) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAntennaCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        title = "Antenna check"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.apList.layoutManager = LinearLayoutManager(this)
        binding.apList.adapter = adapter
        binding.changeButton.setOnClickListener { pick(null) }
        binding.zeroButton.setOnClickListener { zeroNow() }
        binding.identifyButton.setOnClickListener { identifyNow() }
        binding.captureA.setOnClickListener { capture(a = true) }
        binding.captureB.setOnClickListener { capture(a = false) }
        demo = com.rfsentinel.app.BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { if (demo) feedDemo(); refresh(); delay(500) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AntennaCheck.open()
        ref?.let { AntennaCheck.lock(it.bssid, it.channel); lockedAt = System.currentTimeMillis() }
    }

    override fun onStop() {
        AntennaCheck.close() // back to hopping
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun pick(ap: AntennaCheck.Ap?) {
        ref = ap
        zero = null; identifyAt = 0L; identifyBefore = null; capA = null; capB = null
        binding.identifyText.text = ""; binding.abText.text = ""
        if (ap == null) AntennaCheck.unlock()
        else { AntennaCheck.lock(ap.bssid, ap.channel); lockedAt = System.currentTimeMillis() }
        refresh()
    }

    private fun last5s(): AntennaCheck.Stats { val now = System.currentTimeMillis(); return AntennaCheck.stats(now - 5_000, now) }

    private fun zeroNow() {
        val s = last5s()
        binding.identifyText.text = if (s.both >= AntennaCheck.MIN_BOTH && s.delta != null) {
            zero = s.delta
            "Zeroed: these antennas now read 0 dB apart. Swap in the antenna to test and watch the difference."
        } else "Not enough frames with both antennas yet: wait a few seconds and retry."
    }

    private fun identifyNow() {
        val s = last5s()
        if (s.both < AntennaCheck.MIN_BOTH) {
            binding.identifyText.text = "Couldn't read both antennas yet: wait a few seconds and retry."; return
        }
        identifyBefore = s
        identifyAt = System.currentTimeMillis()
        refresh()
    }

    private fun capture(a: Boolean) {
        val s = last5s()
        if (s.frames < 5 || s.rssi == null) {
            binding.abText.text = "Not enough frames from the reference yet: wait a few seconds and retry."; return
        }
        if (a) capA = s else capB = s
        showCaptures()
    }

    private fun showCaptures() {
        val a = capA; val b = capB
        fun line(name: String, s: AntennaCheck.Stats?) = if (s == null) "$name: not captured" else
            "$name: %.1f dBm, %d frames".format(s.rssi, s.frames) +
                if (s.a != null && s.b != null) " · chain 0 %.1f, chain 1 %.1f".format(s.a, s.b) else ""
        val out = mutableListOf(line("A", a), line("B", b))
        if (a?.rssi != null && b?.rssi != null) {
            out += AntennaCheck.compare(a.rssi, b.rssi)
            if (a.a != null && b.a != null && a.b != null && b.b != null) {
                out += AntennaCheck.compare(a.a, b.a, "Chain 0: ")
                out += AntennaCheck.compare(a.b, b.b, "Chain 1: ")
            }
        }
        binding.abText.text = out.joinToString("\n")
    }

    private fun feedDemo() {
        val now = System.currentTimeMillis()
        val single = intent.getBooleanExtra("single", false)
        listOf(Triple("02:00:00:00:00:01", "Demo network", 36), Triple("02:00:00:00:00:02", null, 6),
            Triple("02:00:00:00:00:03", "A much longer demo network name", 5975)).forEachIndexed { i, (m, n, ch) ->
            AntennaCheck.heardAp(m, n, ch, -45 - 12 * i, now)
        }
        if (ref != null) AntennaCheck.holding = AntennaCheck.lockChannel
        repeat(5) {
            val a = -48 - (0..2).random(); val b = -53 - (0..2).random()
            AntennaCheck.demoSample(maxOf(a, b), if (single) null else a, if (single) null else b, now)
        }
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun refresh() {
        val now = System.currentTimeMillis()
        val live = demo || ScanForegroundService.isRunning && UsbWifi.status.contains("live")
        if (!live) {
            binding.pickPanel.visibility = View.GONE
            binding.checkPanel.visibility = View.GONE
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = "Start a scan with a USB WiFi adapter plugged in.\n" +
                "The check tests the adapter's antennas, not the phone's own WiFi."
            return
        }
        binding.emptyText.visibility = View.GONE
        val r = ref
        if (r == null) {
            binding.pickPanel.visibility = View.VISIBLE
            binding.checkPanel.visibility = View.GONE
            val fresh = AntennaCheck.aps(now)
            if (fresh.map { Triple(it.bssid, it.ssid, it.rssi) } != aps.map { Triple(it.bssid, it.ssid, it.rssi) }) {
                aps = fresh
                adapter.notifyDataSetChanged()
            }
            binding.pickHint.text = if (aps.isEmpty()) "Listening for access points… the adapter visits every channel, which can take 15 s."
                else "Tap a strong access point that stays put to use as the reference:"
            return
        }
        binding.pickPanel.visibility = View.GONE
        binding.checkPanel.visibility = View.VISIBLE
        val s = AntennaCheck.stats(now - 5_000, now)
        val holding = AntennaCheck.holding == r.channel
        binding.refTitle.text = "Reference: ${r.ssid ?: "Hidden network"}"
        val stuck = !holding && now - lockedAt > 8_000
        val hold = when {
            holding -> "channel held"
            stuck -> "can't hold this channel"
            else -> "switching to its channel…"
        }
        binding.refSub.text = "${AntennaCheck.channelName(r.channel)} · $hold\n" +
            "${s.frames} frames in the last 5 s" + (s.rssi?.let { " · %.1f dBm".format(it) } ?: "")

        val recent = AntennaCheck.stats(now - 15_000, now)
        val dual = recent.both >= AntennaCheck.MIN_BOTH
        binding.zeroButton.isEnabled = dual
        binding.identifyButton.isEnabled = dual && identifyAt == 0L
        if (dual && s.a != null && s.b != null) {
            val d = s.delta?.minus(zero ?: 0.0)
            binding.chainsText.text = "Chain 0  %6.1f dBm\nChain 1  %6.1f dBm\n".format(s.a, s.b) +
                (d?.let { "Difference  %+.1f dB".format(it) + if (zero != null) " · zeroed" else "" } ?: "")
            binding.chainVerdict.text = if (d != null && s.both >= AntennaCheck.MIN_BOTH) AntennaCheck.chainVerdict(d)
                else "Waiting for more frames with both antennas…"
        } else if (dual) {
            binding.chainsText.text = "No frames from the reference in the last 5 s"
            binding.chainVerdict.text = ""
        } else {
            binding.chainsText.text = s.rssi?.let { "Signal  %6.1f dBm".format(it) } ?: "No frames from the reference yet"
            binding.chainVerdict.text = if (s.frames >= 5) "This adapter reports one signal for these frames, not one per antenna: use the swap test below."
                else "Waiting for frames from the reference…"
        }

        if (stuck) binding.chainVerdict.text = "This adapter can't stay on that channel: change the reference."

        val before = identifyBefore
        if (identifyAt > 0L && before != null) {
            val left = 6 - (now - identifyAt) / 1000
            if (left > 0) binding.identifyText.text = "Cover or unscrew ONE antenna now and hold still… $left s"
            else {
                binding.identifyText.text = AntennaCheck.identify(before, AntennaCheck.stats(identifyAt + 1_500, now))
                identifyAt = 0L; identifyBefore = null
            }
        }
    }
}
