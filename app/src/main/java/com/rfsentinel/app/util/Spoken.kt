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

    /** A distance said aloud: "300 meters" (to the nearest 50), "1 kilometer", "1.2 kilometers". */
    fun distanceWords(m: Double): String {
        val r = Math.round(m / 50.0) * 50
        if (r < 1000) return "${r.coerceAtLeast(50)} meters"
        val km = Math.round(m / 100.0) / 10.0
        return if (km == Math.floor(km)) "${km.toLong()} kilometer${if (km == 1.0) "" else "s"}" else "$km kilometers"
    }

    /** Where a report is, said aloud; empty when the heading is unknown. */
    fun sideWords(side: com.rfsentinel.app.online.WazePolice.Side?): String = when (side) {
        com.rfsentinel.app.online.WazePolice.Side.AHEAD -> "ahead"
        com.rfsentinel.app.online.WazePolice.Side.BEHIND -> "behind you"
        com.rfsentinel.app.online.WazePolice.Side.LEFT -> "on your left"
        com.rfsentinel.app.online.WazePolice.Side.RIGHT -> "on your right"
        null -> ""
    }

    /**
     * What is said for a Waze report: "Police ahead, 800 meters" (short), or
     * "Police reported on Waze, 800 meters ahead" (full). The same short form is used as you get closer.
     */
    fun wazePhrase(type: com.rfsentinel.app.online.WazePolice.Type, distanceM: Double,
                   side: com.rfsentinel.app.online.WazePolice.Side?, short: Boolean): String {
        val where = sideWords(side)
        val dist = distanceWords(distanceM)
        return if (short) type.singular + (if (where.isEmpty()) "" else " $where") + ", $dist"
        else "${type.singular} reported on Waze, $dist" + (if (where.isEmpty()) "" else " $where")
    }

    /** [wazePhrase] in the style Settings asks for (short spoken alerts, or the radar-detector style). */
    fun waze(context: Context, type: com.rfsentinel.app.online.WazePolice.Type, distanceM: Double,
             side: com.rfsentinel.app.online.WazePolice.Side?): String = wazePhrase(type, distanceM, side, short(context))

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
        Category.POLICE_REPORT -> "Waze " + (com.rfsentinel.app.online.WazePolice.Type.entries
            .firstOrNull { hit.label.startsWith(it.singular) }?.singular?.lowercase() ?: "report")
        Category.SKIMMER -> "Card skimmer"
        Category.OTHER_CAMERA -> if (hit.label.startsWith("Hidden")) "Hidden camera" else "Action camera"
        Category.HACKER -> "Hacking tool"
        Category.CUSTOM -> "Watchlist"
    }
}
