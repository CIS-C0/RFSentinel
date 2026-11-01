package com.rfsentinel.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.rfsentinel.app.R
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

    /**
     * [rssi] and [distanceM] are null while it isn't heard; [trend] 1 getting closer, -1 moving away, 0 steady.
     * [sweep]: the on-foot sweep's hint while locating on a phone with a compass.
     */
    data class Read(
        val mode: Mode, val mac: String, val title: String, val tag: String?, val color: Int,
        val rssi: Int?, val distanceM: Double?, val trend: Int, val sweep: String? = null
    )

    private const val TICK_MS = 1_000L
    /** Quick enough to catch nearly every packet of a fast-advertising device during a brisk turn. */
    private const val SAMPLE_MS = 50L
    /** Walked this far since the lock: the direction is old, sweep again. */
    const val SWEEP_STALE_M = 25f
    /** Heard within this: its signal is live. */
    private const val HEARD_MS = 10_000L
    /** A pinpointed device unheard this long gives way to another flagged one. */
    private const val PINPOINT_SWITCH_MS = 15_000L
    /** The pinpointing card hides after this on one device (pinpointing goes on), so a parked one doesn't keep it up. */
    private const val PINPOINT_CARD_MS = 60_000L
    private const val NO_FLAG_COLOR = 0xFF9AA5AD.toInt()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Read?) -> Unit>()

    // ---- Locate (main thread) ----
    /** The address Locate was started on, null when off. */
    @Volatile var locating: String? = null
        private set
    /** The address it is heard under now (it changes when the device rotates its address). */
    private var followed: String? = null
    private var appContext: Context? = null

    // ---- Sweep (main thread): compass heading + each new signal reading while locating ----
    private val sweep = Sweep()
    /** The way the phone faces (true north, flat or upright), shared with the radar. */
    private var compass: Compass? = null
    /** Compass degrees the phone faces, null without a compass or before its first reading. */
    val heading get() = compass?.degrees
    private var sweepFed = 0L
    private var tickAt = 0L
    private var tickHeading: Float? = null
    /** The direction the sweep locked on (kept until Sweep again or Locate stops), and where you stood then. */
    private var lock: Sweep.Estimate? = null
    private var lockedAt: android.location.Location? = null

    /** The sweep as the Locate guide draws it. [moved]: metres walked since it locked. */
    class SweepState(val heading: Float?, val sectors: Array<Float?>, val covered: Int,
                     val estimate: Sweep.Estimate?, val locked: Sweep.Estimate?, val moved: Float?)

    // ---- Pinpoint (the scan's pipeline thread) ----
    private var pinMac: String? = null
    private var pinHeard = 0L
    /** When [pinMac] became the one read. */
    @Volatile private var pinSince = 0L
    @Volatile private var pinUntil = 0L
    /** The pinpointed device whose card was dismissed (it comes back for another device or the next pinpointing). */
    @Volatile private var dismissedPin: String? = null

    /** Starts Locate on [mac]: beeps that speed up as the signal gets stronger. Main thread. */
    fun startLocate(context: Context, mac: String) {
        stopLocate()
        appContext = context.applicationContext
        locating = mac
        followed = null
        sweep.clear(); sweepFed = 0L; lock = null; lockedAt = null
        compass = Compass(context).also { c ->
            com.rfsentinel.app.service.ScanForegroundService.lastFix?.let { c.setLocation(it.latitude, it.longitude, it.altitude) }
            c.start()
        }
        Motion.start(context)
        main.post(beep)
        main.post(sample)
        tick()
    }

    /** Forgets the sweep and its lock, for a fresh turn (somewhere new, or a lock that looks wrong). Main thread. */
    fun sweepAgain() {
        sweep.clear(); sweepFed = 0L; lock = null; lockedAt = null
        tick()
    }

    /** What the Locate guide shows, null when not locating. Main thread. */
    fun sweepState(now: Long = System.currentTimeMillis()): SweepState? {
        if (locating == null) return null
        val mac = locating ?: return null
        // Placed (it stays put as you walk): no need to sweep again.
        val moved = if (RadarTrack.place(mac, now) != null) null
            else lockedAt?.let { a -> com.rfsentinel.app.service.ScanForegroundService.lastFix?.distanceTo(a) }
        return SweepState(heading, sweep.sectors(now), sweep.covered(now), sweep.estimate(now), held(now), moved)
    }

    /** The lock, turned to where the device is from where you stand now once [RadarTrack] placed it. */
    private fun held(now: Long): Sweep.Estimate? {
        val l = lock ?: return null
        val mac = locating ?: return l
        return RadarTrack.place(mac, now)?.let { l.copy(bearing = it.bearing) } ?: l
    }

    /** The locked direction (compass degrees) of [mac] while it is being located, for the radar. Main thread. */
    fun sweptBearing(mac: String): Float? {
        val m = locating ?: return null
        return held(System.currentTimeMillis())?.bearing?.takeIf { mac == m || mac == followed }
    }

    /** Main thread. */
    fun stopLocate() {
        main.removeCallbacks(beep)
        main.removeCallbacks(sample)
        val was = locating != null
        locating = null; followed = null
        compass?.stop(); compass = null
        if (was) { Motion.stop(); tick() }
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
            AlertPlayer.locateTick(ctx)
            // Faster as the signal gets stronger (same curve as the radar beeps).
            main.postDelayed(this, Beeper.tickIntervalMs(s.rssi))
        }
    }

    /**
     * Twenty times a second: each new raw signal reading with the way the phone faced when it was heard. Locks the
     * direction once the turn makes it clear and it holds, with a short buzz.
     */
    private val sample = object : Runnable {
        override fun run() {
            val mac = locating ?: return
            main.postDelayed(this, SAMPLE_MS)
            val h = heading ?: return
            val now = System.currentTimeMillis()
            val t0 = tickAt; val h0 = tickHeading ?: h
            tickAt = now; tickHeading = h
            val (seen, a) = DeviceRegistry.lastHeard(followed ?: mac) ?: return
            if (seen == sweepFed) return
            sweepFed = seen
            val facing = Sweep.headingAt(seen, t0, h0, now, h)
            sweep.add(seen, facing, a.rssi)
            RadarTrack.reading(mac, seen, facing, a)
            if (lock != null) return
            val e = sweep.settled(seen) ?: return
            lock = e
            lockedAt = com.rfsentinel.app.service.ScanForegroundService.lastFix
            RadarTrack.line(mac, e.bearing, e.confidence, seen)
            appContext?.let { AlertPlayer.vibrate(it, longArrayOf(0, 60, 90, 60)) }
            tick()
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
        if (cur == null || cur == mac || now > pinUntil || now - pinHeard > PINPOINT_SWITCH_MS) {
            if (cur != mac || now > pinUntil) pinSince = now
            pinMac = mac; pinHeard = now
        }
        val starting = now > pinUntil
        pinUntil = until
        if (starting) main.post { tick() }
    }

    /** The scan stopped: no Locate, no pinpointing. */
    fun reset() = main.post {
        stopLocate()
        RadarTrack.clear()
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
        locating?.let { m -> return read(Mode.LOCATE, m, now).copy(sweep = sweepText(now)) }
        val p = synchronized(this) { pinMac } ?: return null
        if (now > pinUntil || p == dismissedPin || now - pinSince > PINPOINT_CARD_MS) return null
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

    /** The sweep's hint while locating: how to turn, then the locked direction. Null without a compass. Main thread. */
    fun sweepText(now: Long = System.currentTimeMillis()): String? {
        val h = heading ?: return null
        val l = held(now) ?: return "Phone on your chest, turn slowly: ${sweep.covered(now) * 360 / Sweep.SECTORS}° of 360°"
        val moved = sweepState(now)?.moved?.takeIf { it > SWEEP_STALE_M }
        val far = locating?.let { RadarTrack.place(it, now) }?.let { " · ~" + DeviceIntel.formatDistance(it.distanceM.toDouble()) }.orEmpty()
        return "${Sweep.compassPoint(l.bearing)}$far · ${turnText(Sweep.turn(h, l.bearing))} · " +
            if (moved != null) "you moved, sweep again" else "${(l.confidence * 100).toInt()}% sure"
    }

    /** "ahead", "turn right 40°" or "turn left 40°". */
    fun turnText(turn: Int) = when {
        kotlin.math.abs(turn) <= 20 -> "ahead"
        turn > 0 -> "turn right $turn°"
        else -> "turn left ${-turn}°"
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
        b.liveSweep.text = r.sweep
        b.liveSweep.visibility = if (r.sweep == null) View.GONE else View.VISIBLE
        val level = VisualMode.dbmLevel(r.rssi)
        b.liveBar.set(level, VisualMode.heat(level))
        b.liveClose.contentDescription = if (r.mode == LiveRead.Mode.LOCATE) "Stop locating" else "Hide the live read"
        b.root.visibility = View.VISIBLE
    }
}

/**
 * The live read card in a small window of its own over other apps (under the floating map, beside the threat
 * bubble), shown while there is a read. [place] sets the window's gravity, x, y and width for a card [height]
 * tall; call [replace] when what it sits by moves. [wanted]: false while another overlay shows the card.
 */
internal class LiveReadOverlay(
    private val context: Context,
    private val wanted: () -> Boolean = { true },
    private val place: (lp: WindowManager.LayoutParams, height: Int) -> Unit
) {
    private var b: ViewLiveReadBinding? = null
    private var lp: WindowManager.LayoutParams? = null
    private var card: LiveReadCard? = null
    private var stopListening: (() -> Unit)? = null
    private val wm get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun start() { if (stopListening == null) stopListening = LiveRead.addListener { show(it) } }

    fun stop() {
        stopListening?.invoke(); stopListening = null
        remove()
    }

    fun replace() {
        val b = b ?: return; val lp = lp ?: return
        place(lp, b.root.height.takeIf { it > 0 } ?: (84 * context.resources.displayMetrics.density).toInt())
        runCatching { wm.updateViewLayout(b.root, lp) }
    }

    private fun show(r: LiveRead.Read?) {
        if (r == null || !wanted()) { remove(); return }
        if (b == null) {
            val nb = ViewLiveReadBinding.inflate(LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_RFSentinel)))
            // A little smaller than on the full map: it sits beside another app.
            nb.liveTitle.textSize = 14f; nb.liveDetail.textSize = 12f; nb.liveSweep.textSize = 12f
            nb.root.elevation = 6f * context.resources.displayMetrics.density
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val nlp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            )
            if (runCatching { wm.addView(nb.root, nlp) }.isFailure) return
            b = nb; lp = nlp; card = LiveReadCard(nb)
            // Placed again once its height is known (it may go above what it sits by when there's no room below).
            nb.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) replace()
            }
        }
        card?.show(r)
        replace()
    }

    private fun remove() {
        val v = b ?: return
        runCatching { wm.removeView(v.root) }
        b = null; lp = null; card = null
    }
}
