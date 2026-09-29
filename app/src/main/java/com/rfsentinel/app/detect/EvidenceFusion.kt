package com.rfsentinel.app.detect

import kotlin.math.roundToInt

/**
 * Combines several matches on one device into a single, better-calibrated
 * score. Each rule reports a confidence on its own; when two *different* rules
 * of the same category agree (for example a Zebra service UUID plus a watchlisted
 * address), the device is more likely what they both claim. The patrol-vehicle
 * hit is excluded: it is derived from the device's own matches.
 *
 * Uses noisy-OR with every supporting hit discounted by half, because rules
 * often look at related evidence (same vendor, same packet) and are not truly
 * independent. Hits under [MIN_SUPPORT] never corroborate anything, and fusion
 * alone can't push a score past [FUSED_CAP].
 */
object EvidenceFusion {

    const val MIN_SUPPORT = 30
    const val FUSED_CAP = 90
    private const val SUPPORT_WEIGHT = 0.5

    fun fuse(hits: List<Hit>): List<Hit> {
        if (hits.size < 2) return hits
        val sorted = hits.sortedByDescending { it.confidence }
        // The patrol-vehicle hit is itself derived from this device's own matches (they give
        // it its role), so it neither corroborates them nor is corroborated by them: fusing
        // the two would count the same evidence twice.
        val top = sorted.firstOrNull { it.source != PatrolCluster.SOURCE } ?: return sorted
        val support = sorted
            .filter { it !== top && it.source != PatrolCluster.SOURCE }
            .filter { it.category == top.category && it.confidence >= MIN_SUPPORT && it.label != top.label }
            .distinctBy { it.label }
        if (support.isEmpty() || top.confidence >= FUSED_CAP) return sorted

        var miss = 1.0 - top.confidence / 100.0
        support.forEach { miss *= 1.0 - SUPPORT_WEIGHT * it.confidence / 100.0 }
        val fused = ((1.0 - miss) * 100).roundToInt().coerceIn(top.confidence, FUSED_CAP)
        if (fused == top.confidence) return sorted

        val combined = top.copy(
            confidence = fused,
            evidence = top.evidence + ". Corroborated by: " +
                support.joinToString("; ") { "${it.label} (${it.confidence}%)" } +
                " - combined ${top.confidence}% -> $fused%"
        )
        return (sorted.map { if (it === top) combined else it }).sortedByDescending { it.confidence }
    }
}
