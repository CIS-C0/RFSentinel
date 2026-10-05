package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rfsentinel.app.detect.GnssAnalyzer

/**
 * Listens to the phone's satellite receiver (every system: GPS, GLONASS, Galileo, BeiDou,
 * QZSS, NavIC, SBAS) for [GnssAnalyzer]. It doesn't turn the GPS on by itself: it hears
 * the satellites whenever something else uses GPS (the map, a trace, camera warnings, or
 * the satellite screen, which asks for GPS while it's open). Users are counted, so the
 * scanner and the screen can share it.
 */
object GnssWatch {

    @Volatile var latest: GnssAnalyzer.Snapshot? = null; private set
    @Volatile var latestAt = 0L; private set
    /** True when the phone reports its receiver gain (needed to tell jamming from a tunnel). */
    @Volatile var hasAgc = false; private set
    val analyzer = GnssAnalyzer()

    private val listeners = LinkedHashSet<(GnssAnalyzer.Anomaly) -> Unit>()
    private var users = 0
    private var agcDb: Double? = null
    private var agcAt = 0L
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val now = System.currentTimeMillis()
            val sats = (0 until status.satelliteCount).map { i ->
                GnssAnalyzer.Sat(
                    GnssAnalyzer.System.of(status.getConstellationType(i)), status.getSvid(i), status.getCn0DbHz(i),
                    status.getElevationDegrees(i), status.usedInFix(i),
                    if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) / 1e6f else null
                )
            }
            val snap = GnssAnalyzer.Snapshot(now, sats, agcDb?.takeIf { now - agcAt < 5_000L })
            latest = snap; latestAt = now
            emit(analyzer.onSnapshot(snap))
        }
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            // Receiver gain on the L1 / E1 / B1 / G1 band (the one jammers usually hit).
            val values = ArrayList<Double>()
            if (Build.VERSION.SDK_INT >= 33) {
                event.gnssAutomaticGainControls.filter { it.carrierFrequencyHz > 1_500_000_000L }.forEach { values += it.levelDb }
            }
            if (values.isEmpty()) @Suppress("DEPRECATION") event.measurements.forEach { m ->
                if (m.hasAutomaticGainControlLevelDb() && (!m.hasCarrierFrequencyHz() || m.carrierFrequencyHz > 1.5e9f))
                    values += m.automaticGainControlLevelDb
            }
            if (values.isNotEmpty()) {
                values.sort()
                agcDb = values[values.size / 2]; agcAt = System.currentTimeMillis(); hasAgc = true
            }
        }
    }

    private val fixListener = LocationListener { loc: Location ->
        if (loc.provider == LocationManager.GPS_PROVIDER)
            emit(analyzer.onFix(GnssAnalyzer.Fix(loc.time, System.currentTimeMillis(), loc.latitude, loc.longitude, loc.accuracy)))
    }

    private fun emit(list: List<GnssAnalyzer.Anomaly>) {
        if (list.isEmpty()) return
        val ls = synchronized(this) { listeners.toList() }
        for (a in list) ls.forEach { it(a) }
    }

    /** Starts listening (needs the fine location permission); [onAnomaly] gets jamming / spoofing signs. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, onAnomaly: ((GnssAnalyzer.Anomaly) -> Unit)? = null) {
        onAnomaly?.let { listeners += it }
        users++
        if (users > 1) return
        val lm = context.applicationContext.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.registerGnssStatusCallback(statusCallback, handler) }
        runCatching { lm.registerGnssMeasurementsCallback(measurementsCallback, handler) }
        // Fixes other parts of the app (or other apps) ask for; costs nothing extra.
        runCatching { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, fixListener, Looper.getMainLooper()) }
    }

    @Synchronized
    fun stop(context: Context, onAnomaly: ((GnssAnalyzer.Anomaly) -> Unit)? = null) {
        onAnomaly?.let { listeners -= it }
        if (users == 0) return
        users--
        if (users > 0) return
        val lm = context.applicationContext.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.unregisterGnssStatusCallback(statusCallback) }
        runCatching { lm.unregisterGnssMeasurementsCallback(measurementsCallback) }
        runCatching { lm.removeUpdates(fixListener) }
    }
}
