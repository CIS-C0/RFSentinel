package com.rfsentinel.app.ui

import com.rfsentinel.app.alpr.CameraPrefetch

/** One line describing a camera pre-download, for the setup page and Settings. */
fun cameraPrefetchText(s: CameraPrefetch.State, located: Boolean): String = when (s) {
    CameraPrefetch.State.Idle -> if (located) "" else "Needs the location permission first."
    CameraPrefetch.State.Locating -> "Finding your position..."
    is CameraPrefetch.State.Downloading ->
        "Downloading... ${s.done}/${s.total} areas, ${s.found} cameras so far\n${s.detail}\n" +
            "No need to wait - it keeps going in the background (see your notifications)."
    is CameraPrefetch.State.Done -> "✓ ${s.cameras} known cameras saved within ~${s.radiusKm} km of you." +
        (if (s.failedAreas > 0) " ${s.failedAreas} area(s) failed - the map fetches them when you look there." else "")
    is CameraPrefetch.State.Failed -> "Couldn't download: ${s.reason}"
}
