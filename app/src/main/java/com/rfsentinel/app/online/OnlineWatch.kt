package com.rfsentinel.app.online

import android.content.Context
import android.location.Location
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * The two internet sources, both off by default: police / government aircraft from community ADS-B
 * feeds (every 60 s), and Waze reports ([WazeWatch]: through the user's OpenWeb Ninja key or straight
 * from Waze, as often as Settings says). Aircraft send a position rounded to ~1 km. New sightings go to
 * [onAlert]; the strongest current one feeds the threat headline ([AmbientThreats]); [aircraftStatus] /
 * [wazeStatus] say how each source is doing (shown in Settings).
 */
class OnlineWatch(
    private val context: Context,
    private val scope: CoroutineScope,
    private val location: () -> Location?,
    onCallout: (String) -> Unit = {},
    private val onAlert: (Alert) -> Unit
) {
    /** A new sighting for the service to log and, if it clears the alert threshold, to alert on. */
    data class Alert(
        val hit: Hit,
        val lat: Double,
        val lon: Double,
        val key: String,
        /** What to say aloud; null for the short word of the hit. */
        val spoken: String? = null,
        /** Sound and voice, a notification only, or nothing beyond the log. */
        val level: WazePolice.Level = WazePolice.Level.LOUD,
        /** False for a sighting already logged that only now clears the alert threshold. */
        val log: Boolean = true
    )

    /** An answer other than 200. */
    internal class HttpError(val code: Int) : Exception("HTTP $code")

    private var aircraftJob: Job? = null
    private val loiter = PoliceAircraft.LoiterTracker()
    private val lastAircraftHit = HashMap<String, Long>()
    private val waze = WazeWatch(context, scope, location, onAlert, onCallout)

    /** Starts or stops each source to match Settings (call on every scan (re)start and when Settings closes). */
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
        waze.sync()
    }

    fun stop() {
        aircraftJob?.cancel()
        aircraftJob = null
        waze.stop()
        AmbientThreats.clear()
        PoliceAircraft.latest = emptyList()
    }

    private fun pollAircraft() {
        val me = location() ?: run { aircraftStatus = "Waiting for a GPS fix"; return }
        val registry = PoliceAircraft.load(context)
        var lastError: String? = null
        val radiusKm = Prefs.aircraftRadiusKm(context)
        val json = PoliceAircraft.feedUrls(me.latitude, me.longitude, radiusKm).firstNotNullOfOrNull { url ->
            runCatching { httpGet(url, emptyMap()) }.onFailure { lastError = it.message }.getOrNull()
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
                    onAlert(Alert(hit, p.lat, p.lon, "air:" + p.hex))
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

    private fun clock(t: Long) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))

    companion object {
        const val WAZE_KEY_NAME = "openwebninja_api_key"
        private const val AIRCRAFT_MS = 60_000L
        private const val AIRCRAFT_REPEAT_MS = 20 * 60_000L

        @Volatile var aircraftStatus = ""
        @Volatile var wazeStatus = ""

        /** A plain GET returning the body as text; throws [HttpError] for any answer other than 200. */
        internal fun httpGet(url: String, headers: Map<String, String>): String {
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
    }
}
