package com.rfsentinel.app.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity

/**
 * The map full screen: a saved trace (Traces), a place to look at (a cell tower, a Waze report),
 * or the live map from a notification or the floating map. The map itself is [MapFragment],
 * the same one the main screen's Map view shows.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        const val HEADING_MIN_SPEED_MS = MapFragment.HEADING_MIN_SPEED_MS
        const val EXTRA_TRIP_ID = MapFragment.EXTRA_TRIP_ID
        /** Open the map centred here (e.g. a cell tower), without following your position. */
        const val EXTRA_CENTER_LAT = MapFragment.EXTRA_CENTER_LAT
        const val EXTRA_CENTER_LON = MapFragment.EXTRA_CENTER_LON
        /** Turn the cell tower layer on. */
        const val EXTRA_SHOW_TOWERS = MapFragment.EXTRA_SHOW_TOWERS
        /** Open the map on an aircraft (its ICAO address): where it is now, else its last known position. */
        const val EXTRA_AIRCRAFT = MapFragment.EXTRA_AIRCRAFT
        /** Open the map on a Waze report (its id), with its details while it's current. */
        const val EXTRA_WAZE = MapFragment.EXTRA_WAZE
        /** Open the map on a place to plan a route to: name, address, latitude, longitude. */
        const val EXTRA_NAV_NAME = MapFragment.EXTRA_NAV_NAME
        const val EXTRA_NAV_ADDRESS = MapFragment.EXTRA_NAV_ADDRESS
        const val EXTRA_NAV_LAT = MapFragment.EXTRA_NAV_LAT
        const val EXTRA_NAV_LON = MapFragment.EXTRA_NAV_LON

        /**
         * The map on what an internet alert is about - [key] "air:<ICAO address>" or "waze:<report id>" -
         * last seen at [lat] / [lon] (null when not saved); null for any other key.
         */
        fun onlineIntent(context: android.content.Context, key: String, lat: Double?, lon: Double?): android.content.Intent? {
            val extra = when (key.substringBefore(':')) { "air" -> EXTRA_AIRCRAFT; "waze" -> EXTRA_WAZE; else -> return null }
            return android.content.Intent(context, MapActivity::class.java).apply {
                putExtra(extra, key.substringAfter(':'))
                if (lat != null && lon != null) { putExtra(EXTRA_CENTER_LAT, lat); putExtra(EXTRA_CENTER_LON, lon) }
            }
        }

        /** Whether what [key] is about is still in the live feed (an aircraft in range, a current Waze report). */
        fun onlineIsLive(key: String): Boolean = when (key.substringBefore(':')) {
            "air" -> com.rfsentinel.app.online.PoliceAircraft.current(key.substringAfter(':')) != null
            "waze" -> com.rfsentinel.app.online.WazePolice.current(key.substringAfter(':')) != null
            else -> false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val host = androidx.fragment.app.FragmentContainerView(this).apply { id = com.rfsentinel.app.R.id.mapHost }
        setContentView(host)
        if (savedInstanceState == null) {
            // The app closed while the map was starting last time: say so (and try with OpenGL, or ask before trying again).
            MapSafetyUi.tell(this, MapSafety.recover(this), onTryAgain = { addMap() }, onNotNow = { finish() })
        }
    }

    private fun addMap() {
        if (isFinishing || isDestroyed) return
        supportFragmentManager.beginTransaction()
            .replace(com.rfsentinel.app.R.id.mapHost, MapFragment.create(intent.extras ?: Bundle()))
            .commitAllowingStateLoss()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
