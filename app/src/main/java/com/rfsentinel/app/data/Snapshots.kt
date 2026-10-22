package com.rfsentinel.app.data

import android.content.Context
import com.rfsentinel.app.service.DeviceRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snapshots: the device list saved as it is at one moment, to look at again later without recording a trace.
 * Kept with the traces (same tables, [TripEntity.KIND_SNAPSHOT], no route), so they open on the map and export
 * the same way.
 */
object Snapshots {

    fun defaultName(now: Long = System.currentTimeMillis()): String =
        "Snapshot " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))

    /** Saves [devices] under [name]; returns the new snapshot's id. */
    suspend fun save(context: Context, name: String, devices: List<DeviceRegistry.Snapshot>): Long {
        val now = System.currentTimeMillis()
        val dao = AppDatabase.getInstance(context).tripDao()
        val rows = devices.map { s ->
            // Trusted (whitelisted) devices are saved unflagged, as a trace records them.
            val best = s.best?.takeUnless { WhitelistCache.contains(s.mac) }
            val place = s.place
            TripDeviceEntity(
                tripId = 0, mac = s.mac,
                label = best?.label ?: s.name ?: s.deviceType.ifEmpty { s.mac },
                name = s.name, vendor = s.vendor, deviceType = s.deviceType.ifEmpty { null },
                source = s.source.name,
                category = best?.category?.name, confidence = best?.confidence ?: 0, evidence = best?.evidence,
                firstSeen = s.firstSeen, lastSeen = s.lastSeen, bestRssi = s.bestRssi,
                lat = place?.lat, lon = place?.lon
            )
        }
        val id = dao.insertTrip(TripEntity(
            name = name, startTime = now, endTime = now,
            deviceCount = rows.size, flaggedCount = rows.count { it.flagged }, kind = TripEntity.KIND_SNAPSHOT
        ))
        rows.map { it.copy(tripId = id) }.chunked(300).forEach { dao.upsertDevices(it) }
        return id
    }
}
