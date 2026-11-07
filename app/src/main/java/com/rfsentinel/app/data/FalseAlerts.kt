package com.rfsentinel.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.service.DeviceRegistry

/**
 * Alerts you marked as false, kept to look at later (History > False alerts) and export, e.g. to fix a signature.
 * Marking one also mutes the device ([AlertMutes], until you take it off the list).
 */
object FalseAlerts {

    /** The match as it was when marked, in the match log's shape so its list and exports are reused, plus your note. */
    data class Entry(val match: DetectionEntity, val note: String = "")

    private const val PREFS = "false_alerts"
    private const val KEY = "entries"
    private val gson = Gson()
    private val type = object : TypeToken<List<Entry>>() {}.type
    @Volatile private var entries: List<Entry>? = null

    @Synchronized
    fun all(context: Context): List<Entry> = entries ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
        val raw = p.getString(KEY, null)
        // The next save overwrites a list that wouldn't load: a copy keeps what it held.
        runCatching { gson.fromJson<List<Entry>>(raw, type) }.onFailure { p.edit().putString(KEY + "_bad", raw).apply() }
            .getOrNull().orEmpty().also { entries = it }
    }

    fun contains(context: Context, mac: String) = all(context).any { it.match.mac == mac }

    @Synchronized
    private fun save(context: Context, list: List<Entry>) {
        entries = list
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, gson.toJson(list)).apply()
    }

    /** Logs [mac]'s alert from what is known now: the live device, else its last logged match. Mutes it. */
    suspend fun mark(context: Context, mac: String, note: String): Entry? {
        val s = DeviceRegistry.get(mac)
        val last = AppDatabase.getInstance(context).detectionDao().forMac(mac).firstOrNull()
        val hit = s?.best
        val match = when {
            hit != null && s != null -> DetectionEntity(
                mac = mac, label = hit.label, source = s.source.name, rssi = s.rssi, timestamp = System.currentTimeMillis(),
                latitude = (s.bestPosition ?: s.path.lastOrNull())?.lat ?: last?.latitude,
                longitude = (s.bestPosition ?: s.path.lastOrNull())?.lon ?: last?.longitude,
                category = hit.category.name, confidence = hit.confidence, evidence = hit.evidence,
                vendor = s.vendor, deviceName = s.name, radio = s.advert?.let { DeviceIntel.radioSummary(it) })
            last != null -> last.copy(id = 0, timestamp = System.currentTimeMillis())
            else -> return null
        }
        val entry = Entry(match, note.trim())
        add(context, entry)
        return entry
    }

    /** Puts [entry] (back) on the list and mutes its device. */
    fun add(context: Context, entry: Entry) {
        save(context, listOf(entry) + all(context).filter { it.match.mac != entry.match.mac })
        AlertMutes.mute(context, entry.match.mac, AlertMutes.FOREVER)
    }

    /** Takes [mac] off the list and unmutes it. */
    fun remove(context: Context, mac: String) {
        save(context, all(context).filter { it.match.mac != mac })
        AlertMutes.unmute(context, mac)
    }

    /** The rows for the list and the exports: your note first in the evidence. */
    fun rows(context: Context): List<DetectionEntity> = all(context).map { e ->
        if (e.note.isEmpty()) e.match else e.match.copy(evidence = "False alert: ${e.note} · ${e.match.evidence}")
    }
}
