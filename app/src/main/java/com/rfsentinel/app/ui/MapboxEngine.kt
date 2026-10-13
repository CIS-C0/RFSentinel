package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.bindgen.Value
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.common.TelemetryUtils
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraBoundsOptions
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.maps.plugin.viewport.ViewportStatus
import com.mapbox.maps.plugin.viewport.data.DefaultViewportTransitionOptions
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.maps.plugin.viewport.data.ViewportOptions
import com.mapbox.maps.plugin.viewport.state.FollowPuckViewportState
import com.mapbox.maps.plugin.viewport.viewport
import com.mapbox.maps.toCameraOptions
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.util.Permissions
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * [MapEngine] on Mapbox's own SDK and its Standard map (day / night light, 3D buildings). Needs the
 * app's Mapbox token; Mapbox's own usage telemetry is switched off. Mapbox's logo and info button
 * stay on the map, as its terms require.
 */
internal class MapboxEngine(private val context: Context) : MapEngine {

    override val provider = MapEngine.Provider.MAPBOX
    override val credit = "© Mapbox © OpenStreetMap contributors"
    override val labelFont = listOf("Open Sans Regular", "Arial Unicode MS Regular")

    // Mapbox's documented way to turn its usage telemetry off (the library marks it internal).
    @android.annotation.SuppressLint("RestrictedApi")
    private fun telemetryOff() = runCatching { TelemetryUtils.setEventsCollectionState(false) { } }

    init {
        MapboxOptions.accessToken = BuildConfig.MAPBOX_TOKEN
        telemetryOff() // before any map exists
    }

    // The screen forwards the lifecycle, as for MapLibre (no automatic lifecycle plugin).
    override val view = MapView(context, MapInitOptions(context,
        plugins = MapInitOptions.defaultPluginList.filter { it.id != Plugin.MAPBOX_LIFECYCLE_PLUGIN_ID }))
    private val map = view.mapboxMap
    private val main = Handler(Looper.getMainLooper())
    private var style: Style? = null
    private var dark = false
    private val subscriptions = ArrayList<Cancelable>()
    private var destroyed = false

    override var onCameraMove: (() -> Unit)? = null
    override var onIdle: (() -> Unit)? = null
    override var onClick: ((Float, Float) -> Unit)? = null
    override var onLongClick: ((Double, Double) -> Unit)? = null
    override var onUserGesture: (() -> Unit)? = null
    override var onFollowDismissed: (() -> Unit)? = null

    override fun onCreate(state: Bundle?) {}
    override fun onStart() { if (!destroyed) view.onStart() }
    override fun onResume() { if (!destroyed) view.onResume() }
    override fun onPause() {}
    override fun onStop() { if (!destroyed) view.onStop() }
    override fun onLowMemory() { if (!destroyed) view.onLowMemory() }
    override fun onSaveInstanceState(out: Bundle) {}
    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        subscriptions.forEach { runCatching { it.cancel() } }; subscriptions.clear()
        style = null
        view.onDestroy()
    }

    override fun whenReady(ready: () -> Unit) {
        view.compass.enabled = false // the map button shows (and sets) north up
        view.scalebar.enabled = false // the app's own scale
        view.viewport.options = ViewportOptions.Builder().transitionsToIdleUponUserInteraction(false).build()
        subscriptions += map.subscribeCameraChanged { onCameraMove?.invoke() }
        subscriptions += map.subscribeMapIdle { onIdle?.invoke() }
        view.gestures.addOnMapClickListener { point ->
            val p = map.pixelForCoordinate(point)
            onClick?.invoke(p.x.toFloat(), p.y.toFloat())
            false
        }
        view.gestures.addOnMapLongClickListener { point ->
            onLongClick?.invoke(point.latitude(), point.longitude())
            onLongClick != null
        }
        view.gestures.addOnMoveListener(object : OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                onUserGesture?.invoke()
                // One finger: you're looking elsewhere - stop following (a pinch keeps it).
                if (following() && detector.pointersCount <= 1) { view.viewport.idle(); followMode = MapEngine.Follow.NONE; onFollowDismissed?.invoke() }
            }
            override fun onMove(detector: MoveGestureDetector) = false
            override fun onMoveEnd(detector: MoveGestureDetector) {}
        })
        view.gestures.addOnScaleListener(object : OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                // Your zoom from now on: following no longer sets one.
                followState?.let { s -> if (following()) s.options = s.options.toBuilder().zoom(null).build() }
                onUserGesture?.invoke()
            }
            override fun onScale(detector: StandardScaleGestureDetector) {}
            override fun onScaleEnd(detector: StandardScaleGestureDetector) {}
        })
        ready()
    }

    // ---- style ----

    override fun setStyle(dark: Boolean, loaded: (Boolean) -> Unit) {
        this.dark = dark
        val current = style
        if (current != null && current.isValid()) {
            // The Standard map turns night by its light, without reloading (your view stays as it is).
            lightPreset(current)
            loaded(false)
            return
        }
        map.loadStyle(Style.STANDARD) { s ->
            if (destroyed) return@loadStyle
            style = s
            lightPreset(s)
            loaded(true)
        }
    }

    private fun lightPreset(s: Style) {
        runCatching { s.setStyleImportConfigProperty("basemap", "lightPreset", Value.valueOf(if (dark) "night" else "day")) }
    }

    private fun live(): Style? = style?.takeIf { !destroyed && it.isValid() }
    override val styleLoaded get() = live() != null
    override fun hasSource(id: String) = live()?.styleSourceExists(id) == true
    override fun addImage(id: String, bitmap: Bitmap) { live()?.addImage(id, bitmap) }

    override fun addSource(id: String) {
        val s = live() ?: return
        s.addStyleSource(id, json("""{"type":"geojson","data":${GeoJson.EMPTY}}""")).error?.let { throw IllegalStateException(it) }
    }

    override fun setData(source: String, geoJson: String) { live()?.getSourceAs<GeoJsonSource>(source)?.data(geoJson) }

    override fun addLayer(json: JSONObject) {
        val s = live() ?: return
        s.addStyleLayer(json(json.toString()), null).error?.let { throw IllegalStateException(it) }
    }

    override fun setPaint(layer: String, name: String, value: String) {
        live()?.setStyleLayerProperty(layer, name, Value.valueOf(value))
    }

    private fun json(text: String): Value = Value.fromJson(text).let { it.value ?: throw IllegalArgumentException(it.error) }

    // ---- camera ----

    override val camera: MapEngine.Camera get() {
        val c = map.cameraState
        return MapEngine.Camera(c.center.latitude(), c.center.longitude(), c.zoom, c.bearing, c.pitch)
    }

    override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {
        map.setCamera(CameraOptions.Builder().center(Point.fromLngLat(lon, lat)).zoom(zoom).build())
    }

    override fun zoomTo(zoom: Double) { map.setCamera(CameraOptions.Builder().zoom(zoom).build()) }

    override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {
        val b = CameraOptions.Builder()
        if (lat != null && lon != null) b.center(Point.fromLngLat(lon, lat))
        b.zoom(zoom).bearing(bearing).pitch(tilt)
        if (flat) b.padding(EdgeInsets(0.0, 0.0, 0.0, 0.0))
        map.easeTo(b.build(), MapAnimationOptions.mapAnimationOptions { duration(ms) })
    }

    override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int) {
        if (points.isEmpty()) return
        if (points.size == 1) { jumpTo(points[0].first, points[0].second); return }
        val p = paddingPx.toDouble()
        val cam = map.cameraForCoordinates(points.map { Point.fromLngLat(it.second, it.first) },
            CameraOptions.Builder().bearing(0.0).pitch(0.0).build(), EdgeInsets(p, p, bottomPx.toDouble(), p), null, null)
        map.setCamera(cam)
    }

    override fun visibleBox(): MapEngine.Box? = runCatching {
        val b = map.coordinateBoundsForCamera(map.cameraState.toCameraOptions())
        MapEngine.Box(b.north(), b.east(), b.south(), b.west())
    }.getOrNull()

    override fun toLatLon(x: Float, y: Float): Pair<Double, Double>? =
        runCatching { map.coordinateForPixel(ScreenCoordinate(x.toDouble(), y.toDouble())) }.getOrNull()?.let { it.latitude() to it.longitude() }

    override fun setMinZoom(zoom: Double) { map.setBounds(CameraBoundsOptions.Builder().minZoom(zoom).build()) }

    override fun query(box: RectF, layers: List<Pair<String, String>>, result: (List<List<String>>) -> Unit) {
        // Asynchronous here: every layer is asked, the answer comes once all have replied.
        val answers = arrayOfNulls<List<String>>(layers.size)
        var left = layers.size
        if (left == 0) return result(emptyList())
        val geometry = RenderedQueryGeometry(ScreenBox(ScreenCoordinate(box.left.toDouble(), box.top.toDouble()),
            ScreenCoordinate(box.right.toDouble(), box.bottom.toDouble())))
        layers.forEachIndexed { i, (layer, key) ->
            map.queryRenderedFeatures(geometry, RenderedQueryOptions(listOf(layer), null)) { found ->
                val ids = found.value?.mapNotNull { f ->
                    val feature = f.queriedFeature.feature
                    if (feature.hasProperty(key)) feature.getStringProperty(key) else null
                } ?: emptyList()
                main.post {
                    answers[i] = ids
                    if (--left == 0 && !destroyed) result(answers.map { it ?: emptyList() })
                }
            }
        }
    }

    // ---- your position ----

    /** Your fixes, from the screen's own GPS (as on MapLibre: the screen keeps the heading steady). */
    private val fixes = object : LocationProvider {
        val consumers = CopyOnWriteArraySet<LocationConsumer>()
        var last: Location? = null
        override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
            consumers += locationConsumer
            last?.let { send(locationConsumer, it) }
        }
        override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) { consumers -= locationConsumer }
        fun send(c: LocationConsumer, fix: Location) {
            c.onLocationUpdated(Point.fromLngLat(fix.longitude, fix.latitude))
            if (fix.hasBearing()) c.onBearingUpdated(fix.bearing.toDouble())
        }
    }
    private var puck = false
    override val puckOn get() = puck && !destroyed

    private var marker: Bitmap? = null

    private fun puck(): com.mapbox.maps.plugin.LocationPuck = marker?.let { b ->
        val image = com.mapbox.maps.ImageHolder.from(b)
        // The picture is the bearing layer, which turns with your course; no fixed top picture (it would stay
        // north up behind it), no shadow, no zoom scaling.
        com.mapbox.maps.plugin.LocationPuck2D(topImage = null, bearingImage = image, shadowImage = null, scaleExpression = """["interpolate",["linear"],["zoom"],0,1.0,22,1.0]""")
    } ?: createDefault2DPuck(withBearing = true)

    override fun setMarker(bitmap: Bitmap?) {
        marker = bitmap
        if (puck && !destroyed) view.location.locationPuck = puck()
    }

    override fun startPuck(): Boolean {
        if (destroyed) return false
        if (!Permissions.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (!puck) {
            view.location.apply {
                setLocationProvider(fixes)
                locationPuck = puck()
                puckBearing = PuckBearing.COURSE
                puckBearingEnabled = true
                enabled = true
            }
            puck = true
        }
        return true
    }

    override fun pushFix(fix: Location) {
        fixes.last = fix
        fixes.consumers.forEach { fixes.send(it, fix) }
    }

    private var followState: FollowPuckViewportState? = null
    private var followMode = MapEngine.Follow.NONE
    private fun following() = followMode != MapEngine.Follow.NONE

    override fun follow(mode: MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) {
        if (!puckOn) return
        val vp = view.viewport
        followMode = mode
        if (mode == MapEngine.Follow.NONE) { vp.idle(); return }
        val options = FollowPuckViewportStateOptions.Builder()
            .padding(EdgeInsets(topPaddingPx ?: (followState?.options?.padding?.top ?: 0.0), 0.0, 0.0, 0.0))
            .pitch(tilt)
            .zoom(zoom)
            .bearing(if (mode == MapEngine.Follow.HEADING) FollowPuckViewportStateBearing.SyncWithLocationPuck
                     else FollowPuckViewportStateBearing.Constant(0.0))
            .build()
        val state = followState ?: vp.makeFollowPuckViewportState(options).also { followState = it }
        state.options = options
        val status = vp.status
        if (status is ViewportStatus.State && status.state === state) return
        val transition = if (ms <= 0) vp.makeImmediateViewportTransition()
            else vp.makeDefaultViewportTransition(DefaultViewportTransitionOptions.Builder().maxDurationMs(ms).build())
        vp.transitionTo(state, transition, null)
    }

    private var brandingTop = -1
    private var brandingAtEnd = false

    override fun placeBranding(topPx: Int, atEnd: Boolean) {
        if (topPx == brandingTop && atEnd == brandingAtEnd) return // only when it moved: setting it again asks for another layout pass
        brandingTop = topPx
        brandingAtEnd = atEnd
        val d = context.resources.displayMetrics.density
        val side = if (atEnd) Gravity.END else Gravity.START
        view.logo.position = Gravity.TOP or side
        view.logo.marginTop = topPx.toFloat()
        view.logo.marginLeft = if (atEnd) 0f else 8 * d
        view.logo.marginRight = if (atEnd) 8 * d else 0f
        view.attribution.position = Gravity.TOP or side
        view.attribution.marginTop = topPx.toFloat()
        view.attribution.marginLeft = if (atEnd) 0f else 96 * d
        view.attribution.marginRight = if (atEnd) 96 * d else 0f
    }
}
