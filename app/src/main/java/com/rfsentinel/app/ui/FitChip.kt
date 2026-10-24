package com.rfsentinel.app.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.chip.Chip

/**
 * A chip as wide as the label it draws. A stock chip can't be narrower than its background, which measures
 * the label in the theme's chip font, not the bold one we draw it in: long labels ("TVs & consoles") were
 * left with a gap at the end.
 */
class FitChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipStyle
) : Chip(context, attrs, defStyleAttr) {
    override fun getSuggestedMinimumWidth(): Int = minimumWidth
}
