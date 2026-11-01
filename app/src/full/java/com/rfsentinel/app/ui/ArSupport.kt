package com.rfsentinel.app.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.util.Prefs

/** The full build: Locate's camera view (ARCore, Google Play Services for AR, which it offers to install). */
internal object ArSupport {
    /** A back camera: whether ARCore runs on it is only asked when you open the view (no query to Google before). */
    fun available(activity: Activity) = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)

    /** Opens the camera view; the first time, Google's notice (ARCore's terms ask apps to show it) comes first. */
    fun open(activity: Activity) {
        if (Prefs.arNoticeSeen(activity)) return start(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Camera view uses Google")
            .setMessage("This runs on Google Play Services for AR (ARCore), which is provided by Google and governed by " +
                "the Google Privacy Policy. Google may collect usage data from it.\n\n" +
                "RF Sentinel keeps the camera picture on the phone and saves nothing from it.")
            .setPositiveButton("Continue") { _, _ -> Prefs.setArNoticeSeen(activity); start(activity) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Privacy Policy") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://policies.google.com/privacy"))) }
            }
            .show()
    }

    private fun start(activity: Activity) = activity.startActivity(Intent(activity, ArLocateActivity::class.java))
}
