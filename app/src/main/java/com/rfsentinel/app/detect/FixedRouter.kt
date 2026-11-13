package com.rfsentinel.app.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * Vehicle / cellular routers (Cradlepoint, Sierra Wireless...) ride in police cars, but the same routers sit in
 * shops, kiosks and traffic-signal cabinets. A router address you keep hearing, scan after scan and day after day,
 * is bolted down somewhere on your way: it's listed, not alerted - unless something else backs it (an Axon
 * network, a patrol cluster, your watchlist). So is one whose own network says it isn't in a police car: an open
 * network (passenger or shop WiFi) or a Cradlepoint branch / kiosk model's default name.
 */
object FixedRouter {

    private val ROUTER = Regex("cradlepoint|sierra wireless|peplink|inseego|cellular router", RegexOption.IGNORE_CASE)
    /** Heard in this many scanning sessions, the first on an earlier day: a fixed router. */
    const val MIN_SESSIONS = 3

    /** Router addresses heard often enough, loaded as a scan starts. */
    private val familiar = ConcurrentHashMap.newKeySet<String>()

    /** Every match is only "it's a vehicle router": nothing else backs it. */
    fun routerOnly(hits: List<Hit>) = hits.isNotEmpty() && hits.all { ROUTER.containsMatchIn(it.label) }

    /** Cradlepoint's fixed-site routers (branch, kiosk, ATM: E100, E300, AER2200, MBR1400...) on their default name, model + last 3 address digits. */
    private val BRANCH_DEFAULT = Regex("^(e\\d{3,4}|aer\\d{3,4}|mbr\\d{3,4}|cba\\d{3,4})-[0-9a-f]{3}(-5g)?$", RegexOption.IGNORE_CASE)

    /**
     * True for a router-only device ([hits]) that is one of the [familiar] ones, or whose network ([ssid], security
     * [capabilities]) is open or a branch router's default name. On every threshold, Weak too.
     */
    fun quiet(mac: String, hits: List<Hit>, ssid: String? = null, capabilities: String? = null) = routerOnly(hits) &&
        (mac.uppercase() in familiar || (ssid != null && BRANCH_DEFAULT.matches(ssid)) ||
            (!capabilities.isNullOrBlank() && DeviceIntel.security(capabilities) == "Open (no password)"))

    /** [macs]: the router makers' addresses heard in [MIN_SESSIONS] sessions or more, the first before today. */
    fun load(macs: Collection<String>) {
        familiar.clear()
        macs.mapTo(familiar) { it.uppercase() }
    }
}
