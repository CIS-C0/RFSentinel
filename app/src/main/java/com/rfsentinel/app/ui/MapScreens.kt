package com.rfsentinel.app.ui

import com.rfsentinel.app.nav.Place
import com.rfsentinel.app.nav.Route
import com.rfsentinel.app.nav.Valhalla

/**
 * What the live map shows, kept for both of its screens: the small map on the main screen and the full screen
 * map. The one leaving the screen writes it; the other, coming back, shows the same: the same place, zoom,
 * heading and tilt, following you or not, and the same place card and routes.
 */
internal object MapScreens {
    class View(
        val lat: Double, val lon: Double, val zoom: Double, val bearing: Double, val tilt: Double,
        val following: Boolean, val userZoom: Boolean, val at: Long
    )

    class Card(val place: Place, val routes: List<Route>, val selected: Int, val options: Valhalla.Options)

    /** Counts the writes: a map that has seen the latest one has nothing to pick up. */
    var version = 0
        private set
    var view: View? = null
        private set
    var card: Card? = null
        private set

    fun save(view: View, card: Card?) {
        this.view = view
        this.card = card
        version++
    }
}
