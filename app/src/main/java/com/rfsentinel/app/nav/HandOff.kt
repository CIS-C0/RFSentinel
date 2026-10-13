package com.rfsentinel.app.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

/** Opens a destination in another navigation app (Google Maps, Waze, or any app that takes a map link). */
object HandOff {

    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    fun googleMapsUri(lat: Double, lon: Double): String = String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=d", lat, lon)
    fun wazeUri(lat: Double, lon: Double): String = String.format(Locale.US, "waze://?ll=%.6f,%.6f&navigate=yes", lat, lon)
    fun geoUri(lat: Double, lon: Double, name: String): String =
        String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon,
            java.net.URLEncoder.encode(name.ifBlank { "Destination" }, "UTF-8").replace("+", "%20"))

    /** Opens [place] in the app named by [target] ("google", "waze" or anything else for the phone's choice). */
    fun open(context: Context, place: Place, target: String) {
        val intent = when (target) {
            "google" -> Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsUri(place.lat, place.lon))).setPackage(GOOGLE_MAPS)
            "waze" -> Intent(Intent.ACTION_VIEW, Uri.parse(wazeUri(place.lat, place.lon))).setPackage(WAZE)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(place.lat, place.lon, place.name)))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, if (target == "google") "Google Maps isn't installed" else if (target == "waze") "Waze isn't installed" else "No map app found", Toast.LENGTH_LONG).show()
        }
    }
}
