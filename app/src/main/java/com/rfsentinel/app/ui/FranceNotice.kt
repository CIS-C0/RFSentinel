package com.rfsentinel.app.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Shown when the France preset is switched on: French law bans radar detectors and any
 * device or app that signals speed cameras (Code de la route R413-15, décret 2012-3).
 */
object FranceNotice {

    const val TEXT = "In France, radar detectors and devices or apps that signal the location of speed cameras " +
        "are illegal (Code de la route R413-15, décret n° 2012-3: €1,500 fine, 6 points, device seized). " +
        "Since 2021 the authorities can also require apps to hide police checks on given roads.\n\n" +
        "If you drive in France, turn off speed-camera and red-light camera warnings (Settings > Known cameras) " +
        "and Waze police reports, and don't use RF Sentinel to locate speed-enforcement equipment. " +
        "You are responsible for complying with the law."

    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Driving in France")
            .setMessage(TEXT)
            .setPositiveButton("I understand", null)
            .show()
    }
}
