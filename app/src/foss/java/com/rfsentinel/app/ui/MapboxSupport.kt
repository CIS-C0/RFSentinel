package com.rfsentinel.app.ui

import android.content.Context

/** The FOSS build has no Mapbox SDK: the map is always OpenFreeMap (MapLibre). */
internal object MapboxSupport {
    const val INCLUDED = false

    fun engine(context: Context): MapEngine = MapLibreEngine(context)
}
