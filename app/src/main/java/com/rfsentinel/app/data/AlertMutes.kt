package com.rfsentinel.app.data

import android.content.Context
import androidx.core.content.edit
import com.rfsentinel.app.service.DeviceRegistry
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The ways an alert reaches you; each can be turned off per watchlist entry ([com.rfsentinel.app.oui.OuiEntry.alertsOff])
 * and per category ([com.rfsentinel.app.util.Prefs.categoryAlertsOff]).
 */
enum class AlertKind(val label: String) {
    /** The radar-detector ticks while it's near. */
    BEEP("Beep"),
    /** The alert sound. */
    SOUND("Sound"),
    /** The spoken alert. */
    VOICE("Voice"),
    VIBRATE("Vibrate"),
    /** The notification, the map card and the bubble's popup. */
    BANNER("Banner");

    companion object {
        fun parse(text: String?): Set<AlertKind> =
            text.orEmpty().split(',').mapNotNull { k -> entries.firstOrNull { it.name == k.trim() } }.toSet()

        fun format(off: Set<AlertKind>): String? = off.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
    }
}

/**
 * Devices you acknowledged from the list: no alerts from them (sound, voice, vibration, banner, radar beep) for a
 * while, or until you unmute them. Still listed, coloured and logged.
 */
object AlertMutes {

    private const val PREFS = "alert_mutes"
    private const val KEY = "mutes"
    /** Muted until you unmute it. */
    const val FOREVER = Long.MAX_VALUE
    const val TAP_MS = 30 * 60_000L

    private val until = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}")
                val now = System.currentTimeMillis()
                o.keys().forEach { mac -> o.optLong(mac).takeIf { it > now }?.let { until[mac] = it } }
            }
            loaded = true
        }
    }

    private fun save(context: Context) {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        until.forEach { (mac, t) -> if (t > now) o.put(mac, t) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, o.toString()) }
    }

    /** When [mac]'s mute ends ([FOREVER] for never), or null when it isn't muted. Its earlier address counts too. */
    fun mutedUntil(context: Context, mac: String, now: Long = System.currentTimeMillis()): Long? {
        load(context)
        if (until.isEmpty()) return null
        return listOfNotNull(mac, DeviceRegistry.linkedFrom(mac)).firstNotNullOfOrNull { m -> until[m]?.takeIf { it > now } }
    }

    fun isMuted(context: Context, mac: String, now: Long = System.currentTimeMillis()) = mutedUntil(context, mac, now) != null

    fun mute(context: Context, mac: String, untilTime: Long) {
        load(context)
        until[mac] = untilTime
        save(context)
    }

    fun unmute(context: Context, mac: String) {
        load(context)
        until.remove(mac)
        DeviceRegistry.linkedFrom(mac)?.let { until.remove(it) }
        save(context)
    }

    /** "muted 29m" / "muted" for the list row; null when not muted. */
    fun label(context: Context, mac: String, now: Long = System.currentTimeMillis()): String? {
        val t = mutedUntil(context, mac, now) ?: return null
        if (t == FOREVER) return "muted"
        val min = ((t - now + 59_999) / 60_000).coerceAtLeast(1)
        return if (min >= 60) "muted ${min / 60}h${(min % 60).toString().padStart(2, '0')}" else "muted ${min}m"
    }
}
