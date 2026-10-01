package com.rfsentinel.app.ui

import android.content.Context
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry

/**
 * One colour code for a device everywhere it's drawn - the list, the radar,
 * the phone map and the car map: its category colour for probable and strong
 * matches, amber for weak ones, and the theme's quiet scope colour for
 * ordinary (or whitelisted) devices. Night Drive turns them all red.
 */
object DeviceColors {

    const val WEAK = 0xFFB26A00.toInt()

    /** Ordinary devices: the radar scope colour of the current theme. */
    fun ordinary(context: Context): Int =
        MaterialColors.getColor(context, R.attr.rfScopeColor, 0xFF0B5C63.toInt())

    /** A match of [category] with [confidence]%; null category = ordinary device. */
    fun forMatch(context: Context, category: Category?, confidence: Int): Int =
        if (category == null) ordinary(context)
        else ThemeManager.ink(context, if (confidence in 1 until Tier.MEDIUM.min) WEAK else category.colorArgb)

    /** Flagged = matched and not whitelisted. */
    fun isFlagged(s: DeviceRegistry.Snapshot) = s.best != null && !WhitelistCache.contains(s.mac)

    fun forDevice(context: Context, s: DeviceRegistry.Snapshot): Int {
        val best = s.best
        return if (best == null || !isFlagged(s)) ordinary(context)
        else ThemeManager.ink(context, if (best.tier == Tier.WEAK) WEAK else best.category.colorArgb)
    }
}
