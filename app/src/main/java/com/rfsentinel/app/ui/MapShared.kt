package com.rfsentinel.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rfsentinel.app.service.DeviceRegistry
import java.util.Locale

/*
 * What the map ([MapFragment] / [RfMap]) and its saved-trace view share: which devices go on
 * the map and how they're described, the dialogs that open when something is tapped, and the
 * shapes drawn around things.
 */

/** One device to draw, from the live registry or a saved trip. */
internal data class MapPin(
    val mac: String, val lat: Double, val lon: Double, val label: String, val flagged: Boolean,
    val color: Int, val details: String,
    /** Pinpointed (see [com.rfsentinel.app.detect.Locator]): the circle it is very likely in. */
    val radiusM: Double? = null,
    val whereNote: String = "Position = where your phone was when this device's signal was strongest (approximate)."
)

internal object MapPins {

    /** The live devices [filter] shows, with a position (pinpointed, broadcast or where heard strongest). */
    fun live(context: android.content.Context, devices: List<DeviceRegistry.Snapshot>, filter: DeviceFilter): List<MapPin> =
        devices.mapNotNull { s ->
            val pos = s.place ?: return@mapNotNull null
            val flagged = DeviceColors.isFlagged(s)
            val located = s.located?.takeIf { flagged && s.broadcast == null }
            if (!filter.matches(s) || filter == DeviceFilter.RADIO) return@mapNotNull null
            val best = s.best
            MapPin(
                s.mac, pos.lat, pos.lon,
                com.rfsentinel.app.data.DeviceNames.of(s.mac) ?: best?.label ?: s.name ?: s.deviceType,
                flagged,
                DeviceColors.forDevice(context, s),
                buildString {
                    append(s.mac)
                    s.vendor?.let { append("\n").append(it) }
                    if (best != null) append("\n${best.category.title} · ${best.tier.label} ${best.confidence}%\n${best.evidence}")
                    if (located != null) append("\nPinpointed ${com.rfsentinel.app.detect.Locator.describe(located)}")
                    append("\nStrongest signal: ${s.bestRssi} dBm")
                },
                radiusM = located?.radiusM,
                whereNote = if (s.broadcast != null)
                    "Position = the vehicle's own GPS, broadcast over V2X (exact, not an estimate)."
                else if (located != null)
                    "Position = pinpointed from the signal at the many spots you passed: very likely inside the circle " +
                        "(${located.radiusM.toInt()} m). The more sides you pass it from, the smaller the circle."
                else if (flagged)
                    "Position = where your phone was when this device's signal was strongest (approximate). " +
                        "Drive or walk past it from another side - around the block - to pinpoint it."
                else "Position = where your phone was when this device's signal was strongest (approximate)."
            )
        }
}

internal object MapDialogs {

    /** One device, or a list to pick from when several were heard at the same spot. */
    fun showPins(a: AppCompatActivity, list: List<MapPin>) {
        if (list.isEmpty()) return
        if (list.size == 1) { showPin(a, list[0]); return }
        val sorted = list.sortedByDescending { it.flagged }
        AlertDialog.Builder(a)
            .setTitle("${list.size} devices here")
            .setItems(sorted.map { (if (it.flagged) "⚠ " else "") + it.label }.toTypedArray()) { _, i -> showPin(a, sorted[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    fun showPin(a: AppCompatActivity, p: MapPin) {
        AlertDialog.Builder(a)
            .setTitle(p.label)
            .setMessage(p.details + "\n\n" + p.whereNote)
            .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(a, p.mac) }
            .setNegativeButton("Close", null)
            .show()
    }

    fun showWaze(a: AppCompatActivity, w: com.rfsentinel.app.online.WazePolice.Shown) {
        AlertDialog.Builder(a)
            .setTitle(w.hit.label)
            .setMessage("${w.hit.tier.label} ${w.hit.confidence}%\n${com.rfsentinel.app.online.WazePolice.relativeText(w.where, w.trend)}\n${w.hit.evidence}")
            .setNegativeButton("Close", null)
            .show()
    }

    /** [me] = your position (lat, lon) for the distance, when known. */
    fun showAircraft(a: AppCompatActivity, p: com.rfsentinel.app.online.PoliceAircraft.Plane, hit: com.rfsentinel.app.detect.Hit?,
                     me: Pair<Double, Double>?) {
        val text = buildString {
            hit?.let { append(it.evidence).append("\n\n") }
            p.registration?.let { append("Registration: $it\n") }
            p.callsign?.let { append("Callsign: $it\n") }
            p.type?.let { append("Type: $it\n") }
            p.owner?.let { append("Owner / operator: $it\n") }
            p.altitudeFt?.let { append(if (it <= 0) "On the ground\n" else "Altitude: $it ft\n") }
            p.speedKt?.let { append(String.format(Locale.US, "Speed: %.0f kt (%.0f km/h)\n", it, it * 1.852)) }
            p.trackDeg?.let { append(String.format(Locale.US, "Heading: %.0f°\n", it)) }
            me?.let { append(String.format(Locale.US, "Distance: %.1f km\n",
                DeviceRegistry.metersBetween(it.first, it.second, p.lat, p.lon) / 1000)) }
            append("ICAO address: ${p.hex.uppercase()}\nSource: community ADS-B (adsb.fi / adsb.lol)")
        }
        AlertDialog.Builder(a)
            .setTitle(hit?.label ?: (p.registration ?: p.callsign ?: "Aircraft"))
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    fun droneDetails(s: DeviceRegistry.Snapshot, r: com.rfsentinel.app.detect.RemoteId.Info) = buildString {
        r.uasId?.let { append("ID: $it\n") }
        r.uaType?.let { append("Type: $it\n") }
        r.status?.let { append("Status: $it\n") }
        r.heightM?.let { append(String.format(Locale.US, "Height: %.0f m\n", it)) }
        r.altitudeGeoM?.let { append(String.format(Locale.US, "Altitude (GPS): %.0f m\n", it)) }
        r.speedMs?.let { append(String.format(Locale.US, "Speed: %.0f km/h\n", it * 3.6)) }
        r.directionDeg?.let { append("Heading: $it°\n") }
        r.operatorId?.let { append("Operator ID: $it\n") }
        r.selfIdDescription?.let { append("Description: $it\n") }
        append("Radio: ${s.mac} · ${s.rssi} dBm")
    }

    fun showDrone(a: AppCompatActivity, mac: String, text: String) {
        AlertDialog.Builder(a)
            .setTitle("Drone (Remote ID)")
            .setMessage(text + "\n\nPositions are broadcast by the drone itself (ASTM F3411 Remote ID).")
            .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(a, mac) }
            .setNegativeButton("Close", null)
            .show()
    }

    fun showCctv(a: AppCompatActivity, c: com.rfsentinel.app.alpr.CctvCamera) {
        val text = buildString {
            append(when (c.zone) {
                "public" -> "Watches a public space (street, square, station...).\n"
                "outdoor" -> "A private camera that watches outside (an entrance, a car park...).\n"
                "indoor" -> "Inside a building or shop.\n"
                else -> "Whether it watches public space isn't mapped.\n"
            })
            c.area?.let { append("Watches: $it\n") }
            c.operator?.let { append("Operator: $it\n") }
            c.mount?.let { append("Mounted on: $it\n") }
            c.height?.let { append("Height: ${"%.0f".format(it)} m\n") }
            c.direction?.let { append("Faces: $it°" + (c.angle?.let { ang -> ", $ang° wide" } ?: "") + "\n") }
            append("OSM: ${c.osmId}\n\n")
            append("Mapped by OpenStreetMap volunteers. Most of these cameras are wired and give off no Bluetooth or WiFi " +
                "signal, so the scan can't detect them - this layer shows the ones that have been mapped. " +
                "The shaded area is an estimate of what it sees.\n\nMap data © OpenStreetMap contributors (ODbL).")
        }
        AlertDialog.Builder(a)
            .setTitle(com.rfsentinel.app.alpr.Cctv.title(c))
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Open in OpenStreetMap") { _, _ ->
                runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/${c.osmId}"))) }
            }
            .show()
    }

    /** [onChanged] after its alerts were turned off or on again (redraw it faded / normal). */
    fun showKnownCamera(a: AppCompatActivity, cam: com.rfsentinel.app.alpr.KnownCamera, onChanged: () -> Unit) {
        val ignored = com.rfsentinel.app.alpr.IgnoredCameras.contains(a, cam.osmId)
        val userDir = if (cam.direction == null) com.rfsentinel.app.alpr.CameraDirections.userSet(a, cam.osmId) else null
        val text = buildString {
            if (ignored) append("Alerts for this camera are off.\n\n")
            append(when (cam.type) {
                com.rfsentinel.app.alpr.KnownCamera.Kind.ALPR -> "A license-plate reader mapped in OpenStreetMap.\n\n"
                com.rfsentinel.app.alpr.KnownCamera.Kind.SPEED -> "A speed camera mapped in OpenStreetMap.\n\n"
                com.rfsentinel.app.alpr.KnownCamera.Kind.RED_LIGHT -> "A red-light camera mapped in OpenStreetMap.\n\n"
            })
            cam.brand?.let { append("Maker: $it\n") }
            cam.operator?.let { append("Operator: $it\n") }
            cam.direction?.let { append("Faces: $it°\n") }
            userDir?.let { append("Faces: ${compass(it).lowercase()} (set by you)\n") }
            append("OSM: ${cam.osmId}\n\n")
            append("These cameras send their data over cellular or wire and have no Bluetooth/WiFi signature, so they can't be detected by radio - this map is the only warning. Mobile speed cameras move and are rarely mapped.\n\nMap data © OpenStreetMap contributors (ODbL).")
        }
        AlertDialog.Builder(a)
            .setTitle(cam.label)
            .setMessage(text)
            // Kept until turned back on here (or cleared in Settings).
            .setPositiveButton(if (ignored) "Alert again" else "Ignore alerts") { _, _ ->
                com.rfsentinel.app.alpr.IgnoredCameras.set(a, cam.osmId, !ignored)
                Toast.makeText(a, if (ignored) "You'll be warned about this camera again"
                    else "No more alerts for this camera (tap it again to undo)", Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .apply {
                // No direction in OpenStreetMap: let the user say which way it faces (this phone only).
                if (cam.direction == null) setNegativeButton("Set direction") { _, _ -> pickDirection(a, cam, userDir, onChanged) }
                else setNegativeButton("Close", null)
            }
            .setNeutralButton("Open in OpenStreetMap") { _, _ ->
                runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/${cam.osmId}"))) }
            }
            .show()
    }

    private val COMPASS = listOf("North", "North-east", "East", "South-east", "South", "South-west", "West", "North-west")

    private fun compass(deg: Int) = COMPASS[((deg + 22) % 360) / 45]

    /** Which way the camera faces, from 8 compass points; saved on this phone, drawn as its cone. */
    private fun pickDirection(a: AppCompatActivity, cam: com.rfsentinel.app.alpr.KnownCamera, current: Int?, onChanged: () -> Unit) {
        val items = COMPASS.map { "Faces ${it.lowercase()}" } + "Not set"
        val checked = current?.let { ((it + 22) % 360) / 45 } ?: COMPASS.size
        AlertDialog.Builder(a)
            .setTitle("Which way does it face?")
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, i ->
                com.rfsentinel.app.alpr.CameraDirections.set(a, cam.osmId, if (i < COMPASS.size) i * 45 else null)
                d.dismiss()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showTower(a: AppCompatActivity, t: com.rfsentinel.app.service.CellTowerStore.Tower) {
        val net = listOfNotNull(t.operator, "${t.mcc ?: "?"}-${t.mnc ?: "?"}").joinToString(" · ")
        val dateFmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        AlertDialog.Builder(a)
            .setTitle(t.ratLabel)
            .setMessage("$net\nArea ${t.area ?: "?"} · Cell ${t.cellId}" +
                (t.bestDbm?.let { "\nStrongest signal $it dBm" } ?: "") +
                "\nSeen ${t.timesSeen}×, last ${dateFmt.format(java.util.Date(t.lastSeen))}" +
                "\n\nPlaced where your phone heard it strongest: an estimate, not the tower's exact position.")
            .setPositiveButton("All towers") { _, _ -> a.startActivity(Intent(a, CellTowersActivity::class.java)) }
            .setNegativeButton("Close", null)
            .show()
    }
}

/** Shapes on the map, as (lat, lon) rings (closed: the last point repeats the first). */
internal object MapGeo {
    private const val EARTH_M = 6_371_000.0

    /** The point [distM] metres from ([lat], [lon]) towards [bearingDeg] (clockwise from north). */
    fun offset(lat: Double, lon: Double, distM: Double, bearingDeg: Double): Pair<Double, Double> {
        val d = distM / EARTH_M
        val b = Math.toRadians(bearingDeg)
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        val la2 = kotlin.math.asin(kotlin.math.sin(la) * kotlin.math.cos(d) + kotlin.math.cos(la) * kotlin.math.sin(d) * kotlin.math.cos(b))
        val lo2 = lo + kotlin.math.atan2(kotlin.math.sin(b) * kotlin.math.sin(d) * kotlin.math.cos(la),
            kotlin.math.cos(d) - kotlin.math.sin(la) * kotlin.math.sin(la2))
        return Math.toDegrees(la2) to Math.toDegrees(lo2)
    }

    /** A circle of [radiusM] around a point. */
    fun circle(lat: Double, lon: Double, radiusM: Double, n: Int = 48): List<Pair<Double, Double>> =
        (0..n).map { offset(lat, lon, radiusM, 360.0 * (it % n) / n) }

    /** A wedge from a point: [sweepDeg] wide around [dirDeg], [rangeM] long (a CCTV camera's view). */
    fun wedge(lat: Double, lon: Double, dirDeg: Double, sweepDeg: Double, rangeM: Double): List<Pair<Double, Double>> {
        if (sweepDeg >= 359.0) return circle(lat, lon, rangeM)
        val steps = (sweepDeg / 6).toInt().coerceAtLeast(4)
        val arc = (0..steps).map { offset(lat, lon, rangeM, dirDeg - sweepDeg / 2 + sweepDeg * it / steps) }
        return listOf(lat to lon) + arc + listOf(lat to lon)
    }
}
