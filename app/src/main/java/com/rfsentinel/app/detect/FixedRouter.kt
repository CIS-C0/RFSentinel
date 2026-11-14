package com.rfsentinel.app.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * Vehicle / cellular routers (Cradlepoint, Sierra Wireless...) and mobile printers (Zebra, Brother) ride in police
 * cars, but the same models sit in shops, kiosks and traffic-signal cabinets. One you keep hearing, session after
 * session and day after day, always around the same spot, is bolted down there (as a radar detector learns a shop's
 * door opener): near that spot it's listed, not alerted - unless something else backs it (an Axon network, a patrol
 * cluster, your watchlist). Heard anywhere else it alerts again: it moved, so it rides in something. A router whose
 * own network says it isn't in a police car (open passenger or shop WiFi, a Cradlepoint branch / kiosk model's
 * default name) is listed, not alerted, everywhere.
 */
object FixedRouter {

    private val ROUTER = Regex("cradlepoint|sierra wireless|peplink|inseego|cellular router", RegexOption.IGNORE_CASE)
    /** Heard in this many scanning sessions, the first on an earlier day: a fixed router. */
    const val MIN_SESSIONS = 3

    /** Within this of its usual spot it stays quiet (a long-range antenna hears a router a few hundred metres off). */
    const val NEAR_M = 1_000.0
    /** Logged further apart than this, it isn't bolted down. */
    const val SPREAD_M = 1_500.0

    /** No spot logged for it. */
    private val NOWHERE = doubleArrayOf()
    /** Addresses heard often enough, with their usual spot (lat, lon) or [NOWHERE], loaded as a scan starts. */
    private val familiar = ConcurrentHashMap<String, DoubleArray>()

    /** Every match is only "it's a vehicle router": nothing else backs it. */
    fun routerOnly(hits: List<Hit>) = hits.isNotEmpty() && hits.all { ROUTER.containsMatchIn(it.label) }

    /** Every match is only a vehicle router or a mobile printer: the kinds shops have too. */
    private fun fixedKindOnly(hits: List<Hit>) =
        hits.isNotEmpty() && hits.all { ROUTER.containsMatchIn(it.label) || it.category == Category.MOBILE_PRINTER }

    /** Cradlepoint's fixed-site routers (branch, kiosk, ATM: E100, E300, AER2200, MBR1400...) on their default name, model + last 3 address digits. */
    private val BRANCH_DEFAULT = Regex("^(e\\d{3,4}|aer\\d{3,4}|mbr\\d{3,4}|cba\\d{3,4})-[0-9a-f]{3}(-5g)?$", RegexOption.IGNORE_CASE)

    /**
     * True for a router- or printer-only device ([hits]) that is one of the [familiar] ones, heard near its usual spot
     * ([lat], [lon]: where it's heard from; unknown counts as near), or a router whose network ([ssid], security
     * [capabilities]) is open or a branch router's default name. On every threshold, Weak too.
     */
    fun quiet(mac: String, hits: List<Hit>, ssid: String? = null, capabilities: String? = null,
              lat: Double? = null, lon: Double? = null): Boolean {
        if (!fixedKindOnly(hits)) return false
        val spot = familiar[mac.uppercase()]
        if (spot != null && (spot.isEmpty() || lat == null || lon == null ||
                GnssAnalyzer.distanceM(spot[0], spot[1], lat, lon) <= NEAR_M)) return true
        return routerOnly(hits) && ((ssid != null && BRANCH_DEFAULT.matches(ssid)) ||
            (!capabilities.isNullOrBlank() && DeviceIntel.security(capabilities) == "Open (no password)"))
    }

    /**
     * [macs]: the router and printer makers' addresses heard in [MIN_SESSIONS] sessions or more, the first before
     * today; [spots]: where each was logged. One logged more than [SPREAD_M] apart moves around: not familiar.
     */
    fun load(macs: Collection<String>, spots: Collection<com.rfsentinel.app.data.Spot> = emptyList()) {
        val at = spots.associateBy { it.mac.uppercase() }
        familiar.clear()
        for (m in macs) {
            val mac = m.uppercase()
            val s = at[mac]
            if (s == null) familiar[mac] = NOWHERE
            else if (GnssAnalyzer.distanceM(s.minLat, s.minLon, s.maxLat, s.maxLon) <= SPREAD_M) familiar[mac] = doubleArrayOf(s.lat, s.lon)
        }
    }
}
