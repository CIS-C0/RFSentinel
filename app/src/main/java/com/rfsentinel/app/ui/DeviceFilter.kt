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
            TRACKER_TYPE.containsMatchIn(s.type))
        DRONES -> s.hits.any { it.category == Category.DRONE } || s.remoteId || s.type.startsWith("Drone")
        NEW -> s.isNew
        CELLS, WAZE, RADIO, AIRCRAFT -> false
        EXTERNAL -> com.rfsentinel.app.esp.HeardBy.esp.recent(s.mac) || com.rfsentinel.app.esp.HeardBy.usb.recent(s.mac)
        SENTINELS -> com.rfsentinel.app.esp.HeardBy.peer.recent(s.mac)
        FAVORITES -> Favorites.contains(s.mac)
        BLE -> Advert.Source.BLE in s.sources
        WIFI -> Advert.Source.WIFI in s.sources
        VEHICLES -> VEHICLE_TYPE.containsMatchIn(s.type) || s.hits.any { it.category == Category.V2X }
        // A watch counts as its owner's phone for tracker checks, not on this chip.
        PHONES -> DeviceIntel.isPhone(s.type, s.name) && !s.type.startsWith("Apple Watch")
        AUDIO -> (AUDIO_TYPE.containsMatchIn(s.type) || s.facts.contains("Identified by" to "Fast Pair model ID")) &&
            s.hits.none { it.category == Category.AUDIO_SENSOR }
        CAMERAS -> CAMERA_TYPE.containsMatchIn(s.type) || s.hits.any { it.category in CAMERA_CATEGORIES }
        ROUTERS -> ROUTER_TYPE.containsMatchIn(s.type)
        HOTSPOTS -> HOTSPOT_TYPE.containsMatchIn(s.type)
        TVS -> TV_TYPE.containsMatchIn(s.type)
        SMART_HOME -> SMART_HOME_TYPE.containsMatchIn(s.type) && !VEHICLE_TYPE.containsMatchIn(s.type)
    }

    companion object {
        /** Settings > Exclude Apple AirTags: kept here so the list and map filters needn't read prefs per device. */
        @Volatile var excludeAirTags = false

        /** The device-kind filters (from the device's type), after the main ones. */
        val KINDS = listOf(VEHICLES, PHONES, AUDIO, CAMERAS, ROUTERS, HOTSPOTS, TVS, SMART_HOME)

        // Each kind from the types DeviceIntel gives (its own names, the Bluetooth appearance names and
        // older versions' names, so saved snapshots and traces sort the same way).
        private fun kind(re: String) = Regex(re, RegexOption.IGNORE_CASE)
        private val TRACKER_TYPE = kind("tracker|find my|airtag|key finder|^tag$|^keyring")
        private val VEHICLE_TYPE = kind("^vehicle|^motorized vehicle|^train wifi|tire pressure")
        private val AUDIO_TYPE = kind("headphone|earbud|airpods|beats|speaker|soundbar|\\bbuds\\b|audio|headset|hearing aid|" +
            "bose|jbl|jabra|sennheiser|soundcore|skullcandy|sonos")
        private val CAMERA_TYPE = kind("^(dashcam|action camera|security camera|camera)|body cam|\\bcam(era)?\\b")
        /** Detection categories that are cameras (police, home security, action / hidden, recording glasses). */
        private val CAMERA_CATEGORIES = setOf(Category.BODY_CAM, Category.ALPR, Category.NETWORK_CAMERA, Category.OTHER_CAMERA, Category.GLASSES)
        private val ROUTER_TYPE = kind("^(home router|wifi access point|vehicle / cellular router|mesh router)|access point$")
        // Business access points and account sign-in WiFi stay off both: they're neither.
        private val HOTSPOT_TYPE = kind("^(public wifi hotspot|mobile hotspot box|phone hotspot|train wifi|open wifi)")
        private val TV_TYPE = kind("^(smart tv|tv / streaming|game console|apple tv)|television|set-top|media player|^gaming")
        private val SMART_HOME_TYPE = kind("smart-home|smart home|^iot|smart plug|bthome|^sensor|environmental sensor|light|" +
            "thermostat|^hvac|^heating|air conditioning|humidifier|^fan\\b|access control|door lock|power device|domestic appliance|" +
            "window covering|motorized device")

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
