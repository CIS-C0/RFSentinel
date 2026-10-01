package com.rfsentinel.app.ui

import com.rfsentinel.app.alpr.CameraPrefetch

/** One line describing a camera pre-download, for the setup page and Settings. */
fun cameraPrefetchText(s: CameraPrefetch.State, located: Boolean): String = when (s) {
    CameraPrefetch.State.Idle -> if (located) "" else "Grant location access first (above)."
    CameraPrefetch.State.Locating -> "Finding your position..."
    is CameraPrefetch.State.Downloading -> "Downloading cameras around you... ${s.done}/${s.total}"
    is CameraPrefetch.State.Done -> "✓ ${s.cameras} known cameras saved within ~100 km of you."
    is CameraPrefetch.State.Failed -> "Couldn't download: ${s.reason}"
}
