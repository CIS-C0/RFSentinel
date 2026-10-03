package com.rfsentinel.app.ui

import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.service.DeviceRegistry

/** The device filters shared by the list and the map, so both pick the same devices. */
enum class DeviceFilter(val label: String) {
    // Chip order: most useful first.
    ALL("All"),
    FLAGGED("Flagged"),
    TRACKERS("Trackers"),
    DRONES("Drones"),
    FAVORITES("Favorites"),
    NEW("New"),
    BLE("Bluetooth"),
    WIFI("WiFi"),
    /** Cell towers: not devices, listed from the cell snapshot (list only, not the map). */
    CELLS("Cells"),
    ESP32("ESP32");

    fun matches(s: DeviceRegistry.Snapshot): Boolean = when (this) {
        ALL -> true
        FLAGGED -> s.best != null && !WhitelistCache.contains(s.mac)
        TRACKERS -> !(excludeAirTags && isAppleFindMy(s)) && (s.hits.any { it.category == Category.TRACKER } ||
            s.deviceType.contains("tracker", true) || s.deviceType.contains("Find My", true))
        DRONES -> s.hits.any { it.category == Category.DRONE } || s.remoteId != null
        NEW -> s.isNew
        CELLS -> false
        ESP32 -> com.rfsentinel.app.esp.HeardBy.esp.recent(s.mac) || com.rfsentinel.app.esp.HeardBy.usb.recent(s.mac)
        FAVORITES -> Favorites.contains(s.mac)
        BLE -> Advert.Source.BLE in s.sources
        WIFI -> Advert.Source.WIFI in s.sources
    }

    companion object {
        /** Settings > Exclude Apple AirTags: kept here so the list and map filters needn't read prefs per device. */
        @Volatile var excludeAirTags = false

        private fun isAppleFindMy(s: DeviceRegistry.Snapshot) =
            s.deviceType.contains("Find My", true) || s.deviceType.contains("AirTag", true) ||
                s.hits.any { com.rfsentinel.app.detect.SignatureEngine.isAppleFindMy(it) }

        fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: ALL
    }
}
