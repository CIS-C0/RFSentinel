package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
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
import com.rfsentinel.app.R
import com.rfsentinel.app.util.applySystemBarInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Antenna check for the USB WiFi adapter (after Wardrive Go's): pick a nearby access point as
 * the reference, the adapter holds its channel, and its frames are compared per antenna (chain 0
 * vs chain 1, with Zero and Identify) and between captures with antenna A and antenna B.
 * With a USB Bluetooth adapter, the reference is a nearby Bluetooth LE device and the swap test
 * compares the antennas (one signal per advertisement, so no Zero / Identify).
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
    /** Auto: the strongest access point becomes the reference once the adapter has visited every channel. */
    private var auto = true
    /** When Auto started listening (the screen opened, or the reference was dropped). */
    private var autoSince = 0L
    /** Checking the USB Bluetooth adapter's antennas instead of the USB WiFi adapter's. */
    private var bt = false

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) } // rows stay put across refreshes, so a tap isn't lost to a rebind
        override fun getItemCount() = aps.size
        override fun getItemId(position: Int) = aps[position].bssid.hashCode().toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ap = aps[position]
            ItemDetectionBinding.bind(holder.itemView).apply {
                labelText.text = ap.ssid ?: if (bt) "Unnamed device" else "Hidden network"
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
        binding.radioGroup.check(R.id.radioWifi)
        binding.radioGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || (id == R.id.radioBt) == bt) return@addOnButtonCheckedListener
            bt = id == R.id.radioBt
            AntennaCheck.useBluetooth(bt)
            binding.modePick.text = if (bt) "Pick device" else "Pick AP"
            pick(null)
        }
        binding.modeGroup.check(R.id.modeAuto)
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            auto = id == R.id.modeAuto
            autoSince = System.currentTimeMillis()
            refresh()
        }
        styleCards()
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
        AntennaCheck.useBluetooth(bt)
        if (ref == null) autoSince = System.currentTimeMillis()
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
        if (ap == null) { AntennaCheck.unlock(); autoSince = System.currentTimeMillis() }
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
        val unit = if (bt) "adverts" else "frames"
        fun line(name: String, s: AntennaCheck.Stats?) = if (s == null) "$name: not captured" else
            "$name: %.1f dBm, %d $unit".format(s.rssi, s.frames) +
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

    /** Rounded cards and chips in the theme's highlight colour. */
    private fun styleCards() {
        val (accent, _) = ChipStyle.accent(this)
        val dp = resources.displayMetrics.density
        fun tint(alpha: Int) = (accent and 0x00FFFFFF) or (alpha shl 24)
        fun card(fillAlpha: Int, strokeAlpha: Int, radius: Float) = GradientDrawable().apply {
            cornerRadius = radius * dp
            setColor(tint(fillAlpha))
            if (strokeAlpha > 0) setStroke((1.2f * dp).toInt().coerceAtLeast(1), tint(strokeAlpha))
        }
        binding.headerCard.background = card(0x14, 0x66, 16f)
        binding.meterCard.background = card(0x14, 0x66, 16f)
        binding.chainVerdict.background = card(0x2E, 0, 12f)
        binding.channelChip.background = card(0x26, 0x99, 12f)
        binding.channelChip.setTextColor(accent)
        binding.meterTitle.setTextColor(accent)
        binding.adapterName.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_car_usb, 0, 0, 0)
        binding.adapterName.compoundDrawableTintList = ColorStateList.valueOf(accent)
    }

    private fun feedDemo() {
        val now = System.currentTimeMillis()
        if (bt) {
            listOf("02:00:00:00:00:11" to "Demo speaker", "02:00:00:00:00:12" to null).forEachIndexed { i, (m, n) ->
                AntennaCheck.heardBt(m, n, -55 - 10 * i, now)
            }
            repeat(3) { AntennaCheck.demoSample(-58 - (0..3).random(), null, null, now) }
            return
        }
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
        val running = ScanForegroundService.isRunning
        val wifiLive = demo || running && UsbWifi.status.contains("live")
        val btLive = demo || running && com.rfsentinel.app.usb.UsbBluetooth.runningChip != null
        // Only one adapter live: the check is on that one; both: the WiFi / Bluetooth buttons choose.
        if (bt && !btLive && wifiLive) binding.radioGroup.check(R.id.radioWifi)
        if (!bt && !wifiLive && btLive) binding.radioGroup.check(R.id.radioBt)
        binding.radioGroup.visibility = if (wifiLive && btLive) View.VISIBLE else View.GONE
        val live = if (bt) btLive else wifiLive
        if (!live) {
            binding.pickPanel.visibility = View.GONE
            binding.checkPanel.visibility = View.GONE
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = "Start a scan with a USB WiFi or Bluetooth adapter plugged in.\n" +
                "The check tests the adapter's antennas, not the phone's own."
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
            if (auto) {
                binding.apList.visibility = View.GONE
                val settle = if (bt) 5_000L else 12_000L
                AntennaCheck.strongest(fresh, now, autoSince, settle, if (bt) 3_000L else 8_000L)?.let { pick(it); return }
                val left = ((autoSince + settle - now) / 1000).coerceAtLeast(0)
                binding.pickHint.text = when {
                    bt && left > 0 -> "Finding the strongest Bluetooth device… $left s"
                    bt -> "No Bluetooth device heard yet: keep the adapter near a speaker, TV or other Bluetooth device."
                    left > 0 -> "Finding the strongest access point… $left s\nThe adapter visits every channel first."
                    else -> "No access point heard yet: keep the adapter near a WiFi network."
                }
                return
            }
            binding.apList.visibility = View.VISIBLE
            binding.pickHint.text = when {
                bt && aps.isEmpty() -> "Listening for Bluetooth devices…"
                bt -> "Tap a strong device that stays put (a speaker, TV or beacon) to use as the reference:"
                aps.isEmpty() -> "Listening for access points… the adapter visits every channel, which can take 15 s."
                else -> "Tap a strong access point that stays put to use as the reference:"
            }
            return
        }
        binding.pickPanel.visibility = View.GONE
        binding.checkPanel.visibility = View.VISIBLE
        val s = AntennaCheck.stats(now - 5_000, now)
        val holding = bt || AntennaCheck.holding == r.channel
        binding.adapterName.text = when {
            demo -> "Demo adapter"
            bt -> com.rfsentinel.app.usb.UsbBluetooth.runningChip?.let { "$it Bluetooth" } ?: "USB Bluetooth adapter"
            else -> UsbWifi.runningChip ?: "USB WiFi adapter"
        }
        binding.channelChip.text = AntennaCheck.channelName(r.channel)
        binding.refTitle.text = (if (auto) "Auto · " else "") + "Reference: ${r.ssid ?: if (bt) "Unnamed device" else "Hidden network"}"
        val stuck = !holding && now - lockedAt > 8_000
        val hold = when {
            bt -> "all advertising channels"
            holding -> "channel held"
            stuck -> "can't hold this channel"
            else -> "switching to its channel…"
        }
        binding.refSub.text = "${AntennaCheck.channelName(r.channel)} · $hold\n" +
            "${s.frames} ${if (bt) "adverts" else "frames"} in the last 5 s" + (s.rssi?.let { " · %.1f dBm".format(it) } ?: "")
        // Bluetooth: one signal per advertisement, so only the swap test applies.
        val chainVis = if (bt) View.GONE else View.VISIBLE
        binding.chainButtons.visibility = chainVis
        binding.identifyText.visibility = chainVis
        binding.zeroHelp.visibility = chainVis
        binding.aboutText.text = if (bt) "This compares how strongly each antenna hears the reference Bluetooth device. " +
            "It doesn't measure SWR or impedance. On a combo adapter WiFi and Bluetooth share the antennas."
            else "This compares how strongly each antenna hears the reference access point. It doesn't measure SWR or " +
            "impedance. While this screen is open the adapter stays on the reference's channel."

        val recent = AntennaCheck.stats(now - 15_000, now)
        val dual = recent.both >= AntennaCheck.MIN_BOTH
        binding.zeroButton.isEnabled = dual
        binding.identifyButton.isEnabled = dual && identifyAt == 0L
        val history = AntennaCheck.history(now)
        binding.meterTitle.text = if (dual) "DUAL ANTENNA · SAME FRAME" else "SIGNAL · ONE READING PER FRAME"
        if (dual && s.a != null && s.b != null) {
            val d = s.delta?.minus(zero ?: 0.0)
            binding.meter.setDual(s.a, s.b, d, zero != null, history)
            binding.chainVerdict.text = if (d != null && s.both >= AntennaCheck.MIN_BOTH) AntennaCheck.chainVerdict(d)
                else "Waiting for more frames with both antennas…"
        } else if (dual) {
            binding.meter.setDual(null, null, null, zero != null, history)
            binding.chainVerdict.text = "No frames from the reference in the last 5 s."
        } else {
            binding.meter.setSingle(s.rssi, history)
            binding.chainVerdict.text = when {
                bt && s.frames >= 5 -> "A Bluetooth adapter reports one signal per advertisement, not one per antenna: use the swap test below."
                bt && now - lockedAt > 10_000 && AntennaCheck.stats(now - 10_000, now).frames == 0 ->
                    "Nothing from the reference in 10 s: it may have changed its address. Change the reference."
                bt -> "Waiting for advertisements from the reference…"
                s.frames >= 5 -> "This adapter reports one signal for these frames, not one per antenna: use the swap test below."
                else -> "Waiting for frames from the reference…"
            }
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
