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
    private const val DEVICES_FILE = "probing_devices.json"
    private const val SAVE_EVERY_MS = 30_000L

    private val book = ProbeBook()
    private var loaded = false
    private var dirty = false
    private var lastSave = 0L
    private val gson = Gson()

    /**
     * Records that [mac] asked for each of [ssids], with what else its frames said about it ([wps],
     * [fingerprint]: kept for a device that asked for a name), and where this phone was ([at]). Only
     * while recording is on, or [force]d: a map trace recording keeps them too.
     */
    @Synchronized
    fun record(context: Context, mac: String, ssids: Collection<String>, rssi: Int, now: Long = System.currentTimeMillis(),
               force: Boolean = false, wps: com.rfsentinel.app.usb.ProbeIntel.Wps? = null, fingerprint: String? = null,
               at: Pair<Double, Double>? = null) {
        if (ssids.isEmpty() && wps == null && fingerprint == null) return
        if (!(force || com.rfsentinel.app.util.Prefs.recordProbes(context))) return
        ensureLoaded(context)
        ssids.forEach { book.record(it, mac, rssi, now, at) }
        if (at != null && onMap?.let { m -> ssids.any { it.equals(m, ignoreCase = true) } } == true) version++
        if (!book.recordDevice(mac, ssids, rssi, now, wps?.deviceName, wps?.product, fingerprint)) return
        dirty = true
        if (now - lastSave > SAVE_EVERY_MS) save(context, now)
    }

    @Synchronized
    fun all(context: Context): List<ProbeBook.Network> { ensureLoaded(context); return book.all() }

    @Synchronized
    fun network(context: Context, ssid: String): ProbeBook.Network? { ensureLoaded(context); return book.network(ssid) }

    /** Every device that asked, newest first (not grouped). */
    @Synchronized
    fun devices(context: Context): List<ProbeBook.Device> { ensureLoaded(context); return book.devices() }

    /** The network whose request spots the map shows ("Show on map"), or null. */
    @Volatile var onMap: String? = null
        private set

    /** Bumped when [onMap] or a place changes, so the map redraws only then. */
    @Volatile var version = 0
        private set

    fun showOnMap(ssid: String?) { onMap = ssid; version++ }

    /** The devices that asked, grouped ([ProbeBook.groups]), newest first. */
    @Synchronized
    fun groups(context: Context): List<ProbeBook.Group> {
        ensureLoaded(context)
        return book.groups({ com.rfsentinel.app.util.MacUtil.isRandomized(it) })
    }

    /** Recorded addresses whose probe requests have the fingerprint [fp]. */
    @Synchronized
    fun sharing(context: Context, fp: String): Int { ensureLoaded(context); return book.sharing(fp) }

    @Synchronized
    fun remove(context: Context, ssid: String) { ensureLoaded(context); book.remove(ssid); version++; dirty = true; save(context) }

    @Synchronized
    fun clear(context: Context) { ensureLoaded(context); book.clear(); version++; dirty = true; save(context) }

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
        runCatching {
            val f = File(context.filesDir, DEVICES_FILE)
            if (f.exists()) {
                val list: List<ProbeBook.Device> = gson.fromJson(f.readText(), object : TypeToken<List<ProbeBook.Device>>() {}.type)
                book.restoreDevices(list.orEmpty())
            }
        }
        // Lists saved before devices were kept (or a device file lost) still show who asked.
        if (book.backfillDevices()) dirty = true
    }

    private fun save(context: Context, now: Long = System.currentTimeMillis()) {
        lastSave = now
        dirty = false
        write(context, FILE, gson.toJson(book.all()))
        write(context, DEVICES_FILE, gson.toJson(book.devices()))
    }

    private fun write(context: Context, name: String, text: String) {
        runCatching {
            val f = File(context.filesDir, name)
            val tmp = File(context.filesDir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}

/** The requested-network list itself (no Android), capped to the most recent names and devices. */
class ProbeBook(private val maxNetworks: Int = 1000, private val maxDevices: Int = 50, private val maxProbers: Int = 2000) {

    data class Network(
        val ssid: String,
        var firstSeen: Long,
        var lastSeen: Long,
        /** Separate encounters: asked for again after a [GAP_MS] pause. */
        var encounters: Int,
        var lastRssi: Int,
        /** Devices that asked for it, most recent last. */
        val devices: MutableList<String>,
        /** Where it was asked for (this phone's position when heard), oldest first. */
        val spots: MutableList<Spot> = mutableListOf()
    )

    /** One place a network was asked for: the strongest request heard there, by [mac], last heard [at]. */
    data class Spot(val lat: Double, val lon: Double, val at: Long, val mac: String, val rssi: Int)

    /** A device that asked for networks: what it asked for and what its frames said about it. */
    data class Device(
        val mac: String,
        var firstSeen: Long,
        var lastSeen: Long,
        /** Separate encounters: heard again after a [GAP_MS] pause. */
        var encounters: Int,
        var lastRssi: Int,
        /** Network names it asked for, most recent last. */
        val ssids: MutableList<String>,
        /** The name its WPS block gave (e.g. a computer name). */
        var wpsName: String? = null,
        /** Maker, model and number from its WPS block. */
        var wpsProduct: String? = null,
        /** How it builds its probe requests ([com.rfsentinel.app.usb.ProbeIntel.fingerprint]). */
        var fingerprint: String? = null
    )

    /**
     * Devices shown together: one address, or random addresses with the same rare probe fingerprint that
     * asked for a network name in common ([likelySame]).
     */
    data class Group(val devices: List<Device>, val likelySame: Boolean) {
        val lastSeen get() = devices.maxOf { it.lastSeen }
        /** Every name the group asked for, most recent first. */
        val ssids: List<String> get() = devices.sortedByDescending { it.lastSeen }
            .flatMap { it.ssids.reversed() }.distinctBy { it.lowercase() }
    }

    private val nets = LinkedHashMap<String, Network>()
    private val probers = LinkedHashMap<String, Device>()

    /** Notes that [mac] asked for [ssid], heard where this phone was ([at], when it had a fix). */
    fun record(ssid: String, mac: String, rssi: Int, now: Long, at: Pair<Double, Double>? = null) {
        val key = ssid.lowercase()
        val m = mac.uppercase()
        val n = nets[key]
        if (n == null) {
            nets[key] = Network(ssid, now, now, 1, rssi, mutableListOf(m)).also { at?.let { p -> spot(it, m, rssi, now, p) } }
            trim()
            return
        }
        if (now - n.lastSeen > GAP_MS) n.encounters++
        n.lastSeen = now
        n.lastRssi = rssi
        n.devices.remove(m); n.devices.add(m)
        while (n.devices.size > maxDevices) n.devices.removeAt(0)
        at?.let { spot(n, m, rssi, now, it) }
    }

    /**
     * A new place, or the last one again (same encounter, within [SPOT_M]): there it moves to the
     * strongest request, the one heard closest to the device.
     */
    private fun spot(n: Network, mac: String, rssi: Int, now: Long, at: Pair<Double, Double>) {
        val last = n.spots.lastOrNull()
        if (last != null && now - last.at <= GAP_MS && meters(last.lat, last.lon, at.first, at.second) < SPOT_M) {
            n.spots[n.spots.lastIndex] = if (rssi > last.rssi) Spot(at.first, at.second, now, mac, rssi) else last.copy(at = now)
            return
        }
        n.spots.add(Spot(at.first, at.second, now, mac, rssi))
        while (n.spots.size > MAX_SPOTS) n.spots.removeAt(0)
    }

    /** One network by name, or null. */
    fun network(ssid: String): Network? = nets[ssid.lowercase()]?.let { it.copy(devices = it.devices.toMutableList(), spots = it.spots.toMutableList()) }

    /**
     * Notes what [mac] asked for and said about itself. A device that never asked for a name isn't
     * kept; returns false when nothing was noted.
     */
    fun recordDevice(mac: String, ssids: Collection<String>, rssi: Int, now: Long,
                     wpsName: String? = null, wpsProduct: String? = null, fingerprint: String? = null): Boolean {
        val m = mac.uppercase()
        val d = probers[m] ?: run {
            if (ssids.isEmpty()) return false
            Device(m, now, now, 1, rssi, mutableListOf()).also { probers[m] = it }
        }
        if (now - d.lastSeen > GAP_MS) d.encounters++
        d.lastSeen = now
        d.lastRssi = rssi
        ssids.forEach { s -> d.ssids.removeAll { it.equals(s, ignoreCase = true) }; d.ssids.add(s) }
        while (d.ssids.size > maxDevices) d.ssids.removeAt(0)
        wpsName?.let { d.wpsName = it }
        wpsProduct?.let { d.wpsProduct = it }
        fingerprint?.let { d.fingerprint = it }
        if (probers.size > maxProbers) {
            probers.values.sortedBy { it.lastSeen }.take(probers.size - maxProbers).forEach { probers.remove(it.mac) }
        }
        return true
    }

    /** Newest first. */
    fun devices(): List<Device> = probers.values.sortedByDescending { it.lastSeen }.map { it.copy(ssids = it.ssids.toMutableList()) }

    /** Addresses whose probe requests have the fingerprint [fp]. */
    fun sharing(fp: String): Int = probers.values.count { it.fingerprint == fp }

    /**
     * The devices as groups, newest first: random addresses ([isRandom]) with the same fingerprint, shared
     * by at most [rareMax] addresses, that asked for at least one name in common are likely one device
     * changing its address. Fixed addresses always stand alone.
     */
    fun groups(isRandom: (String) -> Boolean, rareMax: Int = 20): List<Group> {
        val list = devices()
        val counts = list.mapNotNull { it.fingerprint }.groupingBy { it }.eachCount()
        val parent = IntArray(list.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        list.indices
            .filter { i -> list[i].fingerprint.let { it != null && counts.getValue(it) in 2..rareMax } && isRandom(list[i].mac) }
            .groupBy { list[it].fingerprint }
            .values.forEach { same ->
                for (x in same.indices) for (y in x + 1 until same.size) {
                    val asked = list[same[x]].ssids.map { it.lowercase() }.toSet()
                    if (list[same[y]].ssids.any { it.lowercase() in asked }) parent[root(same[y])] = root(same[x])
                }
            }
        return list.indices.groupBy { root(it) }.values
            .map { idx -> Group(idx.map { list[it] }, likelySame = idx.size > 1) }
            .sortedByDescending { it.lastSeen }
    }

    /** Newest first. */
    fun all(): List<Network> = nets.values.sortedByDescending { it.lastSeen }.map { it.copy(devices = it.devices.toMutableList(), spots = it.spots.toMutableList()) }

    /** Forgets [ssid], and the devices that asked for nothing else. */
    fun remove(ssid: String) {
        nets.remove(ssid.lowercase())
        probers.values.forEach { d -> d.ssids.removeAll { it.equals(ssid, ignoreCase = true) } }
        probers.values.removeAll { it.ssids.isEmpty() }
    }

    fun clear() { nets.clear(); probers.clear() }

    fun restoreDevices(list: List<Device>) {
        probers.clear()
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS") // Gson can leave fields null
        list.filter { it.mac != null }.forEach {
            probers[it.mac.uppercase()] = it.copy(mac = it.mac.uppercase(), ssids = (it.ssids ?: mutableListOf()).toMutableList())
        }
    }

    /**
     * Adds every address a network lists as having asked for it to that device's record, creating the
     * record from the network's times when missing (no WPS or fingerprint: those weren't kept). Returns
     * whether anything was added.
     */
    fun backfillDevices(): Boolean {
        var added = false
        for (n in nets.values.sortedBy { it.lastSeen }) for (mac in n.devices) {
            val m = mac.uppercase()
            val d = probers[m] ?: Device(m, n.firstSeen, n.lastSeen, 1, n.lastRssi, mutableListOf()).also { probers[m] = it; added = true }
            if (d.ssids.none { it.equals(n.ssid, ignoreCase = true) }) {
                d.ssids.add(n.ssid); added = true
                d.firstSeen = minOf(d.firstSeen, n.firstSeen)
                if (n.lastSeen > d.lastSeen) { d.lastSeen = n.lastSeen; d.lastRssi = n.lastRssi }
            }
            while (d.ssids.size > maxDevices) d.ssids.removeAt(0)
        }
        if (probers.size > maxProbers) {
            probers.values.sortedBy { it.lastSeen }.take(probers.size - maxProbers).forEach { probers.remove(it.mac) }
        }
        return added
    }

    fun restore(list: List<Network>) {
        nets.clear()
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS") // Gson can leave fields null
        list.filter { it.ssid != null }.forEach {
            nets[it.ssid.lowercase()] = it.copy(devices = (it.devices ?: mutableListOf()).toMutableList(),
                spots = (it.spots ?: mutableListOf()).filter { s -> s != null }.toMutableList())
        }
        trim()
    }

    private fun trim() {
        if (nets.size <= maxNetworks) return
        nets.values.sortedBy { it.lastSeen }.take(nets.size - maxNetworks).forEach { nets.remove(it.ssid.lowercase()) }
    }

    companion object {
        const val GAP_MS = 10 * 60_000L
        /** Requests heard this close together, in one encounter, are one place. */
        const val SPOT_M = 200.0
        const val MAX_SPOTS = 50

        private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1) * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
            return 6_371_000.0 * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
