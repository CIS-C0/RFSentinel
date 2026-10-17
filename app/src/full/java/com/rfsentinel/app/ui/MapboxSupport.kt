package com.rfsentinel.app.ui

import android.content.Context

/** The full build: the optional Mapbox map (Mapbox Maps SDK, Mapbox terms). */
internal object MapboxSupport {
    const val INCLUDED = true

    fun engine(context: Context): MapEngine = MapboxEngine(context)
}
