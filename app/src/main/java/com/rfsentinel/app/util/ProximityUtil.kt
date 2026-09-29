package com.rfsentinel.app.util

/**
 * Very coarse signal-strength banding. RSSI is NOT a reliable distance
 * measure (it varies with antenna orientation, obstacles, device power
 * settings) - treat this as "closer/farther", never as meters.
 */
object ProximityUtil {
    fun band(rssi: Int): String = when {
        rssi >= -60 -> "Near"
        rssi >= -80 -> "Nearby"
        else -> "Far"
    }
}
