package com.rfsentinel.app.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager

/** The full build: Locate's camera view (ARCore, Google Play Services for AR, which it offers to install). */
internal object ArSupport {
    /** A back camera: whether ARCore runs on it is only asked when you open the view (no query to Google before). */
    fun available(activity: Activity) = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)

    fun open(activity: Activity) = activity.startActivity(Intent(activity, ArLocateActivity::class.java))
}
