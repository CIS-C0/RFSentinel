package com.rfsentinel.app.ui

import android.app.Activity

/** The FOSS build has no ARCore (Google's): Locate is the chest sweep only. */
internal object ArSupport {
    fun available(activity: Activity) = false

    fun open(activity: Activity) {}
}
