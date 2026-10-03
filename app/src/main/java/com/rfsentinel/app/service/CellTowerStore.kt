package com.rfsentinel.app.service

import android.content.Context
import android.location.Location
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsentinel.app.detect.CellAnalyzer
import java.io.File

/**
 * Every cell tower the phone has seen while scanning, kept on the phone only.
 * A tower's position is *estimated*: where your phone was when its signal was
 * strongest. Real tower positions would need an online database, which would
 * reveal where you are, so none is used.
 */
object CellTowerStore {

    data class Tower(
        val key: String,
        val rat: String,
        val mcc: String?,
        val mnc: String?,
        val area: Int?,
        val cellId: Long,
        var channel: Int?,
        var pci: Int?,
        var operator: String?,
        val firstSeen: Long,
        var lastSeen: Long,
        var timesSeen: Int,
        var servedYou: Boolean,
        var bestDbm: Int?,
        var bestLat: Double?,
        var bestLon: Double?
    ) {
        val ratLabel get() = runCatching { CellAnalyzer.Rat.valueOf(rat).label }.getOrDefault(rat)
    }

    private const val FILE = "cell_towers.json"
    private const val MAX_TOWERS = 3000
    private const val SAVE_EVERY = 20
    /** A fix older or coarser than this doesn't place a tower. */
    private const val MAX_FIX_AGE_MS = 60_000L
    private const val MAX_FIX_ACCURACY_M = 100f

    private val gson = Gson()
    private val listType = object : TypeToken<List<Tower>>() {}.type
    private val towers = LinkedHashMap<String, Tower>()
    private var loaded = false
    private var records = 0

    /** The cells of the latest snapshot (serving and neighbours), and when it was taken. */
    @Volatile var current: List<CellAnalyzer.Cell> = emptyList()
        private set
    @Volatile var currentAt = 0L
        private set

    @Synchronized
    fun all(context: Context): List<Tower> {
        ensureLoaded(context)
        return towers.values.map { it.copy() }
    }

    /** Adds one snapshot. Cells without an ID (most neighbours) are only shown live. */
    @Synchronized
    fun record(context: Context, cells: List<CellAnalyzer.Cell>, fix: Location?, now: Long = System.currentTimeMillis()) {
        current = cells
        currentAt = now
        ensureLoaded(context)
        val place = fix?.takeIf {
            now - it.time <= MAX_FIX_AGE_MS && (!it.hasAccuracy() || it.accuracy <= MAX_FIX_ACCURACY_M)
        }
        for (c in cells) {
            val id = c.cellId ?: continue
            val t = towers.getOrPut(c.key) {
                Tower(c.key, c.rat.name, c.mcc, c.mnc, c.area, id, c.channel, c.pci, c.operator,
                    now, now, 0, false, null, null, null)
            }
            t.lastSeen = now
            t.timesSeen++
            if (c.registered) t.servedYou = true
            c.channel?.let { t.channel = it }
            c.pci?.let { t.pci = it }
            c.operator?.let { t.operator = it }
            val dbm = c.dbm?.takeIf { it in -150..-20 }
            if (dbm != null && (t.bestDbm == null || dbm > t.bestDbm!!)) {
                t.bestDbm = dbm
                if (place != null) { t.bestLat = place.latitude; t.bestLon = place.longitude }
            } else if (place != null && t.bestLat == null) {
                t.bestLat = place.latitude; t.bestLon = place.longitude
            }
        }
        if (towers.size > MAX_TOWERS) {
            towers.values.sortedBy { it.lastSeen }.take(towers.size - MAX_TOWERS).forEach { towers.remove(it.key) }
        }
        if (++records % SAVE_EVERY == 0) save(context)
    }

    @Synchronized
    fun save(context: Context) {
        if (!loaded) return
        runCatching { File(context.filesDir, FILE).writeText(gson.toJson(towers.values.toList())) }
    }

    /** Erased with "Forget device history": the list says where you have been. */
    @Synchronized
    fun forget(context: Context) {
        towers.clear()
        current = emptyList()
        loaded = true
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
                ?.let { gson.fromJson<List<Tower>>(it, listType) }
                ?.forEach { towers[it.key] = it }
        }
    }
}
