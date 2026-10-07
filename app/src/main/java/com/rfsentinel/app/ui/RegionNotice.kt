package com.rfsentinel.app.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Shown when a regional preset with driving rules worth knowing is switched on: France bans
 * apps that signal speed cameras. Elsewhere (UK, Portugal...) camera warnings are allowed, and
 * RF Sentinel doesn't detect radar, so there's no notice.
 */
object RegionNotice {

    const val FRANCE = "In France, radar detectors and devices or apps that signal the location of speed cameras " +
        "are illegal (Code de la route R413-15, décret n° 2012-3: €1,500 fine, 6 points, device seized). " +
        "Since 2021 the authorities can also require apps to hide police checks on given roads.\n\n" +
        "If you drive in France, turn off speed-camera and red-light camera warnings (Settings > Known cameras) " +
        "and Waze police reports, and don't use RF Sentinel to locate speed-enforcement equipment. " +
        "You are responsible for complying with the law."

    /** The notice for a preset key, or null when that region has none. */
    fun textFor(preset: String): Pair<String, String>? = when (preset) {
        "france" -> "Driving in France" to FRANCE
        else -> null
    }

    fun show(context: Context, preset: String) {
        val (title, text) = textFor(preset) ?: return
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("I understand", null)
            .show()
    }
}
