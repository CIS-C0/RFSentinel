package com.rfsentinel.app.detect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Signs of GNSS jamming and spoofing, from what the phone's satellite receiver already
 * reports for every system it tracks (GPS, GLONASS, Galileo, BeiDou, QZSS, NavIC, SBAS):
 * each satellite's signal strength (C/N0) and height in the sky, the receiver's input
 * gain (AGC), and its fixes. Passive: nothing is transmitted.
 *
 * - Jamming: the noise the receiver sees jumps (its AGC drops) while every satellite
 *   weakens together. A tunnel or a garage weakens the satellites too, but doesn't raise
 *   the noise, so it isn't mistaken for jamming. Without an AGC reading (some phones
 *   don't report one) a sudden total loss is only logged as a weak sign.
 * - Spoofing: one fake transmitter makes every satellite equally strong whatever its
 *   height; its clock or position can jump; and cheap spoofers fake GPS only, so the other
 *   systems vanish while "GPS" stays strong.
 * These are signs, not proof.
 */
class GnssAnalyzer {

    enum class System(val label: String) {
        GPS("GPS"), SBAS("SBAS"), GLONASS("GLONASS"), QZSS("QZSS"), BEIDOU("BeiDou"),
        GALILEO("Galileo"), NAVIC("NavIC"), OTHER("Other");

        companion object {
            /** From android.location.GnssStatus.CONSTELLATION_* */
            fun of(constellation: Int) = when (constellation) {
                1 -> GPS; 2 -> SBAS; 3 -> GLONASS; 4 -> QZSS; 5 -> BEIDOU; 6 -> GALILEO; 7 -> NAVIC; else -> OTHER
            }
        }
    }

    data class Sat(val system: System, val svid: Int, val cn0: Float, val elevation: Float, val usedInFix: Boolean,
                   val carrierMhz: Float? = null)

    /** One receiver report: the satellites, and the input gain when the phone reports it. */
    data class Snapshot(val time: Long, val sats: List<Sat>, val agcDb: Double? = null)

    /** A GNSS fix with the phone's (network) clock when it arrived. */
    data class Fix(val gnssTime: Long, val systemTime: Long, val lat: Double, val lon: Double, val accuracyM: Float)

    data class Anomaly(val key: String, val title: String, val detail: String, val confidence: Int)

    // Baselines learned while the sky is clear: strongest-4 average C/N0, AGC, systems tracked.
    private var cn0Base: Double? = null
    private var agcBase: Double? = null
    private val systemsSeen = HashMap<System, Long>()
    private var jamSince = 0L
    private var lossSince = 0L
    private var uniformSince = 0L
    private var gpsOnlySince = 0L
    private var lastFix: Fix? = null
    private val lastAlert = HashMap<String, Long>()

    /** The latest state in words, for the satellite screen. */
    var verdict: String = "Waiting for satellites…"; private set

    fun onSnapshot(s: Snapshot): List<Anomaly> {
        val out = ArrayList<Anomaly>()
        val tracked = s.sats.filter { it.cn0 > 0f }
        val top4 = tracked.map { it.cn0.toDouble() }.sortedDescending().take(4)
        val strength = if (top4.isEmpty()) 0.0 else top4.average()
        val clearSky = tracked.count { it.cn0 >= 30f } >= 6

        // ---- jamming ----
        val agcDrop = if (s.agcDb != null && agcBase != null) agcBase!! - s.agcDb else null
        val cn0Drop = cn0Base?.let { it - strength }
        val jammed = agcDrop != null && agcDrop >= AGC_JAM_DB && (cn0Drop ?: 0.0) >= CN0_JAM_DB
        if (jammed) {
            if (jamSince == 0L) jamSince = s.time
            if (s.time - jamSince >= SUSTAIN_MS) report(out, s.time, "jam",
                "GNSS jamming signs",
                "The satellite receiver's noise rose by ${fmt(agcDrop!!)} dB while every satellite weakened by ${fmt(cn0Drop!!)} dB " +
                    "(${systems(tracked)}). A jammer nearby, often a cheap \"GPS blocker\" in a vehicle, does this; a tunnel doesn't raise the noise.",
                if (agcDrop >= 10) 75 else 60)
        } else jamSince = 0L

        // Total loss without an AGC reading: can't tell a jammer from a roof, so only a weak sign.
        val lost = s.agcDb == null && cn0Base != null && cn0Base!! >= 35 && tracked.count { it.cn0 >= 20f } == 0
        if (lost) {
            if (lossSince == 0L) lossSince = s.time
            if (s.time - lossSince >= SUSTAIN_MS) report(out, s.time, "loss", "All satellite signals lost at once",
                "Every satellite system dropped out together after a clear sky. A jammer can do this, but so can a tunnel or a " +
                    "building; this phone doesn't report the receiver noise that would tell them apart.", 25)
        } else lossSince = 0L

        // ---- spoofing: every satellite the same strength whatever its height ----
        val strong = tracked.filter { it.cn0 >= 25f }
        if (strong.size >= 6) {
            val cn = strong.map { it.cn0.toDouble() }
            val mean = cn.average()
            val sd = sqrt(cn.sumOf { (it - mean) * (it - mean) } / cn.size)
            val corr = correlation(strong.map { it.elevation.toDouble() }, cn)
            if (sd < UNIFORM_SD_DB && mean >= 38) {
                if (uniformSince == 0L) uniformSince = s.time
                if (s.time - uniformSince >= SUSTAIN_MS) report(out, s.time, "uniform", "GNSS spoofing signs",
                    "${strong.size} satellites all arrive at almost the same strength (${fmt(mean)} dB-Hz, spread ${fmt(sd)} dB) " +
                        "whatever their height in the sky. Real satellites near the horizon are weaker; one fake transmitter " +
                        "makes them all alike.", if (corr < 0.1) 65 else 50)
            } else uniformSince = 0L
        } else uniformSince = 0L

        // ---- spoofing: GPS stays strong while the other systems vanish ----
        val now = s.time
        val present = tracked.filter { it.cn0 >= 20f }.map { it.system }.toSet()
        for (sys in present) systemsSeen[sys] = now
        val othersRecently = systemsSeen.filter { (sys, t) -> sys != System.GPS && sys != System.SBAS && sys != System.QZSS && now - t < 10 * 60_000L }.keys
        val gpsStrong = tracked.count { it.system == System.GPS && it.cn0 >= 30f } >= 4
        val othersGone = othersRecently.isNotEmpty() && othersRecently.none { it in present }
        if (gpsStrong && othersGone && cn0Base != null) {
            if (gpsOnlySince == 0L) gpsOnlySince = s.time
            if (s.time - gpsOnlySince >= SUSTAIN_MS) report(out, s.time, "gpsonly", "GNSS spoofing signs",
                "GPS is still strong, but ${othersRecently.joinToString { it.label }} - tracked a few minutes ago - vanished at once. " +
                    "Most spoofers fake GPS only.", 50)
        } else gpsOnlySince = 0L

        // ---- learn the baselines on a normal, clear sky ----
        if (clearSky && !jammed && uniformSince == 0L) {
            cn0Base = cn0Base?.let { it + (strength - it) * 0.05 } ?: strength
            s.agcDb?.let { a -> agcBase = agcBase?.let { it + (a - it) * 0.05 } ?: a }
        }

        verdict = when {
            out.isNotEmpty() -> out.first().title
            jammed || lost || uniformSince != 0L || gpsOnlySince != 0L -> "Checking an unusual reading…"
            tracked.isEmpty() -> "No satellites heard (indoors?)"
            cn0Base == null -> "Learning this sky…"
            else -> "No jamming or spoofing signs"
        }
        return out
    }

    /** Spoofing: GNSS time far from network time, or a jump no vehicle could make. */
    fun onFix(f: Fix): List<Anomaly> {
        val out = ArrayList<Anomaly>()
        val skew = abs(f.gnssTime - f.systemTime) / 1000
        if (skew >= TIME_SKEW_S) report(out, f.systemTime, "time", "GNSS spoofing signs",
            "The satellite clock says ${skew} s ${if (f.gnssTime > f.systemTime) "ahead of" else "behind"} the network time. " +
                "Spoofers often shift the time.", 55)
        lastFix?.let { p ->
            val dt = (f.gnssTime - p.gnssTime) / 1000.0
            if (dt in 0.5..120.0) {
                val d = distanceM(p.lat, p.lon, f.lat, f.lon) - p.accuracyM - f.accuracyM
                if (d / dt > MAX_SPEED_MPS) report(out, f.systemTime, "jump", "GNSS spoofing signs",
                    "The position jumped ${fmt(d / 1000)} km in ${fmt(dt)} s (${(d / dt * 3.6).toInt()} km/h). " +
                        "No car or plane moves like that; a spoofer moving the fake position can.", 55)
            }
        }
        lastFix = f
        return out
    }

    private fun report(out: MutableList<Anomaly>, now: Long, key: String, title: String, detail: String, confidence: Int) {
        if (now - (lastAlert[key] ?: Long.MIN_VALUE / 2) < REPEAT_MS) return
        lastAlert[key] = now
        out += Anomaly(key, title, detail, confidence)
    }

    private fun systems(sats: List<Sat>) = sats.map { it.system.label }.distinct().joinToString().ifEmpty { "no satellites left" }

    companion object {
        const val SUSTAIN_MS = 4_000L
        private const val REPEAT_MS = 10 * 60_000L
        private const val AGC_JAM_DB = 6.0
        private const val CN0_JAM_DB = 8.0
        private const val UNIFORM_SD_DB = 1.5
        private const val TIME_SKEW_S = 20L
        private const val MAX_SPEED_MPS = 340.0

        private fun fmt(v: Double) = "%.1f".format(java.util.Locale.US, v)

        fun correlation(x: List<Double>, y: List<Double>): Double {
            if (x.size < 3) return 1.0
            val mx = x.average(); val my = y.average()
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (i in x.indices) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my) }
            return if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / sqrt(sxx * syy)
        }

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
            return 2 * r * kotlin.math.asin(sqrt(a))
        }
    }
}
