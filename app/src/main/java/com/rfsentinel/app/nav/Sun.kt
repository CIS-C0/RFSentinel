package com.rfsentinel.app.nav

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is (pure, offline; NOAA's low-precision solar position formulas, good to about a
 * tenth of a degree): so the map can turn to its night look after sunset while you are guided, with
 * nothing asked of any server.
 */
object Sun {

    /** The sun's height above the horizon in degrees (negative below it) at ([lat], [lon]) at [epochMs]. */
    fun elevationDeg(lat: Double, lon: Double, epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36_525.0
        val l0 = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
        val m = Math.toRadians(357.52911 + t * (35_999.05029 - 0.0001537 * t))
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val lambda = Math.toRadians(l0 + c - 0.00569 - 0.00478 * sin(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = Math.toRadians(eps0 + 0.00256 * cos(omega))
        val decl = asin(sin(eps) * sin(lambda))
        val y = tan(eps / 2).let { it * it }
        val l0r = Math.toRadians(l0)
        val eqTimeMin = 4.0 * Math.toDegrees(
            y * sin(2 * l0r) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0r) - 0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * m))
        val utcMin = (epochMs / 60_000.0).mod(1440.0)
        val trueSolarMin = (utcMin + eqTimeMin + 4.0 * lon).mod(1440.0)
        val hourAngle = Math.toRadians(trueSolarMin / 4.0 - 180.0)
        val latR = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(hourAngle)))
    }

    /** Past sunset, before sunrise: the sun is more than 3 degrees below the horizon (dusk is well on its way). */
    fun isNight(lat: Double, lon: Double, epochMs: Long): Boolean = elevationDeg(lat, lon, epochMs) < NIGHT_BELOW_DEG

    private const val NIGHT_BELOW_DEG = -3.0

}
