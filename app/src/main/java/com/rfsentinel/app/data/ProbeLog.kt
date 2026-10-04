package com.rfsentinel.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * WiFi network names that devices around you asked for (probe requests), as heard by
 * a USB WiFi adapter or an ESP32 Marauder board. A phone or laptop asks by name for
 * networks it has joined before, so a police car's laptop can give away its
 * department's network. Recording is an option (Settings / the list's switch), off by
 * default: these are other people's network names. Kept only on this phone.
 */
object ProbeLog {

    private const val FILE = "probed_networks.json"
    private const val SAVE_EVERY_MS = 30_000L

    private val book = ProbeBook()
    private var loaded = false
    private var dirty = false
    private var lastSave = 0L
    private val gson = Gson()

    /**
     * Records that [mac] asked for each of [ssids]. Only while recording is on, or [force]d:
     * a map trace recording keeps them too.
     */
    @Synchronized
    fun record(context: Context, mac: String, ssids: Collection<String>, rssi: Int, now: Long = System.currentTimeMillis(),
               force: Boolean = false) {
        if (ssids.isEmpty() || !(force || com.rfsentinel.app.util.Prefs.recordProbes(context))) return
        ensureLoaded(context)
        ssids.forEach { book.record(it, mac, rssi, now) }
        dirty = true
        if (now - lastSave > SAVE_EVERY_MS) save(context, now)
    }

    @Synchronized
    fun all(context: Context): List<ProbeBook.Network> { ensureLoaded(context); return book.all() }

    @Synchronized
    fun remove(context: Context, ssid: String) { ensureLoaded(context); book.remove(ssid); dirty = true; save(context) }

    @Synchronized
    fun clear(context: Context) { ensureLoaded(context); book.clear(); dirty = true; save(context) }

    /** Writes pending changes (the scan stopping, the list closing). */
    @Synchronized
    fun flush(context: Context) { if (dirty) save(context) }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = File(context.filesDir, FILE)
            if (f.exists()) {
                val list: List<ProbeBook.Network> = gson.fromJson(f.readText(), object : TypeToken<List<ProbeBook.Network>>() {}.type)
                book.restore(list.orEmpty())
            }
        }
    }

    private fun save(context: Context, now: Long = System.currentTimeMillis()) {
        lastSave = now
        dirty = false
        runCatching {
            val f = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(gson.toJson(book.all()))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}

/** The requested-network list itself (no Android), capped to the most recent names. */
class ProbeBook(private val maxNetworks: Int = 1000, private val maxDevices: Int = 50) {

    data class Network(
        val ssid: String,
        var firstSeen: Long,
        var lastSeen: Long,
        /** Separate encounters: asked for again after a [GAP_MS] pause. */
        var encounters: Int,
        var lastRssi: Int,
        /** Devices that asked for it, most recent last. */
        val devices: MutableList<String>
    )

    private val nets = LinkedHashMap<String, Network>()

    fun record(ssid: String, mac: String, rssi: Int, now: Long) {
        val key = ssid.lowercase()
        val n = nets[key]
        if (n == null) {
            nets[key] = Network(ssid, now, now, 1, rssi, mutableListOf(mac.uppercase()))
            trim()
            return
        }
        if (now - n.lastSeen > GAP_MS) n.encounters++
        n.lastSeen = now
        n.lastRssi = rssi
        val m = mac.uppercase()
        n.devices.remove(m); n.devices.add(m)
        while (n.devices.size > maxDevices) n.devices.removeAt(0)
    }

    /** Newest first. */
    fun all(): List<Network> = nets.values.sortedByDescending { it.lastSeen }.map { it.copy(devices = it.devices.toMutableList()) }

    fun remove(ssid: String) { nets.remove(ssid.lowercase()) }

    fun clear() = nets.clear()

    fun restore(list: List<Network>) {
        nets.clear()
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS") // Gson can leave fields null
        list.filter { it.ssid != null }.forEach {
            nets[it.ssid.lowercase()] = it.copy(devices = (it.devices ?: mutableListOf()).toMutableList())
        }
        trim()
    }

    private fun trim() {
        if (nets.size <= maxNetworks) return
        nets.values.sortedBy { it.lastSeen }.take(nets.size - maxNetworks).forEach { nets.remove(it.ssid.lowercase()) }
    }

    companion object {
        const val GAP_MS = 10 * 60_000L
    }
}
