package com.rfsentinel.app.detect

/**
 * Keeps the weak Flock clues from flagging everyday gadgets. The "community list" module
 * prefixes (Liteon, Silicon Labs, Espressif... chips Flock cameras happen to use) and the
 * generic "FS-<hex>" name are shared with speakers, scooters, bulbs and meeting-room boxes:
 *  - a module-prefix match is dropped when the device names itself as something else
 *    (a Flock module has no name or a Flock-style one);
 *  - outside Flock's market (a European preset on, United States off) every Flock match is dropped, strong
 *    signatures included: Flock isn't used there;
 *  - with Flock cameras switched off (What to detect), every Flock plate camera match is dropped. Other plate
 *    cameras and Flock's Raven audio sensor have their own switches.
 * In Flock's market, strong signatures (Penguin / Falcon / Raven names, Flock's own service, the
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

    private fun isFlock(h: Hit) = h.label.contains("Flock", ignoreCase = true) || isGenericFsName(h)

    fun filter(hits: List<Hit>, name: String?, flockRegion: Boolean, flockCameras: Boolean = true): List<Hit> {
        val named = name?.trim()?.takeIf { it.isNotEmpty() }
        return hits.filterNot { h ->
            when {
                !flockRegion -> isFlock(h)
                !flockCameras && h.category == Category.ALPR && isFlock(h) -> true
                isCommunityPrefix(h) -> named != null && !FLOCK_NAME.containsMatchIn(named)
                else -> false
            }
        }
    }
}
