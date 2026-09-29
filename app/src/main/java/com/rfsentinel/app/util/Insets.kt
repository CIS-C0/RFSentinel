package com.rfsentinel.app.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * targetSdk 35+ forces edge-to-edge: the content view is laid out from the very
 * top of the window, underneath the status bar and the window action bar. AppCompat
 * already folds the action bar height into the top inset it dispatches to content,
 * so padding by the system-bar insets (plus keyboard) clears everything.
 */
fun View.applySystemBarInsets() {
    val start = paddingLeft
    val top = paddingTop
    val end = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.updatePadding(
            left = start + bars.left,
            top = top + bars.top,
            right = end + bars.right,
            bottom = bottom + bars.bottom
        )
        insets
    }
}
