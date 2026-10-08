package com.rfsentinel.app.online

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.online.wazert.WazeRtFetcher
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.SecureStore
import com.rfsentinel.app.util.Spoken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the Waze status screen shows about the checks, kept by [WazeWatch]. */
object WazeStatus {
    @Volatile var direct = false
    /** The interval chosen in Settings, and what it is right now after adapting to how the phone moves. */
    @Volatile var baseS = 0
    @Volatile var everyS = 0
    @Volatile var motion = MotionTracker.State.MOVING
    @Volatile var nextCheckAt = 0L
    @Volatile var lastCheckAt = 0L
    @Volatile var lastCheckMs = 0L
    @Volatile var checks = 0
    /** From the last answer: reports on the map, and how many of them are inside the alert range. */
    @Volatile var onMap = 0
    @Volatile var inAlertRange = 0
}

/**
 * The Waze source. It asks Waze (or OpenWeb Ninja) for the reports around you at the interval set in
 * Settings, and then, every second, works out from your position right now how far each one is, whether
 * it is ahead of you and getting closer, and what to do about it: a new alert the moment one comes inside
 * the alert range (not only at the next check), and spoken call-outs as it gets closer.
 */
class WazeWatch(
    private val context: Context,
    private val scope: CoroutineScope,
    private val location: () -> Location?,
    private val onAlert: (OnlineWatch.Alert) -> Unit,
    private val onCallout: (String) -> Unit
) {
    private var job: Job? = null
    /** Bumped whenever the checks start or stop, so a cancelled loop that is still winding down cannot undo a newer one. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var jobKey: String? = null
    private val motion = MotionTracker()
    private val trend = WazeTrend()
    /** First seen (and logged) reports, and those that have had an alert. */
    private val seen = HashMap<String, Long>()
    private val alerted = HashSet<String>()
    /** The smallest call-out distance already spoken for each report. */
    private val calledBand = HashMap<String, Int>()
    private var known: Map<String, WazePolice.Report> = emptyMap()
    private var via = WazePolice.VIA_DIRECT
    private var lastOkAt = 0L
    private var shownIds: Set<String> = emptySet()
    private var direct = false
    private var wazeDirect: WazeRtFetcher? = null
    private var wazeDirectRegion = ""

    /** Starts or stops the checks to match Settings (call on every scan (re)start and when Settings closes). */
    fun sync() {
        val want = Prefs.categoryEnabled(context, Category.POLICE_REPORT) && Prefs.wazeReady(context)
        val wantDirect = Prefs.wazeBackend(context) == Prefs.WAZE_DIRECT
        // A new or changed key, or a switch of backend, is tried right away, not at the next round.
        val key = if (wantDirect) "direct" else SecureStore.get(context, OnlineWatch.WAZE_KEY_NAME)
        if (key != jobKey) { jobKey = key; job?.cancel(); job = null }
        if (want && job?.isActive != true) {
            direct = wantDirect
            val gen = generation.incrementAndGet()
            job = scope.launch(Dispatchers.IO) { loop(gen) }
        } else if (!want) {
            job?.cancel(); job = null
            clear()
        }
    }

    fun stop() {
        job?.cancel(); job = null
        clear()
    }

    private fun clear() {
        generation.incrementAndGet()
        known = emptyMap()
        shownIds = emptySet()
        AmbientThreats.waze = null
        OnlineWatch.wazeStatus = ""
        WazePolice.running = false
        WazePolice.publish(emptyList(), 0L)
    }

    private suspend fun loop(gen: Int) {
        WazePolice.running = true
        var lastStart = 0L
        var handledNow = WazePolice.checkNowAt
        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                location()?.let { motion.update(it.latitude, it.longitude, if (it.hasSpeed()) it.speed else null, now) }
                if (Prefs.wazePaused(context)) {
                    if (known.isNotEmpty()) { known = emptyMap(); AmbientThreats.waze = null; WazePolice.publish(emptyList(), now) }
                    OnlineWatch.wazeStatus = "Paused - open Waze status to resume"
                    WazeStatus.nextCheckAt = 0L
                    delay(1_000)
                    continue
                }
                val every = effectiveS(now)
                WazeStatus.direct = direct
                val sinceStart = now - lastStart
                val manual = WazePolice.checkNowAt > handledNow
                if (manual) handledNow = WazePolice.checkNowAt
                val due = WazeTiming.checkDue(now, lastStart, every, manual, WazeTiming.minGapS(direct))
                if (due) {
                    lastStart = now
                    val began = SystemClock.elapsedRealtime()
                    val asked = runCatching { if (direct) pollDirect() else pollNinja() }
                        .onFailure { OnlineWatch.wazeStatus = "Error: ${it.message}" }.getOrDefault(true)
                    if (asked) {
                        WazePolice.lastCheckStartedAt = now
                        WazeStatus.lastCheckAt = now
                        WazeStatus.lastCheckMs = SystemClock.elapsedRealtime() - began
                        WazeStatus.checks++
                    } else {
                        // No GPS fix or key yet: look again in a few seconds instead of waiting out the interval.
                        lastStart = now - every * 1000L + RETRY_MS
                    }
                }
                WazeStatus.nextCheckAt = lastStart + every * 1000L
                evaluate(System.currentTimeMillis(), publish = false)
                delay(1_000)
            }
        } finally {
            if (generation.get() == gen) WazePolice.running = false
        }
    }

    /** The seconds between checks right now: the chosen interval, adapted to whether the phone is parked or on a fast road. */
    private fun effectiveS(now: Long): Int {
        val base = Prefs.wazeIntervalS(context, direct)
        val state = motion.state(now)
        val every = WazeTiming.effectiveS(base, state, direct, Prefs.wazeAdaptive(context))
        WazeStatus.baseS = base
        WazeStatus.everyS = every
        WazeStatus.motion = state
        return every
    }

    // ---- the two ways of asking ---------------------------------------------------------------

    /** Returns false when nothing could be asked yet (no key or GPS fix), so the loop retries soon. */
    private fun pollNinja(): Boolean {
        val key = SecureStore.get(context, OnlineWatch.WAZE_KEY_NAME)
        if (key.isNullOrBlank()) { OnlineWatch.wazeStatus = "Add your OpenWeb Ninja API key"; return false }
        val me = location() ?: run { OnlineWatch.wazeStatus = "Waiting for a GPS fix"; return false }
        val types = WazePolice.Type.parse(Prefs.wazeTypes(context)).ifEmpty { setOf(WazePolice.Type.POLICE) }
        val reach = maxOf(Prefs.wazeViewKm(context) * 1000.0, Prefs.wazeAlertM(context).toDouble())
        val json = try {
            OnlineWatch.httpGet(WazePolice.url(me.latitude, me.longitude, reach, types), mapOf("x-api-key" to key))
        } catch (e: OnlineWatch.HttpError) {
            OnlineWatch.wazeStatus = when (e.code) {
                401, 403 -> "API key rejected (HTTP ${e.code})"
                429 -> "OpenWeb Ninja limit reached (HTTP 429)"
                else -> "OpenWeb Ninja answered HTTP ${e.code}"
            }
            return true
        }
        ingest(WazePolice.parse(json, types), WazePolice.VIA_NINJA, System.currentTimeMillis())
        return true
    }

    /** The Waze RT host region for a position: Americas, Israel, or the rest of the world. */
    private fun wazeRegion(lat: Double, lon: Double) = when {
        lon in -170.0..-30.0 -> "na"
        lat in 29.0..33.5 && lon in 34.0..36.0 -> "il"
        else -> "row"
    }

    private fun fetcherFor(region: String): WazeRtFetcher {
        if (wazeDirect == null || wazeDirectRegion != region) {
            wazeDirect = WazeRtFetcher(context, region); wazeDirectRegion = region
        }
        return wazeDirect!!
    }

    /** Direct backend: alerts of the chosen types straight from Waze over the app protocol. */
    private fun pollDirect(): Boolean {
        val me = location() ?: run { OnlineWatch.wazeStatus = "Waiting for a GPS fix"; return false }
        val types = WazePolice.Type.parse(Prefs.wazeTypes(context)).ifEmpty { setOf(WazePolice.Type.POLICE) }
        val reach = maxOf(Prefs.wazeViewKm(context) * 1000.0, Prefs.wazeAlertM(context).toDouble())
        val alerts = try {
            // A box wider than the view range, so reports ahead of you are already loaded.
            fetcherFor(wazeRegion(me.latitude, me.longitude))
                .fetchAlertsNear(me.latitude, me.longitude, reach * 1.2, types.flatMap { it.wire }.toSet())
        } catch (e: Exception) {
            OnlineWatch.wazeStatus = "Waze direct: ${e.message ?: e.javaClass.simpleName}"
            return true
        }
        ingest(alerts.map { WazePolice.fromDirect(it) }, WazePolice.VIA_DIRECT, System.currentTimeMillis())
        return true
    }

    // ---- what to do with the reports ----------------------------------------------------------

    /** A check answered: these are now the known reports. */
    @Synchronized
    internal fun ingest(reports: List<WazePolice.Report>, source: String, now: Long) {
        known = reports.associateBy { it.id }
        via = source
        lastOkAt = now
        evaluate(now, publish = true)
    }

    /**
     * Works out, from the position right now, which known reports are in view, how far and in which
     * direction each is, and which deserve an alert or a call-out. Run once a second, and after each check.
     * With [publish] false the screens are woken only when something they show has changed.
     */
    @Synchronized
    internal fun evaluate(now: Long, publish: Boolean) {
        // Failing checks for a long while: better to show nothing than reports that may be long gone.
        if (known.isNotEmpty() && now - lastOkAt > staleAfterMs(now)) known = emptyMap()
        val me = location() ?: return
        val alertM = Prefs.wazeAlertM(context).toDouble()
        val viewM = maxOf(Prefs.wazeViewKm(context) * 1000.0, alertM) // never narrower than the alert range
        val aheadOnly = Prefs.wazeAheadOnly(context)
        val approachOn = Prefs.wazeApproach(context)
        val threshold = Prefs.alertThreshold(context)
        val levels = WazePolice.Type.entries.associateWith { Prefs.wazeLevel(context, it) }

        val shown = ArrayList<WazePolice.Shown>(known.size)
        for (r in known.values) {
            val hit = WazePolice.hit(r, me.latitude, me.longitude, now, via, alertM, viewM)?.first ?: continue
            val w = WazePolice.where(me, r.lat, r.lon)
            trend.update(r.id, w.distanceM, now)
            shown += WazePolice.Shown(r, hit, w, trend.trend(r.id), levels.getValue(r.type))
        }
        trend.keepOnly(known.keys)

        // In the alert range, and (if asked) not behind you.
        fun counts(s: WazePolice.Shown) = s.distanceM <= alertM && !(aheadOnly && s.where.isBehind)

        var fired = false
        for (s in shown) {
            if (!counts(s)) continue
            val id = s.report.id
            val first = id !in seen
            if (first) seen[id] = now
            val eligible = s.hit.confidence >= threshold
            if (first || (eligible && id !in alerted)) {
                if (eligible) alerted += id
                fired = true
                calledBand.putIfAbsent(id, bandFor(s.distanceM))
                onAlert(OnlineWatch.Alert(
                    s.hit, s.report.lat, s.report.lon, "waze:$id",
                    Spoken.waze(context, s.report.type, s.distanceM, s.where.side), s.level, log = first
                ))
            } else if (approachOn && s.level == WazePolice.Level.LOUD && eligible &&
                s.trend == WazeTrend.Trend.CLOSING && s.where.side != WazePolice.Side.BEHIND
            ) {
                val band = bandFor(s.distanceM)
                if (band < (calledBand[id] ?: Int.MAX_VALUE)) {
                    calledBand[id] = band
                    onCallout(Spoken.wazePhrase(s.report.type, s.distanceM, s.where.side, short = true))
                }
            }
        }
        seen.entries.removeAll { now - it.value > WazePolice.MAX_AGE_MS * 2 }
        alerted.retainAll(seen.keys)
        calledBand.keys.retainAll(seen.keys)

        // The threat headline: the strongest report that counts and is allowed to be loud enough to matter.
        val headline = shown.filter { counts(it) && it.level != WazePolice.Level.LOG }.maxByOrNull { it.hit.confidence }
        AmbientThreats.waze = headline?.let { now to AmbientThreats.Threat(it.hit.confidence, it.hit.label, Category.POLICE_REPORT) }
        AmbientThreats.wazeTtlMs = 20_000L // refreshed every second while the checks run

        val ids = shown.mapTo(HashSet()) { it.report.id }
        val changed = ids != shownIds
        shownIds = ids
        if (publish) {
            val every = WazeTiming.effectiveS(Prefs.wazeIntervalS(context, via == WazePolice.VIA_DIRECT), motion.state(now), via == WazePolice.VIA_DIRECT, Prefs.wazeAdaptive(context))
            val inRange = shown.count { counts(it) }
            WazeStatus.onMap = shown.size
            WazeStatus.inAlertRange = inRange
            OnlineWatch.wazeStatus = "Updated ${clock(now)}: ${shown.size} on the map (within ${Prefs.formatRange(viewM.toInt())}), " +
                "$inRange in alert range (${Prefs.formatRange(alertM.toInt())}) · every ${Prefs.formatInterval(every)}" +
                (if (every != Prefs.wazeIntervalS(context, via == WazePolice.VIA_DIRECT)) " (adapted)" else "")
            WazePolice.publish(shown, now)
        } else if (changed || fired) {
            WazeStatus.onMap = shown.size
            WazeStatus.inAlertRange = shown.count { counts(it) }
            WazePolice.publish(shown, WazePolice.latestAt)
        } else {
            WazePolice.updateQuiet(shown)
        }
    }

    /** Reports are dropped when no check has answered for this long (a little over three intervals, never under 10 minutes). */
    private fun staleAfterMs(now: Long) = maxOf(10 * 60_000L, WazeTiming.effectiveS(
        Prefs.wazeIntervalS(context, direct), motion.state(now), direct, Prefs.wazeAdaptive(context)) * 3_500L)

    private fun clock(t: Long) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))

    private companion object {
        /** After a skipped check (no fix or key yet), look again this soon. */
        const val RETRY_MS = 5_000L
        /** The distances at which a report coming closer is called out again. */
        val APPROACH_BANDS_M = intArrayOf(200, 500, 1000)

        /** The smallest call-out distance a report is already inside (none: a very large number). */
        fun bandFor(distanceM: Double): Int = APPROACH_BANDS_M.firstOrNull { distanceM <= it } ?: Int.MAX_VALUE
    }
}
