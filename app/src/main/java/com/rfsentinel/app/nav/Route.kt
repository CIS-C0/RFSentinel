package com.rfsentinel.app.nav

/**
 * A driving route as navigation uses it: the line, and the steps along it with what to show and say
 * before each turn. Built by [Valhalla] (pure Kotlin, unit-tested).
 */
data class Route(
    /** The line, as parallel arrays (the steps' own lines joined end to end). */
    val lats: DoubleArray,
    val lons: DoubleArray,
    /** Metres from the start to each point of the line (measured on it, so it matches [RouteProgress]). */
    val along: DoubleArray,
    /** Valhalla's own figures for the whole route. */
    val distanceM: Double,
    val durationS: Double,
    val steps: List<Step>,
    /** The main roads it takes ("Highway 40, Rue Principale"), to tell routes apart. */
    val summary: String,
    /** Where it ends (the last stop). */
    val end: Pair<Double, Double>,
    /** What is on it of the cameras being avoided ("no plate readers", "2 speed cameras"); null when none are. */
    val cameraLine: String? = null
) {
    val size: Int get() = lats.size
    val lengthM: Double get() = along.lastOrNull() ?: 0.0

    /** The line as (lat, lon) pairs, for drawing. */
    fun points(): List<Pair<Double, Double>> = List(size) { lats[it] to lons[it] }

    /**
     * The line from [fromM] along it to the end (the part still ahead), at most about [maxPoints] points:
     * a longer one is thinned out evenly (the end is always kept) - the screen can't show more.
     */
    fun pointsFrom(fromM: Double, maxPoints: Int = 1_500): List<Pair<Double, Double>> {
        val i = along.indexOfFirst { it > fromM }.takeIf { it > 0 } ?: return if (fromM <= 0) thin(points(), maxPoints) else listOf(end)
        val t = (fromM - along[i - 1]) / (along[i] - along[i - 1]).coerceAtLeast(1e-9)
        val start = (lats[i - 1] + t * (lats[i] - lats[i - 1])) to (lons[i - 1] + t * (lons[i] - lons[i - 1]))
        return thin(listOf(start) + (i until size).map { lats[it] to lons[it] }, maxPoints)
    }

    private fun thin(pts: List<Pair<Double, Double>>, maxPoints: Int): List<Pair<Double, Double>> {
        if (pts.size <= maxPoints) return pts
        val stride = (pts.size + maxPoints - 1) / maxPoints
        return pts.filterIndexed { i, _ -> i % stride == 0 || i == pts.size - 1 }
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * One stretch of the route, from one manoeuvre to the next. What it [banners] and [voice] about is
 * the manoeuvre at its END (the start of the next step), as navigation apps announce the next turn.
 */
data class Step(
    /** This step's own manoeuvre (where it starts): OSRM words - "turn", "fork", "roundabout"... */
    val type: String,
    val modifier: String?,
    /** The road this step is on. */
    val name: String,
    val exit: Int?,
    /** Valhalla's wording of this step's own manoeuvre ("Turn right onto Rue Principale."). */
    val instruction: String,
    val lat: Double,
    val lon: Double,
    val lengthM: Double,
    val durationS: Double,
    /** Where it starts and ends on [Route.along]. */
    val startM: Double,
    val endM: Double,
    /** The leg it belongs to: 0 up to the first stop, then 1... */
    val leg: Int,
    val voice: List<Voice>,
    val banners: List<Banner>,
    /** Which side of the road they drive on here ("right" or "left"): the way round a roundabout, which way a U-turn goes. */
    val drivingSide: String = "right"
) {
    /** A spoken prompt, due when the next manoeuvre is [beforeM] away or less. */
    data class Voice(val beforeM: Double, val text: String)

    /** What the banner shows from [beforeM] before the next manoeuvre: that manoeuvre, and the lanes for it. */
    data class Banner(
        val beforeM: Double,
        val text: String,
        val type: String,
        val modifier: String?,
        val lanes: List<Lane>
    )

    /** One lane at the coming junction: its arrows, whether it is one to be in, and which arrow to follow if it is. */
    data class Lane(val directions: List<String>, val active: Boolean, val activeDirection: String? = null)

    val isArrival: Boolean get() = type == "arrive"
}
