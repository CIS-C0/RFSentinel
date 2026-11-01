package com.rfsentinel.app.ui

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ties the AR camera's world (metres from where it started, its own "north") to [Motion]'s east / north: the
 * compass gives the turn between the two, averaged over the first [ALIGN_FRAMES] level frames, and where Motion had
 * you then gives the shift. After that the camera alone says where you are and which way you face. Pure (unit-tested).
 */
class ArFrame {
    private var sumSin = 0.0
    private var sumCos = 0.0
    private var frames = 0
    /** AR north to true north, radians clockwise; null until aligned. */
    private var offset: Double? = null
    private var originX = 0.0
    private var originY = 0.0

    val aligned get() = offset != null

    /** Tracking was lost or paused: the camera's world may have moved, align again. */
    fun reset() { sumSin = 0.0; sumCos = 0.0; frames = 0; offset = null }

    /**
     * One tracked frame: the camera at AR ([x], [z]) looking [arHeading] (degrees clockwise from AR north, -z);
     * [compass] (null unless held level) and [here] (Motion's place) align it. Where you are, once aligned.
     */
    fun place(x: Float, z: Float, arHeading: Float, compass: Float?, here: Motion.Pos?): Motion.Pos? {
        val o = offset ?: run {
            if (compass == null || here == null) return null
            val d = Math.toRadians(HeadingFusion.diff(compass, arHeading).toDouble())
            sumSin += sin(d); sumCos += cos(d)
            if (++frames < ALIGN_FRAMES) return null
            val o = atan2(sumSin, sumCos)
            val (e, n) = turn(x, z, o)
            originX = here.x - e; originY = here.y - n
            offset = o
            o
        }
        val (e, n) = turn(x, z, o)
        return Motion.Pos(originX + e, originY + n)
    }

    /** True compass degrees for an AR heading, null until aligned. */
    fun heading(arHeading: Float): Float? = offset?.let { HeadingFusion.norm(arHeading + Math.toDegrees(it).toFloat()) }

    /** AR (x, z) as east / north turned [o] clockwise. */
    private fun turn(x: Float, z: Float, o: Double): Pair<Double, Double> {
        val e = x.toDouble(); val n = -z.toDouble()
        return (e * cos(o) + n * sin(o)) to (-e * sin(o) + n * cos(o))
    }

    companion object {
        /** About a second and a half of updates held level. */
        const val ALIGN_FRAMES = 30

        /** Degrees clockwise from AR north (-z) of the way the camera looks ([fx], [fz]: its forward, flattened). */
        fun arHeading(fx: Float, fz: Float) = HeadingFusion.norm(Math.toDegrees(atan2(fx.toDouble(), -fz.toDouble())).toFloat())
    }
}
