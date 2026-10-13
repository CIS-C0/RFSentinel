package com.rfsentinel.app.nav

import android.content.Context
import com.rfsentinel.app.util.Prefs
import java.util.Locale

/** Distances, times and icons as navigation shows them (pure apart from [miles]; unit-tested). */
object NavFormat {

    private const val M_PER_MILE = 1609.344
    private const val FT_PER_M = 3.28084

    /** Regions that drive by miles. */
    private val MILE_REGIONS = setOf("US", "GB", "LR", "MM")

    /** Whether guidance counts in miles: the Settings choice, or by the phone's region. */
    fun miles(context: Context, locale: Locale = Locale.getDefault()): Boolean = when (Prefs.navUnits(context)) {
        "mi" -> true
        "km" -> false
        else -> locale.country.uppercase(Locale.ROOT) in MILE_REGIONS
    }

    /** "300 m", "1.2 km"; "400 ft", "0.3 mi". Rounded the way a driver reads it. */
    fun distance(meters: Double, miles: Boolean): String {
        val m = meters.coerceAtLeast(0.0)
        return if (!miles) when {
            m < 100 -> "${(Math.round(m / 10.0) * 10).toInt()} m"
            m < 1000 -> "${(Math.round(m / 50.0) * 50).toInt()} m"
            m < 10_000 -> String.format(Locale.US, "%.1f km", m / 1000)
            else -> "${Math.round(m / 1000.0)} km"
        } else {
            val ft = m * FT_PER_M
            when {
                ft < 1000 -> "${(Math.round(ft / 50.0) * 50).toInt().coerceAtLeast(0)} ft"
                m < 10 * M_PER_MILE -> String.format(Locale.US, "%.1f mi", m / M_PER_MILE)
                else -> "${Math.round(m / M_PER_MILE)} mi"
            }
        }
    }

    /** "18 min", "1 h 05 min". */
    fun duration(seconds: Double): String {
        val min = Math.round(seconds.coerceAtLeast(0.0) / 60.0).toInt().coerceAtLeast(1)
        return if (min < 60) "$min min" else String.format(Locale.US, "%d h %02d min", min / 60, min % 60)
    }

    /** Your speed as the speedometer shows it. */
    fun speed(metersPerSecond: Double, miles: Boolean): String =
        if (miles) "${Math.round(metersPerSecond * 2.236936).toInt()} mph" else "${Math.round(metersPerSecond * 3.6).toInt()} km/h"

    /** Which arrow stands for a manoeuvre (the screens map these to drawables). */
    enum class Icon { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN_LEFT, U_TURN_RIGHT,
        ROUNDABOUT, ROUNDABOUT_LEFT, MERGE, FORK_LEFT, FORK_RIGHT, RAMP_LEFT, RAMP_RIGHT, ARRIVE }

    /** "1st", "2nd", "3rd", "4th", "11th", "21st"... for a roundabout exit. */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    /**
     * The arrow for an OSRM manoeuvre [type] and [modifier], on a road where they drive on [drivingSide]
     * ("left" turns the roundabout the other way and puts a U-turn on the other side).
     */
    fun iconFor(type: String?, modifier: String?, drivingSide: String? = null): Icon {
        val leftHand = drivingSide == "left"
        val left = modifier?.contains("left") == true
        val right = modifier?.contains("right") == true
        return when (type) {
            "arrive" -> Icon.ARRIVE
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> if (leftHand) Icon.ROUNDABOUT_LEFT else Icon.ROUNDABOUT
            "merge" -> Icon.MERGE
            "fork" -> if (left) Icon.FORK_LEFT else Icon.FORK_RIGHT
            "on ramp", "off ramp" -> if (left) Icon.RAMP_LEFT else if (right) Icon.RAMP_RIGHT else Icon.STRAIGHT
            else -> if (modifier == "uturn" && leftHand) Icon.U_TURN_RIGHT else byModifier(modifier)
        }
    }

    private fun byModifier(modifier: String?): Icon = when (modifier) {
        "left" -> Icon.LEFT
        "right" -> Icon.RIGHT
        "slight left" -> Icon.SLIGHT_LEFT
        "slight right" -> Icon.SLIGHT_RIGHT
        "sharp left" -> Icon.SHARP_LEFT
        "sharp right" -> Icon.SHARP_RIGHT
        "uturn" -> Icon.U_TURN_LEFT
        else -> Icon.STRAIGHT
    }

    /** The arrow for one lane direction ("straight", "slight right"...). */
    fun laneIcon(direction: String?): Icon = when (direction) {
        "left" -> Icon.LEFT
        "right" -> Icon.RIGHT
        "slight left" -> Icon.SLIGHT_LEFT
        "slight right" -> Icon.SLIGHT_RIGHT
        "sharp left" -> Icon.SHARP_LEFT
        "sharp right" -> Icon.SHARP_RIGHT
        "uturn" -> Icon.U_TURN_LEFT
        else -> Icon.STRAIGHT
    }

    /** The direction to draw for a lane: the one to take when it is the lane to be in, else its first. */
    fun laneDirection(lane: Step.Lane): String? = if (lane.active) lane.activeDirection ?: lane.directions.firstOrNull() else lane.directions.firstOrNull()
}
