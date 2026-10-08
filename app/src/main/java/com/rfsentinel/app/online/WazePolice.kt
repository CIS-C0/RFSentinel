package com.rfsentinel.app.online

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.online.wazert.WazeRtFetcher
import com.rfsentinel.app.service.DeviceRegistry
import java.time.Instant
import kotlin.math.abs

/**
 * Reports from Waze users (police by default, other event types if the user enables them),
 * read either through OpenWeb Ninja's Waze API with the user's own API key, or (opt-in)
 * straight from Waze over the app's own protocol (see [WazeRtFetcher]). Off by default;
 * third-party services RF Sentinel doesn't run or endorse.
 * Scored by distance and age: a fresh report next to you is a probable sign, one far away
 * or 40 minutes old only a weak one.
 */
object WazePolice {

    /** What an alert of one event type does: all of it, a notification only, or nothing beyond the list and map. */
    enum class Level(val label: String) {
        LOUD("Sound and voice"),
        NOTIFY("Notification only"),
        LOG("Silent (list and map)")
    }

    /** The event kinds the user can pick under "What to alert on". Police is the default. */
    enum class Type(
        val label: String, val wire: List<String>, val color: Int, val baseScore: Double, val defaultLevel: Level
    ) {
        POLICE("Police", listOf("POLICE"), 0xFF1E88E5.toInt(), 90.0, Level.LOUD),
        ACCIDENT("Accidents", listOf("ACCIDENT"), 0xFFE53935.toInt(), 75.0, Level.LOUD),
        HAZARD("Hazards", listOf("HAZARD", "NEW_BAD_WEATHER"), 0xFFFB8C00.toInt(), 70.0, Level.NOTIFY),
        ROAD_CLOSED("Road closures", listOf("ROAD_CLOSED", "SYSTEM_ROAD_CLOSED", "TURN_CLOSED", "NEW_LANE_CLOSED"), 0xFF6D4C41.toInt(), 70.0, Level.NOTIFY),
        JAM("Traffic jams", listOf("JAM"), 0xFF8E24AA.toInt(), 65.0, Level.LOG);

        val singular: String get() = when (this) {
            POLICE -> "Police"; ACCIDENT -> "Accident"; HAZARD -> "Hazard"; ROAD_CLOSED -> "Road closure"; JAM -> "Traffic jam"
        }

        companion object {
            fun ofWire(w: String?): Type? = entries.firstOrNull { t -> t.wire.any { it.equals(w, ignoreCase = true) } }
            fun parse(names: Set<String>): Set<Type> = entries.filter { it.name in names }.toSet()
            /** The type a hit is about, from its label ("Accident reported on Waze (major)"). */
            fun ofHit(hit: Hit): Type? = entries.firstOrNull { hit.label.startsWith(it.singular) }
        }
    }

    data class Report(
        val id: String,
        val lat: Double,
        val lon: Double,
        val publishedMs: Long?,
        val street: String?,
        val city: String?,
        val thumbsUp: Int,
        val reliability: Int?,
        val type: Type = Type.POLICE,
        /** Waze's own name for it, e.g. POLICE_HIDING; see [subtypeLabel]. */
        val subtype: String? = null
    )

    /** Default search radius: about 2 km around you (Settings can change it). */
    const val RADIUS_M = 2_000.0
    /** Reports older than this are ignored. */
    const val MAX_AGE_MS = 45 * 60_000L

    // ---- where a report is, relative to you -------------------------------------------------

    enum class Side { AHEAD, BEHIND, LEFT, RIGHT }

    /**
     * [distanceM] and the [compass] point from you to the report; [side] and [relDeg] (-180..180,
     * 0 = straight ahead) are known only while you are driving, i.e. with a heading and some speed.
     */
    data class Where(val distanceM: Double, val compass: String, val side: Side?, val relDeg: Float?) {
        /** Clearly behind (more than 100 degrees off your heading); false when the heading is unknown. */
        val isBehind: Boolean get() = relDeg != null && abs(relDeg) > 100f
    }

    /** Works out [Where] from plain numbers; [heading] is null when the phone is not driving. */
    fun where(meLat: Double, meLon: Double, heading: Double?, lat: Double, lon: Double): Where {
        val d = DeviceRegistry.metersBetween(meLat, meLon, lat, lon)
        val p1 = Math.toRadians(meLat)
        val p2 = Math.toRadians(lat)
        val dl = Math.toRadians(lon - meLon)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        val compass = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((bearing + 22.5) / 45.0).toInt() % 8]
        val rel = heading?.let { (((bearing - it + 540.0) % 360.0) - 180.0).toFloat() }
        val side = rel?.let {
            when {
                abs(it) <= 45f -> Side.AHEAD
                abs(it) >= 135f -> Side.BEHIND
                it > 0 -> Side.RIGHT
                else -> Side.LEFT
            }
        }
        return Where(d, compass, side, rel)
    }

    /** [where] from a fix: the heading counts only while the phone is moving (over about 5 km/h). */
    fun where(me: android.location.Location, lat: Double, lon: Double): Where =
        where(me.latitude, me.longitude, if (me.hasBearing() && me.hasSpeed() && me.speed > 1.5f) me.bearing.toDouble() else null, lat, lon)

    /** Exact distance: whole metres under 1 km, then km to 10 m. */
    fun preciseDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else String.format(java.util.Locale.US, "%.2f km", m / 1000)

    /** "412 m NE · ahead · closing in": distance, compass point, side while driving, and whether it is getting closer. */
    fun relativeText(w: Where, trend: WazeTrend.Trend = WazeTrend.Trend.STEADY): String {
        val side = when (w.side) {
            Side.AHEAD -> " · ahead"
            Side.BEHIND -> " · behind you"
            Side.RIGHT -> " · to your right"
            Side.LEFT -> " · to your left"
            null -> ""
        }
        val moving = when {
            trend == WazeTrend.Trend.CLOSING -> " · closing in"
            trend == WazeTrend.Trend.AWAY && w.side != Side.BEHIND -> " · moving away"
            else -> ""
        }
        return "${preciseDistance(w.distanceM)} ${w.compass}$side$moving"
    }

    /** Where a report is relative to a fix, as text (no trend). */
    fun relative(me: android.location.Location, lat: Double, lon: Double): String = relativeText(where(me, lat, lon))

    // ---- what the screens show --------------------------------------------------------------

    /** One report as the list and map show it: how it scored, where it is now, and whether it is getting closer. */
    data class Shown(
        val report: Report,
        val hit: Hit,
        val where: Where,
        val trend: WazeTrend.Trend = WazeTrend.Trend.STEADY,
        val level: Level = Level.LOUD
    ) {
        val distanceM: Double get() = where.distanceM
    }

    /** The current reports (every enabled type, inside the view range). */
    @Volatile var latest: List<Shown> = emptyList()
        private set
    /** When the last check answered. */
    @Volatile var latestAt = 0L
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Calls [onChange] (on the polling thread) every time the list of reports changes in a way worth
     * redrawing at once, so a screen need not wait for its next tick. Returns a function that removes it.
     */
    fun addListener(onChange: () -> Unit): () -> Unit {
        listeners.add(onChange)
        return { listeners.remove(onChange) }
    }

    /** Replaces the current reports and tells every open screen. */
    fun publish(reports: List<Shown>, at: Long) {
        latest = reports
        latestAt = at
        for (l in listeners) runCatching { l() }
    }

    /** Refreshes distances and scores once a second without waking the listeners: the screens redraw on their own ticks. */
    fun updateQuiet(reports: List<Shown>) {
        latest = reports
    }

    // ---- the poller's state, for the "check now" button and the status screen ---------------

    /** True while the Waze checks are running (scanning, Waze on, not paused). */
    @Volatile var running = false
    /** When the last check began (0 = none yet). */
    @Volatile var lastCheckStartedAt = 0L
    /** Set by "Check now"; the poller runs a check as soon as it sees a newer value. */
    @Volatile var checkNowAt = 0L
        private set

    /**
     * Asks the poller to check right now. Returns null when asked, or how many seconds to wait:
     * manual checks are spaced out so Waze is not hammered and OpenWeb Ninja does not bill a burst.
     */
    fun requestCheckNow(minGapS: Int, now: Long = System.currentTimeMillis()): Int? {
        val since = now - lastCheckStartedAt
        if (lastCheckStartedAt != 0L && since < minGapS * 1000L) return ((minGapS * 1000L - since + 999) / 1000).toInt()
        checkNowAt = now
        return null
    }

    // ---- fetching ----------------------------------------------------------------------------

    fun url(lat: Double, lon: Double, radiusM: Double = RADIUS_M, types: Set<Type> = setOf(Type.POLICE)): String {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * kotlin.math.max(0.01, kotlin.math.cos(Math.toRadians(lat))))
        fun f(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
        // OpenWeb Ninja filters alerts by these names; jams are a separate list there, so JAM is not sent.
        val names = types.filter { it != Type.JAM }.ifEmpty { listOf(Type.POLICE) }.joinToString(",") { it.name }
        return "https://api.openwebninja.com/waze/alerts-and-jams?bottom_left=${f(lat - dLat)},${f(lon - dLon)}" +
            "&top_right=${f(lat + dLat)},${f(lon + dLon)}&alert_types=$names&max_alerts=200&max_jams=0"
    }

    /** Alerts of the wanted types from the API's JSON (re-filtered here in case the server ignores the type filter). */
    internal fun parse(json: String, types: Set<Type> = setOf(Type.POLICE)): List<Report> {
        val root = JsonParser.parseString(json).asJsonObject
        val data = root.getAsJsonObject("data") ?: return emptyList()
        val alerts = data.getAsJsonArray("alerts") ?: return emptyList()
        return alerts.mapNotNull { e ->
            val o = e.asJsonObject
            val type = Type.ofWire(o.str("type"))?.takeIf { it in types } ?: return@mapNotNull null
            Report(
                id = o.str("alert_id") ?: return@mapNotNull null,
                lat = o.num("latitude") ?: return@mapNotNull null,
                lon = o.num("longitude") ?: return@mapNotNull null,
                publishedMs = o.str("publish_datetime_utc")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                street = o.str("street"),
                city = o.str("city"),
                thumbsUp = o.num("num_thumbs_up")?.toInt() ?: 0,
                reliability = o.num("alert_reliability")?.toInt(),
                type = type,
                subtype = o.str("subtype")
            )
        }
    }

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }

    private fun JsonObject.num(k: String): Double? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    // ---- scoring -----------------------------------------------------------------------------

    /** Points lost between the base score (within 100 m) and the edge of the alert radius. */
    private const val EDGE_DROP = 20.0
    /** Most points a report loses as it ages over [MAX_AGE_MS]. */
    private const val MAX_DECAY = 8.0

    /**
     * 0-100: the type's base score (90 for police) within 100 m, falling by 20 at the edge of the
     * alert radius; minus up to 8 points as the report ages over 45 minutes; plus up to 6 for other
     * drivers confirming it.
     */
    fun score(distanceM: Double, ageMs: Long, thumbsUp: Int, radiusM: Double = RADIUS_M, base: Double = 90.0): Int {
        val byDistance = when {
            distanceM <= 100 -> base
            distanceM >= radiusM -> base - EDGE_DROP
            else -> base - EDGE_DROP * (distanceM - 100) / (radiusM - 100)
        }
        val decay = MAX_DECAY * (ageMs.coerceIn(0, MAX_AGE_MS).toDouble() / MAX_AGE_MS)
        val trust = (thumbsUp * 2).coerceAtMost(6)
        return (byDistance - decay + trust).toInt().coerceIn(0, 100)
    }

    /** A police alert from the direct (Waze app protocol) backend. Its thumbs-up count is the crowd-trust signal. */
    internal fun fromDirect(a: WazeRtFetcher.PoliceAlert) = Report(
        id = a.uuid, lat = a.lat, lon = a.lon,
        publishedMs = a.pubMillis.takeIf { it > 0 },
        street = a.street?.ifBlank { null }, city = a.city?.ifBlank { null },
        thumbsUp = a.thumbsUp, reliability = null,
        type = Type.ofWire(a.type) ?: Type.POLICE,
        subtype = a.subtype?.ifBlank { null }
    )

    // ---- readable names for Waze's subtypes --------------------------------------------------

    private val SUBTYPE_WORDS = mapOf(
        "POLICE_VISIBLE" to "visible",
        "POLICE_HIDING" to "hiding",
        "POLICE_WITH_MOBILE_CAMERA" to "mobile speed camera",
        "ACCIDENT_MINOR" to "minor",
        "ACCIDENT_MAJOR" to "major",
        "JAM_LIGHT_TRAFFIC" to "light",
        "JAM_MODERATE_TRAFFIC" to "moderate",
        "JAM_HEAVY_TRAFFIC" to "heavy",
        "JAM_STAND_STILL_TRAFFIC" to "standstill",
        "HAZARD_ON_ROAD" to "on the road",
        "HAZARD_ON_SHOULDER" to "on the shoulder",
        "HAZARD_WEATHER" to "weather",
        "HAZARD_ON_ROAD_OBJECT" to "object on the road",
        "HAZARD_ON_ROAD_POT_HOLE" to "pothole",
        "HAZARD_ON_ROAD_ROAD_KILL" to "animal on the road",
        "HAZARD_ON_ROAD_LANE_CLOSED" to "lane closed",
        "HAZARD_ON_ROAD_OIL" to "oil on the road",
        "HAZARD_ON_ROAD_ICE" to "ice on the road",
        "HAZARD_ON_ROAD_CONSTRUCTION" to "construction",
        "HAZARD_ON_ROAD_CAR_STOPPED" to "car stopped on the road",
        "HAZARD_ON_ROAD_TRAFFIC_LIGHT_FAULT" to "traffic light fault",
        "HAZARD_ON_ROAD_EMERGENCY_VEHICLE" to "emergency vehicle",
        "HAZARD_ON_SHOULDER_CAR_STOPPED" to "car stopped on the shoulder",
        "HAZARD_ON_SHOULDER_ANIMALS" to "animals on the shoulder",
        "HAZARD_ON_SHOULDER_MISSING_SIGN" to "missing sign",
        "HAZARD_WEATHER_FOG" to "fog",
        "HAZARD_WEATHER_HAIL" to "hail",
        "HAZARD_WEATHER_HEAVY_RAIN" to "heavy rain",
        "HAZARD_WEATHER_HEAVY_SNOW" to "heavy snow",
        "HAZARD_WEATHER_FLOOD" to "flood",
        "HAZARD_WEATHER_FREEZING_RAIN" to "freezing rain",
        "ROAD_CLOSED_HAZARD" to "hazard",
        "ROAD_CLOSED_CONSTRUCTION" to "construction",
        "ROAD_CLOSED_EVENT" to "event"
    )

    private val TYPE_PREFIXES = listOf("POLICE_", "ACCIDENT_", "JAM_", "HAZARD_", "ROAD_CLOSED_", "LANE_CLOSURE_", "BAD_WEATHER_")

    /** "hiding", "mobile speed camera", "heavy"...; null when Waze gave no subtype. */
    fun subtypeLabel(subtype: String?): String? {
        val s = subtype?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        if (s == "NO_SUBTYPE" || s.startsWith("__NOT_IN_USE")) return null
        SUBTYPE_WORDS[s]?.let { return it }
        val prefix = TYPE_PREFIXES.firstOrNull { s.startsWith(it) }
        return (if (prefix != null) s.removePrefix(prefix) else s).lowercase().replace('_', ' ').ifEmpty { null }
    }

    const val VIA_NINJA = "OpenWeb Ninja"
    const val VIA_DIRECT = "Waze direct"

    /**
     * [radiusM] is the alert radius: the score falls from its base by 20 across it. [maxM] is how
     * far away a report is still kept (the map's view range), so changing the view never changes a score.
     */
    fun hit(r: Report, lat: Double, lon: Double, now: Long, via: String = VIA_NINJA, radiusM: Double = RADIUS_M, maxM: Double = radiusM * 1.5): Pair<Hit, Double>? {
        val d = DeviceRegistry.metersBetween(lat, lon, r.lat, r.lon)
        if (d > maxM) return null
        val age = r.publishedMs?.let { now - it } ?: 0L
        if (age > MAX_AGE_MS) return null
        val where = listOfNotNull(r.street, r.city).joinToString(", ").ifEmpty { "nearby" }
        val ago = r.publishedMs?.let { " ${(age / 60_000).coerceAtLeast(0)} min ago" } ?: ""
        val dist = if (d < 1000) "${(d / 10).toInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", d / 1000)
        val sub = subtypeLabel(r.subtype)?.let { " ($it)" } ?: ""
        return Hit(Category.POLICE_REPORT, "${r.type.singular} reported on Waze$sub", score(d, age, r.thumbsUp, radiusM, r.type.baseScore),
            "Reported$ago on $where, about $dist away" +
                (if (r.thumbsUp > 0) " (${r.thumbsUp} driver${if (r.thumbsUp == 1) "" else "s"} confirmed)" else "") +
                ". Crowd report via $via - not verified.",
            "Waze user reports via $via (third party)") to d
    }
}
