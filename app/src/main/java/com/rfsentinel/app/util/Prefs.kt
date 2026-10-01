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

    /** Master mute for alert sound and voice (phone menu and Android Auto button). Vibration unaffected. */
    fun alertsMuted(context: Context): Boolean = bool(context, "alerts_muted", false)
    fun setAlertsMuted(context: Context, value: Boolean) = setBool(context, "alerts_muted", value)

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
    fun autoCameras(context: Context): Boolean = bool(context, "auto_cameras", true)
    fun setAutoCameras(context: Context, value: Boolean) = setBool(context, "auto_cameras", value)
    /** Map: every device heard (true) or flagged ones only. */
    fun mapShowAll(context: Context): Boolean = bool(context, "map_show_all", true)
    fun setMapShowAll(context: Context, value: Boolean) = setBool(context, "map_show_all", value)
    /** Last destinations picked in the car (newest first, kept on this phone only). */
    fun recentDestinations(context: Context): List<com.rfsentinel.app.nav.OsmRouting.Destination> = runCatching {
        val json = sp(context).getString("recent_destinations", null) ?: return emptyList()
        com.google.gson.Gson().fromJson(json, Array<com.rfsentinel.app.nav.OsmRouting.Destination>::class.java).toList()
    }.getOrDefault(emptyList())
    fun addRecentDestination(context: Context, d: com.rfsentinel.app.nav.OsmRouting.Destination) {
        val list = (listOf(d) + recentDestinations(context).filterNot { it.lat == d.lat && it.lon == d.lon }).take(5)
        sp(context).edit { putString("recent_destinations", com.google.gson.Gson().toJson(list)) }
    }
    fun clearRecentDestinations(context: Context) = sp(context).edit { remove("recent_destinations") }
    /** Tracker "following you" warnings paused until this time (epoch ms). */
    fun trackerFollowPausedUntil(context: Context): Long = sp(context).getLong("tracker_follow_paused_until", 0L)
    fun setTrackerFollowPausedUntil(context: Context, value: Long) = sp(context).edit { putLong("tracker_follow_paused_until", value) }
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

    /** Floating threat bubble over other apps while scanning. */
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
}
