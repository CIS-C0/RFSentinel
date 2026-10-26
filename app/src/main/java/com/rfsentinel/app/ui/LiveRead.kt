package com.rfsentinel.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.rfsentinel.app.data.DeviceNames
import com.rfsentinel.app.databinding.ViewLiveReadBinding
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.AlertPlayer
import com.rfsentinel.app.util.Beeper
import com.rfsentinel.app.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The live read of the device being followed - by Locate (the device screen's beeps) or by pinpointing a flagged
 * device - for the card over the map and under the floating map ([LiveReadCard]): what it is, how strong, how far.
 * Locate keeps beeping when you leave the device screen to look at the map, until Stop, the card's ✕, the scan
 * stopping or the device going out of range.
 */
object LiveRead {

    enum class Mode(val label: String) { LOCATE("LOCATING"), PINPOINT("PINPOINTING") }

    /** [rssi] and [distanceM] are null while it isn't heard; [trend] 1 getting closer, -1 moving away, 0 steady. */
    data class Read(
        val mode: Mode, val mac: String, val title: String, val tag: String?, val color: Int,
        val rssi: Int?, val distanceM: Double?, val trend: Int
    )

    private const val TICK_MS = 1_000L
    /** Heard within this: its signal is live. */
    private const val HEARD_MS = 10_000L
    /** A pinpointed device unheard this long gives way to another flagged one. */
    private const val PINPOINT_SWITCH_MS = 15_000L
    private const val NO_FLAG_COLOR = 0xFF9AA5AD.toInt()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Read?) -> Unit>()

    // ---- Locate (main thread) ----
    /** The address Locate was started on, null when off. */
    @Volatile var locating: String? = null
        private set
    /** The address it is heard under now (it changes when the device rotates its address). */
    private var followed: String? = null
    private var tone: ToneGenerator? = null
    private var appContext: Context? = null

    // ---- Pinpoint (the scan's pipeline thread) ----
    private var pinMac: String? = null
    private var pinHeard = 0L
    @Volatile private var pinUntil = 0L
    /** The pinpointed device whose card was dismissed (it comes back for another device or the next pinpointing). */
    @Volatile private var dismissedPin: String? = null

    /** Starts Locate on [mac]: beeps that speed up as the signal gets stronger. Main thread. */
    fun startLocate(context: Context, mac: String) {
        stopLocate()
        appContext = context.applicationContext
        locating = mac
        followed = null
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        main.post(beep)
        tick()
    }

    /** Main thread. */
    fun stopLocate() {
        main.removeCallbacks(beep)
        val was = locating != null
        locating = null; followed = null
        tone?.release(); tone = null
        if (was) tick()
    }

    private val beep = object : Runnable {
        override fun run() {
            val mac = locating ?: return
            val ctx = appContext ?: return
            // Keeps following it when it changes its address (a tag rotating its random address).
            val liveMac = DeviceRegistry.currentAddress(mac)
            if (liveMac != followed) {
                if (followed != null) Toast.makeText(ctx, "It changed its address - still following it", Toast.LENGTH_SHORT).show()
                followed = liveMac
            }
            // Out of range (gone from the list): Locate ends, as on the device screen.
            val s = DeviceRegistry.get(liveMac) ?: DeviceRegistry.get(mac) ?: run { stopLocate(); return }
            if (!AlertPlayer.locateTick(ctx)) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            // Faster as the signal gets stronger (same curve as the radar beeps).
            main.postDelayed(this, Beeper.tickIntervalMs(s.rssi))
        }
    }

    /**
     * A flagged device was heard while pinpointing is on (any thread): it becomes the one read, unless the one
     * already read was heard in the last [PINPOINT_SWITCH_MS]. [until]: when pinpointing ends unless heard again.
     */
    @Synchronized
    fun pinpoint(mac: String, now: Long, until: Long) {
        val cur = pinMac
        if (now > pinUntil) dismissedPin = null // a new round of pinpointing
        if (cur == null || cur == mac || now > pinUntil || now - pinHeard > PINPOINT_SWITCH_MS) { pinMac = mac; pinHeard = now }
        val starting = now > pinUntil
        pinUntil = until
        if (starting) main.post { tick() }
    }

    /** The scan stopped: no Locate, no pinpointing. */
    fun reset() = main.post {
        stopLocate()
        synchronized(this) { pinMac = null; pinUntil = 0L; dismissedPin = null }
        tick()
    }

    /** The card's ✕: ends Locate, or hides the card for the device being pinpointed. Main thread. */
    fun dismiss() {
        if (locating != null) { stopLocate(); return }
        dismissedPin = synchronized(this) { pinMac }
        tick()
    }

    /** What to show now, or null for no card. */
    fun current(now: Long = System.currentTimeMillis()): Read? {
        locating?.let { m -> return read(Mode.LOCATE, m, now) }
        val p = synchronized(this) { pinMac } ?: return null
        if (now > pinUntil || p == dismissedPin) return null
        return read(Mode.PINPOINT, p, now)
    }

    private fun read(mode: Mode, mac: String, now: Long): Read {
        val liveMac = DeviceRegistry.currentAddress(mac)
        val s = DeviceRegistry.get(liveMac) ?: DeviceRegistry.get(mac)
        val hit = s?.best
        val heard = s != null && now - s.lastSeen <= HEARD_MS
        return Read(
            mode, liveMac,
            title = DeviceNames.of(mac) ?: hit?.label ?: s?.name ?: s?.deviceType ?: mac,
            tag = hit?.category?.shortTag,
            color = hit?.category?.colorArgb ?: NO_FLAG_COLOR,
            rssi = s?.rssi?.takeIf { heard },
            distanceM = s?.distanceM?.takeIf { heard && it > 0 },
            trend = if (heard) trend(s!!) else 0
        )
    }

    /** 1 getting closer, -1 moving away, 0 steady: the newest reading against the few before it. */
    fun trend(s: DeviceRegistry.Snapshot): Int {
        val recent = s.history.takeLast(6).dropLast(1)
        if (recent.isEmpty()) return 0
        val avg = recent.map { it.rssi }.average()
        return when {
            s.rssi > avg + 3 -> 1
            s.rssi < avg - 3 -> -1
            else -> 0
        }
    }

    /** Calls [onRead] now and about once a second while there is something to read (null once it ends). Main thread. */
    fun addListener(onRead: (Read?) -> Unit): () -> Unit {
        listeners.add(onRead)
        tick()
        return { listeners.remove(onRead) }
    }

    private val ticker = Runnable { tick() }

    private fun tick() {
        main.removeCallbacks(ticker)
        if (listeners.isEmpty()) return
        val r = current()
        listeners.forEach { runCatching { it(r) } }
        if (r != null || locating != null || System.currentTimeMillis() <= pinUntil) main.postDelayed(ticker, TICK_MS)
    }
}

/**
 * The live read card: a rounded dark card like the map's alert card, with what is being followed, a signal bar
 * and the rough distance, and a ✕ ([LiveRead.dismiss]). Respects Discreet mode (no name, no flag).
 */
internal class LiveReadCard(private val b: ViewLiveReadBinding) {
    private val ctx = b.root.context

    init {
        b.liveClose.imageTintList = ColorStateList.valueOf(0xFFB0BAC1.toInt())
        b.liveClose.setOnClickListener { LiveRead.dismiss() }
        b.liveBar.track = 0x33FFFFFF
    }

    fun show(r: LiveRead.Read?) {
        if (r == null) { b.root.visibility = View.GONE; return }
        val discreet = Prefs.discreetMode(ctx)
        b.liveContext.text = r.mode.label
        b.liveTag.text = if (discreet) "" else r.tag.orEmpty()
        b.liveTag.setTextColor(r.color)
        b.liveTitle.text = if (discreet) "Live read" else r.title
        b.liveDetail.text = if (r.rssi == null) "Not heard right now" else listOfNotNull(
            "${r.rssi} dBm",
            r.distanceM?.let { DeviceIntel.formatDistance(it) + " (rough)" },
            when (r.trend) { 1 -> "▲ closer"; -1 -> "▼ farther"; else -> null }
        ).joinToString(" · ")
        val level = VisualMode.dbmLevel(r.rssi)
        b.liveBar.set(level, VisualMode.heat(level))
        b.liveClose.contentDescription = if (r.mode == LiveRead.Mode.LOCATE) "Stop locating" else "Hide the live read"
        b.root.visibility = View.VISIBLE
    }
}
