package com.rfsentinel.app.online

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.DeviceRegistry
import java.time.Instant

/**
 * Police reported by Waze users, read through OpenWeb Ninja's Waze API with the user's
 * own API key (off by default; a third-party service RF Sentinel doesn't run or endorse).
 * Scored by distance and age: a fresh report next to you is a probable sign, one a
 * kilometre away or 40 minutes old only a weak one.
 */
object WazePolice {

    data class Report(
        val id: String,
        val lat: Double,
        val lon: Double,
        val publishedMs: Long?,
        val street: String?,
        val city: String?,
        val thumbsUp: Int,
        val reliability: Int?
    )

    /** Search box half-size: about 2 km around you. */
    const val RADIUS_M = 2_000.0
    /** Reports older than this are ignored. */
    const val MAX_AGE_MS = 45 * 60_000L

    fun url(lat: Double, lon: Double): String {
        val dLat = RADIUS_M / 111_000.0
        val dLon = RADIUS_M / (111_000.0 * kotlin.math.max(0.01, kotlin.math.cos(Math.toRadians(lat))))
        fun f(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
        return "https://api.openwebninja.com/waze/alerts-and-jams?bottom_left=${f(lat - dLat)},${f(lon - dLon)}" +
            "&top_right=${f(lat + dLat)},${f(lon + dLon)}&alert_types=POLICE&max_alerts=50&max_jams=0"
    }

    /** POLICE alerts from the API's JSON (re-filtered here in case the server ignores the type filter). */
    internal fun parse(json: String): List<Report> {
        val root = JsonParser.parseString(json).asJsonObject
        val data = root.getAsJsonObject("data") ?: return emptyList()
        val alerts = data.getAsJsonArray("alerts") ?: return emptyList()
        return alerts.mapNotNull { e ->
            val o = e.asJsonObject
            if (!o.str("type").equals("POLICE", ignoreCase = true)) return@mapNotNull null
            Report(
                id = o.str("alert_id") ?: return@mapNotNull null,
                lat = o.num("latitude") ?: return@mapNotNull null,
                lon = o.num("longitude") ?: return@mapNotNull null,
                publishedMs = o.str("publish_datetime_utc")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                street = o.str("street"),
                city = o.str("city"),
                thumbsUp = o.num("num_thumbs_up")?.toInt() ?: 0,
                reliability = o.num("alert_reliability")?.toInt()
            )
        }
    }

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }

    private fun JsonObject.num(k: String): Double? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    /**
     * 0-100: 70 within 100 m, falling to 40 at 2 km; minus up to 12 points as the report
     * ages over 45 minutes; plus up to 6 for other drivers confirming it.
     */
    fun score(distanceM: Double, ageMs: Long, thumbsUp: Int): Int {
        val byDistance = when {
            distanceM <= 100 -> 70.0
            distanceM >= RADIUS_M -> 40.0
            else -> 70.0 - 30.0 * (distanceM - 100) / (RADIUS_M - 100)
        }
        val decay = 12.0 * (ageMs.coerceIn(0, MAX_AGE_MS).toDouble() / MAX_AGE_MS)
        val trust = (thumbsUp * 2).coerceAtMost(6)
        return (byDistance - decay + trust).toInt().coerceIn(0, 100)
    }

    fun hit(r: Report, lat: Double, lon: Double, now: Long): Pair<Hit, Double>? {
        val d = DeviceRegistry.metersBetween(lat, lon, r.lat, r.lon)
        if (d > RADIUS_M * 1.5) return null
        val age = r.publishedMs?.let { now - it } ?: 0L
        if (age > MAX_AGE_MS) return null
        val where = listOfNotNull(r.street, r.city).joinToString(", ").ifEmpty { "nearby" }
        val ago = r.publishedMs?.let { " ${(age / 60_000).coerceAtLeast(0)} min ago" } ?: ""
        val dist = if (d < 1000) "${(d / 10).toInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", d / 1000)
        return Hit(Category.POLICE_REPORT, "Police reported on Waze", score(d, age, r.thumbsUp),
            "Reported$ago on $where, about $dist away" +
                (if (r.thumbsUp > 0) " (${r.thumbsUp} driver${if (r.thumbsUp == 1) "" else "s"} confirmed)" else "") +
                ". Crowd report via OpenWeb Ninja - not verified.",
            "Waze user reports via OpenWeb Ninja (third party)") to d
    }
}
