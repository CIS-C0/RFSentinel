package com.rfsentinel.app.ui

import android.graphics.RectF
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.alpr.Cctv
import com.rfsentinel.app.alpr.CctvStore
import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.online.PoliceAircraft
import com.rfsentinel.app.online.WazePolice
import com.rfsentinel.app.service.CellTowerStore
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.service.TripRecorder
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * RF Sentinel's map on its [MapEngine] (OpenFreeMap or Mapbox): the light or night style, and every
 * layer drawn on it - devices and their pinpoint circles, plate / speed / red-light cameras, CCTV
 * with their view, cell towers, drones, police aircraft, Waze reports and the trace - with the same
 * details on a tap as before. The screen around it ([MapFragment]) owns the buttons, the camera
 * and your position; this owns what is drawn.
 */
internal class RfMap(private val activity: AppCompatActivity, val map: MapEngine) {

    companion object {
        const val TRACE_COLOR = "#E0622D"
        const val PAST_TRACE_COLOR = "#1F5FBF"
        private const val DRONE_COLOR = "#1F5FBF"
        /** Vector zoom levels sit ~1 below the old raster map's for the same scale (512-pixel tiles). */
        const val CAMERAS_MIN_ZOOM = 8.0
        private const val CCTV_MIN_ZOOM = 11.0
        private const val CCTV_CONE_ZOOM = 14.0
        /** Plate, speed and red-light cameras' facing cones show from here (a street's scale). */
        private const val CAMERA_CONE_ZOOM = 13.0
        /** Drawn view of a road camera: about how far a plate reader reads, and its usual width. */
        private const val CAMERA_CONE_M = 60.0
        private const val CAMERA_CONE_DEG = 40.0

        private const val PRECISION = "rf-precision"; private const val CONES = "rf-cctv-cones"
        private const val CAM_CONES = "rf-camera-cones"
        private const val TRACE = "rf-trace"; private const val DRONE_LINES = "rf-drone-lines"
        private const val TOWERS = "rf-towers"; private const val CCTV = "rf-cctv"; private const val CAMERAS = "rf-cameras"
        private const val DEVICES = "rf-devices"; private const val DOTS = "rf-device-dots"; private const val LABELS = "rf-device-labels"
        private const val WAZE = "rf-waze"; private const val DRONE_OPS = "rf-drone-ops"; private const val DRONES = "rf-drones"
        private const val AIRCRAFT = "rf-aircraft"
        private const val NAV_ROUTES = "rf-nav-routes"; private const val NAV_PLACES = "rf-nav-places"
        private const val NAV_ROUTE_COLOR = "#1A73E8"; private const val NAV_ALT_COLOR = "#7C8DA6"

        /**
         * North, east, south, west of a box grown by [fraction] on every side, clamped to valid
         * latitudes and longitudes (a view of the whole world would otherwise pass the poles).
         */
        internal fun grownBox(north: Double, east: Double, south: Double, west: Double, fraction: Double): List<Double> {
            val dLat = (north - south) * fraction; val dLon = (east - west) * fraction
            return listOf((north + dLat).coerceIn(-85.0, 85.0), (east + dLon).coerceIn(-180.0, 180.0),
                (south - dLat).coerceIn(-85.0, 85.0), (west - dLon).coerceIn(-180.0, 180.0))
        }

        private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

        /** Map style JSON: an object (null values left out) and an array / expression. */
        internal fun o(vararg kv: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in kv) if (v != null) put(k, v) }
        internal fun a(vararg v: Any) = JSONArray().apply { v.forEach { put(it) } }
    }

    /** A saved trace is shown (no live layers; its own pins and route). */
    var tripMode = false
    var filter = DeviceFilter.ALL
    var isDark = false; private set
    /** What the CCTV layer is doing, for the status line (null while the layer is off). */
    var cctvLine: String? = null; private set
    /** Cell towers with a position (the Cells chip's count). */
    var towerCount = 0; private set
    /** After a camera download or an ignore changed what's on the map. */
    var onStaticChanged: (() -> Unit)? = null

    // What's on the map now, to find what a tap hit.
    private var pins: Map<String, MapPin> = emptyMap()
    private var wazeShown: Map<String, WazePolice.Shown> = emptyMap()
    private var planes: Map<String, PoliceAircraft.Seen> = emptyMap()
    private var drones: Map<String, DeviceRegistry.Snapshot> = emptyMap()
    private var cameras: Map<String, KnownCamera> = emptyMap()
    private var cctvs: Map<String, com.rfsentinel.app.alpr.CctvCamera> = emptyMap()
    private var towers: Map<String, CellTowerStore.Tower> = emptyMap()
    /** The area the camera, CCTV and tower layers were drawn for (redrawn when the view leaves it). */
    private var staticBox: MapEngine.Box? = null
    private var staticAt = 0L
    private var lastCameraFetch = 0L
    private var lastCctvFetch = 0L

    /** Loads the light or night style; [onLoaded] once its layers are in. */
    fun loadStyle(dark: Boolean, onLoaded: () -> Unit) {
        isDark = dark
        map.setStyle(dark) { fresh ->
            if (activity.isFinishing || activity.isDestroyed) return@setStyle
            if (fresh || !map.hasSource(DEVICES)) safe("style") {
                addImages()
                addLayers(dark)
            } else safe("style") {
                // The same map turned night (Mapbox): the labels' colours follow.
                val (ink, halo) = labelColors(dark)
                map.setPaint(LABELS, "text-color", ink); map.setPaint(LABELS, "text-halo-color", halo)
            }
            staticBox = null
            safe("loaded") { onLoaded() }
        }
    }

    /**
     * Runs [block] and keeps whatever it throws here: map callbacks run inside the map engine,
     * where an exception aborts the whole app instead of failing one drawing.
     */
    inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { android.util.Log.w("RfMap", "$what failed", e) }
    }

    /** The same icons as before. */
    private fun addImages() {
        val dp = activity.resources.displayMetrics.density
        KnownCamera.Kind.entries.forEach { map.addImage("rf-cam-${it.name}", MapIcons.cameraIcon(dp, it)) }
        map.addImage("rf-cctv-public", MapIcons.cctvIcon(dp, false))
        map.addImage("rf-cctv-private", MapIcons.cctvIcon(dp, true))
        map.addImage("rf-plane-police", MapIcons.planeIcon(dp, true))
        map.addImage("rf-plane", MapIcons.planeIcon(dp, false))
        map.addImage("rf-plane-clear", MapIcons.planeIcon(dp, false, clear = true))
        map.addImage("rf-tower", MapIcons.towerIcon(dp))
        map.addImage("rf-drone", droneArrow(dp))
    }

    /** Every layer, as map style JSON (the same on both engines). */
    private fun addLayers(dark: Boolean) {
        listOf(NAV_ROUTES, NAV_PLACES, PRECISION, CONES, CAM_CONES, TRACE, DRONE_LINES, TOWERS, CCTV, CAMERAS, DEVICES, WAZE, DRONE_OPS, DRONES, AIRCRAFT)
            .forEach { map.addSource(it) }
        fun get(k: String) = a("get", k)
        val color = a("to-color", get("color"))
        val flagged = a("==", get("flagged"), true)
        val font = JSONArray(map.labelFont)
        val (ink, halo) = labelColors(dark)
        val overlap = arrayOf<Pair<String, Any?>>("icon-allow-overlap" to true, "icon-ignore-placement" to true)
        // Mapbox's night look is lit like a real night: a layer that gives off no light of its own goes almost
        // black on it (the route showed as dark navy, the Waze dots as black). Every overlay is given full
        // brightness there, whatever kind of layer it is; only Mapbox knows these properties, so the other
        // engine never sees them.
        val lit = map.provider == MapEngine.Provider.MAPBOX
        fun glow(type: String): List<String> = when (type) {
            "line" -> listOf("line-emissive-strength")
            "circle" -> listOf("circle-emissive-strength")
            "fill" -> listOf("fill-emissive-strength")
            "symbol" -> listOf("icon-emissive-strength", "text-emissive-strength")
            else -> emptyList()
        }
        fun layer(id: String, type: String, source: String = id, minzoom: Double? = null, filter: JSONArray? = null,
                  layout: JSONObject? = null, paint: JSONObject? = null): JSONObject {
            val lightedPaint = if (lit && glow(type).isNotEmpty()) (paint ?: JSONObject()).also { p -> glow(type).forEach { p.put(it, 1.0) } } else paint
            return o("id" to id, "type" to type, "source" to source, "minzoom" to minzoom, "filter" to filter, "layout" to layout, "paint" to lightedPaint)
        }
        val routeLine = o("line-cap" to "round", "line-join" to "round")
        listOf(
            // A route to follow: the other routes offered under it, in grey, then the chosen one with a white edge.
            layer("$NAV_ROUTES-alt", "line", NAV_ROUTES, filter = a("==", get("sel"), false), layout = routeLine,
                paint = o("line-color" to NAV_ALT_COLOR, "line-width" to 6.0, "line-opacity" to 0.85)),
            layer("$NAV_ROUTES-edge", "line", NAV_ROUTES, filter = a("==", get("sel"), true), layout = routeLine,
                paint = o("line-color" to "#FFFFFF", "line-width" to 11.0)),
            layer("$NAV_ROUTES-line", "line", NAV_ROUTES, filter = a("==", get("sel"), true), layout = routeLine,
                paint = o("line-color" to NAV_ROUTE_COLOR, "line-width" to 7.0)),
            // Pinpointed devices: the circle each is very likely in, in its colour.
            layer("$PRECISION-fill", "fill", PRECISION, paint = o("fill-color" to color, "fill-opacity" to 0.18)),
            layer("$PRECISION-line", "line", PRECISION, paint = o("line-color" to color, "line-width" to 1.5, "line-opacity" to 0.7)),
            // What each CCTV camera watches (zoomed in).
            layer("$CONES-fill", "fill", CONES, minzoom = CCTV_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            // Which way each plate, speed and red-light camera faces (when mapped), in its icon's colour.
            layer("$CAM_CONES-fill", "fill", CAM_CONES, minzoom = CAMERA_CONE_ZOOM, paint = o("fill-color" to color, "fill-opacity" to get("opacity"))),
            layer("$CAM_CONES-line", "line", CAM_CONES, minzoom = CAMERA_CONE_ZOOM,
                paint = o("line-color" to color, "line-width" to 1.2, "line-opacity" to a("*", get("opacity"), 2.5))),
            layer(TRACE, "line", layout = o("line-cap" to "round", "line-join" to "round"),
                paint = o("line-color" to color, "line-width" to 5.0)),
            layer(DRONE_LINES, "line", paint = o("line-color" to DRONE_COLOR, "line-width" to 2.5, "line-dasharray" to a(4.0, 2.5))),
            layer(TOWERS, "symbol", layout = o("icon-image" to "rf-tower", *overlap)),
            layer(CCTV, "symbol", minzoom = CCTV_MIN_ZOOM, layout = o("icon-image" to get("icon"), *overlap)),
            // Plate, speed and red-light cameras; the ones whose alerts you turned off, faded.
            layer(CAMERAS, "symbol", minzoom = CAMERAS_MIN_ZOOM, layout = o("icon-image" to get("icon"), *overlap),
                paint = o("icon-opacity" to get("opacity"))),
            // Devices: flagged ones bigger and on top, in the same colours as the list.
            layer(DOTS, "circle", DEVICES, layout = o("circle-sort-key" to a("case", flagged, 1.0, 0.0)),
                paint = o("circle-color" to color, "circle-radius" to a("case", flagged, 8.0, 5.0),
                    "circle-stroke-color" to "#FFFFFF", "circle-stroke-width" to 1.5)),
            layer(LABELS, "symbol", DEVICES, filter = flagged,
                layout = o("text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                    "text-offset" to a(0.0, 1.1), "text-optional" to true),
                paint = o("text-color" to ink, "text-halo-color" to halo, "text-halo-width" to 1.5)),
            // Waze reports: blue police, red accident, orange hazard, brown closure, purple jam.
            layer(WAZE, "circle", paint = o("circle-color" to color, "circle-radius" to 9.0,
                "circle-stroke-color" to "#FFFFFF", "circle-stroke-width" to 2.0)),
            // A drone's operator / take-off point, and the drone pointing its way.
            layer(DRONE_OPS, "circle", paint = o("circle-color" to "#F5E100", "circle-radius" to 7.0,
                "circle-stroke-color" to DRONE_COLOR, "circle-stroke-width" to 2.0)),
            layer(DRONES, "symbol", layout = o("icon-image" to "rf-drone", "icon-rotate" to get("dir"),
                "icon-rotation-alignment" to "map", *overlap)),
            // Aircraft: police / government ones bold purple with their agency and altitude, others small and grey
            // (under the Aircraft filter: every one large and labelled).
            // Where the route ends (red) and the stops on the way (white with a blue edge), above everything else.
            layer("$NAV_PLACES-pin", "circle", NAV_PLACES, paint = o(
                "circle-color" to a("case", a("==", get("end"), true), "#D93025", "#FFFFFF"), "circle-radius" to 9.0,
                "circle-stroke-color" to a("case", a("==", get("end"), true), "#FFFFFF", NAV_ROUTE_COLOR), "circle-stroke-width" to 3.0)),
            layer(AIRCRAFT, "symbol", layout = o("icon-image" to get("icon"), "icon-rotate" to get("track"),
                "icon-rotation-alignment" to "map",
                "icon-size" to a("interpolate", a("linear"), a("zoom"), 9, a("case", a("==", get("clear"), true), 1.0, 0.5), 12, 1.0, 16, 2.0),
                *overlap, "symbol-sort-key" to a("case", flagged, 1.0, 0.0),
                "text-field" to get("label"), "text-font" to font, "text-size" to 12.0, "text-anchor" to "top",
                "text-offset" to a(0.0, 1.6), "text-optional" to true),
                paint = o("text-color" to "#FFFFFF", "text-halo-color" to a("case", flagged, hex(MapIcons.POLICE_AIRCRAFT_COLOR),
                    hex(MapIcons.CLEAR_AIRCRAFT_COLOR)), "text-halo-width" to 3.0))
        ).forEach { map.addLayer(it) }
    }

    /** Device labels: text and halo colours on the light or night map. */
    private fun labelColors(dark: Boolean) = if (dark) "#FFFFFF" to "#1B1F27" else "#1B1F27" to "#FFFFFF"

    private fun ready() = map.styleLoaded

    private fun set(source: String, features: List<JSONObject>) {
        map.setData(source, GeoJson.collection(features))
    }

    /** Devices (and their pinpoint circles); returns what was drawn. */
    fun drawPins(shown: List<MapPin>) {
        if (!ready()) return
        pins = shown.associateBy { it.mac }
        set(DEVICES, shown.map { p ->
            GeoJson.feature(GeoJson.point(p.lat, p.lon), "mac" to p.mac, "label" to p.label.take(32),
                "color" to hex(p.color), "flagged" to p.flagged)
        })
        set(PRECISION, shown.filter { it.radiusM != null }.map { p ->
            GeoJson.feature(GeoJson.polygon(MapGeo.circle(p.lat, p.lon, p.radiusM!!)), "color" to hex(p.color))
        })
    }

    /**
     * Routes to follow: [lines] as (lat, lon) lists, the one at [selected] on top in blue and the
     * others grey. Empty clears them.
     */
    fun drawRoutes(lines: List<List<Pair<Double, Double>>>, selected: Int) {
        if (!ready()) return
        set(NAV_ROUTES, lines.mapIndexedNotNull { i, pts ->
            if (pts.size < 2) null else GeoJson.feature(GeoJson.line(pts), "sel" to (i == selected), "idx" to i)
        })
    }

    /** The destination and the stops on the way ([stops] white, [end] red). Null clears them. */
    fun drawNavPlaces(stops: List<Pair<Double, Double>>, end: Pair<Double, Double>?) {
        if (!ready()) return
        set(NAV_PLACES, stops.map { GeoJson.feature(GeoJson.point(it.first, it.second), "end" to false) } +
            listOfNotNull(end?.let { GeoJson.feature(GeoJson.point(it.first, it.second), "end" to true) }))
    }

    /** A route: the trace being recorded (orange) or a saved one (blue). */
    fun drawTrace(points: List<Pair<Double, Double>>, color: String) {
        if (!ready()) return
        set(TRACE, if (points.size < 2) emptyList() else listOf(GeoJson.feature(GeoJson.line(points), "color" to color)))
    }

    /**
     * Fast-changing live layers, every few seconds: devices, Waze, drones, aircraft, the trace.
     * Returns the devices drawn.
     */
    fun drawLive(devices: List<DeviceRegistry.Snapshot>): List<MapPin> {
        if (!ready()) return emptyList()
        val shown = MapPins.live(activity, devices, filter)
        drawPins(shown)

        val waze = if (filter == DeviceFilter.ALL || filter == DeviceFilter.WAZE) WazePolice.latest else emptyList()
        wazeShown = waze.associateBy { it.report.id }
        set(WAZE, waze.map { w ->
            GeoJson.feature(GeoJson.point(w.report.lat, w.report.lon), "id" to w.report.id, "color" to hex(w.report.type.color))
        })

        val flying = devices.filter { it.remoteId?.hasPosition == true }
        drones = flying.associateBy { it.mac }
        set(DRONES, flying.map { s ->
            val r = s.remoteId!!
            GeoJson.feature(GeoJson.point(r.latitude!!, r.longitude!!), "mac" to s.mac, "dir" to (r.directionDeg ?: 0).toDouble())
        })
        val withOperator = flying.filter { it.remoteId!!.hasOperatorPosition }
        set(DRONE_OPS, withOperator.map { s ->
            val r = s.remoteId!!
            GeoJson.feature(GeoJson.point(r.operatorLatitude!!, r.operatorLongitude!!), "mac" to s.mac)
        })
        set(DRONE_LINES, withOperator.map { s ->
            val r = s.remoteId!!
            GeoJson.feature(GeoJson.line(listOf(r.operatorLatitude!! to r.operatorLongitude!!, r.latitude!! to r.longitude!!)))
        })

        val air = PoliceAircraft.all()
        planes = air.associateBy { it.plane.hex }
        val clear = filter == DeviceFilter.AIRCRAFT
        set(AIRCRAFT, air.map { (p, hit) ->
            GeoJson.feature(GeoJson.point(p.lat, p.lon),
                "hex" to p.hex,
                "icon" to if (hit != null) "rf-plane-police" else if (clear) "rf-plane-clear" else "rf-plane",
                "track" to (p.trackDeg ?: 0.0),
                "flagged" to (hit != null),
                "clear" to clear,
                "label" to if (hit == null) {
                    if (clear) listOfNotNull(AircraftActivity.name(p), AircraftActivity.altitude(p)).joinToString(" · ") else ""
                } else listOfNotNull(
                    hit.label.substringAfter(": ", hit.label).take(28), p.altitudeFt?.takeIf { it > 0 }?.let { "$it ft" }
                ).joinToString(" · "))
        })

        val trace = if (TripRecorder.isRecording) TripRecorder.livePoints() else emptyList()
        drawTrace(trace.map { it.lat to it.lon }, TRACE_COLOR)
        return shown
    }

    /**
     * Cameras, CCTV and cell towers: many, and they don't move - drawn for the area in view (and
     * around it), again once the view leaves that area or every 15 s for new downloads.
     */
    fun maybeRedrawStatic(force: Boolean = false) {
        if (!ready()) return
        if (tripMode) { clearStatic(); return }
        towerCount = CellTowerStore.all(activity).count { it.bestLat != null }
        if (map.camera.zoom < CAMERAS_MIN_ZOOM) {
            if (force) clearStatic()
            updateCctvLine(null)
            return
        }
        val view = map.visibleBox() ?: return
        val now = System.currentTimeMillis()
        val box = staticBox
        if (!force && box != null && box.contains(view) && now - staticAt < 15_000) return
        val (n, e, so, w) = grownBox(view.north, view.east, view.south, view.west, 0.5)
        val big = MapEngine.Box(n, e, so, w)
        staticBox = big; staticAt = now
        fun inside(lat: Double, lon: Double) = big.contains(lat, lon)

        val cams = if (Prefs.showKnownAlpr(activity)) AlprStore.cameras.filter { inside(it.lat, it.lon) }.take(1500) else emptyList()
        cameras = cams.associateBy { it.osmId }
        set(CAMERAS, cams.map { c ->
            GeoJson.feature(GeoJson.point(c.lat, c.lon), "id" to c.osmId, "icon" to "rf-cam-${c.type.name}",
                "opacity" to if (com.rfsentinel.app.alpr.IgnoredCameras.contains(activity, c.osmId)) 0.35 else 1.0)
        })
        set(CAM_CONES, cams.mapNotNull { c ->
            val dir = com.rfsentinel.app.alpr.CameraDirections.of(activity, c) ?: return@mapNotNull null
            val faded = com.rfsentinel.app.alpr.IgnoredCameras.contains(activity, c.osmId)
            GeoJson.feature(GeoJson.polygon(MapGeo.wedge(c.lat, c.lon, dir.toDouble(), CAMERA_CONE_DEG, CAMERA_CONE_M)),
                "color" to hex(MapIcons.cameraColor(c.type)), "opacity" to if (faded) 0.08 else 0.25)
        })

        val showCctv = Prefs.showCctv(activity)
        val private = Prefs.cctvPrivate(activity)
        val cctv = if (showCctv && map.camera.zoom >= CCTV_MIN_ZOOM)
            CctvStore.cameras.filter { (private || !it.isPrivate) && inside(it.lat, it.lon) }.take(3000) else emptyList()
        cctvs = cctv.associateBy { it.osmId }
        set(CCTV, cctv.map { c ->
            GeoJson.feature(GeoJson.point(c.lat, c.lon), "id" to c.osmId, "icon" to if (c.isPrivate) "rf-cctv-private" else "rf-cctv-public")
        })
        set(CONES, cctv.mapNotNull { c ->
            val color = hex(if (c.isPrivate) MapIcons.CCTV_PRIVATE_COLOR else MapIcons.CCTV_PUBLIC_COLOR)
            val range = Cctv.viewRangeM(c)
            val dir = c.direction
            val ring = when {
                dir != null -> MapGeo.wedge(c.lat, c.lon, dir.toDouble(), Cctv.viewAngle(c).toDouble(), range)
                // No direction: only domes / panning cameras get a (fainter, smaller) all-round area.
                c.cameraType == "dome" || c.cameraType == "panning" -> MapGeo.circle(c.lat, c.lon, range * 0.6, 24)
                else -> return@mapNotNull null
            }
            GeoJson.feature(GeoJson.polygon(ring), "color" to color, "opacity" to if (dir != null) 0.2 else 0.1)
        })
        updateCctvLine(if (!showCctv) null else view)

        val showTowers = filter == DeviceFilter.CELLS || (filter == DeviceFilter.ALL && Prefs.showCellTowers(activity))
        val tw = if (showTowers) CellTowerStore.all(activity).filter { it.bestLat != null } else emptyList()
        towers = tw.associateBy { it.key }
        set(TOWERS, tw.map { t ->
            GeoJson.feature(GeoJson.point(t.bestLat!!, t.bestLon!!), "id" to t.key)
        })

        maybeFetchCameras(view)
    }

    private fun clearStatic() {
        listOf(CAMERAS, CAM_CONES, CCTV, CONES, TOWERS).forEach { set(it, emptyList()) }
        cameras = emptyMap(); cctvs = emptyMap(); towers = emptyMap(); staticBox = null
    }

    /** The CCTV layer's state for the status line, as before; [view] null = the layer is off. */
    private fun updateCctvLine(view: MapEngine.Box?) {
        if (!Prefs.showCctv(activity) || tripMode) { cctvLine = null; return }
        val store = CctvStore
        if (view == null) {
            cctvLine = store.progress?.let { "CCTV: downloading around you, $it" }
            return
        }
        val private = Prefs.cctvPrivate(activity)
        fun inView(c: com.rfsentinel.app.alpr.CctvCamera) = view.contains(c.lat, c.lon)
        val shown = cctvs.values.count { inView(it) }
        val hiddenPrivate = if (private) 0 else store.cameras.count { it.isPrivate && inView(it) }
        cctvLine = when {
            map.camera.zoom < CCTV_MIN_ZOOM -> store.progress?.let { "CCTV: downloading around you, $it" }
            store.progress != null -> "CCTV: downloading around you, ${store.progress}"
            store.isBusy -> "CCTV: downloading..."
            System.currentTimeMillis() - store.lastFailureAt < 60_000 -> "CCTV: map server busy - retrying"
            store.missingAreas > 0 && shown > 0 -> "CCTV: $shown in view - ${store.missingAreas} area(s) around you still missing (servers busy), retried next time"
            shown == 0 && store.needsDownload(view.south, view.west, view.north, view.east) -> "CCTV: downloading..."
            shown == 0 && hiddenPrivate > 0 -> "CCTV: only private ones here ($hiddenPrivate) - Settings > Known cameras > Include private"
            shown == 0 -> null // nothing to say: no banner
            else -> "CCTV: $shown in view" + if (hiddenPrivate > 0) " (+$hiddenPrivate private hidden)" else ""
        }
    }

    /**
     * Fetches the plate / speed cameras of the area in view when they aren't cached yet, and the CCTV
     * around you and in view; at most every 20 s so following you at speed doesn't hammer the servers.
     */
    private fun maybeFetchCameras(view: MapEngine.Box) {
        val now = System.currentTimeMillis()
        val span = AlprStore.MAX_SPAN_DEG
        val small = view.north - view.south <= span && view.east - view.west <= span
        if (Prefs.showCctv(activity) && now - lastCctvFetch > 20_000 && !CctvStore.isBusy) {
            lastCctvFetch = now
            val here = ScanForegroundService.lastFix
            if (here != null) CctvStore.ensureAround(activity, here.latitude, here.longitude, Prefs.cameraRadiusKm(activity).toDouble())
            if (!CctvStore.isBusy && small && map.camera.zoom >= CCTV_MIN_ZOOM) activity.lifecycleScope.launch {
                runCatching { CctvStore.autoDownload(activity, view.south, view.west, view.north, view.east) }
                safe("static") { maybeRedrawStatic(force = true); onStaticChanged?.invoke() }
            }
        }
        if (now - lastCameraFetch < 20_000) return
        if (!Prefs.autoCameras(activity) || !Prefs.showKnownAlpr(activity) || AlprStore.isBusy || !small) return
        lastCameraFetch = now
        activity.lifecycleScope.launch {
            val changed = runCatching {
                AlprStore.autoDownload(activity, view.south, view.west, view.north, view.east)
            }.getOrDefault(false)
            if (changed) safe("static") { maybeRedrawStatic(force = true); onStaticChanged?.invoke() }
        }
    }

    /** What's under the finger: the topmost kind of thing wins; several devices at one spot give a list. */
    fun onTap(x: Float, y: Float, me: Pair<Double, Double>?) {
        val r = 22f * activity.resources.displayMetrics.density
        val box = RectF(x - r, y - r, x + r, y + r)
        val asked = listOf(AIRCRAFT to "hex", WAZE to "id", DRONES to "mac", DRONE_OPS to "mac", DOTS to "mac",
            CAMERAS to "id", CCTV to "id", TOWERS to "id")
        map.query(box, asked) { found ->
            if (activity.isFinishing || activity.isDestroyed) return@query
            safe("tap") { showTapped(found, me) }
        }
    }

    private fun showTapped(found: List<List<String>>, me: Pair<Double, Double>?) {
        val (air, waze, drone, op, dots, cams, cctv, tower) = found
        air.firstNotNullOfOrNull { planes[it] }?.let { s -> MapDialogs.showAircraft(activity, s.plane, s.hit, me); return }
        waze.firstNotNullOfOrNull { wazeShown[it] }?.let { MapDialogs.showWaze(activity, it); return }
        (drone + op).firstNotNullOfOrNull { drones[it] }?.let { s ->
            MapDialogs.showDrone(activity, s.mac, MapDialogs.droneDetails(s, s.remoteId!!)); return
        }
        val here = dots.distinct().mapNotNull { pins[it] }
        if (here.isNotEmpty()) { MapDialogs.showPins(activity, here); return }
        cams.firstNotNullOfOrNull { cameras[it] }?.let { c -> MapDialogs.showKnownCamera(activity, c) { maybeRedrawStatic(force = true) }; return }
        cctv.firstNotNullOfOrNull { cctvs[it] }?.let { MapDialogs.showCctv(activity, it); return }
        tower.firstNotNullOfOrNull { towers[it] }?.let { MapDialogs.showTower(activity, it) }
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]
    private operator fun <T> List<T>.component8() = this[7]

    /** Opens what an aircraft / Waze link points at, when it's on the map now. */
    fun showAircraft(seen: PoliceAircraft.Seen, me: Pair<Double, Double>?) = MapDialogs.showAircraft(activity, seen.plane, seen.hit, me)

    /** A blue arrow pointing north, turned to the drone's heading by the layer. */
    private fun droneArrow(dp: Float): android.graphics.Bitmap {
        val size = (30 * dp).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply {
            moveTo(size / 2f, 2 * dp); lineTo(size - 5 * dp, size - 4 * dp)
            lineTo(size / 2f, size * 0.68f); lineTo(5 * dp, size - 4 * dp); close()
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawPath(path, paint.apply { color = android.graphics.Color.parseColor(DRONE_COLOR); style = android.graphics.Paint.Style.FILL })
        c.drawPath(path, paint.apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2 * dp })
        return bmp
    }
}
