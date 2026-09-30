package com.rfsentinel.app.detect

import com.rfsentinel.app.service.DeviceRegistry
import kotlin.math.roundToInt

/**
 * "Drone overhead": a drone whose Remote ID position is within [RADIUS_M] of
 * you (horizontal distance). Remote ID broadcasts the aircraft's own GPS
 * position, so unlike RSSI this is a real location.
 */
object DroneProximity {

    const val RADIUS_M = 200.0

    /** The alert hit, or null when the drone is farther away or its position is unknown. */
    fun overheadHit(info: RemoteId.Info, myLat: Double, myLon: Double): Hit? {
        val lat = info.latitude ?: return null
        val lon = info.longitude ?: return null
        val d = DeviceRegistry.metersBetween(myLat, myLon, lat, lon)
        if (d > RADIUS_M) return null
        val height = info.heightM ?: info.altitudeGeoM
        val detail = buildString {
            append("Remote ID position ~${d.roundToInt()} m from you")
            height?.let { append(", ${it.roundToInt()} m up") }
            info.speedMs?.let { append(", ${(it * 3.6).roundToInt()} km/h") }
            info.uasId?.let { append(" (ID $it)") }
        }
        return Hit(Category.DRONE, "Drone overhead", 95, detail, "ASTM F3411 Remote ID (the drone's own GPS position)")
    }
}
