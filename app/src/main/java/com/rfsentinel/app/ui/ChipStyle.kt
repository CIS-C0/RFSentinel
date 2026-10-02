package com.rfsentinel.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import com.google.android.material.chip.Chip
import com.rfsentinel.app.R

/**
 * One look for the filter chips everywhere (list and map), readable in every
 * theme: the selected chip is filled with the theme's highlight colour, the
 * others are outlined in the text colour. Replaces the stock choice chips,
 * whose selected state was dark teal on near-black in the dark theme.
 */
object ChipStyle {

    /** The theme's highlight colour and the text colour on it. */
    fun accent(context: Context): Pair<Int, Int> {
        val accent = attr(context, R.attr.rfAccent) ?: attr(context, androidx.appcompat.R.attr.colorPrimary) ?: 0xFF0B5C63.toInt()
        val on = attr(context, R.attr.rfOnAccent) ?: attr(context, com.google.android.material.R.attr.colorOnPrimary) ?: 0xFFFFFFFF.toInt()
        return ThemeManager.ink(context, accent) to on
    }

    fun onSurface(context: Context): Int =
        attr(context, com.google.android.material.R.attr.colorOnSurface) ?: attr(context, android.R.attr.textColorPrimary) ?: 0xFF888888.toInt()

    /** [overMap]: unselected chips get a solid background so they read on any map. */
    fun apply(chip: Chip, overMap: Boolean = false) {
        val ctx = chip.context
        val (accent, onAccent) = accent(ctx)
        val text = onSurface(ctx)
        val checked = intArrayOf(android.R.attr.state_checked)
        val none = intArrayOf()
        val idle = if (overMap) (attr(ctx, com.google.android.material.R.attr.colorSurface) ?: attr(ctx, android.R.attr.colorBackground)
            ?: 0xFF202020.toInt()) or 0xF0000000.toInt() else 0x00000000
        chip.chipBackgroundColor = ColorStateList(arrayOf(checked, none), intArrayOf(accent, idle))
        if (overMap) chip.elevation = 3f * ctx.resources.displayMetrics.density
        chip.setTextColor(ColorStateList(arrayOf(checked, none), intArrayOf(onAccent, text)))
        chip.chipStrokeColor = ColorStateList(arrayOf(checked, none), intArrayOf(accent, (text and 0x00FFFFFF) or 0x66000000))
        chip.chipStrokeWidth = 1.5f * ctx.resources.displayMetrics.density
        chip.isCheckedIconVisible = false
        chip.setEnsureMinTouchTargetSize(true)
        chip.typeface = android.graphics.Typeface.create(chip.typeface, android.graphics.Typeface.BOLD)
    }

    private fun attr(context: Context, id: Int): Int? {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(id, tv, true)) return null
        return if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data
        else runCatching { context.getColor(tv.resourceId) }.getOrNull()
    }
}
