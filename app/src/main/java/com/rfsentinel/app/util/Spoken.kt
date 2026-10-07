package com.rfsentinel.app.util

import android.content.Context
import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit

/**
 * The words spoken for an alert: the full label, or two or three words with
 * Settings > Short spoken alerts ("Body cam", "Police car", "Speed camera, 50").
 */
object Spoken {

    /** Short phrases; always in the radar-detector sound style, which speaks like a detector. */
    /** Short phrases; always in the radar-detector sound style, which speaks like a detector. */
    fun short(context: Context) = Prefs.shortVoice(context) || Prefs.detectorSound(context) || Prefs.detectorSound(context)

    fun device(context: Context, hit: Hit, following: Boolean = false): String {
        if (!short(context)) return if (following) "Warning. ${hit.label} may be following you." else "${hit.label} nearby"
        val word = shortWord(hit)
        return if (following) "$word following" else word
    }

    fun camera(context: Context, cam: KnownCamera): String {
        if (!short(context)) return cam.spoken
        return when (cam.type) {
            KnownCamera.Kind.ALPR -> "Plate camera"
            KnownCamera.Kind.SPEED -> "Speed camera" + (cam.maxspeed?.let { ", $it" } ?: "")
            KnownCamera.Kind.RED_LIGHT -> "Red light camera"
        }
    }

    fun shortWord(hit: Hit): String = when (hit.category) {
        Category.BODY_CAM -> "Body cam"
        Category.ALPR -> "Plate camera"
        Category.AUDIO_SENSOR -> "Audio sensor"
        Category.PUBLIC_SAFETY -> if (hit.label.contains("police vehicle", ignoreCase = true)) "Police car" else "Police gear"
        Category.DRONE -> "Drone"
        Category.TRACKER -> "Tracker"
        Category.GLASSES -> "Camera glasses"
        Category.NETWORK_CAMERA -> "Camera"
        Category.CELL_ANOMALY -> "Cell warning"
        Category.RADIO -> "Radio nearby"
        Category.GNSS -> "GPS warning"
        Category.AIRCRAFT -> if (hit.label.startsWith("Police")) "Police aircraft" else "Aircraft circling"
        Category.POLICE_REPORT -> "Waze police"
        Category.SKIMMER -> "Card skimmer"
        Category.OTHER_CAMERA -> if (hit.label.startsWith("Hidden")) "Hidden camera" else "Action camera"
        Category.HACKER -> "Hacking tool"
        Category.CUSTOM -> "Watchlist"
    }
}
