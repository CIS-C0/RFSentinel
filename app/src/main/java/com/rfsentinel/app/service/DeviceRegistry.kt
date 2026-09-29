package com.rfsentinel.app.service

import com.rfsentinel.app.data.KnownDeviceEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.AdvertFingerprint
import com.rfsentinel.app.detect.PatrolCluster
import com.rfsentinel.app.detect.WifiFingerprint
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.RemoteId
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-memory state for every device heard recently, watchlisted or not. Kept
 * out of Room on purpose: BLE reports each advertiser several times a second
 * and a busy street can have hundreds of them. Only matches go to the detection
 * log, and a throttled summary goes to known_devices.
 *
 * Written by scanner threads, read by the UI - both in the same process.
 */
object DeviceRegistry {

    /** Devices not heard from for this long drop off the live list. */
    const val PRUNE_AFTER_MS = 3 * 60 * 1000L
    private const val HISTORY_MAX = 600
    private const val HISTORY_MIN_SPACING_MS = 1000L
    private const val LOCATION_SPACING_MS = 45_000L
    private const val ROTATION_SILENCE_MS = 1_500L
    /** How long a device keeps a match after the evidence was last observed. */
    const val HIT_HOLD_MS = 120_000L
    private const val ROTATION_WINDOW_MS = 30_000L

    data class Sample(val time: Long, val rssi: Int)
    data class GeoSample(val time: Long, val lat: Double, val lon: Double)

    /** Immutable view of one device for the UI. */
    data class Snapshot(
        val mac: String,
        val source: Advert.Source,
        val sources: Set<Advert.Source>,
        val name: String?,
        val vendor: String?,
        val deviceType: String,
        val facts: List<Pair<String, String>>,
        val hits: List<Hit>,
        val rssi: Int,
        val bestRssi: Int,
        val distanceM: Double,
        val addressType: AddressType,
        val firstSeen: Long,
        val lastSeen: Long,
        val sightings: Int,
        val remoteId: RemoteId.Info?,
        val following: Boolean,
        val known: KnownDeviceEntity?,
        /** Latest raw observation (detail screen only). */
        val advert: Advert?,
        val history: List<Sample>,
        val path: List<GeoSample>,
        /** Where YOUR phone was when this device's signal was strongest (approximate device position). */
        val bestPosition: GeoSample?
    ) {
        val best: Hit? get() = hits.firstOrNull()
        /**
         * True when this address has no history from an earlier session. The
         * history row is looked up once, when the device is first heard this
         * session, so a returning device flips to "not new" within a moment.
         */
        val isNew: Boolean get() = known == null
    }

    private class Track(val mac: String, now: Long) {
        val sources = HashSet<Advert.Source>()
        var advert: Advert? = null
        var name: String? = null
        var vendor: String? = null
        var identity = DeviceIntel.Identity("Device", emptyList())
        var hits: List<Hit> = emptyList()
        var rssi = -127
        var bestRssi = -127
        var distance = 0.0
        val firstSeen = now
        var lastSeen = now
        var sightings = 0
        val history = ArrayDeque<Sample>()
        val path = ArrayDeque<GeoSample>()
        var remoteId: RemoteId.Info? = null
        var following = false
        var known: KnownDeviceEntity? = null
        var bestPosition: GeoSample? = null
        var fingerprint: String? = null
        /** Patrol-kit role (see [PatrolCluster.roleOf]) - sticky once known. */
        var role: String? = null
        /** The address this device most likely used before its address rotated. */
        var linkedFrom: String? = null
        var inherited: List<Hit> = emptyList()
        /** The device's own strongest recent match, held for [HIT_HOLD_MS] after it was last seen. */
        var heldHit: Hit? = null
        var heldUntil = 0L
    }

    private val tracks = ConcurrentHashMap<String, Track>()

    @Volatile var sessionStart = 0L
        private set

    fun startSession(now: Long = System.currentTimeMillis()) {
        tracks.clear()
        sessionStart = now
    }

    /**
     * Records one observation. Returns true the first time the device is seen
     * this session (the caller then loads its long-term history).
     */
    fun report(
        a: Advert,
        hits: List<Hit>,
        identity: DeviceIntel.Identity,
        vendor: String?,
        remoteId: RemoteId.Info?,
        location: GeoSample?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        var isFirst = false
        val t = tracks.computeIfAbsent(a.mac) { isFirst = true; Track(a.mac, now) }
        if (isFirst) linkRotatedAddress(t, a, now)
        synchronized(t) {
            PatrolCluster.roleOf(hits, vendor, a.name)?.let { t.role = it }
            t.sources += a.source
            t.advert = a
            a.name?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }?.let { t.name = it }
            vendor?.let { t.vendor = it }
            // Keep the richest identity: a later advert may lack the fields an earlier one had.
            if (identity.facts.size >= t.identity.facts.size || t.identity.type == "Device") t.identity = identity
            t.hits = mergeHits(t, hits, now)
            remoteId?.let { t.remoteId = it }
            t.rssi = a.rssi
            if (location != null && (a.rssi >= t.bestRssi || t.bestPosition == null)) t.bestPosition = location
            if (a.rssi > t.bestRssi) t.bestRssi = a.rssi
            t.distance = DeviceIntel.distanceMeters(a)
            t.lastSeen = now
            t.sightings++
            if (t.history.isEmpty() || now - t.history.last.time >= HISTORY_MIN_SPACING_MS) {
                t.history.addLast(Sample(now, a.rssi))
                if (t.history.size > HISTORY_MAX) t.history.removeFirst()
            }
            if (location != null && (t.path.isEmpty() || now - t.path.last.time >= LOCATION_SPACING_MS)) {
                t.path.addLast(location)
                if (t.path.size > 200) t.path.removeFirst()
            }
        }
        return isFirst
    }

    /**
     * Current matches plus the device's own strongest recent one. Payload tags
     * can be intermittent (not every packet carries them), so a match is held
     * for [HIT_HOLD_MS] after it was last observed instead of flickering, then
     * fades. The patrol-vehicle hit is never held: it is recomputed from the live
     * group every classification, so it disappears when the group breaks up.
     */
    private fun mergeHits(t: Track, hits: List<Hit>, now: Long): List<Hit> {
        val own = hits.firstOrNull { it.source != PatrolCluster.SOURCE }
        if (own != null) {
            val held = t.heldHit
            if (held == null || now > t.heldUntil || own.confidence >= held.confidence) t.heldHit = own
            if (own.label == t.heldHit?.label) t.heldUntil = now + HIT_HOLD_MS
        }
        val held = t.heldHit?.takeIf { now <= t.heldUntil }
        val merged = if (held != null && hits.none { it.label == held.label }) hits + held else hits
        return merged.sortedByDescending { it.confidence }
    }

    /**
     * Address-rotation linking: if exactly one device with the same advert
     * fingerprint went silent shortly before this new address appeared, treat
     * them as the same device and carry its matches over. Two or more
     * candidates (e.g. several identical earbuds) means no link.
     */
    private fun linkRotatedAddress(t: Track, a: Advert, now: Long) {
        val fp = AdvertFingerprint.of(a) ?: return
        synchronized(t) { t.fingerprint = fp }
        if (!AdvertFingerprint.mayRotate(a)) return
        val candidates = tracks.values.filter { o ->
            o !== t && synchronized(o) {
                o.fingerprint == fp && o.lastSeen <= now - ROTATION_SILENCE_MS && now - o.lastSeen <= ROTATION_WINDOW_MS
            }
        }
        val prev = candidates.singleOrNull() ?: return
        val carried = synchronized(prev) {
            (prev.hits.ifEmpty { prev.inherited }).map { h ->
                h.copy(
                    confidence = (h.confidence - 5).coerceAtLeast(0),
                    evidence = "Same device as ${prev.mac} before its address rotated (identical advert " +
                        "fingerprint, ${(now - prev.lastSeen) / 1000} s gap). Original evidence: ${h.evidence}"
                )
            } to (prev.name to prev.role)
        }
        synchronized(t) {
            t.linkedFrom = prev.mac
            t.inherited = carried.first
            if (t.name == null) t.name = carried.second.first
            if (t.role == null) t.role = carried.second.second
        }
    }

    /** Matches carried over from this device's previous (rotated) address. */
    fun inheritedHits(mac: String): List<Hit> = tracks[mac]?.let { synchronized(it) { it.inherited } }.orEmpty()

    @Volatile private var groupCache: Pair<Long, List<PatrolCluster.Group>> = 0L to emptyList()

    /** The patrol-vehicle cluster hit for [mac], if it's part of a group (groups recomputed every 2 s). */
    fun clusterHit(mac: String, now: Long = System.currentTimeMillis()): Hit? {
        var (time, groups) = groupCache
        if (now - time >= 2_000L) {
            // Devices the user trusts (whitelisted) never make others look like a patrol car.
            val members = tracks.values.filterNot { WhitelistCache.contains(it.mac) }.mapNotNull { t ->
                synchronized(t) {
                    t.role?.let { role ->
                        PatrolCluster.Member(t.mac, role, t.firstSeen, t.lastSeen, t.history.map { it.time to it.rssi })
                    }
                }
            }
            groups = PatrolCluster.groups(members, now)
            groupCache = now to groups
        }
        return PatrolCluster.hitFor(mac, groups)
    }

    fun remoteIdOf(mac: String): RemoteId.Info? = tracks[mac]?.let { synchronized(it) { it.remoteId } }

    fun setKnown(mac: String, known: KnownDeviceEntity?) {
        tracks[mac]?.let { synchronized(it) { it.known = known } }
    }

    /**
     * Follower check: the device has been heard continuously (never out of range
     * for more than [PRUNE_AFTER_MS]) for at least [minDurationMs] while you
     * moved at least [minDistanceM]. Returns true exactly once per device.
     */
    fun checkFollowing(mac: String, minDurationMs: Long, minDistanceM: Double): Boolean {
        val t = tracks[mac] ?: return false
        synchronized(t) {
            if (t.following || t.path.size < 3) return false
            val first = t.path.first
            if (t.lastSeen - t.firstSeen < minDurationMs) return false
            val moved = t.path.maxOf { metersBetween(first.lat, first.lon, it.lat, it.lon) }
            if (moved < minDistanceM) return false
            t.following = true
            return true
        }
    }

    /** Light snapshots for the live list; pruned first. */
    fun snapshot(now: Long = System.currentTimeMillis()): List<Snapshot> {
        tracks.values.removeIf { now - it.lastSeen > PRUNE_AFTER_MS }
        return tracks.values.map { t -> synchronized(t) { snap(t, full = false) } }
    }

    /** Full snapshot (history, path, raw advert) for the detail screen. */
    fun get(mac: String): Snapshot? = tracks[mac]?.let { t -> synchronized(t) { snap(t, full = true) } }

    /** Devices heard since [since] with their summary, for persisting to known_devices. */
    fun summaries(since: Long): List<Snapshot> =
        tracks.values.filter { it.lastSeen >= since }.map { t -> synchronized(t) { snap(t, full = false) } }

    fun count(): Int = tracks.size

    fun clear() = tracks.clear()

    private fun snap(t: Track, full: Boolean) = Snapshot(
        mac = t.mac,
        source = t.advert?.source ?: Advert.Source.BLE,
        sources = t.sources.toSet(),
        name = t.name,
        vendor = t.vendor,
        deviceType = t.identity.type,
        facts = t.identity.facts +
            listOfNotNull(
                t.linkedFrom?.let { "Previous address" to "$it (address rotated, same advert fingerprint)" },
                t.role?.let { "Patrol-kit role" to it }
            ) + if (full && Advert.Source.WIFI in t.sources) sameApFacts(t) else emptyList(),
        hits = t.hits,
        rssi = t.rssi,
        bestRssi = t.bestRssi,
        distanceM = t.distance,
        addressType = t.advert?.addressType ?: AddressType.UNKNOWN,
        firstSeen = t.firstSeen,
        lastSeen = t.lastSeen,
        sightings = t.sightings,
        remoteId = t.remoteId,
        following = t.following,
        known = t.known,
        advert = if (full) t.advert else null,
        history = if (full) t.history.toList() else emptyList(),
        path = if (full) t.path.toList() else emptyList(),
        bestPosition = t.bestPosition
    )

    /**
     * Other networks broadcast by the same access point (see
     * [WifiFingerprint.sameAccessPoint]). For a hidden network this usually
     * reveals the visible network - and so the owner/router - it belongs to.
     */
    private fun sameApFacts(t: Track): List<Pair<String, String>> {
        val siblings = tracks.values.filter { o ->
            o !== t && WifiFingerprint.sameAccessPoint(t.mac, o.mac) && synchronized(o) { Advert.Source.WIFI in o.sources }
        }.map { o -> synchronized(o) { Triple(o.mac, o.name, o.identity.type) } }
        if (siblings.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        out += "Same access point as" to siblings.joinToString("; ") { (mac, ssid, _) ->
            (ssid?.let { "\"$it\"" } ?: "hidden network") + " ($mac)"
        }
        // A visible sibling identified by model or maker names this network's hardware too.
        siblings.map { it.third }.firstOrNull { it.contains(" - ") }
            ?.let { out += "Probable hardware (from sibling)" to it.substringAfter(" - ") }
        return out
    }

    /** Great-circle distance in metres (haversine). */
    fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
