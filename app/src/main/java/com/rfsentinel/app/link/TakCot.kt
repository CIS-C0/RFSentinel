package com.rfsentinel.app.link

import android.content.Context
import android.util.Log
import com.rfsentinel.app.util.ExportText
import com.rfsentinel.app.util.Prefs
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Settings › Sentinel Link › Send matches to TAK: each logged match goes out as a Cursor on Target event (UDP) so it
 * shows on ATAK / WinTAK / iTAK. By default to TAK's situational-awareness multicast, which ATAK on this phone or the
 * same WiFi listens to; or to a host:port the user sets. The marker is where this phone was, not the device itself.
 */
object TakCot {
    private const val TAG = "TakCot"
    const val DEFAULT_TARGET = "239.2.3.1:6969"
    /** A device's marker is refreshed at most this often; it goes stale (greyed) after [STALE_MS]. */
    private const val GAP_MS = 30_000L
    private const val STALE_MS = 10 * 60_000L

    private val io = Executors.newSingleThreadExecutor { Thread(it, "rf-tak").apply { isDaemon = true } }
    private val lastSent = ConcurrentHashMap<String, Long>()

    /** "host:port" or "host" (port 6969); null when it isn't one. shortcut: no IPv6 literals, add "[v6]:port" if asked. */
    fun parse(text: String): Pair<String, Int>? {
        val m = Regex("""([A-Za-z0-9.-]+)(?::(\d{1,5}))?""").matchEntire(text.trim()) ?: return null
        val port = m.groupValues[2].ifEmpty { "6969" }.toInt().takeIf { it in 1..65535 } ?: return null
        return m.groupValues[1] to port
    }

    /** Sends [uid]'s marker when TAK sharing is on and there's a position. */
    fun send(context: Context, uid: String, label: String, category: String, confidence: Int, lat: Double?, lon: Double?,
             air: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (!Prefs.takOn(context) || lat == null || lon == null || lat.isNaN() || lon.isNaN()) return
        if (now - (lastSent[uid] ?: 0L) < GAP_MS) return
        lastSent[uid] = now
        val target = parse(Prefs.takTarget(context)) ?: return
        val xml = event(uid, label, category, confidence, lat, lon, air, now).toByteArray()
        io.execute {
            runCatching {
                DatagramSocket().use { s -> s.send(DatagramPacket(xml, xml.size, InetAddress.getByName(target.first), target.second)) }
            }.onFailure { Log.w(TAG, "send failed: ${it.javaClass.simpleName}") } // no address in the log
        }
    }

    /** One CoT event: an unknown-affiliation ground (or air) point with the match as its callsign and remarks. */
    internal fun event(uid: String, label: String, category: String, confidence: Int, lat: Double, lon: Double, air: Boolean, now: Long): String {
        val t = stamp(now)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<event version=\"2.0\" uid=\"rfsentinel-${x(uid)}\" type=\"${if (air) "a-u-A" else "a-u-G"}\" how=\"m-g\" " +
            "time=\"$t\" start=\"$t\" stale=\"${stamp(now + STALE_MS)}\">" +
            String.format(Locale.US, "<point lat=\"%.6f\" lon=\"%.6f\" hae=\"9999999.0\" ce=\"50.0\" le=\"9999999.0\"/>", lat, lon) +
            "<detail><contact callsign=\"${x(label)}\"/>" +
            "<remarks>${x("RF Sentinel: $label · $category · confidence $confidence% · $uid · heard near here")}</remarks></detail></event>"
    }

    private fun x(s: String) = ExportText.xml(s)

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
