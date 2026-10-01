package com.rfsentinel.app.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.rfsentinel.app.R
import com.rfsentinel.app.service.DeviceRegistry

/**
 * One place on its own (e.g. a camera tapped in the list): the car map centres
 * on it. Android Auto only lets navigation apps pan and zoom their map, so
 * "Navigate" hands the spot to your navigation app, where you can move freely.
 */
class PlaceFocusScreen(
    carContext: CarContext,
    private val title: String,
    private val detail: String,
    private val lat: Double,
    private val lon: Double,
    private val color: CarColor,
    private val label: String?
) : LiveScreen(carContext, periodMs = 5_000L) {

    override fun onGetTemplate(): Template {
        val me = CarUi.currentLocation(carContext)
        val marker = PlaceMarker.Builder().setColor(color)
        label?.let { marker.setLabel(it) }
        val place = Place.Builder(CarLocation.create(lat, lon)).setMarker(marker.build()).build()

        val row = Row.Builder().setTitle(title).setMetadata(Metadata.Builder().setPlace(place).build())
        if (me != null) {
            // Map rows carry their distance as a DistanceSpan (the car formats it).
            val text = SpannableString("  · $detail")
            val d = DeviceRegistry.metersBetween(me.latitude, me.longitude, lat, lon)
            text.setSpan(DistanceSpan.create(NearbyMapScreen.distanceOf(d)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            row.addText(text)
        } else {
            // No position yet, so no distance: Android Auto only accepts a map row
            // without a DistanceSpan when it's browsable - let it start navigation.
            row.addText(detail)
                .setBrowsable(true)
                .setOnClickListener { CarUi.navigateTo(carContext, lat, lon) }
        }

        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            // Your own position off, so the map frames this place rather than both of you.
            .setCurrentLocationEnabled(false)
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder()
                        .setTitle("Navigate")
                        .setIcon(CarUi.icon(carContext, R.drawable.ic_car_navigate))
                        .setOnClickListener { CarUi.navigateTo(carContext, lat, lon) }
                        .build()
                ).build()
            )
            .setItemList(ItemList.Builder().addItem(row.build()).build())
            .build()
    }
}
