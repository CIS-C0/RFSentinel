package com.rfsentinel.app.sdr

import android.content.Context
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Names RTL-SDR hits from RadioReference.com's database (Settings > RTL-SDR radio, off by
 * default): the FCC licences within a few km of you (fccGetProxCallsigns), then each licence's
 * frequencies (fccGetCallsign), so a hit within ± the tolerance gets the licensee's name.
 * Needs the user's own RadioReference Premium login and a RadioReference developer key (both
 * stored encrypted on the phone). What's sent: the login and your position rounded to ~1 km,
 * only when you're somewhere not looked up in the last day. FCC licences cover the US.
 */
object RadioReference {

    const val USER_KEY = "radioreference_user"
    const val PASS_KEY = "radioreference_password"
    const val APPKEY_KEY = "radioreference_appkey"

    private const val ENDPOINT = "https://api.radioreference.com/soap2/index.php"
    private const val NS = "http://api.radioreference.com/soap2"
    /** RadioReference asks to keep proximity searches under ~3 miles. */
    private const val RANGE_KM = 4.5
    private const val MAX_CALLSIGNS = 25
    private const val REFRESH_MS = 24 * 3600_000L

    @Volatile var status: String = ""; private set
    @Volatile private var lastArea: Pair<Double, Double>? = null
    @Volatile private var lastAreaAt = 0L
    @Volatile private var busy = false

    data class Auth(val user: String, val password: String, val appKey: String)

    fun auth(context: Context): Auth? {
        val u = SecureStore.get(context, USER_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val p = SecureStore.get(context, PASS_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val k = SecureStore.get(context, APPKEY_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return Auth(u, p, k)
    }

    fun ready(context: Context) = Prefs.radioReferenceOn(context) && auth(context) != null

    /** ~1 km grid, so the exact position never leaves the phone. */
    fun snap(lat: Double, lon: Double) = Math.round(lat * 100) / 100.0 to Math.round(lon * 100) / 100.0

    /**
     * Looks up the licences around [lat] / [lon] unless that ~1 km area was done in the last day;
     * fills [FreqNames.nearby]. Safe to call on every hit.
     */
    suspend fun refreshAround(context: Context, lat: Double, lon: Double) {
        if (!ready(context) || busy) return
        val area = snap(lat, lon)
        if (area == lastArea && System.currentTimeMillis() - lastAreaAt < REFRESH_MS) return
        val a = auth(context) ?: return
        busy = true
        try {
            withContext(Dispatchers.IO) {
                status = "RadioReference: looking up licences near you…"
                val calls = proxCallsigns(a, area.first, area.second)
                val names = ArrayList<FreqNames.Name>()
                for (c in calls.take(MAX_CALLSIGNS)) {
                    runCatching { callsignFreqs(a, c.callsign) }.getOrNull()?.forEach { hz ->
                        names += FreqNames.Name(hz, "${c.licensee.take(60)} (${c.callsign}, %.1f km)".format(java.util.Locale.US, c.km), "RadioReference")
                    }
                }
                FreqNames.nearby = names
                lastArea = area; lastAreaAt = System.currentTimeMillis()
                status = "RadioReference: ${calls.size} licences, ${names.size} frequencies near you"
            }
        } catch (e: Exception) {
            status = "RadioReference: " + (e.message ?: "lookup failed")
        } finally {
            busy = false
        }
    }

    /** Settings "Test login": one small query; returns a human answer. */
    suspend fun test(context: Context): String = withContext(Dispatchers.IO) {
        val a = auth(context) ?: return@withContext "Enter the username, password and developer key first"
        // Any position checks the login; 0,0 sends nothing about where you are.
        runCatching { proxCallsigns(a, 0.0, 0.0); "Login works" }.getOrElse { it.message ?: "Failed" }
    }

    data class Callsign(val callsign: String, val licensee: String, val km: Double)

    private fun proxCallsigns(a: Auth, lat: Double, lon: Double): List<Callsign> {
        val xml = call("fccGetProxCallsigns",
            "<lat xsi:type=\"xsd:decimal\">$lat</lat><lon xsi:type=\"xsd:decimal\">$lon</lon>" +
                "<range xsi:type=\"xsd:decimal\">$RANGE_KM</range><unit xsi:type=\"xsd:string\">k</unit>", a)
        return parseProx(xml)
    }

    private fun callsignFreqs(a: Auth, callsign: String): List<Long> =
        parseFrequencies(call("fccGetCallsign", "<callsign xsi:type=\"xsd:string\">${esc(callsign)}</callsign>", a))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** One SOAP (rpc / encoded) call; throws with RadioReference's own message on a fault. */
    private fun call(op: String, params: String, a: Auth): String {
        val body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ns1=\"$NS\" " +
            "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\" SOAP-ENV:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<SOAP-ENV:Body><ns1:$op>$params<authInfo xsi:type=\"ns1:authInfo\">" +
            "<username xsi:type=\"xsd:string\">${esc(a.user)}</username><password xsi:type=\"xsd:string\">${esc(a.password)}</password>" +
            "<appKey xsi:type=\"xsd:string\">${esc(a.appKey)}</appKey><version xsi:type=\"xsd:string\">latest</version>" +
            "<style xsi:type=\"xsd:string\">rpc</style></authInfo></ns1:$op></SOAP-ENV:Body></SOAP-ENV:Envelope>"
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", "\"$NS#$op\"")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            val text = stream.use { String(it.readBytes(), Charsets.UTF_8) }
            fault(text)?.let { throw java.io.IOException(it) }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ---- response parsing (pure; unit-tested) ----

    /** The faultstring of a SOAP fault, or null. */
    fun fault(xml: String): String? {
        if (!xml.contains("Fault")) return null
        return Regex("<faultstring[^>]*>(.*?)</faultstring>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
    }

    /** The child elements' text of each <item> in the response (the answers are flat). */
    private fun items(xml: String): List<Map<String, String>> =
        Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { item ->
            Regex("<([A-Za-z0-9_]+)\\b[^>]*>([^<]*)</\\1>").findAll(item.groupValues[1])
                .associate { it.groupValues[1] to unescape(it.groupValues[2].trim()) }
        }.toList()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    fun parseProx(xml: String): List<Callsign> = items(xml).mapNotNull { m ->
        val cs = m["callsign"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val dist = m["distance"]?.toDoubleOrNull() ?: 0.0
        Callsign(cs, m["licensee"]?.takeIf { it.isNotBlank() } ?: cs, dist)
    }.sortedBy { it.km }

    /** The frequencies (MHz → Hz) of an fccGetCallsign answer. */
    fun parseFrequencies(xml: String): List<Long> =
        Regex("<frequency[^>]*>([0-9.]+)</frequency>").findAll(xml)
            .mapNotNull { it.groupValues[1].toDoubleOrNull()?.let { mhz -> (mhz * 1_000_000).toLong() } }
            .filter { it in 25_000_000L..1_800_000_000L }.distinct().toList()
}
