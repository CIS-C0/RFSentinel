package com.rfsentinel.app.online

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/**
 * Where a WiFi network name (SSID) or access point address (BSSID) has been seen, from WiGLE's
 * database of wardriving uploads (api.wigle.net, network/search). Needs the user's own WiGLE
 * API name and token (wigle.net/account), stored encrypted on this phone. What's sent: the name
 * or address searched and, for a search near you, a box around your position rounded to ~10 km.
 * WiGLE gives new accounts only a few searches a day, so each answer is kept here for a day.
 */
object Wigle {

    const val NAME_KEY = "wigle_api_name"
    const val TOKEN_KEY = "wigle_api_token"

    private const val ENDPOINT = "https://api.wigle.net/api/v2/network/search"
    /** A search near you covers this far around you. */
    const val NEAR_KM = 50.0
    private const val RESULTS = 25
    private const val CACHE_FILE = "wigle_cache.json"
    private const val CACHE_MS = 24 * 3600_000L
    private const val CACHE_MAX = 200

    /** One access point WiGLE knows: [lat] / [lon] is its estimated position. */
    data class Net(
        val ssid: String?,
        val bssid: String,
        val lat: Double,
        val lon: Double,
        val encryption: String?,
        val channel: Int?,
        /** When WiGLE last saw it, as WiGLE writes it (e.g. "2025-06-01T12:00:00.000Z"). */
        val lastSeen: String?,
        /** Road, city, region and country, when WiGLE has them. */
        val place: String?
    )

    data class Answer(val nets: List<Net>, val total: Int, val near: Boolean, val cached: Boolean)

    class Failure(message: String) : Exception(message)

    private data class Cached(val at: Long, val total: Int, val nets: List<Net>)

    private val gson = Gson()
    private var cache: MutableMap<String, Cached>? = null

    fun hasAccount(context: Context) = auth(context) != null

    private fun auth(context: Context): String? {
        val name = SecureStore.get(context, NAME_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val token = SecureStore.get(context, TOKEN_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "Basic " + Base64.encodeToString("$name:$token".toByteArray(), Base64.NO_WRAP)
    }

    /** True for an access point address: 12 hex digits, with or without ':' / '-' between pairs. */
    fun isBssid(text: String): Boolean = BSSID.matches(text.trim())

    private val BSSID = Regex("(?i)^[0-9a-f]{2}([:-]?[0-9a-f]{2}){5}$")

    /** "aa-bb-cc..." or "aabbcc..." as "AA:BB:CC:DD:EE:FF". */
    fun bssid(text: String): String =
        text.trim().replace(":", "").replace("-", "").uppercase(Locale.US).chunked(2).joinToString(":")

    /** ~10 km grid: the exact position never leaves the phone. */
    fun snap(lat: Double, lon: Double) = Math.round(lat * 10) / 10.0 to Math.round(lon * 10) / 10.0

    /**
     * The search URL: an address by itself, a name exactly (or with * as a wildcard), within
     * [NEAR_KM] of [near] when given.
     */
    internal fun url(text: String, near: Pair<Double, Double>?): String {
        val q = StringBuilder(ENDPOINT).append("?onlymine=false&resultsPerPage=").append(RESULTS)
        val t = text.trim()
        when {
            isBssid(t) -> q.append("&netid=").append(enc(bssid(t)))
            '*' in t -> q.append("&ssidlike=").append(enc(t.replace('*', '%')))
            else -> q.append("&ssid=").append(enc(t))
        }
        if (near != null) {
            val (lat, lon) = near
            val dLat = NEAR_KM / 111.0
            val dLon = NEAR_KM / (111.0 * max(cos(Math.toRadians(lat)), 0.05))
            fun f(v: Double) = String.format(Locale.US, "%.3f", v)
            q.append("&latrange1=").append(f((lat - dLat).coerceIn(-90.0, 90.0)))
                .append("&latrange2=").append(f((lat + dLat).coerceIn(-90.0, 90.0)))
                .append("&longrange1=").append(f((lon - dLon).coerceIn(-180.0, 180.0)))
                .append("&longrange2=").append(f((lon + dLon).coerceIn(-180.0, 180.0)))
        }
        return q.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** WiGLE's answer: how many it has in all, and the ones sent (those without a position left out). */
    internal fun parse(json: String): Pair<Int, List<Net>> {
        val o = JSONObject(json)
        if (!o.optBoolean("success", false)) throw Failure(o.optString("message").ifBlank { "WiGLE couldn't search" })
        val arr = o.optJSONArray("results") ?: return 0 to emptyList()
        val nets = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = r.optDouble("trilat", Double.NaN); val lon = r.optDouble("trilong", Double.NaN)
            val id = r.optString("netid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return@mapNotNull null
            fun s(k: String) = r.optString(k).takeIf { it.isNotBlank() && it != "null" && it != "?" }
            Net(s("ssid"), id.uppercase(Locale.US), lat, lon, s("encryption"), r.optInt("channel", 0).takeIf { it > 0 },
                s("lastupdt") ?: s("lasttime"),
                listOfNotNull(s("road"), s("city"), s("region"), s("country")).distinct().joinToString(", ").ifEmpty { null })
        }
        return max(o.optInt("totalResults", nets.size), nets.size) to nets
    }

    /**
     * Searches WiGLE for [text] (a name, or an address), within [NEAR_KM] of [near] when given
     * (rounded first). The same search within a day is answered from this phone.
     */
    suspend fun search(context: Context, text: String, near: Pair<Double, Double>?): Answer = withContext(Dispatchers.IO) {
        val area = near?.let { snap(it.first, it.second) }
        val key = (if (isBssid(text)) bssid(text) else text.trim()) + "|" + (area?.let { "${it.first},${it.second}" } ?: "world")
        val now = System.currentTimeMillis()
        cached(context, key, now)?.let { return@withContext Answer(it.nets, it.total, area != null, cached = true) }
        val authHeader = auth(context) ?: throw Failure("Enter your WiGLE API name and token first")
        val c = (URL(url(text, area)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Authorization", authHeader)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
        }
        val body = try {
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                401, 403 -> throw Failure("WiGLE didn't accept the API name and token")
                429 -> throw Failure("WiGLE's search limit for today is used up (new accounts get only a few searches a day)")
                else -> throw Failure("WiGLE answered HTTP ${c.responseCode}")
            }
        } catch (e: java.io.IOException) {
            throw Failure("Couldn't reach WiGLE: ${e.message ?: "no connection"}")
        } finally {
            c.disconnect()
        }
        val (total, nets) = parse(body)
        remember(context, key, Cached(now, total, nets))
        Answer(nets, total, area != null, cached = false)
    }

    @Synchronized
    private fun cached(context: Context, key: String, now: Long): Cached? =
        load(context)[key]?.takeIf { now - it.at < CACHE_MS }

    @Synchronized
    private fun remember(context: Context, key: String, value: Cached) {
        val m = load(context)
        m[key] = value
        val now = System.currentTimeMillis()
        m.entries.removeAll { now - it.value.at >= CACHE_MS }
        while (m.size > CACHE_MAX) m.remove(m.entries.minByOrNull { it.value.at }!!.key)
        runCatching { File(context.filesDir, CACHE_FILE).writeText(gson.toJson(m)) }
    }

    private fun load(context: Context): MutableMap<String, Cached> = cache ?: run {
        val m = runCatching {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) null
            else gson.fromJson<Map<String, Cached>>(f.readText(), object : TypeToken<Map<String, Cached>>() {}.type)
        }.getOrNull().orEmpty().toMutableMap()
        cache = m
        m
    }
}
