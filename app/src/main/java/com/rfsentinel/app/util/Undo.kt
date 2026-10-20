package com.rfsentinel.app.util

import android.app.Activity
import android.view.View
import com.google.android.material.snackbar.Snackbar

/** A short note after removing something, with Undo to put it back (a toast can't take it back). */
object Undo {
    fun show(activity: Activity, message: String, undo: () -> Unit) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).setAction("Undo") { undo() }.show()
    }
}
