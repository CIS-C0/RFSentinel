package com.rfsentinel.app.nav

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.util.Prefs

/** Asks for a new name for a starred place; [done] gets the renamed place. */
object RenamePlace {
    fun ask(context: Context, p: Place, done: (Place) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            setText(p.name); setSelectAllOnFocus(true); isSingleLine = true
            hint = "Name"
        }
        val box = FrameLayout(context).apply {
            val side = (20 * dp).toInt(); setPadding(side, (8 * dp).toInt(), side, 0); addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Rename starred place")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.renameNavFavorite(context, p, input.text.toString())?.let(done)
            }
            .setNegativeButton("Cancel", null)
            .show()
            .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }
}
