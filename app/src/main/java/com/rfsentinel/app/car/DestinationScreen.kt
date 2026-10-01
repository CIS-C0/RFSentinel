package com.rfsentinel.app.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.nav.Navigator
import com.rfsentinel.app.nav.OsmRouting
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a destination: recent ones, or a search in OpenStreetMap (Nominatim),
 * sent only when you submit it - Nominatim's usage policy forbids
 * search-as-you-type, and it keeps requests to a minimum.
 */
class DestinationScreen(carContext: CarContext, private val map: LiveMapScreen) : Screen(carContext) {

    private var results: List<OsmRouting.Destination>? = null
    private var loading = false
    private var query = ""
    /** What's typed so far (searched only when submitted or tapped - no search-as-you-type). */
    private var typed = ""

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val shown = results ?: Prefs.recentDestinations(carContext)
        val me = CarUi.currentLocation(carContext)
        // Some cars' keyboards don't send "submit": offer the search as a row too.
        if (typed.isNotBlank() && typed != query) {
            list.addItem(Row.Builder()
                .setTitle("Search \"${typed.trim()}\"")
                .addText("in OpenStreetMap")
                .setOnClickListener { search(typed) }
                .build())
        }
        shown.forEach { d ->
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
            .setShowKeyboardByDefault(results == null && Prefs.recentDestinations(carContext).isEmpty())
            .setLoading(loading)
            .apply { if (!loading) setItemList(list.build()) }
            .build()
    }

    private fun search(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        loading = true
        invalidate()
        val me = CarUi.currentLocation(carContext)
        lifecycleScope.launch {
            val found = runCatching {
                withContext(Dispatchers.IO) {
                    OsmRouting.search(q, me?.latitude, me?.longitude, "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
                }
            }
            loading = false
            found.onSuccess { list ->
                // Closest first: in the car the nearby match is almost always the one meant.
                results = if (me == null) list else list.sortedBy { DeviceRegistry.metersBetween(me.latitude, me.longitude, it.lat, it.lon) }
            }.onFailure {
                results = emptyList()
                CarToast.makeText(carContext, "Search failed - no connection?", CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }

    private fun go(d: OsmRouting.Destination) {
        val from = CarUi.currentLocation(carContext)
        if (from == null) {
            CarToast.makeText(carContext, "Waiting for your location...", CarToast.LENGTH_SHORT).show(); return
        }
        loading = true
        invalidate()
        lifecycleScope.launch {
            runCatching { Navigator.start(carContext, d, from) }
                .onSuccess {
                    Prefs.addRecentDestination(carContext, d)
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
