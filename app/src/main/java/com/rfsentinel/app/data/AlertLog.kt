package com.rfsentinel.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.Tier

/**
 * The alerts RF Sentinel raised recently (devices, known cameras, cell warnings),
 * newest first, kept on the phone only. Android Auto lists them, and a device's
 * car screen falls back to its last alert once the device is out of range.
 */
object AlertLog {

    data class Entry(
        /** Device MAC, or "alpr:<osm id>" / "cell:<key>" for map alerts. */
        val key: String,
        /** The device's MAC when the alert is about a device. */
        val mac: String?,
        val label: String,
        val category: String,
        val tier: String,
        val confidence: Int,
        val evidence: String,
        val rssi: Int?,
        val time: Long,
        val following: Boolean = false,
        /** Where it is (a camera, a drone) or where your phone was when it alerted. */
        val lat: Double? = null,
        val lon: Double? = null
    ) {
        val categoryEnum: Category? get() = runCatching { Category.valueOf(category) }.getOrNull()
        val tierEnum: Tier? get() = runCatching { Tier.valueOf(tier) }.getOrNull()
    }

    private const val PREFS = "alert_log"
    private const val KEY = "entries"
    const val MAX = 40

    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type
    private var entries: MutableList<Entry>? = null

    /** Tests: start empty without touching storage. */
    @Synchronized
    fun resetForTest() { entries = ArrayList() }

    @Synchronized
    private fun list(context: Context?): MutableList<Entry> {
        entries?.let { return it }
        val loaded: List<Entry> = context?.let { c ->
            runCatching {
                c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                    ?.let { gson.fromJson<List<Entry>>(it, listType) }
            }.getOrNull()
        }.orEmpty()
        return ArrayList(loaded.filterNotNull()).also { entries = it }
    }

    @Synchronized
    fun add(
        context: Context, key: String, mac: String?, hit: Hit, rssi: Int?, following: Boolean,
        lat: Double?, lon: Double?, now: Long = System.currentTimeMillis()
    ) {
        val l = list(context)
        // The same thing alerting again replaces its older entry (newest on top).
        l.removeAll { it.key == key }
        l.add(0, Entry(key, mac, hit.label, hit.category.name, hit.tier.name, hit.confidence, hit.evidence,
            rssi, now, following, lat, lon))
        while (l.size > MAX) l.removeAt(l.size - 1)
        save(context, l)
    }

    @Synchronized
    fun recent(context: Context?): List<Entry> = list(context).toList()

    @Synchronized
    fun latestFor(context: Context?, mac: String): Entry? = list(context).firstOrNull { it.mac.equals(mac, ignoreCase = true) }

    @Synchronized
    fun clear(context: Context) {
        list(context).clear()
        save(context, emptyList())
    }

    private fun save(context: Context, l: List<Entry>) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, gson.toJson(l)).apply()
        }
    }
}
