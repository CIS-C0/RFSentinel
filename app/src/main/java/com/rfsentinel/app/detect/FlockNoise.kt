package com.rfsentinel.app.detect

/**
 * Keeps the weak Flock clues from flagging everyday gadgets. The "community list" module
 * prefixes (Liteon, Silicon Labs, Espressif... chips Flock cameras happen to use) and the
 * generic "FS-<hex>" name are shared with speakers, scooters, bulbs and meeting-room boxes:
 *  - a module-prefix match is dropped when the device names itself as something else
 *    (a Flock module has no name or a Flock-style one);
 *  - outside Flock's market (France preset on, US and Canada off) both are dropped entirely.
 * Strong Flock signatures (Penguin / Falcon / Raven names, Flock's own service, the
 * battery name, XUNTONG ID) are never touched.
 */
object FlockNoise {

    /** Names a Flock camera, battery or module can carry. */
    private val FLOCK_NAME = Regex("^(penguin|fs[ -]|flock|falcon|sparrow|raven|\\d{8,12}$)", RegexOption.IGNORE_CASE)

    fun isCommunityPrefix(h: Hit) =
        h.category == Category.ALPR && h.label.contains("seen on Flock", ignoreCase = true) &&
            h.label.contains("community list", ignoreCase = true)

    fun isGenericFsName(h: Hit) =
        h.category == Category.ALPR && h.evidence.contains("generic white-label", ignoreCase = true)

    fun filter(hits: List<Hit>, name: String?, flockRegion: Boolean): List<Hit> {
        val named = name?.trim()?.takeIf { it.isNotEmpty() }
        return hits.filterNot { h ->
            when {
                isCommunityPrefix(h) -> !flockRegion || (named != null && !FLOCK_NAME.containsMatchIn(named))
                isGenericFsName(h) -> !flockRegion
                else -> false
            }
        }
    }
}
