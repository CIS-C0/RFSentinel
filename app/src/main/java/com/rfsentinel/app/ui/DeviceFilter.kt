package com.rfsentinel.app.ui

import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
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
    /** RTL-SDR radio hits: not devices, listed from the radio log (list only). */
    RADIO("Radio"),
    /** Waze reports: not devices, drawn on the map from the Waze poll (map only). */
    WAZE("Waze"),
    /** Aircraft from the RTL-SDR's ADS-B receiver and the online feeds: not devices (list and map). */
    AIRCRAFT("Aircraft"),
    /** Heard by external hardware: an ESP32 board (USB or OUI-SPY over Bluetooth) or a USB WiFi adapter. */
    EXTERNAL("External"),
    /** Heard by a linked Sentinel (Sentinel Link). */
    SENTINELS("Sentinels"),
    // Kinds of device, from what identified them (see DeviceIntel).
    VEHICLES("Vehicles"),
    PHONES("Phones"),
    AUDIO("Audio"),
    CAMERAS("Cameras"),
    ROUTERS("Routers"),
    HOTSPOTS("Hotspots"),
    TVS("TVs & consoles"),
    SMART_HOME("Smart home");

    fun matches(s: DeviceRegistry.Snapshot): Boolean = when (this) {
        ALL -> true
        FLAGGED -> s.best != null && !WhitelistCache.contains(s.mac)
        TRACKERS -> !(excludeAirTags && isAppleFindMy(s)) && (s.hits.any { it.category == Category.TRACKER } ||
            s.deviceType.contains("tracker", true) || s.deviceType.contains("Find My", true))
        DRONES -> s.hits.any { it.category == Category.DRONE } || s.remoteId != null || s.deviceType.startsWith("Drone")
        NEW -> s.isNew
        CELLS, WAZE, RADIO, AIRCRAFT -> false
        EXTERNAL -> com.rfsentinel.app.esp.HeardBy.esp.recent(s.mac) || com.rfsentinel.app.esp.HeardBy.usb.recent(s.mac)
        SENTINELS -> com.rfsentinel.app.esp.HeardBy.peer.recent(s.mac)
        FAVORITES -> Favorites.contains(s.mac)
        BLE -> Advert.Source.BLE in s.sources
        WIFI -> Advert.Source.WIFI in s.sources
        VEHICLES -> s.deviceType.startsWith("Vehicle")
        PHONES -> DeviceIntel.isPhone(s.deviceType, s.name)
        AUDIO -> AUDIO_TYPE.containsMatchIn(s.deviceType) || s.facts.contains("Identified by" to "Fast Pair model ID")
        CAMERAS -> CAMERA_TYPE.containsMatchIn(s.deviceType)
        ROUTERS -> s.deviceType.startsWith("Home router")
        HOTSPOTS -> HOTSPOT_TYPE.containsMatchIn(s.deviceType)
        TVS -> TV_TYPE.containsMatchIn(s.deviceType)
        SMART_HOME -> SMART_HOME_TYPE.containsMatchIn(s.deviceType)
    }

    companion object {
        /** Settings > Exclude Apple AirTags: kept here so the list and map filters needn't read prefs per device. */
        @Volatile var excludeAirTags = false

        /** The device-kind filters (from the device's type), after the main ones. */
        val KINDS = listOf(VEHICLES, PHONES, AUDIO, CAMERAS, ROUTERS, HOTSPOTS, TVS, SMART_HOME)

        private val AUDIO_TYPE = Regex("headphone|earbud|airpods|beats|speaker|soundbar|\\bbuds\\b|audio", RegexOption.IGNORE_CASE)
        private val CAMERA_TYPE = Regex("^(dashcam|action camera|security camera|smart-home / camera)", RegexOption.IGNORE_CASE)
        private val HOTSPOT_TYPE = Regex("^(public wifi hotspot|mobile hotspot box|phone hotspot)", RegexOption.IGNORE_CASE)
        private val TV_TYPE = Regex("^(smart tv|tv / streaming|game console|apple tv)", RegexOption.IGNORE_CASE)
        private val SMART_HOME_TYPE = Regex("^(iot board|iot / smart-home|smart plug|smart-home device)", RegexOption.IGNORE_CASE)

        private fun isAppleFindMy(s: DeviceRegistry.Snapshot) =
            s.deviceType.contains("Find My", true) || s.deviceType.contains("AirTag", true) ||
                s.hits.any { com.rfsentinel.app.detect.SignatureEngine.isAppleFindMy(it) }

        fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: ALL

        /**
         * The main screen's chip order: the counts worth a glance first (flagged, then what's heard on each
         * radio), so they fit without scrolling; the narrower filters after them.
         */
        val MAIN_ORDER = listOf(ALL, FLAGGED, BLE, WIFI, CELLS, TRACKERS, DRONES, AIRCRAFT, RADIO, WAZE, EXTERNAL, SENTINELS, FAVORITES, NEW) + KINDS
    }
}
