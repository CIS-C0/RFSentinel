package com.rfsentinel.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The full build: Locate's camera view (ARCore, Google Play Services for AR, which it offers to install). */
internal object ArSupport {
    /** The Locate guide's button for it, with a back camera: whether ARCore runs is only asked on opening (no query to Google before). */
    fun button(activity: Activity): String? = "Use camera".takeIf { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) }

    /** Opens the camera view; the first time, Google's notice (ARCore's terms ask apps to show it) comes first. */
    fun open(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(NOTICE_SEEN, false)) return start(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Camera view uses Google")
            .setMessage("This runs on Google Play Services for AR (ARCore), which is provided by Google and governed by " +
                "the Google Privacy Policy. Google may collect usage data from it.\n\n" +
                "RF Sentinel keeps the camera picture on the phone and saves nothing from it.")
            .setPositiveButton("Continue") { _, _ -> prefs.edit().putBoolean(NOTICE_SEEN, true).apply(); start(activity) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Privacy Policy") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://policies.google.com/privacy"))) }
            }
            .show()
    }

    private const val PREFS = "ar_locate"
    private const val NOTICE_SEEN = "notice_seen"

    private fun start(activity: Activity) = activity.startActivity(Intent(activity, ArLocateActivity::class.java))
}
