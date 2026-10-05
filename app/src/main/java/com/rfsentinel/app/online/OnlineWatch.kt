package com.rfsentinel.app.online

import android.content.Context
import android.location.Location
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * The two internet sources, both off by default: police / government aircraft from
 * community ADS-B feeds (every 60 s) and Waze police reports through the user's
 * OpenWeb Ninja key (every 4 min). Each sends only a position rounded to ~1 km (ADS-B)
 * or a ~4 km box (Waze). New sightings go to [onHit]; the strongest current one feeds
 * the threat headline ([AmbientThreats]); [aircraftStatus] / [wazeStatus] say how each
 * source is doing (shown in Settings).
 */
class OnlineWatch(
    private val context: Context,
    private val scope: CoroutineScope,
    private val location: () -> Location?,
    private val onHit: (hit: Hit, lat: Double, lon: Double, key: String) -> Unit
) {
    private var aircraftJob: Job? = null
    private var wazeJob: Job? = null
    private val loiter = PoliceAircraft.LoiterTracker()
    private val lastAircraftHit = HashMap<String, Long>()
    private val wazeSeen = HashMap<String, Long>()
    private var wazeKey: String? = null

    /** Starts or stops each source to match Settings (call on every scan (re)start). */
    fun sync() {
        val wantAir = Prefs.categoryEnabled(context, Category.AIRCRAFT)
        if (wantAir && aircraftJob?.isActive != true) {
            aircraftJob = scope.launch(Dispatchers.IO) {
                while (isActive) { runCatching { pollAircraft() }.onFailure { aircraftStatus = "Error: ${it.message}" }; delay(AIRCRAFT_MS) }
            }
        } else if (!wantAir) {
            aircraftJob?.cancel(); aircraftJob = null; AmbientThreats.aircraft = null; aircraftStatus = ""
            PoliceAircraft.latest = emptyList()
        }
        val wantWaze = Prefs.categoryEnabled(context, Category.POLICE_REPORT) && Prefs.wazeAccepted(context)
        // A new or changed key is tried right away, not at the next 4-minute round.
        val key = SecureStore.get(context, WAZE_KEY_NAME)
        if (key != wazeKey) { wazeKey = key; wazeJob?.cancel(); wazeJob = null }
        if (wantWaze && wazeJob?.isActive != true) {
            wazeJob = scope.launch(Dispatchers.IO) {
                while (isActive) { runCatching { pollWaze() }.onFailure { wazeStatus = "Error: ${it.message}" }; delay(WAZE_MS) }
            }
        } else if (!wantWaze) {
            wazeJob?.cancel(); wazeJob = null; AmbientThreats.waze = null; wazeStatus = ""
        }
    }

    fun stop() {
        aircraftJob?.cancel(); wazeJob?.cancel()
        aircraftJob = null; wazeJob = null
        AmbientThreats.clear()
        PoliceAircraft.latest = emptyList()
    }

    private fun pollAircraft() {
        val me = location() ?: run { aircraftStatus = "Waiting for a GPS fix"; return }
        val registry = PoliceAircraft.load(context)
        var lastError: String? = null
        val radiusKm = Prefs.aircraftRadiusKm(context)
        val json = PoliceAircraft.feedUrls(me.latitude, me.longitude, radiusKm).firstNotNullOfOrNull { url ->
            runCatching { get(url, emptyMap()) }.onFailure { lastError = it.message }.getOrNull()
        } ?: run { aircraftStatus = "ADS-B feeds unreachable" + (lastError?.let { " ($it)" } ?: ""); return }
        val planes = PoliceAircraft.parseFeed(json)
        val now = System.currentTimeMillis()
        var best: Hit? = null
        val seen = ArrayList<PoliceAircraft.Seen>(planes.size)
        synchronized(loiter) {
            for (p in planes) {
                val circling = loiter.update(p, now)
                val hit = PoliceAircraft.classify(p, registry, circling, PoliceAircraft.distance(p, me.latitude, me.longitude))
                seen += PoliceAircraft.Seen(p, hit)
                if (hit == null) continue
                if (best == null || hit.confidence > best!!.confidence) best = hit
                // One report per aircraft per 20 minutes (or sooner if it starts circling).
                val last = lastAircraftHit[p.hex] ?: 0L
                if (now - last >= AIRCRAFT_REPEAT_MS) {
                    lastAircraftHit[p.hex] = now
                    onHit(hit, p.lat, p.lon, "air:" + p.hex)
                }
            }
            loiter.prune(now)
            lastAircraftHit.entries.removeAll { now - it.value > AIRCRAFT_REPEAT_MS }
        }
        PoliceAircraft.latest = seen
        PoliceAircraft.latestAt = now
        AmbientThreats.aircraft = best?.let { now to AmbientThreats.Threat(it.confidence, it.label, Category.AIRCRAFT) }
        aircraftStatus = "Updated ${clock(now)}: ${planes.size} aircraft within $radiusKm km" +
            (best?.let { " · ${it.label}" } ?: ", none police")
    }

    private fun pollWaze() {
        val key = SecureStore.get(context, WAZE_KEY_NAME)
        if (key.isNullOrBlank()) { wazeStatus = "Add your OpenWeb Ninja API key"; return }
        val me = location() ?: run { wazeStatus = "Waiting for a GPS fix"; return }
        val json = try {
            get(WazePolice.url(me.latitude, me.longitude), mapOf("x-api-key" to key))
        } catch (e: HttpError) {
            wazeStatus = when (e.code) {
                401, 403 -> "API key rejected (HTTP ${e.code})"
                429 -> "OpenWeb Ninja limit reached (HTTP 429)"
                else -> "OpenWeb Ninja answered HTTP ${e.code}"
            }
            return
        }
        val now = System.currentTimeMillis()
        val hits = WazePolice.parse(json).mapNotNull { r -> WazePolice.hit(r, me.latitude, me.longitude, now)?.let { r to it.first } }
        for ((r, hit) in hits) {
            if (r.id in wazeSeen) continue
            wazeSeen[r.id] = now
            onHit(hit, r.lat, r.lon, "waze:" + r.id)
        }
        wazeSeen.entries.removeAll { now - it.value > WazePolice.MAX_AGE_MS * 2 }
        val best = hits.maxByOrNull { it.second.confidence }?.second
        AmbientThreats.waze = best?.let { now to AmbientThreats.Threat(it.confidence, it.label, Category.POLICE_REPORT) }
        wazeStatus = "Updated ${clock(now)}: ${hits.size} police report${if (hits.size == 1) "" else "s"} within 2 km"
    }

    private class HttpError(val code: Int) : Exception("HTTP $code")

    private fun get(url: String, headers: Map<String, String>): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (c.responseCode != 200) throw HttpError(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun clock(t: Long) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))

    companion object {
        const val WAZE_KEY_NAME = "openwebninja_api_key"
        private const val AIRCRAFT_MS = 60_000L
        private const val WAZE_MS = 4 * 60_000L
        private const val AIRCRAFT_REPEAT_MS = 20 * 60_000L

        @Volatile var aircraftStatus = ""
        @Volatile var wazeStatus = ""
    }
}
