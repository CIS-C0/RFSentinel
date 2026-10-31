package com.rfsentinel.app.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * Vehicle / cellular routers (Cradlepoint, Sierra Wireless...) ride in police cars, but the same routers sit in
 * shops, kiosks and traffic-signal cabinets. A router address you keep hearing, scan after scan and day after day,
 * is bolted down somewhere on your way: it's listed, not alerted - unless something else backs it (an Axon
 * network, a patrol cluster, your watchlist).
 */
object FixedRouter {

    private val ROUTER = Regex("cradlepoint|sierra wireless|peplink|inseego|cellular router", RegexOption.IGNORE_CASE)
    /** Heard in this many scanning sessions, the first on an earlier day: a fixed router. */
    const val MIN_SESSIONS = 3

    /** Router addresses heard often enough, loaded as a scan starts. */
    private val familiar = ConcurrentHashMap.newKeySet<String>()

    /** Every match is only "it's a vehicle router": nothing else backs it. */
    fun routerOnly(hits: List<Hit>) = hits.isNotEmpty() && hits.all { ROUTER.containsMatchIn(it.label) }

    /** True for a router-only device ([hits]) that is one of the [familiar] ones. Never on the Weak threshold (0): it alerts on every match. */
    fun quiet(mac: String, hits: List<Hit>, threshold: Int) = threshold > 0 && mac.uppercase() in familiar && routerOnly(hits)

    /** [macs]: the router makers' addresses heard in [MIN_SESSIONS] sessions or more, the first before today. */
    fun load(macs: Collection<String>) {
        familiar.clear()
        macs.mapTo(familiar) { it.uppercase() }
    }
}
