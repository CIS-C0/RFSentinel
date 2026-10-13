package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.nav.Navigator
import com.rfsentinel.app.nav.PlaceRank
import com.rfsentinel.app.nav.Place
import com.rfsentinel.app.nav.Places
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.launch

/**
 * Pick a destination: Home, Work, starred and recent ones, or a search in OpenStreetMap (Photon and
 * Nominatim together), sent only when you submit it - a car keyboard is slow, and the screen redraws on every letter.
 */
class DestinationScreen(carContext: CarContext, private val map: LiveMapScreen) : SafeScreen(carContext) {

    private var results: List<Place>? = null
    private var loading = false
    private var query = ""
    /** What's typed so far (searched only when submitted or tapped - no search-as-you-type). */
    private var typed = ""

    override fun buildTemplate(): Template {
        val list = ItemList.Builder()
        val shown = results ?: savedPlaces()
        val me = CarUi.currentLocation(carContext)
        // Some cars' keyboards don't send "submit": offer the search as a row too.
        if (typed.isNotBlank() && typed != query) {
            list.addItem(Row.Builder()
                .setTitle("Search \"${typed.trim()}\"")
                .addText("in OpenStreetMap")
                .setOnClickListener { search(typed) }
                .build())
        }
        // The car shows a few rows at a time (and the "Search" row above takes one): the best matches come first.
        val room = CarUi.listLimit(carContext) - if (typed.isNotBlank() && typed != query) 1 else 0
        shown.take(room.coerceAtLeast(1)).forEach { d ->
            val row = Row.Builder().setTitle(d.name)
            // Short for a car screen (older saved entries may hold the full address).
            val where = d.address.split(", ").filter { it.isNotBlank() }.take(2).joinToString(", ").ifBlank { "OpenStreetMap" }
            row.addText(
                if (me != null) NearbyMapScreen.distanceText(DeviceRegistry.metersBetween(me.latitude, me.longitude, d.lat, d.lon)) + " · " + where
                else where
            )
            row.setOnClickListener { go(d) }
            list.addItem(row.build())
        }
        if (shown.isEmpty() && !loading && (typed.isBlank() || typed == query)) {
            list.setNoItemsMessage(if (results == null) "Search for an address or a place" else "Nothing found for \"$query\"")
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) = search(searchText)
            override fun onSearchTextChanged(searchText: String) {
                val was = typed.isNotBlank() && typed != query
                typed = searchText
                // Only re-render when the row appears / its text changes (no network here).
                if (was || typed.isNotBlank()) invalidate()
            }
        })
            .setInitialSearchText(typed)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Destination (searched in OpenStreetMap)")
            .setShowKeyboardByDefault(results == null && savedPlaces().isEmpty())
            .setLoading(loading)
            .apply { if (!loading) setItemList(list.build()) }
            .build()
    }

    private fun search(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        // "48.137, 11.575" is a place already.
        PlaceRank.coordinates(q)?.let { results = listOf(it); invalidate(); return }
        loading = true
        invalidate()
        val me = CarUi.currentLocation(carContext)
        // Your own places that fit come first, then the answers: Photon and Nominatim are asked together
        // (a submitted search, which is what Nominatim's rules allow) and the first to answer is on the
        // screen at once.
        val own = PlaceRank.mine(q, Prefs.navHome(carContext), Prefs.navWork(carContext), Prefs.navFavorites(carContext),
            Prefs.recentDestinations(carContext), limit = 2)
        lifecycleScope.launch {
            val found = runCatching {
                Places.searchSubmitted(q, me?.latitude, me?.longitude, "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}",
                    graceMs = 4_000L) { list ->
                    results = own + list.filter { r -> own.none { it.lat == r.lat && it.lon == r.lon } }
                    loading = false
                    invalidate()
                }
            }
            loading = false
            found.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                results = own
                CarToast.makeText(carContext, "Search failed - no connection?", CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }

    /** Home and Work when set, then starred places, then recent ones (each place once). */
    private fun savedPlaces(): List<Place> = (listOfNotNull(
        Prefs.navHome(carContext)?.let { it.copy(name = "Home") }, Prefs.navWork(carContext)?.let { it.copy(name = "Work") }
    ) + Prefs.navFavorites(carContext) + Prefs.recentDestinations(carContext))
        .distinctBy { "%.4f,%.4f".format(java.util.Locale.US, it.lat, it.lon) }.take(10)

    private fun go(d: Place) {
        val from = CarUi.currentLocation(carContext)
        if (from == null) {
            CarToast.makeText(carContext, "Waiting for your location...", CarToast.LENGTH_SHORT).show(); return
        }
        loading = true
        invalidate()
        lifecycleScope.launch {
            com.rfsentinel.app.nav.attempt { Navigator.start(carContext, d, from) }
                .onSuccess {
                    screenManager.pop()
                    map.onNavigationStarted()
                }
                .onFailure {
                    loading = false
                    CarToast.makeText(carContext, "No route: ${it.message ?: "no connection"}", CarToast.LENGTH_LONG).show()
                    invalidate()
                }
        }
    }
}
