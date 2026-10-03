package com.rfsentinel.app.car

import android.app.Presentation
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.ui.DeviceColors
import com.rfsentinel.app.ui.MapIcons
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.ln

/**
 * Draws RF Sentinel's own OpenStreetMap map on the car screen (Android Auto
 * "map with content", car API 7+): every device heard around you as a dot in
 * the list / radar colours, the known cameras, your position and, while
 * navigating, the route. The driver drags, flings and pinches it freely;
 * [following] keeps it centred on the car until they move it.
 *
 * The map is an ordinary osmdroid MapView shown on a virtual display that
 * renders straight into the car's surface.
 */
class CarMapRenderer(private val carContext: CarContext, private val scope: CoroutineScope) : SurfaceCallback {

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var map: MapView? = null
    // Marker groups belong to one MapView: osmdroid disables them when it's detached,
    // so each new car surface gets fresh ones.
    private var devices = FolderOverlay()
    private var cameras = FolderOverlay()
    private var me = FolderOverlay()
    private var towers = FolderOverlay()
    /** Icons are built once per map, not on every redraw (keyed by colour and size). */
    private val dotIcons = HashMap<Long, android.graphics.drawable.Drawable>()
    private var cameraIcons: Map<KnownCamera.Kind, BitmapDrawable> = emptyMap()
    private var fadedCameraIcons: Map<KnownCamera.Kind, BitmapDrawable> = emptyMap()
    private var towerIcon: BitmapDrawable? = null
    /** What the marker layers were last built from: unchanged data isn't rebuilt. */
    private var devicesKey: Any? = null
    private var camerasKey: Any? = null
    private var towersKey: Any? = null
    /** The navigation route; created with each map (osmdroid needs the MapView for it). */
    private var route: Polyline? = null
    private var visibleArea: Rect? = null
    private val main = Handler(Looper.getMainLooper())

    /** True while the map follows the car; any drag or fling turns it off until [recenter]. */
    var following = true
        private set
    var onFollowingChanged: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            redraw()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        try {
            MapIcons.configureOsm(carContext)
            val vd = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "RFSentinelMap", container.width, container.height, container.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            val p = Presentation(carContext, vd.display)
            devices = FolderOverlay(); cameras = FolderOverlay(); me = FolderOverlay(); towers = FolderOverlay()
            dotIcons.clear(); devicesKey = null; camerasKey = null; towersKey = null
            val m = MapView(p.context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(false) // touch comes from the car via onScroll/onScale
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 4.0
                maxZoomLevel = 19.0
                controller.setZoom(16.0)
                overlays.add(towers)
                overlays.add(cameras)
                overlays.add(devices)
                overlays.add(me)
            }
            p.setContentView(m)
            p.show()
            m.onResume()
            val dp = m.context.resources.displayMetrics.density
            // Car screens sit further away: bigger camera icons than on the phone.
            cameraIcons = KnownCamera.Kind.entries.associateWith { BitmapDrawable(m.context.resources, MapIcons.cameraIcon(dp * 1.6f, it)) }
            fadedCameraIcons = KnownCamera.Kind.entries.associateWith {
                BitmapDrawable(m.context.resources, MapIcons.cameraIcon(dp * 1.6f, it)).apply { alpha = 90 }
            }
            towerIcon = BitmapDrawable(m.context.resources, MapIcons.towerIcon(dp * 1.3f))
            display = vd; presentation = p; map = m
            applyDayNight()
            main.post(tick)
        } catch (e: Exception) {
            Log.w("CarMap", "Couldn't start the car map", e)
            release()
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onVisibleAreaChanged(area: Rect) { visibleArea = area; if (following) centreOnCar() }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val m = map ?: return
        setFollowing(false)
        m.scrollBy(distanceX.toInt(), distanceY.toInt())
        m.invalidate()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val m = map ?: return
        setFollowing(false)
        m.scrollBy((-velocityX * 0.15f).toInt(), (-velocityY * 0.15f).toInt())
        m.invalidate()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val m = map ?: return
        // Double-tap sends no focus (-1): zoom in a level around the centre.
        val delta = if (scaleFactor <= 0f || focusX < 0) 1.0 else ln(scaleFactor.toDouble()) / ln(2.0)
        val z = (m.zoomLevelDouble + delta).coerceIn(m.minZoomLevel, m.maxZoomLevel)
        if (following || focusX < 0) m.controller.setZoom(z)
        else (m.controller as org.osmdroid.views.MapController).zoomToFixing(z, focusX.toInt(), focusY.toInt(), 0L)
        if (following) centreOnCar()
        redraw()
    }

    fun zoomBy(levels: Double) {
        val m = map ?: return
        m.controller.setZoom((m.zoomLevelDouble + levels).coerceIn(m.minZoomLevel, m.maxZoomLevel))
        if (following) centreOnCar()
        redraw()
    }

    fun recenter() { setFollowing(true); centreOnCar(); redraw() }

    /** Frames a whole route (e.g. when navigation starts); stops following. */
    fun showWholeRoute() {
        val m = map ?: return
        val pts = route?.actualPoints ?: return
        if (pts.size < 2) return
        setFollowing(false)
        m.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false)
    }

    private fun setFollowing(on: Boolean) {
        if (following != on) { following = on; onFollowingChanged?.invoke() }
    }

    /** Puts the car in the middle of the part of the screen the content panel doesn't cover. */
    private fun centreOnCar() {
        val m = map ?: return
        val fix = CarUi.currentLocation(carContext) ?: return
        m.controller.setCenter(GeoPoint(fix.latitude, fix.longitude))
        visibleArea?.let { a ->
            if (m.width > 0 && !a.isEmpty) m.scrollBy(-(a.centerX() - m.width / 2), -(a.centerY() - m.height / 2))
        }
    }

    private fun applyDayNight() {
        map?.overlayManager?.tilesOverlay?.setColorFilter(if (carContext.isDarkMode) TilesOverlay.INVERT_COLORS else null)
    }

    /** Re-places every marker from the live data (every 2 s and after each zoom). */
    fun redraw() {
        val m = map ?: return
        val dp = m.context.resources.displayMetrics.density
        applyDayNight()
        if (following) centreOnCar()

        syncRoute(m)
        me.items.clear()
        com.rfsentinel.app.nav.Navigator.destination?.let { d ->
            me.add(Marker(m).apply {
                position = GeoPoint(d.lat, d.lon)
                icon = MapIcons.dot(dp, 0xFFD93025.toInt(), 20, 3f)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            })
        }
        CarUi.currentLocation(carContext)?.let { fix ->
            me.add(Marker(m).apply {
                position = GeoPoint(fix.latitude, fix.longitude)
                icon = MapIcons.dot(dp, 0xFF1A73E8.toInt(), 22, 3f)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            })
        }

        // Devices: ordinary first so flagged ones are drawn on top.
        val placed = DeviceRegistry.snapshot().filter { !WhitelistCache.contains(it.mac) }
            .sortedBy { DeviceColors.isFlagged(it) }
            .mapNotNull { s ->
                val rid = s.remoteId?.takeIf { it.hasPosition }
                val lat = rid?.latitude ?: s.bestPosition?.lat ?: return@mapNotNull null
                val lon = rid?.longitude ?: s.bestPosition?.lon ?: return@mapNotNull null
                val best = s.best
                val color = when {
                    best == null -> DevicesMapScreen.ORDINARY_COLOR
                    best.tier == Tier.WEAK -> DeviceColors.WEAK
                    else -> best.category.colorArgb
                }
                DeviceDot(lat, lon, color, if (DeviceColors.isFlagged(s)) 18 else 11)
            }
        if (placed != devicesKey) {
            devicesKey = placed
            devices.items.clear()
            for (d in placed) {
                devices.add(Marker(m).apply {
                    position = GeoPoint(d.lat, d.lon)
                    icon = dotIcons.getOrPut((d.color.toLong() shl 8) or d.sizeDp.toLong()) { MapIcons.dot(dp, d.color, d.sizeDp) }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                })
            }
        }

        // Cell towers (phone map menu: Show cell towers), at their estimated spots.
        val showTowers = Prefs.showCellTowers(carContext)
        val tKey = if (showTowers) com.rfsentinel.app.service.CellTowerStore.currentAt / 30_000L else null
        if (tKey != towersKey) {
            towersKey = tKey
            towers.items.clear()
            if (showTowers) com.rfsentinel.app.service.CellTowerStore.all(carContext).forEach { t ->
                val lat = t.bestLat ?: return@forEach
                val lon = t.bestLon ?: return@forEach
                towers.add(Marker(m).apply {
                    position = GeoPoint(lat, lon)
                    icon = towerIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                })
            }
        }

        if (m.zoomLevelDouble >= 9.0) {
            val box = m.boundingBox.increaseByScale(1.4f)
            val inBox = AlprStore.cameras.asSequence()
                .filter { it.lat in box.latSouth..box.latNorth && it.lon in box.lonWest..box.lonEast }
                .take(600)
                .toList()
            // Rebuilt when the cameras in view change, not on every 2 s tick.
            val ignoredIds = inBox.filter { com.rfsentinel.app.alpr.IgnoredCameras.contains(carContext, it.osmId) }.map { it.osmId }.toSet()
            val cKey = listOf(inBox.size, inBox.firstOrNull()?.osmId, inBox.lastOrNull()?.osmId, ignoredIds)
            if (cKey != camerasKey) {
                camerasKey = cKey
                cameras.items.clear()
                inBox.forEach { c ->
                    cameras.add(Marker(m).apply {
                        position = GeoPoint(c.lat, c.lon)
                        // Silenced cameras stay on the map, faded (like on the phone).
                        icon = (if (c.osmId in ignoredIds) fadedCameraIcons else cameraIcons)[c.type]
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setInfoWindow(null)
                    })
                }
            }
            // Fetch the cameras for wherever the driver moved the map.
            if (Prefs.autoCameras(carContext) && !AlprStore.isBusy && box.latNorth - box.latSouth <= AlprStore.MAX_SPAN_DEG) {
                scope.launch {
                    if (AlprStore.autoDownload(carContext, box.latSouth, box.lonWest, box.latNorth, box.lonEast)) redraw()
                }
            }
        } else if (camerasKey != null) {
            camerasKey = null
            cameras.items.clear()
        }
        m.invalidate()
    }

    private data class DeviceDot(val lat: Double, val lon: Double, val color: Int, val sizeDp: Int)

    private var drawnRoute: com.rfsentinel.app.nav.OsmRouting.Route? = null

    /** Swaps in a new route line when the route changes (osmdroid lines are rebuilt, not edited). */
    private fun syncRoute(m: MapView) {
        val r = com.rfsentinel.app.nav.Navigator.route
        if (r === drawnRoute) return
        drawnRoute = r
        route?.let { m.overlays.remove(it) }
        route = null
        if (r == null || r.points.size < 2) return
        route = Polyline(m).apply {
            outlinePaint.color = 0xFF1A73E8.toInt()
            outlinePaint.strokeWidth = 10f * m.context.resources.displayMetrics.density
            setPoints(r.points.map { GeoPoint(it.first, it.second) })
            setInfoWindow(null)
        }
        m.overlays.add(0, route) // under the markers
    }

    /** What's on the map now, for the side panel. */
    fun counts(): Pair<Int, Int> {
        val m = map ?: return 0 to 0
        val box = m.boundingBox
        val d = devices.items.size
        val c = AlprStore.cameras.count { it.lat in box.latSouth..box.latNorth && it.lon in box.lonWest..box.lonEast }
        return d to c
    }

    fun release() {
        main.removeCallbacks(tick)
        runCatching { map?.onPause(); map?.onDetach() }
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        map = null; presentation = null; display = null; route = null; drawnRoute = null
    }

}
