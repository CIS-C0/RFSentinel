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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val host = androidx.fragment.app.FragmentContainerView(this).apply { id = com.rfsentinel.app.R.id.mapHost }
        setContentView(host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(com.rfsentinel.app.R.id.mapHost, MapFragment.create(intent.extras ?: Bundle()))
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
