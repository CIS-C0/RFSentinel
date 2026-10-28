package com.rfsentinel.app.util

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.BuildConfig

/**
 * The FOSS (F-Droid) build asks once before it fetches the known cameras from OpenStreetMap on its own; until
 * then it fetches nothing. The full build keeps the Settings switch's default (on).
 */
object CameraQuestion {

    @Volatile private var asking = false

    /** Shows the question when it's still open; [then] runs once it's answered (or right away when nothing to ask). */
    fun askIfNeeded(activity: AppCompatActivity, then: () -> Unit) {
        if (BuildConfig.FLAVOR != "foss" || Prefs.camerasAsked(activity)) { then(); return }
        if (asking || activity.isFinishing || activity.isDestroyed) return
        asking = true
        fun answer(yes: Boolean) {
            Prefs.setAutoCameras(activity, yes)
            Prefs.setCamerasAsked(activity, true)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Load camera locations?")
            .setMessage("RF Sentinel can download the plate-reader, speed and red-light cameras mapped in OpenStreetMap " +
                "for the area the map shows and the route you drive, to warn you before them. OpenStreetMap's servers " +
                "see your IP address and that area; nothing about your scans is sent.\n\nWithout it, nothing is " +
                "downloaded on its own.\n\nYou can change this any time in Settings.")
            .setPositiveButton("Load cameras") { _, _ -> answer(true); then() }
            .setNegativeButton("Don't load") { _, _ -> answer(false); then() }
            .setCancelable(false)
            .setOnDismissListener { asking = false }
            .show()
    }
}
