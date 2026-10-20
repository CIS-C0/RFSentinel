package com.rfsentinel.app.service

import android.content.Context
import android.location.Location
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.data.TripPointEntity
import com.rfsentinel.app.detect.Hit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Records a scan trace ("trip"): your GPS path, plus every device heard along
 * it, placed where its signal was strongest. Lives inside ScanForegroundService
 * so it keeps recording with the screen off. Buffers in memory and flushes to
 * Room every ~30 s, so a crash loses at most that much.
 */
object TripRecorder {

    /** Minimum movement / time between recorded trace points. */
    private const val MIN_POINT_DISTANCE_M = 10.0
    private const val MIN_POINT_INTERVAL_MS = 30_000L
    /** Ignore fixes worse than this (indoor network fixes jump around). */
    private const val MAX_ACCURACY_M = 60f
    /**
     * A long wardrive hears tens of thousands of devices: past [DEVICES_IN_MEMORY], one not heard for
     * [DEVICE_IDLE_MS] (and already written) is let go from memory, and read back if it is heard again.
     */
    private const val DEVICES_IN_MEMORY = 2_000
    private const val DEVICE_IDLE_MS = 10 * 60_000L

    data class LivePoint(val time: Long, val lat: Double, val lon: Double)

    @Volatile var activeTripId: Long? = null
        private set
    @Volatile var startedAt = 0L
        private set
    @Volatile var distanceM = 0.0
        private set

    val isRecording get() = activeTripId != null

    private val points = mutableListOf<LivePoint>()          // whole trace (for the live map)
    private val pendingPoints = mutableListOf<TripPointEntity>()
    private val devices = ConcurrentHashMap<String, TripDeviceEntity>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    /** Devices let go from memory (all written), and which of them are flagged, for the totals. */
    private val evicted = ConcurrentHashMap.newKeySet<String>()
    private val evictedFlagged = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var tripDao: com.rfsentinel.app.data.TripDao? = null
    /** One database write at a time: a flush still writing when the trip is stopped must not reopen or zero it. */
    private val writing = Mutex()

    @Synchronized
    fun livePoints(): List<LivePoint> = points.toList()

    /** The latest trace point, without copying the whole trace. */
    @Synchronized
    fun lastPoint(): LivePoint? = points.lastOrNull()

    fun deviceCount() = devices.size + evicted.size
    fun flaggedCount() = devices.values.count { it.flagged } + evictedFlagged.size

    /** Starts a new trip. Returns its id. */
    suspend fun start(context: Context, name: String? = null): Long {
        activeTripId?.let { return it }
        val now = System.currentTimeMillis()
        val label = name ?: ("Trace " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now)))
        val dao = AppDatabase.getInstance(context).tripDao()
        val id = dao.insertTrip(TripEntity(name = label, startTime = now))
        synchronized(this) {
            tripDao = dao
            points.clear(); pendingPoints.clear()
            devices.clear(); dirty.clear(); evicted.clear(); evictedFlagged.clear()
            distanceM = 0.0
            startedAt = now
            activeTripId = id
        }
        return id
    }

    /** What's left to write for a trip that was just stopped (see [detach]). */
    class Final internal constructor(
        val id: Long,
        val points: List<TripPointEntity>,
        val devices: List<TripDeviceEntity>,
        val distanceM: Double,
        val totalPoints: Int,
        val deviceCount: Int,
        val flaggedCount: Int
    )

    /**
     * Stops recording immediately and hands back everything not yet written.
     * Synchronous, so a scan restarted right after sees no active trip and can
     * start a new one; write the result with [persist].
     */
    fun detach(): Final? = synchronized(this) {
        val id = activeTripId ?: return null
        val changed = takeDirty()
        Final(
            id, pendingPoints.toList(), changed, distanceM, points.size,
            deviceCount(), flaggedCount()
        ).also {
            // Drop the live trace too: the process often outlives the scan (and a swiped-away
            // app), and the live map would keep drawing the old route.
            points.clear(); pendingPoints.clear()
            devices.clear(); dirty.clear(); evicted.clear(); evictedFlagged.clear()
            distanceM = 0.0
            activeTripId = null
        }
    }

    /** Writes a detached trip and closes it; deletes it if nothing was recorded. */
    suspend fun persist(context: Context, f: Final) = writing.withLock {
        val dao = AppDatabase.getInstance(context).tripDao()
        if (f.points.isNotEmpty()) dao.insertPoints(f.points)
        if (f.devices.isNotEmpty()) f.devices.chunked(300).forEach { dao.upsertDevices(it) }
        dao.updateStats(f.id, System.currentTimeMillis(), f.distanceM, f.totalPoints, f.deviceCount, f.flaggedCount)
        // An empty trip (no GPS fix at all) isn't worth keeping.
        val trip = dao.get(f.id)
        if (trip != null && trip.pointCount == 0 && trip.deviceCount == 0) dao.delete(f.id)
    }

    /** Stops recording and writes the final state. */
    suspend fun stop(context: Context) {
        detach()?.let { persist(context, it) }
    }

    /**
     * Takes the changed devices. Clearing only what was read (not the whole set)
     * means a device marked dirty by a scanner thread meanwhile stays dirty for
     * the next flush instead of being lost; its state is read after removal, so
     * it's never older than what's recorded.
     */
    private fun takeDirty(): List<TripDeviceEntity> {
        val keys = dirty.toList()
        dirty.removeAll(keys.toSet())
        return keys.mapNotNull { devices[it] }
    }

    /** Called for every location fix while recording. */
    fun onLocation(loc: Location) {
        val id = activeTripId ?: return
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        synchronized(this) {
            val last = points.lastOrNull()
            val moved = last?.let { DeviceRegistry.metersBetween(it.lat, it.lon, loc.latitude, loc.longitude) } ?: Double.MAX_VALUE
            if (last != null && moved < MIN_POINT_DISTANCE_M && loc.time - last.time < MIN_POINT_INTERVAL_MS) return
            if (last != null) distanceM += moved
            points += LivePoint(loc.time, loc.latitude, loc.longitude)
            pendingPoints += TripPointEntity(
                tripId = id, time = loc.time, lat = loc.latitude, lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                speedMs = if (loc.hasSpeed()) loc.speed else null
            )
        }
    }

    /** Called for every observation while recording (cheap, in memory). */
    fun onDevice(
        mac: String, rssi: Int, source: String, best: Hit?, name: String?, vendor: String?, type: String?,
        loc: Location?, now: Long, probed: List<String> = emptyList()
    ) {
        val id = activeTripId ?: return
        devices.compute(mac) { _, held ->
            val old = held ?: readBack(id, mac)
            val stronger = old == null || rssi > old.bestRssi
            val hitCategory = best?.category?.name ?: old?.category
            TripDeviceEntity(
                tripId = id,
                mac = mac,
                label = best?.label ?: old?.label ?: name ?: type ?: mac,
                name = name ?: old?.name,
                vendor = vendor ?: old?.vendor,
                deviceType = type ?: old?.deviceType,
                source = source,
                category = hitCategory,
                confidence = maxOf(best?.confidence ?: 0, old?.confidence ?: 0),
                evidence = best?.evidence ?: old?.evidence,
                firstSeen = old?.firstSeen ?: now,
                lastSeen = now,
                bestRssi = if (stronger) rssi else old!!.bestRssi,
                lat = if (stronger && loc != null) loc.latitude else old?.lat ?: loc?.latitude,
                lon = if (stronger && loc != null) loc.longitude else old?.lon ?: loc?.longitude,
                probed = mergeProbed(old?.probedList.orEmpty(), probed)
            )
        }
        dirty += mac
    }

    /** A device let go from memory earlier, read back from the database (null when it is new). */
    private fun readBack(id: Long, mac: String): TripDeviceEntity? {
        if (!evicted.remove(mac)) return null
        evictedFlagged.remove(mac)
        return runCatching { tripDao?.deviceNow(id, mac) }.getOrNull()
    }

    /** Lets go of devices not heard for a while (see [DEVICES_IN_MEMORY]); only ones already written. */
    private fun evictIdle(now: Long) {
        if (devices.size <= DEVICES_IN_MEMORY) return
        for ((mac, d) in devices) {
            if (now - d.lastSeen < DEVICE_IDLE_MS || mac in dirty) continue
            evicted += mac
            if (d.flagged) evictedFlagged += mac
            // Changed meanwhile (heard again): it stays.
            if (!devices.remove(mac, d)) { evicted -= mac; evictedFlagged -= mac }
        }
    }

    /** Network names asked for so far plus new ones, oldest first, at most 16 (null when none). */
    internal fun mergeProbed(old: List<String>, new: List<String>): String? {
        if (new.isEmpty()) return old.ifEmpty { null }?.joinToString("\n")
        val all = LinkedHashSet(old).apply { new.forEach { remove(it); add(it) } }.toList().takeLast(16)
        return all.joinToString("\n")
    }

    /** Writes buffered points / changed devices and the trip's running totals. */
    suspend fun flush(context: Context, closing: Boolean = false) = writing.withLock {
        val id: Long
        val newPoints: List<TripPointEntity>
        val changed: List<TripDeviceEntity>
        val totalPoints: Int
        val distance: Double
        synchronized(this) {
            id = activeTripId ?: return@withLock
            newPoints = pendingPoints.toList(); pendingPoints.clear()
            changed = takeDirty()
            totalPoints = points.size
            distance = distanceM
        }
        val dao = AppDatabase.getInstance(context).tripDao()
        if (newPoints.isNotEmpty()) dao.insertPoints(newPoints)
        if (changed.isNotEmpty()) changed.chunked(300).forEach { dao.upsertDevices(it) }
        // Stopped while writing: the trip's final totals come from persist (which waits for this), not from here.
        if (activeTripId != id) return@withLock
        evictIdle(System.currentTimeMillis())
        dao.updateStats(
            id, if (closing) System.currentTimeMillis() else null, distance,
            totalPoints, deviceCount(), flaggedCount()
        )
    }

    /** Closes a trip left open by a crash or force-stop (called at app start). */
    suspend fun closeStale(context: Context) {
        if (isRecording) return
        val dao = AppDatabase.getInstance(context).tripDao()
        val open = dao.openTrip() ?: return
        val pts = dao.points(open.id)
        val end = pts.lastOrNull()?.time ?: open.startTime
        var dist = 0.0
        pts.zipWithNext { a, b -> dist += DeviceRegistry.metersBetween(a.lat, a.lon, b.lat, b.lon) }
        dao.updateStats(open.id, end, dist, pts.size, dao.deviceCount(open.id), dao.flaggedCount(open.id))
    }
}
