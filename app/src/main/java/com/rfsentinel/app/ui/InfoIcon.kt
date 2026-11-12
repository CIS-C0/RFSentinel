package com.rfsentinel.app.ui

import android.content.Context
import android.view.Gravity
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The ⓘ beside a setting: tapping it shows [text] under [title] (Settings and the Sentinel Link screen). */
fun infoIcon(context: Context, text: String, title: () -> String): TextView {
    val dp = context.resources.displayMetrics.density
    val ripple = android.util.TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }.resourceId
    return TextView(context).apply {
        this.text = "ⓘ"
        textSize = 18f
        alpha = 0.7f
        minWidth = (48 * dp).toInt(); minHeight = (48 * dp).toInt(); gravity = Gravity.CENTER
        setBackgroundResource(ripple)
        contentDescription = "About ${title()}"
        setOnClickListener { MaterialAlertDialogBuilder(context).setTitle(title()).setMessage(text).setPositiveButton("OK", null).show() }
    }
}
