package com.rfsentinel.app.ui

import android.content.Context
import com.rfsentinel.app.data.Favorites
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.Prefs

/** The device filters shared by the list and the map, so both pick the same devices. */
enum class DeviceFilter(val label: String, val liveOnly: Boolean = false) {
    // Chip order: most useful first.
    ALL("All"),
    FLAGGED("Flagged"),
    TRACKERS("Trackers"),
    DRONES("Drones"),
    FAVORITES("Favorites"),
    NEW("New", liveOnly = true),
    BLE("Bluetooth"),
    WIFI("WiFi"),
    /** Cell towers: not devices, listed from the cell snapshot (list only, not the map). */
    CELLS("Cells", liveOnly = true),
    /** RTL-SDR radio hits: not devices, listed from the radio log (list only). */
    RADIO("Radio", liveOnly = true),
    /** Waze reports: not devices, drawn on the map from the Waze poll (map only). */
    WAZE("Waze", liveOnly = true),
    /** Aircraft from the RTL-SDR's ADS-B receiver and the online feeds: not devices (list and map). */
    AIRCRAFT("Aircraft", liveOnly = true),
    /** Heard by external hardware: an ESP32 board (USB or OUI-SPY over Bluetooth) or a USB WiFi adapter. */
    EXTERNAL("External", liveOnly = true),
    /** Heard by a linked Sentinel (Sentinel Link). */
    SENTINELS("Sentinels", liveOnly = true),
    // Kinds of device, from what identified them (see DeviceIntel).
    VEHICLES("Vehicles"),
    PHONES("Phones"),
    AUDIO("Audio"),
    CAMERAS("Cameras"),
    ROUTERS("Routers"),
    HOTSPOTS("Hotspots"),
    TVS("TVs & consoles"),
    SMART_HOME("Smart home");

    /**
     * What a filter looks at, the same for a device heard now and one saved in a snapshot or a trace,
     * so every filter (and any added later) works on both.
     */
    class Seen(
        val mac: String,
        val type: String,
        val name: String?,
        val hits: List<Hit>,
        val sources: Set<Advert.Source>,
        val flagged: Boolean,
        val facts: List<Pair<String, String>> = emptyList(),
        val isNew: Boolean = false,
        val remoteId: Boolean = false
    )

    fun matches(s: DeviceRegistry.Snapshot): Boolean = matches(Seen(
        s.mac, s.deviceType, s.name, s.hits, s.sources, s.best != null && !WhitelistCache.contains(s.mac),
        s.facts, s.isNew, s.remoteId != null
    ))

    /** A device saved in a snapshot or a trace (its type read with today's names, see [DeviceIntel.savedType]). */
    fun matches(d: TripDeviceEntity): Boolean = !liveOnly && matches(Seen(
        d.mac, DeviceIntel.savedType(d.deviceType, d.vendor, d.mac).orEmpty(), d.name,
        listOfNotNull(Category.parse(d.category)?.let { Hit(it, d.label, d.confidence, d.evidence.orEmpty(), "") }),
        setOfNotNull(Advert.Source.entries.firstOrNull { it.name == d.source }), d.flagged
    ))

    fun matches(s: Seen): Boolean = when (this) {
        ALL -> true
        FLAGGED -> s.flagged
        TRACKERS -> !(excludeAirTags && isAppleFindMy(s)) && (s.hits.any { it.category == Category.TRACKER } ||
            s.type.contains("tracker", true) || s.type.contains("Find My", true))
        DRONES -> s.hits.any { it.category == Category.DRONE } || s.remoteId || s.type.startsWith("Drone")
        NEW -> s.isNew
        CELLS, WAZE, RADIO, AIRCRAFT -> false
        EXTERNAL -> com.rfsentinel.app.esp.HeardBy.esp.recent(s.mac) || com.rfsentinel.app.esp.HeardBy.usb.recent(s.mac)
        SENTINELS -> com.rfsentinel.app.esp.HeardBy.peer.recent(s.mac)
        FAVORITES -> Favorites.contains(s.mac)
        BLE -> Advert.Source.BLE in s.sources
        WIFI -> Advert.Source.WIFI in s.sources
        VEHICLES -> s.type.startsWith("Vehicle")
        PHONES -> DeviceIntel.isPhone(s.type, s.name)
        AUDIO -> AUDIO_TYPE.containsMatchIn(s.type) || s.facts.contains("Identified by" to "Fast Pair model ID")
        CAMERAS -> CAMERA_TYPE.containsMatchIn(s.type)
        ROUTERS -> s.type.startsWith("Home router")
        HOTSPOTS -> HOTSPOT_TYPE.containsMatchIn(s.type)
        TVS -> TV_TYPE.containsMatchIn(s.type)
        SMART_HOME -> SMART_HOME_TYPE.containsMatchIn(s.type)
    }

    companion object {
        /** Settings > Exclude Apple AirTags: kept here so the list and map filters needn't read prefs per device. */
        @Volatile var excludeAirTags = false

        /** The device-kind filters (from the device's type), after the main ones. */
        val KINDS = listOf(VEHICLES, PHONES, AUDIO, CAMERAS, ROUTERS, HOTSPOTS, TVS, SMART_HOME)

        private val AUDIO_TYPE = Regex("headphone|earbud|airpods|beats|speaker|soundbar|\\bbuds\\b|audio|bose|jbl|jabra|sennheiser|soundcore|skullcandy|sonos", RegexOption.IGNORE_CASE)
        private val CAMERA_TYPE = Regex("^(dashcam|action camera|security camera|smart-home / camera)", RegexOption.IGNORE_CASE)
        private val HOTSPOT_TYPE = Regex("^(public wifi hotspot|mobile hotspot box|phone hotspot)", RegexOption.IGNORE_CASE)
        private val TV_TYPE = Regex("^(smart tv|tv / streaming|game console|apple tv)", RegexOption.IGNORE_CASE)
        private val SMART_HOME_TYPE = Regex("^(iot board|iot / smart-home|smart plug|smart-home device)", RegexOption.IGNORE_CASE)

        private fun isAppleFindMy(s: Seen) =
            s.type.contains("Find My", true) || s.type.contains("AirTag", true) ||
                s.hits.any { com.rfsentinel.app.detect.SignatureEngine.isAppleFindMy(it) }

        fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: ALL

        /**
         * The main screen's chip order: the counts worth a glance first (flagged, then what's heard on each
         * radio), so they fit without scrolling; the narrower filters after them.
         */
        val MAIN_ORDER = listOf(ALL, FLAGGED, BLE, WIFI, CELLS, TRACKERS, DRONES, AIRCRAFT, RADIO, WAZE, EXTERNAL, SENTINELS, FAVORITES, NEW) + KINDS

        /** The chips of a saved snapshot or trace: the main screen's, less those that only mean something live. */
        val SAVED_ORDER = MAIN_ORDER.filterNot { it.liveOnly }

        /** The main screen's chosen filter, when a saved snapshot or trace can use it (else All). */
        fun savedStart(context: Context) = parse(Prefs.mapFilter(context)).takeIf { it in SAVED_ORDER } ?: ALL
    }
}
