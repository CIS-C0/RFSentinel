package com.rfsentinel.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.TripDeviceEntity
import com.rfsentinel.app.data.TripEntity
import com.rfsentinel.app.data.WhitelistCache
import com.rfsentinel.app.databinding.ActivityMapBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Tier
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.service.TripRecorder
import com.rfsentinel.app.util.Exporter
import com.rfsentinel.app.util.Permissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * OpenStreetMap view of a scan. Live mode shows your position, the trace being
 * recorded and nearby devices; trip mode shows a saved trace. Devices are drawn
 * where YOUR phone was when their signal was strongest - an approximation.
 *
 * Network use: map tiles only (OpenStreetMap), cached in the app's cache dir.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
        private const val ORDINARY_COLOR = 0xFF607D8B.toInt()
        private const val TRACE_COLOR = 0xFFE0622D.toInt()
        private const val PAST_TRACE_COLOR = 0xFF1F5FBF.toInt()
    }

    /** One device to draw, from the live registry or a saved trip. */
    private data class Pin(
        val mac: String, val lat: Double, val lon: Double, val label: String, val flagged: Boolean,
        val color: Int, val details: String
    )

    private lateinit var binding: ActivityMapBinding
    private var tripId: Long? = null
    private var trip: TripEntity? = null
    private var showAll = false
    private var centeredOnce = false

    private val trace = Polyline()
    private val pins = FolderOverlay()
    private lateinit var myLocation: MyLocationNewOverlay
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid: identify ourselves to the tile server (OSM tile policy) and keep
        // the tile cache inside the app's private cache.
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1).takeIf { it > 0 }
        title = if (tripId == null) "Scan map" else "Recorded trace"

        // Edge-to-edge: keep the controls above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.controls.updatePadding(bottom = bars.bottom + (6 * resources.displayMetrics.density).toInt())
            binding.statusText.translationY = bars.top.toFloat()
            insets
        }

        setupMap()
        binding.showAllChip.setOnCheckedChangeListener { _, checked ->
            showAll = checked
            if (tripId != null) loadTrip() else renderLive()
        }
        binding.centerButton.setOnClickListener { centerOnMe() }
        binding.tracesButton.setOnClickListener { startActivity(Intent(this, TripsActivity::class.java)) }

        if (tripId == null) {
            binding.recordButton.setOnClickListener { toggleRecording() }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        renderLive()
                        delay(3_000)
                    }
                }
            }
        } else {
            binding.recordButton.visibility = View.GONE
            loadTrip()
        }
    }

    private fun setupMap() {
        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        map.controller.setZoom(16.0)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0

        trace.outlinePaint.apply {
            color = if (tripId == null) TRACE_COLOR else PAST_TRACE_COLOR
            strokeWidth = 7f * resources.displayMetrics.density / 2
        }
        map.overlays.add(trace)
        map.overlays.add(pins)

        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        if (Permissions.granted(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            myLocation.enableMyLocation()
            if (tripId == null) {
                myLocation.runOnFirstFix {
                    runOnUiThread { if (!centeredOnce) { centeredOnce = true; centerOnMe() } }
                }
            }
        }
        map.overlays.add(myLocation)
        map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(false); setScaleBarOffset(20, 180) })
        map.overlays.add(CopyrightOverlay(this)) // "© OpenStreetMap contributors" (required attribution)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        myLocation.disableMyLocation()
        binding.map.onDetach()
        super.onDestroy()
    }

    private fun centerOnMe() {
        val fix = myLocation.myLocation
            ?: TripRecorder.livePoints().lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
        if (fix != null) binding.map.controller.animateTo(fix)
        else Toast.makeText(this, "Waiting for a GPS fix...", Toast.LENGTH_SHORT).show()
    }

    // ---- Live mode ----------------------------------------------------------------

    private fun renderLive() {
        val recording = TripRecorder.isRecording
        binding.recordButton.text = if (recording) "Stop recording" else "Record trace"
        binding.recordButton.setBackgroundColor(if (recording) 0xFFB3261E.toInt() else 0xFF0B5C63.toInt())

        trace.setPoints(TripRecorder.livePoints().map { GeoPoint(it.lat, it.lon) })

        val devices = if (ScanForegroundService.isRunning) DeviceRegistry.snapshot() else emptyList()
        val drawn = devices.mapNotNull { s ->
            val pos = s.bestPosition ?: return@mapNotNull null
            val flagged = s.best != null && !WhitelistCache.contains(s.mac)
            if (!flagged && !showAll) return@mapNotNull null
            val best = s.best
            Pin(
                s.mac, pos.lat, pos.lon,
                best?.label ?: s.name ?: s.deviceType,
                flagged,
                if (!flagged) ORDINARY_COLOR else if (best!!.tier == Tier.WEAK) 0xFFB26A00.toInt() else best.category.colorArgb,
                buildString {
                    append(s.mac)
                    s.vendor?.let { append("\n").append(it) }
                    if (best != null) append("\n${best.category.title} · ${best.tier.label} ${best.confidence}%\n${best.evidence}")
                    append("\nStrongest signal here: ${s.bestRssi} dBm")
                }
            )
        }
        drawPins(drawn)

        val positioned = devices.count { it.bestPosition != null }
        binding.statusText.text = when {
            !ScanForegroundService.isRunning -> "Not scanning - start scanning, then Record trace to save your route"
            recording -> {
                val mins = (System.currentTimeMillis() - TripRecorder.startedAt) / 60000
                String.format(
                    Locale.US, "● Recording %d:%02d · %.2f km · %s (%d flagged)",
                    mins / 60, mins % 60, TripRecorder.distanceM / 1000, devicesLabel(TripRecorder.deviceCount()), TripRecorder.flaggedCount()
                )
            }
            positioned == 0 -> "Scanning · waiting for GPS to place devices"
            else -> "Scanning · ${drawn.size} on map" + if (!showAll) " (flagged only - tap All devices)" else ""
        }
        binding.statusText.background.mutate().setTint(if (recording) 0xFFB3261E.toInt() else 0xCC0B5C63.toInt())
        binding.map.invalidate()
    }

    private fun toggleRecording() {
        if (TripRecorder.isRecording) {
            lifecycleScope.launch {
                val id = TripRecorder.activeTripId
                TripRecorder.stop(this@MapActivity)
                refreshServiceLocation()
                val saved = id?.let { AppDatabase.getInstance(this@MapActivity).tripDao().get(it) }
                if (saved != null) {
                    AlertDialog.Builder(this@MapActivity)
                        .setTitle("Trace saved")
                        .setMessage(String.format(Locale.US, "%s\n%.2f km · %s (%d flagged)",
                            saved.name, saved.distanceM / 1000, devicesLabel(saved.deviceCount), saved.flaggedCount))
                        .setPositiveButton("View") { _, _ -> openTrip(saved.id) }
                        .setNeutralButton("Export") { _, _ -> Exporter.showTripExport(this@MapActivity, saved.id) }
                        .setNegativeButton("Close", null)
                        .show()
                } else {
                    Toast.makeText(this@MapActivity, "Nothing recorded (no GPS fix)", Toast.LENGTH_LONG).show()
                }
                renderLive()
            }
            return
        }
        if (Permissions.missingRequired(this).isNotEmpty()) {
            Toast.makeText(this, "Grant location and nearby-devices first (Start scanning on the main screen)", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            if (!ScanForegroundService.isRunning) {
                runCatching { ScanForegroundService.start(this@MapActivity) }
                delay(600)
            }
            TripRecorder.start(this@MapActivity)
            refreshServiceLocation()
            Toast.makeText(this@MapActivity, "Recording your trace - keeps going with the screen off", Toast.LENGTH_SHORT).show()
            renderLive()
        }
    }

    /** Tells the running service to switch location updates to trace mode (or back). */
    private fun refreshServiceLocation() {
        runCatching {
            startService(Intent(this, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_REFRESH_LOCATION))
        }
    }

    private fun openTrip(id: Long) {
        startActivity(Intent(this, MapActivity::class.java).putExtra(EXTRA_TRIP_ID, id))
    }

    // ---- Trip mode ------------------------------------------------------------------

    private fun loadTrip() {
        val id = tripId ?: return
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@MapActivity).tripDao()
            val t = dao.get(id) ?: run { finish(); return@launch }
            trip = t
            val points = dao.points(id).map { GeoPoint(it.lat, it.lon) }
            val devices = dao.devices(id)
            trace.setPoints(points)
            drawPins(devices.filter { it.lat != null && (it.flagged || showAll) }.map { it.toPin() })
            val mins = ((t.endTime ?: System.currentTimeMillis()) - t.startTime) / 60000
            binding.statusText.text = String.format(
                Locale.US, "%s\n%s · %d:%02d · %.2f km · %s (%d flagged)",
                t.name, dateFmt.format(Date(t.startTime)), mins / 60, mins % 60, t.distanceM / 1000,
                devicesLabel(t.deviceCount), t.flaggedCount
            )
            binding.statusText.background.mutate().setTint(0xCC1F5FBF.toInt())
            val all = points + devices.filter { it.lat != null }.map { GeoPoint(it.lat!!, it.lon!!) }
            if (all.isNotEmpty()) {
                binding.map.post {
                    if (all.size == 1) binding.map.controller.setCenter(all[0])
                    else binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.3f), false)
                }
            }
            binding.map.invalidate()
        }
    }

    private fun TripDeviceEntity.toPin(): Pin {
        val cat = Category.parse(category)
        return Pin(
            mac, lat!!, lon!!, label, flagged,
            when {
                cat == null -> ORDINARY_COLOR
                confidence < Tier.MEDIUM.min -> 0xFFB26A00.toInt()
                else -> cat.colorArgb
            },
            buildString {
                append(mac)
                vendor?.let { append("\n").append(it) }
                if (cat != null) append("\n${cat.title} · ${Tier.of(confidence).label} $confidence%")
                evidence?.let { append("\n").append(it) }
                append("\nStrongest signal here: $bestRssi dBm")
                append("\nHeard ${dateFmt.format(Date(firstSeen))}")
            }
        )
    }

    // ---- Drawing ----------------------------------------------------------------------

    private fun drawPins(list: List<Pin>) {
        pins.items.clear()
        val dp = resources.displayMetrics.density
        // Ordinary devices first so flagged ones end up on top.
        list.sortedBy { it.flagged }.forEach { p ->
            val size = ((if (p.flagged) 18 else 10) * dp).toInt()
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.color)
                setStroke((2 * dp).toInt(), Color.WHITE)
                setSize(size, size)
            }
            pins.add(Marker(binding.map).apply {
                position = GeoPoint(p.lat, p.lon)
                icon = dot
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = p.label
                setOnMarkerClickListener { _, _ -> showPin(p); true }
            })
        }
    }

    private fun showPin(p: Pin) {
        AlertDialog.Builder(this)
            .setTitle(p.label)
            .setMessage(p.details + "\n\nPosition = where your phone was when this device's signal was strongest (approximate).")
            .setPositiveButton("Details") { _, _ -> DeviceActions.openDetails(this, p.mac) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- Menu (trip mode) ---------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (tripId != null) {
            menu.add(0, 1, 0, "Export trace...")
            menu.add(0, 2, 1, "Rename")
            menu.add(0, 3, 2, "Delete")
        } else {
            menu.add(0, 4, 0, "Recorded traces")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = tripId
        return when (item.itemId) {
            1 -> { id?.let { Exporter.showTripExport(this, it) }; true }
            2 -> { id?.let { rename(it) }; true }
            3 -> { id?.let { confirmDelete(it) }; true }
            4 -> { startActivity(Intent(this, TripsActivity::class.java)); true }
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun rename(id: Long) {
        val input = EditText(this).apply { setText(trip?.name ?: ""); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Rename trace")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@MapActivity).tripDao().rename(id, input.text.toString().trim().ifEmpty { "Trace" })
                    loadTrip()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete this trace?")
            .setMessage("The route and its device list are deleted from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@MapActivity).tripDao().delete(id)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** "1 device" / "3 devices". */
internal fun devicesLabel(n: Int) = if (n == 1) "1 device" else "$n devices"
