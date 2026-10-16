package com.rfsentinel.app.util

import android.content.Context
import com.rfsentinel.app.nav.NavFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The app's units (Settings > General > Units): metric, imperial, or Automatic - imperial where the phone's region
 * drives in miles. Everything that shows a distance, a length or a speed asks here: device estimates, navigation, the
 * map scale, cameras, Waze reports, aircraft, drones and the car screens. Posted speed limits are not converted: they
 * are shown as signed.
 */
object Units {
    /** Regions that use miles and feet. */
    private val IMPERIAL_REGIONS = setOf("US", "GB", "LR", "MM")

    /** The current choice, kept in step with the setting by [apply] (read where no Context is at hand). */
    @Volatile var imperial = false

    /** Whether [pref] ("auto", "m" or "ft") means imperial in [locale]. */
    fun decide(pref: String, locale: Locale = Locale.getDefault()): Boolean = when (pref) {
        "ft" -> true
        "m" -> false
        else -> locale.country.uppercase(Locale.ROOT) in IMPERIAL_REGIONS
    }

    fun imperial(context: Context, locale: Locale = Locale.getDefault()): Boolean = decide(Prefs.units(context), locale)

    /** Reads the setting into [imperial]: at start and whenever it changes. */
    fun apply(context: Context, locale: Locale = Locale.getDefault()) { imperial = imperial(context, locale) }

    /** A distance on the road, rounded the way a driver reads it: "300 m", "1.2 km"; "400 ft", "0.3 mi". */
    fun distance(m: Double, feet: Boolean = imperial): String = NavFormat.distance(m, feet)

    /** A distance to the metre: "412 m", "1.23 km"; "1352 ft", "0.77 mi". */
    fun precise(m: Double, feet: Boolean = imperial): String =
        if (!feet) { if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.2f km", m / 1000) }
        else { val ft = m * 3.28084; if (ft < 1000) "${ft.toInt()} ft" else String.format(Locale.US, "%.2f mi", m / 1609.344) }

    /** A plain length: "40 m"; "131 ft" (miles past 1000 ft). */
    fun length(m: Double, feet: Boolean = imperial): String = if (!feet) "${m.roundToInt()} m" else {
        val ft = m * 3.28084
        if (ft < 1000) "${ft.roundToInt()} ft" else String.format(Locale.US, "%.1f mi", m / 1609.344)
    }

    /** A setting's whole kilometres in the app's units: "25 km"; "16 mi". */
    fun km(km: Int, feet: Boolean = imperial): String =
        if (feet) "${(km * 0.621371).roundToInt()} mi" else "$km km"

    /** A speed from metres per second: "36 km/h"; "22 mph". */
    fun speed(ms: Double, feet: Boolean = imperial): String = if (feet) "${(ms * 2.236936).roundToInt()} mph" else "${(ms * 3.6).roundToInt()} km/h"
}
