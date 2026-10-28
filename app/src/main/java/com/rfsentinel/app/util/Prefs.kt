package com.rfsentinel.app.util

import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.core.content.edit
import com.rfsentinel.app.detect.Category

object Prefs {
    private const val NAME = "rf_sentinel_prefs"

    private fun sp(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun bool(c: Context, key: String, def: Boolean) = sp(c).getBoolean(key, def)
    private fun setBool(c: Context, key: String, v: Boolean) = sp(c).edit { putBoolean(key, v) }
    private fun int(c: Context, key: String, def: Int) = sp(c).getInt(key, def)
    private fun setInt(c: Context, key: String, v: Int) = sp(c).edit { putInt(key, v) }

    // ---- Scanning -------------------------------------------------------------

    // Android allows ~4 WiFi scans per 2 minutes per app; polling faster than
    // ~30s just gets throttled (visible as "Scan request ... throttled" in logcat).
    fun scanIntervalMs(context: Context): Long =
        sp(context).getLong("scan_interval_ms", 30_000L)

    fun setScanIntervalMs(context: Context, value: Long) {
        sp(context).edit { putLong("scan_interval_ms", value) }
    }

    fun bleEnabled(context: Context): Boolean = bool(context, "ble_enabled", true)
    fun setBleEnabled(context: Context, value: Boolean) = setBool(context, "ble_enabled", value)

    fun wifiEnabled(context: Context): Boolean = bool(context, "wifi_enabled", true)
    fun setWifiEnabled(context: Context, value: Boolean) = setBool(context, "wifi_enabled", value)

    /** ScanSettings.SCAN_MODE_*; low latency catches the most but costs the most battery. */
    fun bleScanMode(context: Context): Int = int(context, "ble_scan_mode", ScanSettings.SCAN_MODE_LOW_LATENCY)
    fun setBleScanMode(context: Context, value: Int) = setInt(context, "ble_scan_mode", value)

    fun autoStartOnBoot(context: Context): Boolean = bool(context, "auto_start_boot", false)
    fun setAutoStartOnBoot(context: Context, value: Boolean) = setBool(context, "auto_start_boot", value)

    // ---- Detection ------------------------------------------------------------

    fun categoryEnabled(context: Context, category: Category): Boolean =
        bool(context, "cat_" + category.name, category.defaultEnabled)

    fun setCategoryEnabled(context: Context, category: Category, value: Boolean) =
        setBool(context, "cat_" + category.name, value)

    // ---- Alerts ---------------------------------------------------------------

    fun soundEnabled(context: Context): Boolean = bool(context, "sound_enabled", true)
    fun setSoundEnabled(context: Context, value: Boolean) = setBool(context, "sound_enabled", value)

    fun vibrateEnabled(context: Context): Boolean = bool(context, "vibrate_enabled", true)
    fun setVibrateEnabled(context: Context, value: Boolean) = setBool(context, "vibrate_enabled", value)

    /** Speak the alert ("Axon body camera nearby") via text-to-speech. */
    fun voiceEnabled(context: Context): Boolean = bool(context, "voice_enabled", false)
    fun setVoiceEnabled(context: Context, value: Boolean) = setBool(context, "voice_enabled", value)
    /** Speech rate for spoken alerts (1.0 = the engine's normal speed). */
    fun voiceRate(context: Context): Float = sp(context).getFloat("voice_rate", 1.0f)

    /** Extra loudness for spoken alerts, in dB (0..15): music in the car is often much louder than the voice. */
    fun voiceBoostDb(context: Context): Int = int(context, "voice_boost_db", 6).coerceIn(0, MAX_VOICE_BOOST_DB)
    fun setVoiceBoostDb(context: Context, db: Int) = setInt(context, "voice_boost_db", db.coerceIn(0, MAX_VOICE_BOOST_DB))
    const val MAX_VOICE_BOOST_DB = 15

    /** Pause music (instead of only lowering it) while an alert is spoken. */
    fun voicePausesMusic(context: Context): Boolean = bool(context, "voice_pauses_music", false)
    fun setVoicePausesMusic(context: Context, value: Boolean) = setBool(context, "voice_pauses_music", value)
    fun setVoiceRate(context: Context, rate: Float) = sp(context).edit { putFloat("voice_rate", rate) }
    /** The chosen speech-engine voice (null = best English voice on the phone). */
    fun voiceName(context: Context): String? = sp(context).getString("voice_name", null)
    fun setVoiceName(context: Context, name: String?) = sp(context).edit {
        if (name == null) remove("voice_name") else putString("voice_name", name)
    }

    /** Master mute for alert sound and voice (phone menu and Android Auto button). Vibration unaffected. */
    /** The user read and accepted the Waze / OpenWeb Ninja use-at-your-own-risk warning. */
    fun wazeAccepted(context: Context): Boolean = bool(context, "waze_accepted", false)
    fun setWazeAccepted(context: Context, value: Boolean) = setBool(context, "waze_accepted", value)

    /** Waze backend: "ninja" (OpenWeb Ninja key, default) or "direct" (Waze app protocol, anonymous account). */
    const val WAZE_NINJA = "ninja"
    const val WAZE_DIRECT = "direct"
    fun wazeBackend(context: Context): String =
        if (sp(context).getString("waze_backend", WAZE_NINJA) == WAZE_DIRECT) WAZE_DIRECT else WAZE_NINJA
    fun setWazeBackend(context: Context, value: String) = sp(context).edit { putString("waze_backend", value) }
    /** How far away (km, 1-20, default 5) Waze reports are drawn on the map. */
    fun wazeViewKm(context: Context): Int = int(context, "waze_view_km", 5).coerceIn(1, 20)
    fun setWazeViewKm(context: Context, km: Int) = setInt(context, "waze_view_km", km.coerceIn(1, 20))
    /** The distances the Waze alert-range slider steps through, in metres (100 m up to 10 km). */
    val WAZE_ALERT_STEPS_M = intArrayOf(100, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 7500, 10000)
    /** How close (metres, default 2000) a Waze report must be to alert, independent of the view range. */
    fun wazeAlertM(context: Context): Int {
        val m = int(context, "waze_alert_m", 2000)
        return WAZE_ALERT_STEPS_M.minByOrNull { kotlin.math.abs(it - m) } ?: 2000
    }
    fun setWazeAlertM(context: Context, m: Int) = setInt(context, "waze_alert_m", m)
    /** "100 m", "750 m", "1.5 km", "10 km". */
    fun formatRange(m: Int): String = if (Units.imperial) Units.length(m.toDouble())
        else if (m < 1000) "$m m" else if (m % 1000 == 0) "${m / 1000} km" else "${m / 1000.0} km"
    /** How often Waze is asked for reports, in seconds: each backend has its own steps and default. */
    val WAZE_DIRECT_INTERVALS_S get() = com.rfsentinel.app.online.WazeTiming.DIRECT_STEPS_S
    val WAZE_NINJA_INTERVALS_S get() = com.rfsentinel.app.online.WazeTiming.NINJA_STEPS_S
    private fun wazeIntervalKey(direct: Boolean) = if (direct) "waze_interval_direct_s" else "waze_interval_ninja_s"
    fun wazeIntervalS(context: Context, direct: Boolean): Int {
        val steps = com.rfsentinel.app.online.WazeTiming.steps(direct)
        val v = int(context, wazeIntervalKey(direct),
            if (direct) com.rfsentinel.app.online.WazeTiming.DIRECT_DEFAULT_S else com.rfsentinel.app.online.WazeTiming.NINJA_DEFAULT_S)
        return steps.minByOrNull { kotlin.math.abs(it - v) } ?: steps[0]
    }
    fun setWazeIntervalS(context: Context, direct: Boolean, seconds: Int) = setInt(context, wazeIntervalKey(direct), seconds)
    /** "15 s", "90 s", "2 min", "10 min". */
    fun formatInterval(seconds: Int): String = if (seconds < 120) "$seconds s" else "${seconds / 60} min"

    /** Slower checks when parked and faster on fast roads (Waze direct only). On by default. */
    fun wazeAdaptive(context: Context): Boolean = bool(context, "waze_adaptive", true)
    fun setWazeAdaptive(context: Context, value: Boolean) = setBool(context, "waze_adaptive", value)
    /** Alert only for reports that are not behind you while you drive. Off by default. */
    fun wazeAheadOnly(context: Context): Boolean = bool(context, "waze_ahead_only", false)
    fun setWazeAheadOnly(context: Context, value: Boolean) = setBool(context, "waze_ahead_only", value)
    /** With "only ahead" on, reports that are not on your way are left off the list (the map keeps them). On by default. */
    fun wazeHideOffWay(context: Context): Boolean = bool(context, "waze_hide_off_way", true)
    fun setWazeHideOffWay(context: Context, value: Boolean) = setBool(context, "waze_hide_off_way", value)
    /** Speak again as a report gets closer (1 km, 500 m, 200 m). On by default. */
    fun wazeApproach(context: Context): Boolean = bool(context, "waze_approach", true)
    fun setWazeApproach(context: Context, value: Boolean) = setBool(context, "waze_approach", value)
    /** Waze checks paused from the Waze status screen (nothing is asked or shown). */
    fun wazePaused(context: Context): Boolean = bool(context, "waze_paused", false)
    fun setWazePaused(context: Context, value: Boolean) = setBool(context, "waze_paused", value)
    /** What an alert of one Waze event type does: sound and voice, a notification only, or nothing beyond the list and map. */
    fun wazeLevel(context: Context, type: com.rfsentinel.app.online.WazePolice.Type): com.rfsentinel.app.online.WazePolice.Level {
        val levels = com.rfsentinel.app.online.WazePolice.Level.entries
        return levels.getOrNull(int(context, "waze_level_${type.name}", type.defaultLevel.ordinal)) ?: type.defaultLevel
    }
    fun setWazeLevel(context: Context, type: com.rfsentinel.app.online.WazePolice.Type, level: com.rfsentinel.app.online.WazePolice.Level) =
        setInt(context, "waze_level_${type.name}", level.ordinal)

    /** Which Waze event kinds to alert on (WazePolice.Type names); police only by default. */
    fun wazeTypes(context: Context): Set<String> =
        sp(context).getStringSet("waze_types", null)?.takeIf { it.isNotEmpty() } ?: setOf("POLICE")
    fun setWazeTypes(context: Context, value: Set<String>) = sp(context).edit { putStringSet("waze_types", value) }

    /** The user read and accepted the warning that "direct" sends a (blurred) position to Waze, a Google service. */
    fun wazeDirectAccepted(context: Context): Boolean = bool(context, "waze_direct_accepted", false)
    fun setWazeDirectAccepted(context: Context, value: Boolean) = setBool(context, "waze_direct_accepted", value)
    /** True when the chosen Waze backend's warning has been accepted. */
    fun wazeReady(context: Context): Boolean =
        wazeAccepted(context) && (wazeBackend(context) != WAZE_DIRECT || wazeDirectAccepted(context))

    fun alertsMuted(context: Context): Boolean = bool(context, "alerts_muted", false)
    fun setAlertsMuted(context: Context, value: Boolean) = setBool(context, "alerts_muted", value)

    /** Alert sound and voice snoozed until this time (the toolbar speaker, a notification's Mute, the car); 0 = not snoozed. */
    fun alertsSnoozedUntil(context: Context): Long = sp(context).getLong("alerts_snoozed_until", 0L)
    fun setAlertsSnoozedUntil(context: Context, time: Long) = sp(context).edit { putLong("alerts_snoozed_until", time) }

    /** How long a snooze lasts, in minutes (one of [SNOOZE_CHOICES]; 30 as before). */
    fun snoozeMinutes(context: Context): Int = int(context, "snooze_minutes", 30).let { m -> if (m in SNOOZE_CHOICES) m else 30 }
    fun setSnoozeMinutes(context: Context, minutes: Int) = setInt(context, "snooze_minutes", minutes)
    val SNOOZE_CHOICES = listOf(5, 10, 15, 30, 60)

    /** Muted, or snoozed for a while: no alert sound or voice. */
    fun alertsSilenced(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        alertsMuted(context) || now < alertsSnoozedUntil(context)

    /** Speak alerts whenever Android Auto is connected, even if phone voice alerts are off. */
    fun carVoice(context: Context): Boolean = bool(context, "car_voice", true)
    fun setCarVoice(context: Context, value: Boolean) = setBool(context, "car_voice", value)

    /** Minimum confidence (0-100) that sounds an alert; weaker matches are only highlighted. */
    fun alertThreshold(context: Context): Int = int(context, "alert_threshold", 50)
    fun setAlertThreshold(context: Context, value: Int) = setInt(context, "alert_threshold", value)

    /** Hide what was detected on the lock screen and in the status notification. */
    fun discreetMode(context: Context): Boolean = bool(context, "discreet_mode", false)
    fun setDiscreetMode(context: Context, value: Boolean) = setBool(context, "discreet_mode", value)

    fun dedupeWindowMs(context: Context): Long = sp(context).getLong("dedupe_window_ms", 5 * 60_000L)
    fun setDedupeWindowMs(context: Context, value: Long) {
        sp(context).edit { putLong("dedupe_window_ms", value) }
    }

    // ---- Location -------------------------------------------------------------

    fun gpsTaggingEnabled(context: Context): Boolean = bool(context, "gps_tagging_enabled", false)
    fun setGpsTaggingEnabled(context: Context, value: Boolean) = setBool(context, "gps_tagging_enabled", value)

    /** Warn when a tracker or flagged device keeps moving with you. Needs location updates. */
    /** Pinpoint flagged devices from many readings as you move (GPS runs precise every second while one is in range). */
    fun pinpointFlagged(context: Context): Boolean = bool(context, "pinpoint_flagged", true)
    fun setPinpointFlagged(context: Context, value: Boolean) = setBool(context, "pinpoint_flagged", value)

    /**
     * How the app turns: NORMAL follows auto-rotate (never upside down on phones like the Pixel), ALL_WAYS
     * follows auto-rotate including upside down, UPSIDE_DOWN always has the USB-C port at the top.
     */
    enum class Rotation { NORMAL, ALL_WAYS, UPSIDE_DOWN }

    fun rotation(context: Context): Rotation =
        sp(context).getString("rotation_mode", null)?.let { v -> Rotation.entries.firstOrNull { it.name == v } }
            // The earlier on/off switch.
            ?: if (bool(context, "upside_down", false)) Rotation.UPSIDE_DOWN else Rotation.NORMAL
    fun setRotation(context: Context, value: Rotation) = sp(context).edit { putString("rotation_mode", value.name) }

    fun followerAlerts(context: Context): Boolean = bool(context, "follower_alerts", true)
    fun setFollowerAlerts(context: Context, value: Boolean) = setBool(context, "follower_alerts", value)

    /** Start recording a trace automatically whenever scanning starts. */
    /** Warn when approaching a plate reader mapped in OpenStreetMap (needs downloaded data). */
    fun knownAlprAlerts(context: Context): Boolean = bool(context, "known_alpr_alerts", true)
    fun setKnownAlprAlerts(context: Context, value: Boolean) = setBool(context, "known_alpr_alerts", value)
    /** Warn when approaching a speed or red-light camera mapped in OpenStreetMap. */
    fun speedCameraAlerts(context: Context): Boolean = bool(context, "speed_camera_alerts", true)
    fun setSpeedCameraAlerts(context: Context, value: Boolean) = setBool(context, "speed_camera_alerts", value)
    /** Fetch known cameras for the map area on screen (and around you in the car) automatically. */
    /** Radius of "download the cameras around me" (Settings, setup wizard, map menu), in km. */
    /** How far to look for aircraft (ADS-B feeds), 10-50 km. */
    /** How long an ordinary device stays in the list / radar after it was last heard (seconds). */
    fun liveBleSec(context: Context): Int = int(context, "live_ble_sec", 30).coerceIn(5, 180)
    fun setLiveBleSec(context: Context, sec: Int) = setInt(context, "live_ble_sec", sec.coerceIn(5, 180))
    fun liveWifiSec(context: Context): Int = int(context, "live_wifi_sec", 60).coerceIn(minLiveWifiSec(context), 180)

    /**
     * The WiFi list window can go below 30 s only when "Wi-Fi scan throttling" is turned off in
     * Developer options: otherwise Android scans about every 30 s and devices would drop out between two scans.
     */
    fun minLiveWifiSec(context: Context): Int = minLiveWifiSec(wifiScanThrottled(context))
    fun minLiveWifiSec(throttled: Boolean): Int = if (throttled) 30 else 5

    /** Android's WiFi scan throttle (Developer options), on unless the user disabled it. */
    /** Android 11+ keeps the developer toggle in the WiFi service; older versions in a global setting. */
    fun wifiScanThrottled(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30)
            runCatching { context.getSystemService(android.net.wifi.WifiManager::class.java).isScanThrottleEnabled }.getOrDefault(true)
        else runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "wifi_scan_throttle_enabled", 1) != 0 }.getOrDefault(true)
    // Stored as chosen (down to 5 s); [liveWifiSec] raises it to 30 s while WiFi scans are throttled.
    fun setLiveWifiSec(context: Context, sec: Int) = setInt(context, "live_wifi_sec", sec.coerceIn(5, 180))
    /** How long a device heard only by external hardware (ESP32 board, USB WiFi adapter) stays in the list and radar. */
    fun liveExternalSec(context: Context): Int = int(context, "live_external_sec", 60).coerceIn(5, 180)
    fun setLiveExternalSec(context: Context, sec: Int) = setInt(context, "live_external_sec", sec.coerceIn(5, 180))

    /** Police aircraft from the online ADS-B feeds; off leaves only the RTL-SDR's own receiver (on by default). */
    fun aircraftOnline(context: Context): Boolean = bool(context, "aircraft_online", true)
    fun setAircraftOnline(context: Context, v: Boolean) = setBool(context, "aircraft_online", v)

    fun aircraftRadiusKm(context: Context): Int = int(context, "aircraft_radius_km", 30).coerceIn(10, 50)
    fun setAircraftRadiusKm(context: Context, km: Int) = setInt(context, "aircraft_radius_km", km.coerceIn(10, 50))

    fun cameraRadiusKm(context: Context): Int = int(context, "camera_radius_km", 100).coerceIn(10, 200)
    fun setCameraRadiusKm(context: Context, km: Int) = setInt(context, "camera_radius_km", km.coerceIn(10, 200))

    /** Fetch the known cameras for the area in view. The FOSS (F-Droid) build fetches nothing until you've said yes. */
    fun autoCameras(context: Context): Boolean =
        bool(context, "auto_cameras", true) && (com.rfsentinel.app.BuildConfig.FLAVOR != "foss" || camerasAsked(context))
    /** Whether the FOSS build asked (once) if it may fetch the known cameras on its own. */
    fun camerasAsked(context: Context): Boolean = bool(context, "cameras_asked", false)
    fun setCamerasAsked(context: Context, value: Boolean) = setBool(context, "cameras_asked", value)
    fun setAutoCameras(context: Context, value: Boolean) = setBool(context, "auto_cameras", value)

    /** Check GitHub for a new release when the app opens. Off by default: it contacts GitHub. */
    fun autoUpdateCheck(context: Context): Boolean = bool(context, "auto_update_check", false)
    fun setAutoUpdateCheck(context: Context, value: Boolean) = setBool(context, "auto_update_check", value)
    /** Whether you were asked (once) if the app may check for updates; until then it never checks on its own. */
    fun updateCheckAsked(context: Context): Boolean = bool(context, "update_check_asked", false)
    fun setUpdateCheckAsked(context: Context, value: Boolean) = setBool(context, "update_check_asked", value)
    fun lastUpdateCheck(context: Context): Long = sp(context).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(context: Context, value: Long) = sp(context).edit { putLong("last_update_check", value) }
    /**
     * Map device filter (a [com.rfsentinel.app.ui.DeviceFilter] name). Older
     * versions had only "all devices" on/off: off becomes Flagged.
     */
    /** The live map's last zoom. A zoom level is not a place, so it is always kept. */
    fun mapZoom(context: Context): Double? = sp(context).getFloat("map_zoom", -1f).takeIf { it > 0f }?.toDouble()

    /** Where the live map last looked; remembered only with GPS tagging on, like the other saved positions. */
    fun lastMapCenter(context: Context): Pair<Double, Double>? {
        if (!gpsTaggingEnabled(context)) return null
        val parts = sp(context).getString("map_center", null)?.split(',') ?: return null
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun saveMapView(context: Context, lat: Double, lon: Double, zoom: Double) = sp(context).edit {
        putFloat("map_zoom", zoom.toFloat())
        if (gpsTaggingEnabled(context)) putString("map_center", "$lat,$lon") else remove("map_center")
    }

    fun mapFilter(context: Context): String =
        sp(context).getString("map_filter", null) ?: if (bool(context, "map_show_all", true)) "ALL" else "FLAGGED"
    fun setMapFilter(context: Context, name: String) = sp(context).edit { putString("map_filter", name) }

    /** The device lists' Sort by / Group by (see ui.DeviceOrder), shared by the main screen and snapshots. */
    fun listSort(context: Context): String? = sp(context).getString("list_sort", null)
    fun setListSort(context: Context, name: String) = sp(context).edit { putString("list_sort", name) }
    fun listGroup(context: Context): String? = sp(context).getString("list_group", null)
    fun setListGroup(context: Context, name: String) = sp(context).edit { putString("list_group", name) }

    /** Last destinations picked (newest first, kept on this phone only). */
    fun recentDestinations(context: Context): List<com.rfsentinel.app.nav.Place> = places(context, "recent_destinations")
    fun addRecentDestination(context: Context, d: com.rfsentinel.app.nav.Place) {
        val list = (listOf(d) + recentDestinations(context).filterNot { sameSpot(it, d) }).take(8)
        savePlaces(context, "recent_destinations", list)
    }
    fun clearRecentDestinations(context: Context) = placesSp(context).edit { remove("recent_destinations") }

    // ---- navigation ----

    /**
     * Home, Work, starred and recent places live in a file of their own, which the cloud backup rules leave out
     * (an address is not something to upload to a Google account). Recents used to sit in the main file, which is
     * backed up: they are moved over the first time this is opened.
     */
    private fun placesSp(c: Context): android.content.SharedPreferences {
        val own = c.getSharedPreferences(PLACES_FILE, Context.MODE_PRIVATE)
        val main = sp(c)
        for (key in PLACE_KEYS) if (main.contains(key)) {
            if (!own.contains(key)) main.getString(key, null)?.let { v -> own.edit { putString(key, v) } }
            main.edit { remove(key) }
        }
        return own
    }
    private const val PLACES_FILE = "nav_places"
    private val PLACE_KEYS = listOf("recent_destinations", "nav_home", "nav_work", "nav_favorites")

    private fun places(c: Context, key: String): List<com.rfsentinel.app.nav.Place> = runCatching {
        val json = placesSp(c).getString(key, null) ?: return emptyList()
        com.google.gson.Gson().fromJson(json, Array<com.rfsentinel.app.nav.Place>::class.java).toList()
    }.getOrDefault(emptyList())
    private fun savePlaces(c: Context, key: String, list: List<com.rfsentinel.app.nav.Place>) =
        placesSp(c).edit { putString(key, com.google.gson.Gson().toJson(list)) }
    private fun sameSpot(a: com.rfsentinel.app.nav.Place, b: com.rfsentinel.app.nav.Place) =
        kotlin.math.abs(a.lat - b.lat) < 0.0002 && kotlin.math.abs(a.lon - b.lon) < 0.0002

    /** Home and Work, saved from the search screen (null = not set). Kept on this phone only. */
    fun navHome(context: Context): com.rfsentinel.app.nav.Place? = places(context, "nav_home").firstOrNull()
    fun setNavHome(context: Context, p: com.rfsentinel.app.nav.Place?) =
        if (p == null) placesSp(context).edit { remove("nav_home") } else savePlaces(context, "nav_home", listOf(p))
    fun navWork(context: Context): com.rfsentinel.app.nav.Place? = places(context, "nav_work").firstOrNull()
    fun setNavWork(context: Context, p: com.rfsentinel.app.nav.Place?) =
        if (p == null) placesSp(context).edit { remove("nav_work") } else savePlaces(context, "nav_work", listOf(p))

    /** Starred places. */
    fun navFavorites(context: Context): List<com.rfsentinel.app.nav.Place> = places(context, "nav_favorites")
    fun isNavFavorite(context: Context, p: com.rfsentinel.app.nav.Place) = navFavorites(context).any { sameSpot(it, p) }
    /** Stars [p], or un-stars it when it already is. Returns whether it is a favourite now. */
    fun toggleNavFavorite(context: Context, p: com.rfsentinel.app.nav.Place): Boolean {
        val list = navFavorites(context)
        val now = list.none { sameSpot(it, p) }
        savePlaces(context, "nav_favorites", if (now) (listOf(p) + list).take(40) else list.filterNot { sameSpot(it, p) })
        return now
    }
    /** Gives a starred place your own name; the new place, or null when it isn't starred. */
    fun renameNavFavorite(context: Context, p: com.rfsentinel.app.nav.Place, name: String): com.rfsentinel.app.nav.Place? {
        val list = navFavorites(context)
        val i = list.indexOfFirst { sameSpot(it, p) }.takeIf { it >= 0 } ?: return null
        val renamed = list[i].copy(name = name.trim().ifEmpty { list[i].name })
        savePlaces(context, "nav_favorites", list.toMutableList().also { it[i] = renamed })
        return renamed
    }
    fun clearNavPlaces(context: Context) = placesSp(context).edit { remove("nav_home"); remove("nav_work"); remove("nav_favorites") }

    /** Spoken driving directions (default on). Separate from the alert voice and its mute / snooze. */
    fun navVoice(context: Context): Boolean = bool(context, "nav_voice", true)
    fun setNavVoice(context: Context, v: Boolean) = setBool(context, "nav_voice", v)
    /** While guiding, a map left on "automatic" turns to its night look after sunset (default on). */
    fun navAutoNight(context: Context): Boolean = bool(context, "nav_auto_night", true)
    fun setNavAutoNight(context: Context, v: Boolean) = setBool(context, "nav_auto_night", v)
    /** Starting guidance to a destination starts the scan too (default on); a scan already running is left alone. */
    fun navStartScan(context: Context): Boolean = bool(context, "nav_start_scan", true)
    fun setNavStartScan(context: Context, v: Boolean) = setBool(context, "nav_start_scan", v)
    /** Keep the screen on while guiding (default on, as navigation apps do). */
    fun navKeepScreenOn(context: Context): Boolean = bool(context, "nav_keep_screen_on", true)
    fun setNavKeepScreenOn(context: Context, v: Boolean) = setBool(context, "nav_keep_screen_on", v)
    /** Routes avoid tolls / highways / ferries when these are on (defaults for every route; each can be changed per route). */
    fun navAvoidTolls(context: Context): Boolean = bool(context, "nav_avoid_tolls", false)
    fun setNavAvoidTolls(context: Context, v: Boolean) = setBool(context, "nav_avoid_tolls", v)
    fun navAvoidHighways(context: Context): Boolean = bool(context, "nav_avoid_highways", false)
    fun setNavAvoidHighways(context: Context, v: Boolean) = setBool(context, "nav_avoid_highways", v)
    fun navAvoidFerries(context: Context): Boolean = bool(context, "nav_avoid_ferries", false)
    fun setNavAvoidFerries(context: Context, v: Boolean) = setBool(context, "nav_avoid_ferries", v)
    /** Routes go round the known speed cameras / plate readers (the camera map's), where there is a way round. */
    fun navAvoidSpeedCameras(context: Context): Boolean = bool(context, "nav_avoid_speed_cameras", false)
    fun setNavAvoidSpeedCameras(context: Context, v: Boolean) = setBool(context, "nav_avoid_speed_cameras", v)
    fun navAvoidPlateReaders(context: Context): Boolean = bool(context, "nav_avoid_plate_readers", false)
    fun setNavAvoidPlateReaders(context: Context, v: Boolean) = setBool(context, "nav_avoid_plate_readers", v)
    /**
     * The app's units ([Units]): "auto" (imperial where the phone's region uses miles), "m" or "ft". Before there was one
     * setting for the whole app, navigation had its own ("km" / "mi"): a choice made there carries over.
     */
    fun units(context: Context): String = sp(context).getString("device_units", null)
        ?: when (sp(context).getString("nav_units", null)) { "km" -> "m"; "mi" -> "ft"; else -> "auto" }
    fun setUnits(context: Context, v: String) = sp(context).edit { putString("device_units", v) }
    /** Tracker "following you" warnings paused until this time (epoch ms). */
    fun trackerFollowPausedUntil(context: Context): Long = sp(context).getLong("tracker_follow_paused_until", 0L)
    fun setTrackerFollowPausedUntil(context: Context, value: Long) = sp(context).edit { putLong("tracker_follow_paused_until", value) }
    /** Bluetooth address of the OUI-SPY board to connect to while scanning (null = none). */
    fun ouiSpyBoard(context: Context): String? = sp(context).getString("ouispy_board", null)
    fun setOuiSpyBoard(context: Context, address: String?) = sp(context).edit {
        if (address == null) remove("ouispy_board") else putString("ouispy_board", address)
    }
    /** Have the OUI-SPY board relay every network and Bluetooth device it hears (its Wardrive engine). */
    /** Whether the screen stays on while an RF Sentinel screen is open. */
    enum class ScreenMode { ALWAYS_ON, ON_WHILE_CHARGING, NORMAL }
    fun screenMode(context: Context): ScreenMode =
        runCatching { ScreenMode.valueOf(sp(context).getString("screen_mode", null)!!) }.getOrDefault(ScreenMode.ON_WHILE_CHARGING)
    fun setScreenMode(context: Context, mode: ScreenMode) = sp(context).edit { putString("screen_mode", mode.name) }
    /** When the US & Canada plate readers were last downloaded from DeFlock (0 = never). */
    fun deflockUpdated(context: Context): Long = sp(context).getLong("deflock_updated", 0L)
    fun setDeflockUpdated(context: Context, time: Long) = sp(context).edit { putLong("deflock_updated", time) }
    fun ouiSpyRelayAll(context: Context): Boolean = bool(context, "ouispy_relay_all", true)
    fun setOuiSpyRelayAll(context: Context, value: Boolean) = setBool(context, "ouispy_relay_all", value)
    /** The OUI-SPY detection engines to switch on (all by default; Flock off where there are no Flock cameras). */
    fun ouiSpyEngines(context: Context): List<Int> {
        val off = sp(context).getStringSet("ouispy_engines_off", emptySet()).orEmpty()
        return com.rfsentinel.app.esp.OuiSpyBleProtocol.ENGINES.filter { it.toString() !in off }
    }
    fun setOuiSpyEngines(context: Context, on: List<Int>) = sp(context).edit {
        putStringSet("ouispy_engines_off", com.rfsentinel.app.esp.OuiSpyBleProtocol.ENGINES.filter { it !in on }.map { it.toString() }.toSet())
    }
    /** Keep the phone's own Bluetooth scan running while the OUI-SPY board is live (off: the board scans alone). */
    fun ouiSpyPhoneBle(context: Context): Boolean = bool(context, "ouispy_phone_ble", true)
    fun setOuiSpyPhoneBle(context: Context, value: Boolean) = setBool(context, "ouispy_phone_ble", value)
    /** Two- or three-word spoken alerts ("Body cam", "Speed camera, 50") instead of full labels. */
    fun shortVoice(context: Context): Boolean = bool(context, "short_voice", false)
    fun setShortVoice(context: Context, value: Boolean) = setBool(context, "short_voice", value)

    /** Leave Apple Find My tags (AirTags and compatible) out of the Trackers category. */
    /** Keep the WiFi network names devices around you ask for (USB WiFi adapter / ESP32 Marauder). */
    fun recordProbes(context: Context): Boolean = bool(context, "record_probes", false)
    fun setRecordProbes(context: Context, value: Boolean) = sp(context).edit().putBoolean("record_probes", value).apply()

    fun excludeAirTags(context: Context): Boolean = bool(context, "exclude_airtags", false)
    fun setExcludeAirTags(context: Context, value: Boolean) {
        setBool(context, "exclude_airtags", value)
        com.rfsentinel.app.ui.DeviceFilter.excludeAirTags = value
    }

    /** Alert each time the phone switches to another serving cell tower (off by default: frequent while driving). */
    fun cellChangeAlerts(context: Context): Boolean = bool(context, "cell_change_alerts", false)
    fun setCellChangeAlerts(context: Context, value: Boolean) = setBool(context, "cell_change_alerts", value)
    /** Map layer: ordinary CCTV cameras mapped in OpenStreetMap (off by default); private ones on request. */
    fun showCctv(context: Context): Boolean = bool(context, "show_cctv", false)
    fun setShowCctv(context: Context, value: Boolean) = setBool(context, "show_cctv", value)
    fun cctvPrivate(context: Context): Boolean = bool(context, "cctv_private", false)
    fun setCctvPrivate(context: Context, value: Boolean) = setBool(context, "cctv_private", value)

    /** Live map turned to the direction of travel instead of north up (map button; off = north up). */
    fun mapHeadingUp(context: Context): Boolean = bool(context, "map_heading_up", false)
    fun setMapHeadingUp(context: Context, value: Boolean) = setBool(context, "map_heading_up", value)

    fun showCellTowers(context: Context): Boolean = bool(context, "show_cell_towers", false)
    fun setShowCellTowers(context: Context, value: Boolean) = setBool(context, "show_cell_towers", value)
    fun showKnownAlpr(context: Context): Boolean = bool(context, "show_known_alpr", true)
    fun setShowKnownAlpr(context: Context, value: Boolean) = setBool(context, "show_known_alpr", value)

    /** Start scanning when the phone connects to the car (chosen Bluetooth devices, or Android Auto). */
    fun carAutoStart(context: Context): Boolean = bool(context, "car_auto_start", false)
    fun setCarAutoStart(context: Context, value: Boolean) = setBool(context, "car_auto_start", value)
    /** Bluetooth addresses of the user's car(s). */
    fun carDevices(context: Context): Set<String> = sp(context).getStringSet("car_devices", emptySet()) ?: emptySet()
    fun setCarDevices(context: Context, value: Set<String>) = sp(context).edit { putStringSet("car_devices", value) }
    /** True while the running scan was started by the car (so leaving the car may stop it). */
    fun startedByCar(context: Context): Boolean = bool(context, "started_by_car", false)
    fun setStartedByCar(context: Context, value: Boolean) = setBool(context, "started_by_car", value)
    /** Record a trace while in the car (chosen Bluetooth devices, or Android Auto). */
    fun carAutoRecord(context: Context): Boolean = bool(context, "car_auto_record", false)
    fun setCarAutoRecord(context: Context, value: Boolean) = setBool(context, "car_auto_record", value)
    /** True while the trace being recorded was started by the car (so leaving the car saves it). */
    fun recordingByCar(context: Context): Boolean = bool(context, "recording_by_car", false)
    fun setRecordingByCar(context: Context, value: Boolean) = setBool(context, "recording_by_car", value)

    /** Floating threat bubble over other apps while scanning. */
    /** Floating mini map with the devices around you, drawn over other apps while scanning. */
    fun floatingMap(context: Context): Boolean = bool(context, "floating_map", false)
    fun setFloatingMap(context: Context, value: Boolean) = setBool(context, "floating_map", value)

    /** Radar-detector mode: after a device alert, keep beeping faster as its signal gets stronger. */
    fun radarBeep(context: Context): Boolean = bool(context, "radar_beep", false)
    fun setRadarBeep(context: Context, value: Boolean) = setBool(context, "radar_beep", value)

    /** Radar-detector sound style: Ka / K / X style tones, startup sweep, "GPS connected", short voice. */
    fun detectorSound(context: Context): Boolean = bool(context, "detector_sound", false)
    fun setDetectorSound(context: Context, value: Boolean) = setBool(context, "detector_sound", value)

    /**
     * Radar-detector sound effect: 0 = by strength (1 strong, 2 probable, 3 weak, 4 following),
     * 1-4 = always that effect. The proximity beeps use a pulse of the same effect.
     */
    fun detectorEffect(context: Context): Int = int(context, "detector_effect", 0).coerceIn(0, 4)
    fun setDetectorEffect(context: Context, value: Int) = setInt(context, "detector_effect", value.coerceIn(0, 4))

    /** Radar-detector sound style: the power-on sweep when a scan starts (off by default). */
    fun startupSweep(context: Context): Boolean = bool(context, "startup_sweep", false)
    fun setStartupSweep(context: Context, value: Boolean) = setBool(context, "startup_sweep", value)

    /** Radar-detector sound style: say "GPS connected" when the GPS locks (on by default). */
    fun gpsVoice(context: Context): Boolean = bool(context, "gps_voice", true)
    fun setGpsVoice(context: Context, value: Boolean) = setBool(context, "gps_voice", value)

    /** Play the intro sound when a scan starts. */
    fun scanIntro(context: Context): Boolean = bool(context, "scan_intro", false)
    fun setScanIntro(context: Context, value: Boolean) = setBool(context, "scan_intro", value)

    // ---- RTL-SDR radio (Settings > RTL-SDR radio) ----

    private fun str(c: Context, key: String) = sp(c).getString(key, "").orEmpty()
    private fun setStr(c: Context, key: String, v: String) = sp(c).edit { putString(key, v) }

    /** Report a radio from this many dB above the noise (15-50; 30 = the original sensitivity). */
    fun radioMinSnr(context: Context): Int = int(context, "radio_min_snr", com.rfsentinel.app.sdr.RadioWatch.DEFAULT_MIN_SNR)
        .coerceIn(com.rfsentinel.app.sdr.RadioWatch.MIN_SNR_SETTING, com.rfsentinel.app.sdr.RadioWatch.MAX_SNR_SETTING)
    fun setRadioMinSnr(context: Context, v: Int) = setInt(context, "radio_min_snr",
        v.coerceIn(com.rfsentinel.app.sdr.RadioWatch.MIN_SNR_SETTING, com.rfsentinel.app.sdr.RadioWatch.MAX_SNR_SETTING))
    /** Built-in bands switched off ("700", "800", "vhf", "uhf"). */
    fun radioBandsOff(context: Context): Set<String> = sp(context).getStringSet("radio_bands_off", emptySet()).orEmpty()

    /** Also sweep the cellular UPLINK bands for a transmitter travelling near you (experimental, off by default). */
    fun radioCellOn(context: Context): Boolean = bool(context, "radio_cell_on", false)
    fun setRadioCellOn(context: Context, value: Boolean) = setBool(context, "radio_cell_on", value)
    /** Also sweep the TETRA handset band (380-385 MHz); on by default where the phone's region uses TETRA. */
    fun radioTetraOn(context: Context): Boolean = bool(context, "radio_tetra_on", tetraRegion(java.util.Locale.getDefault().country))
    fun setRadioTetraOn(context: Context, value: Boolean) = setBool(context, "radio_tetra_on", value)
    /** Europe's emergency services use TETRA on 380-385 MHz (from the phone's language region, not its location). */
    fun tetraRegion(country: String) = country.uppercase() in TETRA_REGIONS
    private val TETRA_REGIONS = setOf("AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HR",
        "HU", "IE", "IS", "IT", "LI", "LT", "LU", "LV", "MT", "NL", "NO", "PL", "PT", "RO", "SE", "SI", "SK")
    fun setRadioBandsOff(context: Context, v: Set<String>) = sp(context).edit { putStringSet("radio_bands_off", v) }
    /** The user's own bands, one "start-end MHz label" per line. */
    fun radioCustomBands(context: Context) = str(context, "radio_custom_bands")
    fun setRadioCustomBands(context: Context, v: String) = setStr(context, "radio_custom_bands", v)
    /** Ranges never to report, one "start-end MHz" per line (e.g. a business band). */
    fun radioExcluded(context: Context) = str(context, "radio_excluded")
    fun setRadioExcluded(context: Context, v: String) = setStr(context, "radio_excluded", v)
    /** Frequencies to watch, one "MHz label" per line. */
    fun radioTargets(context: Context) = str(context, "radio_targets")
    fun setRadioTargets(context: Context, v: String) = setStr(context, "radio_targets", v)
    /** Say the frequency (and its name when known) instead of "Radio transmitting nearby". */
    fun radioSpeakFreq(context: Context): Boolean = bool(context, "radio_speak_freq", false)
    fun setRadioSpeakFreq(context: Context, v: Boolean) = setBool(context, "radio_speak_freq", v)
    /** Call out "closer" / "farther" after the signal moved this many dB (0 = off). */
    fun radioTrendDb(context: Context): Int = int(context, "radio_trend_db", 6).coerceIn(0, 20)
    fun setRadioTrendDb(context: Context, v: Int) = setInt(context, "radio_trend_db", v.coerceIn(0, 20))
    /** A hit matches a named frequency within ± this many kHz. */
    fun radioMatchKhz(context: Context): Int = int(context, "radio_match_khz", 7).coerceIn(1, 50)
    fun setRadioMatchKhz(context: Context, v: Int) = setInt(context, "radio_match_khz", v.coerceIn(1, 50))
    /** The RTL-SDR as an ADS-B aircraft receiver on 1090 MHz instead of the radio sweep (off by default). */
    fun sdrAdsb(context: Context): Boolean = bool(context, "sdr_adsb", false)
    fun setSdrAdsb(context: Context, v: Boolean) = setBool(context, "sdr_adsb", v)

    /** Name hits from RadioReference (needs the user's own Premium login and developer key; off by default). */
    fun radioReferenceOn(context: Context): Boolean = bool(context, "radio_reference_on", false)
    fun setRadioReferenceOn(context: Context, v: Boolean) = setBool(context, "radio_reference_on", v)

    // ---- Live export (Settings > Data) ----

    /** Keep a live copy of matches, devices and radio hits in a folder other apps can read (off by default). */
    fun liveExport(context: Context): Boolean = bool(context, "live_export", false)
    fun setLiveExport(context: Context, v: Boolean) = setBool(context, "live_export", v)
    /** The folder picked for it (a storage-access tree URI). */
    fun liveExportTree(context: Context): String? = sp(context).getString("live_export_tree", null)
    fun setLiveExportTree(context: Context, v: String?) = sp(context).edit { putString("live_export_tree", v) }

    /** ALFA AWUS036ACS / RTL8811AU-8821AU: also hop 5 GHz (experimental, off by default). */
    fun rtl8821au5g(context: Context): Boolean = bool(context, "rtl8821au_5g", false)
    /** The USB adapter's own light (only adapters whose light the app can drive: RTL8811AU / 8821AU): on by default. */
    fun usbAdapterLight(context: Context): Boolean = bool(context, "usb_adapter_light", true)
    fun setUsbAdapterLight(context: Context, value: Boolean) = setBool(context, "usb_adapter_light", value)
    fun setRtl8821au5g(context: Context, value: Boolean) = setBool(context, "rtl8821au_5g", value)

    /** Hide trusted (whitelisted) devices from the live list and radar (off by default). */
    fun hideWhitelisted(context: Context): Boolean = bool(context, "hide_whitelisted", false)
    fun setHideWhitelisted(context: Context, value: Boolean) = setBool(context, "hide_whitelisted", value)

    fun threatBubble(context: Context): Boolean = bool(context, "threat_bubble", false)
    fun setThreatBubble(context: Context, value: Boolean) = setBool(context, "threat_bubble", value)

    fun autoRecordTrace(context: Context): Boolean = bool(context, "auto_record_trace", false)
    fun setAutoRecordTrace(context: Context, value: Boolean) = setBool(context, "auto_record_trace", value)

    fun followMinMinutes(context: Context): Int = int(context, "follow_min_minutes", 10)
    fun setFollowMinMinutes(context: Context, value: Int) = setInt(context, "follow_min_minutes", value)

    fun followMinMeters(context: Context): Int = int(context, "follow_min_meters", 800)
    fun setFollowMinMeters(context: Context, value: Int) = setInt(context, "follow_min_meters", value)

    // ---- Data -----------------------------------------------------------------

    /** Days to keep logged matches and device history; 0 = forever. */
    fun retentionDays(context: Context): Int = int(context, "retention_days", 90)
    fun setRetentionDays(context: Context, value: Int) = setInt(context, "retention_days", value)

    fun onboardingDone(context: Context): Boolean = bool(context, "setup_wizard_done", false)
    fun setOnboardingDone(context: Context) = setBool(context, "setup_wizard_done", true)

    fun radarView(context: Context): Boolean = bool(context, "radar_view", false)
    fun setRadarView(context: Context, value: Boolean) = setBool(context, "radar_view", value)

    /** The main screen's views, any mix of "map", "radar" and "list", shown in that order; older installs only knew list / radar. */
    fun viewPanes(context: Context): Set<String> =
        sp(context).getString("view_panes", null)?.split(',')?.filter { it in PANES }?.toSet()?.takeIf { it.isNotEmpty() }
            ?: if (radarView(context)) setOf("radar") else setOf("list")
    fun setViewPanes(context: Context, value: Set<String>) {
        val panes = PANES.filter { it in value }.ifEmpty { listOf("list") }
        sp(context).edit().putString("view_panes", panes.joinToString(",")).apply()
        setRadarView(context, panes == listOf("radar"))
    }
    val PANES = listOf("map", "radar", "list")

    /** Each view's share of the screen when several show together (dragging a handle moves it). */
    fun paneWeight(context: Context, pane: String): Float =
        sp(context).getFloat("pane_weight_$pane", if (pane == "list") 1.2f else 1f).coerceIn(0.1f, 10f)
    fun setPaneWeight(context: Context, pane: String, value: Float) = sp(context).edit().putFloat("pane_weight_$pane", value).apply()

    /** 3D driving view: heading up, tilted in perspective, your position low on screen (map button, third step). */
    fun map3d(context: Context): Boolean = bool(context, "map_3d", false)
    fun setMap3d(context: Context, value: Boolean) = setBool(context, "map_3d", value)

    /** Light or dark map tiles (the map's sun / moon button); null = follow the phone's dark mode. */
    fun mapDark(context: Context): Boolean? =
        if (sp(context).contains("map_dark")) sp(context).getBoolean("map_dark", false) else null
    fun setMapDark(context: Context, value: Boolean) = setBool(context, "map_dark", value)

    /**
     * How close the map follows you: the zoom (vector map's level) at street speed; the 3D driving view
     * pulls back from it as you speed up, the flat views sit half a level wider. 17 is close to Waze.
     */
    const val MAP_ZOOM_WAZE = 17.0
    fun mapDefaultZoom(context: Context): Double =
        sp(context).getFloat("map_default_zoom", MAP_ZOOM_WAZE.toFloat()).toDouble().coerceIn(14.5, 18.5)
    fun setMapDefaultZoom(context: Context, value: Double) = sp(context).edit().putFloat("map_default_zoom", value.toFloat()).apply()

    /** The 3D driving view's tilt (degrees) at street speed; it leans further back by up to 10 degrees on fast roads. */
    const val MAP_TILT_DEFAULT = 50.0
    fun map3dTilt(context: Context): Double =
        sp(context).getFloat("map_3d_tilt", MAP_TILT_DEFAULT.toFloat()).toDouble().coerceIn(30.0, 60.0)
    fun setMap3dTilt(context: Context, value: Double) = sp(context).edit().putFloat("map_3d_tilt", value.toFloat()).apply()

    /** The marker for your position on the map ([com.rfsentinel.app.ui.MarkerArt]): its style, colour and size (1.0 = 56 dp tall). */
    fun markerStyle(context: Context): String = sp(context).getString("map_marker_style", "arrow") ?: "arrow"
    fun markerColor(context: Context): String = sp(context).getString("map_marker_color", "blue") ?: "blue"
    fun markerSize(context: Context): Float = sp(context).getFloat("map_marker_size", 1.0f).coerceIn(0.6f, 2.0f)
    fun setMarker(context: Context, style: String, color: String, size: Float) =
        sp(context).edit().putString("map_marker_style", style).putString("map_marker_color", color).putFloat("map_marker_size", size).apply()

    /** The live map goes back to following you a few seconds after you stop touching it while you drive (as Google Maps does). */
    fun mapAutoRecenter(context: Context): Boolean = bool(context, "map_auto_recenter", true)
    fun setMapAutoRecenter(context: Context, value: Boolean) = setBool(context, "map_auto_recenter", value)

    /** The speedometer in the corner of the live map: your speed, and the posted limit while it is known. */
    fun mapSpeedometer(context: Context): Boolean = bool(context, "map_speedometer", true)
    fun setMapSpeedometer(context: Context, value: Boolean) = setBool(context, "map_speedometer", value)
    /**
     * The posted limit when you are not navigating: asks the route server about the road you are on every ~12 s
     * while you drive (so it learns where you drive, unlike every other map feature) - off until you turn it on.
     * While guided the limits come with the route either way.
     */
    fun mapSpeedLimitLive(context: Context): Boolean = bool(context, "map_speed_limit_live", false)
    fun setMapSpeedLimitLive(context: Context, value: Boolean) = setBool(context, "map_speed_limit_live", value)
    /** The full screen map with the toolbar, the system bars and the bottom bar hidden (the button on the map). */
    fun mapImmersive(context: Context): Boolean = bool(context, "map_immersive", false)
    fun setMapImmersive(context: Context, value: Boolean) = setBool(context, "map_immersive", value)

    /** The map's provider ("openfreemap", the default, or "mapbox"), Settings > Map. */
    fun mapProvider(context: Context): String? = sp(context).getString("map_provider", null)
    fun setMapProvider(context: Context, value: String) = sp(context).edit().putString("map_provider", value).apply()
}
