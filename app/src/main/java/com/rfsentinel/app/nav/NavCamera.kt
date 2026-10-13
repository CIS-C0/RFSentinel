package com.rfsentinel.app.nav

/** How the map camera helps while you are guided (pure; unit-tested). */
object NavCamera {

    /** The turn starts to matter this far out... */
    private const val FROM_M = 350.0
    /** ...and is as close up as it gets this near. */
    private const val FULL_M = 60.0
    /** The most the map zooms in for a turn (levels). */
    const val MAX_BOOST = 0.9
    /** Above this speed (km/h) the junction comes too fast to zoom for. */
    private const val NO_BOOST_KMH = 90f
    private const val FULL_BOOST_KMH = 50f

    /**
     * Extra zoom levels for a turn [toTurnM] ahead at [kmh]: none while it is far off, rising to
     * [MAX_BOOST] as you reach it, so a junction fills the screen as in navigation apps. Less on fast
     * roads (the turn is a ramp you see coming), none above 90 km/h.
     */
    fun turnBoost(toTurnM: Double, kmh: Float): Double {
        if (toTurnM >= FROM_M || kmh >= NO_BOOST_KMH) return 0.0
        val near = ((FROM_M - toTurnM) / (FROM_M - FULL_M)).coerceIn(0.0, 1.0)
        val speed = if (kmh <= FULL_BOOST_KMH) 1.0 else 1.0 - (kmh - FULL_BOOST_KMH) / (NO_BOOST_KMH - FULL_BOOST_KMH)
        return MAX_BOOST * near * speed
    }
}
