package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.view.View
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The map engine under RF Sentinel's maps: MapLibre with OpenFreeMap (free, no account - the
 * default) or Mapbox's own SDK and maps (Settings > Map > Map provider). Layers are written once,
 * as map style JSON, and data goes in as GeoJSON, so [RfMap] and the screens draw the same on both.
 */
internal interface MapEngine {

    /** [maxTilt]: the steepest 3D view angle the engine draws (MapLibre stops at 60°, Mapbox at 85°). */
    enum class Provider(val key: String, val label: String, val maxTilt: Double) {
        OPENFREEMAP("openfreemap", "OpenFreeMap (OpenStreetMap, free, no account)", 60.0),
        MAPBOX("mapbox", "Mapbox", 85.0);

        companion object {
            fun parse(key: String?) = entries.firstOrNull { it.key == key } ?: OPENFREEMAP
        }
    }

    /** Following your position: not at all, north up, or turning with your heading. */
    enum class Follow { NONE, NORTH, HEADING }

    data class Camera(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double, val tilt: Double)

    /** North, east, south, west. */
    data class Box(val north: Double, val east: Double, val south: Double, val west: Double) {
        fun contains(o: Box) = o.north <= north && o.south >= south && o.east <= east && o.west >= west
        fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
    }

    val provider: Provider
    val view: View
    /** The map data credit to show on the map (Mapbox also shows its logo and an info button). */
    val credit: String
    /** Fonts the map's labels can use (each provider serves its own). */
    val labelFont: List<String>

    // ---- lifecycle (forwarded by the screen) ----
    fun onCreate(state: Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onLowMemory()
    fun onSaveInstanceState(out: Bundle)
    fun onDestroy()

    fun whenReady(ready: () -> Unit)

    // ---- style ----
    /** The light or night map; [loaded] gets true when the style is new (its layers need adding again). */
    fun setStyle(dark: Boolean, loaded: (fresh: Boolean) -> Unit)
    val styleLoaded: Boolean
    fun hasSource(id: String): Boolean
    fun addImage(id: String, bitmap: Bitmap)
    /** An empty GeoJSON source. */
    fun addSource(id: String)
    fun setData(source: String, geoJson: String)
    /** A layer as map style JSON ({"id", "type", "source", "minzoom", "filter", "layout", "paint"}). */
    fun addLayer(json: JSONObject)
    /** Changes one paint property (a colour...) of a layer already on the map. */
    fun setPaint(layer: String, name: String, value: String)

    // ---- camera ----
    val camera: Camera
    fun jumpTo(lat: Double, lon: Double, zoom: Double? = null)
    fun zoomTo(zoom: Double)
    /** Animated; null keeps that part. [flat] also drops any padding. */
    fun easeTo(lat: Double? = null, lon: Double? = null, zoom: Double? = null, bearing: Double? = null,
               tilt: Double? = null, flat: Boolean = false, ms: Long = 600)
    /** Frames [points]; [bottomPx] leaves more room at the bottom (under a card), [paddingPx] the rest. */
    fun fit(points: List<Pair<Double, Double>>, paddingPx: Int, bottomPx: Int = paddingPx)
    fun visibleBox(): Box?
    fun toLatLon(x: Float, y: Float): Pair<Double, Double>?
    fun setMinZoom(zoom: Double)

    var onCameraMove: (() -> Unit)?
    var onIdle: (() -> Unit)?
    var onClick: ((x: Float, y: Float) -> Unit)?
    /** A long press on the map, at this place (to drop a pin and ask for directions). */
    var onLongClick: ((lat: Double, lon: Double) -> Unit)?
    /** A gesture (pinch...) moved the map while following. */
    var onUserGesture: (() -> Unit)?

    /** The [key] property of what's drawn in [box] for each (layer, key), in the same order. */
    fun query(box: RectF, layers: List<Pair<String, String>>, result: (List<List<String>>) -> Unit)

    // ---- your position ----
    /** Shows your arrow (once the style is in); false without location permission. */
    /** The picture of your position, turned with your heading (null: the engine's own arrow); applied now or when the puck starts. */
    fun setMarker(bitmap: Bitmap?)
    /** True when the map can show your position as a 3D model ([setMarker3d]): Mapbox. */
    val draws3dMarker: Boolean
    /** Your position as this 3D model (a .glb file), [size] times the standard size; null: the picture of [setMarker]. */
    fun setMarker3d(model: java.io.File?, size: Float)
    fun startPuck(): Boolean
    val puckOn: Boolean
    fun pushFix(fix: Location)
    /** Keeps you in view: [topPaddingPx] lowers your arrow, [tilt] and [zoom] (null: leave it) set the view. */
    fun follow(mode: Follow, topPaddingPx: Double?, tilt: Double, zoom: Double?, ms: Long)
    /** You panned away: the map stopped following. */
    var onFollowDismissed: (() -> Unit)?
    /** The map provider refused the map (Mapbox: a revoked token...): [chosen] is OpenFreeMap from now on, swap to it. */
    var onFailed: (() -> Unit)?
    /** Where the provider's logo and info button go: their top edge, at the start or (with [atEnd]) the end. */
    fun placeBranding(topPx: Int, atEnd: Boolean = false)
    /** The room the logo and info button take, in dp (width from the edge, height); none for a provider without them. */
    val brandingDp: Pair<Float, Float> get() = 0f to 0f

    companion object {
        /** Mapbox needs the full build (the FOSS build leaves its SDK out) and a token: the user's own, else the built-in one. */
        fun mapboxAvailable(context: Context) = MapboxSupport.INCLUDED && mapboxToken(context).isNotEmpty() && !mapboxRefused

        /** The user's own Mapbox token (Settings › Map), else the one built in from local.properties. */
        fun mapboxToken(context: Context): String =
            com.rfsentinel.app.util.SecureStore.get(context, MAPBOX_TOKEN_KEY)?.trim().orEmpty().ifEmpty { BuildConfig.MAPBOX_TOKEN }

        /** Saves the user's own token ("" goes back to the built-in one) and gives Mapbox another try. */
        fun setMapboxToken(context: Context, token: String) {
            com.rfsentinel.app.util.SecureStore.put(context, MAPBOX_TOKEN_KEY, token.trim().ifEmpty { null })
            mapboxRefused = false
        }

        private const val MAPBOX_TOKEN_KEY = "mapbox_token"

        /** Mapbox refused this run's map: OpenFreeMap until the app restarts or a new token is set. */
        @Volatile var mapboxRefused = false

        /**
         * A Mapbox loading error that means it won't serve this token: an auth refusal only. Other style errors (a 3D
         * model or import failing once, a cancelled load) leave the map working, so they never switch it.
         */
        fun refusedBy(message: String) =
            Regex("""(status( code)?|http)\s*:?\s*40[13]\b|unauthori[sz]ed|forbidden|invalid (access )?token|valid access token""",
                RegexOption.IGNORE_CASE).containsMatchIn(message)

        fun chosen(context: Context): Provider =
            Provider.parse(Prefs.mapProvider(context)).takeIf { it != Provider.MAPBOX || mapboxAvailable(context) } ?: Provider.OPENFREEMAP

        fun create(context: Context, provider: Provider = chosen(context)): MapEngine = when (provider) {
            Provider.MAPBOX -> MapboxSupport.engine(context)
            Provider.OPENFREEMAP -> MapLibreEngine(context)
        }
    }
}

/** GeoJSON for the map sources, built the same way for both engines. */
internal object GeoJson {
    fun point(lat: Double, lon: Double): JSONObject =
        JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat))

    fun line(points: List<Pair<Double, Double>>): JSONObject =
        JSONObject().put("type", "LineString").put("coordinates", coords(points))

    /** A (lat, lon) ring. */
    fun polygon(ring: List<Pair<Double, Double>>): JSONObject =
        JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(coords(ring)))

    private fun coords(points: List<Pair<Double, Double>>) =
        JSONArray().apply { points.forEach { put(JSONArray().put(it.second).put(it.first)) } }

    fun feature(geometry: JSONObject, vararg props: Pair<String, Any?>): JSONObject {
        val p = JSONObject()
        for ((k, v) in props) if (v != null) p.put(k, v)
        return JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", p)
    }

    fun collection(features: List<JSONObject>): String =
        JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString()

    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
}
