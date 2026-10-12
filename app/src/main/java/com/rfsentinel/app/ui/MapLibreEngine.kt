package com.rfsentinel.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import com.rfsentinel.app.util.Permissions
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LayoutPropertyValue
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** [MapEngine] on MapLibre with OpenFreeMap's vector maps (free, no account, no key). */
internal class MapLibreEngine(private val context: Context) : MapEngine {

    companion object {
        /** OpenFreeMap: OpenStreetMap vector tiles, free, no account, no key, no cookies. */
        const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        /** A navy night map (like Waze's), lifted further in [nightTweaks]: OpenFreeMap's "dark" is near black. */
        const val DARK_STYLE = "https://tiles.openfreemap.org/styles/fiord"
        const val CREDIT = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
        private const val BUILDINGS = "rf-buildings-3d"
        private const val GRASS = "rf-night-grass"
        /** Style properties whose value is a plain array, not an expression. */
        private val ARRAY_PROPERTIES = setOf("text-font", "text-offset", "icon-offset", "line-dasharray", "text-variable-anchor", "text-translate", "icon-translate", "circle-translate", "fill-translate", "line-translate")

        /** A style JSON value as MapLibre wants it: expressions, float numbers, typed arrays. */
        internal fun value(name: String, v: Any?): Any? = when {
            v is JSONArray && name in ARRAY_PROPERTIES ->
                if (v.length() > 0 && v.get(0) is String) Array(v.length()) { v.getString(it) }
                else Array(v.length()) { v.getDouble(it).toFloat() }
            v is JSONArray -> Expression.Converter.convert(v.toString())
            v is Number -> v.toFloat()
            else -> v
        }
    }

    override val provider = MapEngine.Provider.OPENFREEMAP
    override val credit = CREDIT
    override val labelFont = listOf("Noto Sans Regular")

    init { MapLibre.getInstance(context.applicationContext) }

    override val view = MapView(context, MapLibreMapOptions.createFromAttributes(context))
    private var map: MapLibreMap? = null

    override var onCameraMove: (() -> Unit)? = null
    override var onIdle: (() -> Unit)? = null
    override var onClick: ((Float, Float) -> Unit)? = null
    override var onUserGesture: (() -> Unit)? = null
    override var onFollowDismissed: (() -> Unit)? = null

    override fun onCreate(state: Bundle?) = view.onCreate(state)
    override fun onStart() = view.onStart()
    override fun onResume() = view.onResume()
    override fun onPause() = view.onPause()
    override fun onStop() = view.onStop()
    override fun onLowMemory() = view.onLowMemory()
    override fun onSaveInstanceState(out: Bundle) = view.onSaveInstanceState(out)
    override fun onDestroy() { map = null; view.onDestroy() }

    override fun whenReady(ready: () -> Unit) {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // the credit line on the map instead
            m.uiSettings.isTiltGesturesEnabled = true
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture?.invoke()
            }
            m.addOnCameraMoveListener { onCameraMove?.invoke() }
            m.addOnCameraIdleListener { onIdle?.invoke() }
            m.addOnMapClickListener { at ->
                val p = m.projection.toScreenLocation(at)
                onClick?.invoke(p.x, p.y)
                false
            }
            ready()
        }
    }

    // ---- style ----

    override fun setStyle(dark: Boolean, loaded: (Boolean) -> Unit) {
        val m = map ?: return
        m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { style ->
            if (dark) runCatching { nightTweaks(style) }
            loaded(true)
        }
    }

    /**
     * The night map, made easier to read at a glance: residential areas drawn near-white in
     * OpenFreeMap's navy style go navy, roads lighter than the ground (as in Waze at night), and
     * 3D buildings, which the style hasn't got.
     */
    private fun nightTweaks(style: Style) {
        // Ground a little lighter, water deeper, and nature green (the style has no green at all).
        style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#4D5977"))
        style.getLayer("water")?.setProperties(PropertyFactory.fillColor("#2C3B60"))
        style.getLayer("landuse_residential")?.setProperties(PropertyFactory.fillColor("#58647F"), PropertyFactory.fillOpacity(0.45f))
        style.getLayer("park")?.setProperties(PropertyFactory.fillColor("#3E6E52"), PropertyFactory.fillOpacity(0.7f))
        style.getLayer("park_outline")?.setProperties(PropertyFactory.lineColor("#4F8463"))
        style.getLayer("landcover_wood")?.setProperties(PropertyFactory.fillColor("#3A6650"),
            PropertyFactory.fillOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(8, 0.4f), Expression.stop(12, 0.7f))))
        style.getLayer("building")?.setProperties(PropertyFactory.fillColor("#5F6B8A"), PropertyFactory.fillOpacity(0.6f))
        addPlaceLabels(style)
        if (style.getLayer("night-oneway") == null) NightLabels.layers.forEach { runCatching { addLayerTo(style, JSONObject(it)) } }
        // Labels: the style's grey-blue text with faint halos disappears on the ground; near-white on a dark outline instead.
        for (layer in style.layers) {
            if (layer !is SymbolLayer || layer.id.startsWith("rf-") || layer.id.startsWith("night-shield")) continue
            val road = layer.id.contains("highway") || layer.id.contains("water") || layer.id.contains("path")
            layer.setProperties(
                PropertyFactory.textColor(if (road) "#D3DCEC" else "#EEF3FA"),
                PropertyFactory.textHaloColor("#1C2436"),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textHaloBlur(0.3f),
                PropertyFactory.textOpacity(1f))
        }
        if (style.getLayer(GRASS) == null) {
            // Grass, meadows and farmland, which the night style doesn't draw: a fainter green, under the woods.
            val grass = FillLayer(GRASS, "openmaptiles").withSourceLayer("landcover").withProperties(
                PropertyFactory.fillColor("#41694F"), PropertyFactory.fillOpacity(0.45f)
            ).withFilter(Expression.Converter.convert("""["match", ["get", "class"], ["grass", "farmland"], true, false]"""))
            if (style.getLayer("landcover_wood") != null) style.addLayerBelow(grass, "landcover_wood") else style.addLayerAbove(grass, "water")
        }
        style.getLayer("highway_minor")?.setProperties(PropertyFactory.lineColor("#6A7899"))
        style.getLayer("highway_major_inner")?.setProperties(PropertyFactory.lineColor("#8190B3"))
        style.getLayer("highway_major_subtle")?.setProperties(PropertyFactory.lineColor("#8190B3"))
        style.getLayer("highway_motorway_inner")?.setProperties(PropertyFactory.lineColor("#9AA8CB"))
        style.getLayer("highway_motorway_subtle")?.setProperties(PropertyFactory.lineColor("#9AA8CB"))
        if (style.getLayer(BUILDINGS) == null) {
            val buildings = FillExtrusionLayer(BUILDINGS, "openmaptiles").withSourceLayer("building").withProperties(
                PropertyFactory.fillExtrusionColor("#36425F"),
                PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                PropertyFactory.fillExtrusionOpacity(0.8f)
            ).apply { minZoom = 14f }
            val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
            if (firstLabel != null) style.addLayerBelow(buildings, firstLabel) else style.addLayer(buildings)
        }
    }

    /**
     * Shops, businesses, landmarks and transit stops, which the night style doesn't draw at all (the
     * light map does): the same layers, icons and zoom levels as the light map, from the same tiles.
     */
    private fun addPlaceLabels(style: Style) {
        if (style.getLayer("night-poi-r1") != null) return
        fun ex(json: String) = Expression.Converter.convert(json)
        val name = ex("""["coalesce", ["get", "name_en"], ["get", "name"]]""")
        val icon = ex("""["match", ["get", "subclass"], ["florist", "furniture"], ["get", "subclass"], ["get", "class"]]""")
        fun poi(id: String, minZoom: Float, filter: String) = SymbolLayer(id, "openmaptiles").withSourceLayer("poi").withFilter(ex(filter))
            .withProperties(PropertyFactory.iconImage(icon), PropertyFactory.textField(name),
                PropertyFactory.textFont(arrayOf("Noto Sans Italic")), PropertyFactory.textSize(12f),
                PropertyFactory.textAnchor("top"), PropertyFactory.textOffset(arrayOf(0f, 0.6f)), PropertyFactory.textMaxWidth(9f))
            .also { it.minZoom = minZoom }
        val point = """["match", ["geometry-type"], ["MultiPoint", "Point"], true, false]"""
        val layers = listOf(
            poi("night-poi-r20", 17f, """["all", $point, [">=", ["get", "rank"], 20]]"""),
            poi("night-poi-r7", 16f, """["all", $point, [">=", ["get", "rank"], 7], ["<", ["get", "rank"], 20]]"""),
            poi("night-poi-r1", 15f, """["all", $point, [">=", ["get", "rank"], 1], ["<", ["get", "rank"], 7]]"""),
            SymbolLayer("night-poi-transit", "openmaptiles").withSourceLayer("poi")
                .withFilter(ex("""["match", ["get", "class"], ["airport", "bus", "rail"], true, false]"""))
                .withProperties(PropertyFactory.iconImage(ex("""["to-string", ["get", "class"]]""")), PropertyFactory.iconSize(0.7f),
                    PropertyFactory.textField(name), PropertyFactory.textFont(arrayOf("Noto Sans Italic")), PropertyFactory.textSize(12f),
                    PropertyFactory.textAnchor("left"), PropertyFactory.textOffset(arrayOf(0.9f, 0f)), PropertyFactory.textMaxWidth(9f))
        )
        layers.forEach { style.addLayer(it) }
    }

    private fun style(): Style? = map?.style?.takeIf { it.isFullyLoaded }
    override val styleLoaded get() = style() != null
    override fun hasSource(id: String) = style()?.getSource(id) != null
    override fun addImage(id: String, bitmap: Bitmap) { style()?.addImage(id, bitmap) }
    override fun addSource(id: String) { style()?.addSource(GeoJsonSource(id)) }
    override fun setData(source: String, geoJson: String) { style()?.getSourceAs<GeoJsonSource>(source)?.setGeoJson(geoJson) }

    override fun addLayer(json: JSONObject) { style()?.let { addLayerTo(it, json) } }

    private fun addLayerTo(style: Style, json: JSONObject) {
        val id = json.getString("id"); val src = json.getString("source")
        val layer: Layer = when (val type = json.getString("type")) {
            "circle" -> CircleLayer(id, src)
            "fill" -> FillLayer(id, src)
            "line" -> LineLayer(id, src)
            "symbol" -> SymbolLayer(id, src)
            "heatmap" -> HeatmapLayer(id, src)
            "fill-extrusion" -> FillExtrusionLayer(id, src)
            else -> throw IllegalArgumentException("layer type $type")
        }
        if (json.has("source-layer")) when (layer) {
            is CircleLayer -> layer.sourceLayer = json.getString("source-layer")
            is FillLayer -> layer.sourceLayer = json.getString("source-layer")
            is LineLayer -> layer.sourceLayer = json.getString("source-layer")
            is SymbolLayer -> layer.sourceLayer = json.getString("source-layer")
            else -> {}
        }
        val props = ArrayList<PropertyValue<*>>()
        json.optJSONObject("layout")?.let { o -> o.keys().forEach { k -> props += LayoutPropertyValue(k, value(k, o.get(k))) } }
        json.optJSONObject("paint")?.let { o -> o.keys().forEach { k -> props += PaintPropertyValue(k, value(k, o.get(k))) } }
        layer.setProperties(*props.toTypedArray())
        json.optJSONArray("filter")?.let { f ->
            val e = Expression.Converter.convert(f.toString())
            when (layer) {
                is CircleLayer -> layer.setFilter(e); is FillLayer -> layer.setFilter(e); is LineLayer -> layer.setFilter(e)
                is SymbolLayer -> layer.setFilter(e); is HeatmapLayer -> layer.setFilter(e); is FillExtrusionLayer -> layer.setFilter(e)
            }
        }
        if (json.has("minzoom")) layer.minZoom = json.getDouble("minzoom").toFloat()
        style.addLayer(layer)
    }

    override fun setPaint(layer: String, name: String, value: String) {
        style()?.getLayer(layer)?.setProperties(PaintPropertyValue(name, value))
    }

    // ---- camera ----

    override val camera: MapEngine.Camera get() {
        val c = map?.cameraPosition ?: return MapEngine.Camera(0.0, 0.0, 0.0, 0.0, 0.0)
        return MapEngine.Camera(c.target?.latitude ?: 0.0, c.target?.longitude ?: 0.0, c.zoom, c.bearing, c.tilt)
    }

    override fun jumpTo(lat: Double, lon: Double, zoom: Double?) {
        val m = map ?: return
        m.cameraPosition = CameraPosition.Builder(m.cameraPosition).target(LatLng(lat, lon)).apply { zoom?.let { zoom(it) } }.build()
    }

    override fun zoomTo(zoom: Double) { map?.moveCamera(CameraUpdateFactory.zoomTo(zoom)) }

    override fun easeTo(lat: Double?, lon: Double?, zoom: Double?, bearing: Double?, tilt: Double?, flat: Boolean, ms: Long) {
        val m = map ?: return
        val b = CameraPosition.Builder(m.cameraPosition)
        if (lat != null && lon != null) b.target(LatLng(lat, lon))
        zoom?.let { b.zoom(it) }; bearing?.let { b.bearing(it) }; tilt?.let { b.tilt(it) }
        if (flat) b.padding(0.0, 0.0, 0.0, 0.0)
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), ms.toInt().coerceAtLeast(1))
    }

    override fun fit(points: List<Pair<Double, Double>>, paddingPx: Int) {
        val m = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1) { jumpTo(points[0].first, points[0].second); return }
        val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, paddingPx))
    }

    override fun visibleBox(): MapEngine.Box? {
        val v = runCatching { map?.projection?.visibleRegion?.latLngBounds }.getOrNull() ?: return null
        return MapEngine.Box(v.latitudeNorth, v.longitudeEast, v.latitudeSouth, v.longitudeWest)
    }

    override fun toLatLon(x: Float, y: Float): Pair<Double, Double>? =
        map?.projection?.fromScreenLocation(PointF(x, y))?.let { it.latitude to it.longitude }

    override fun setMinZoom(zoom: Double) { map?.setMinZoomPreference(zoom) }

    override fun query(box: RectF, layers: List<Pair<String, String>>, result: (List<List<String>>) -> Unit) {
        val m = map ?: return result(layers.map { emptyList() })
        result(layers.map { (layer, key) ->
            runCatching { m.queryRenderedFeatures(box, layer).mapNotNull { f -> if (f.hasProperty(key)) f.getStringProperty(key) else null } }
                .getOrDefault(emptyList())
        })
    }

    // ---- your position ----

    private fun component(): LocationComponent? =
        map?.let { runCatching { it.locationComponent }.getOrNull() }?.takeIf { it.isLocationComponentActivated }

    override val puckOn get() = component() != null

    @SuppressLint("MissingPermission") // checked first
    override fun startPuck(): Boolean {
        val m = map ?: return false
        val style = style() ?: return false
        if (!Permissions.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) return false
        val lc = m.locationComponent
        // Light / dark switch: the component moves to the new style by itself.
        if (!lc.isLocationComponentActivated) {
            val options = LocationComponentOptions.builder(context)
                .pulseEnabled(false)
                .accuracyAnimationEnabled(false)
                .trackingGesturesManagement(true) // a one-finger pan stops following; a pinch keeps it
                .build()
            // Fixes come from the screen, so slow ones can keep the last real heading before the map sees them.
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(options)
                .build())
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { onFollowDismissed?.invoke() }
                override fun onCameraTrackingChanged(mode: Int) {}
            })
        }
        return true
    }

    override fun pushFix(fix: Location) { component()?.forceLocationUpdate(fix) }

    private var reapply: Runnable? = null

    override fun follow(mode: MapEngine.Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long) {
        val lc = component() ?: return
        reapply?.let { view.removeCallbacks(it) }; reapply = null
        if (mode == MapEngine.Follow.NONE) { lc.cameraMode = CameraMode.NONE; return }
        // The component ignores padding / tilt / zoom while its camera mode is changing, so they go first,
        // and once more when the change is over (a mode switch no longer leaves the old angle or zoom).
        fun look(duration: Long) {
            topPaddingPx?.let { lc.paddingWhileTracking(doubleArrayOf(0.0, it, 0.0, 0.0), duration) }
            lc.tiltWhileTracking(tilt, duration)
            zoom?.let { lc.zoomWhileTracking(it, duration) }
        }
        look(ms)
        lc.setCameraMode(if (mode == MapEngine.Follow.HEADING) CameraMode.TRACKING_GPS else CameraMode.TRACKING, ms, null, null, null, null)
        // Also after an instant switch (the map opening already in 3D): the angle set above was dropped.
        Runnable { if (component() != null) look(if (ms > 0) 400 else 0) }.also { reapply = it; view.postDelayed(it, ms + 250) }
        val m = map
        if (mode == MapEngine.Follow.NORTH && m != null && m.cameraPosition.bearing != 0.0) m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 600)
    }

    override fun placeBranding(topPx: Int) {}
}
