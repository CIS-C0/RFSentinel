package com.rfsentinel.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.rfsentinel.app.service.ScanForegroundService
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where you are, to the metre, while the radar or Locate is on screen: steps from the accelerometer, walked the way
 * the compass says (dead reckoning, like phone-finding apps track you between fixes), pulled toward each GPS fix by
 * how sure that fix is. GPS alone wanders 5-10 m on foot, which swings a near device's direction; steps alone
 * drift, which GPS corrects. Positions are metres east / north in one frame ([generation]); a new frame starts when
 * it resumes with no GPS to tie the old one to. Main thread: [start] / [stop] are counted.
 */
object Motion : SensorEventListener {

    class Pos(val x: Double, val y: Double)

    private var users = 0
    private var sm: SensorManager? = null
    private var compass: Compass? = null
    private val fusion = Fusion()
    private val steps = Steps()
    /** Changes when positions from before can't be compared with positions now. */
    var generation = 0
        private set
    private var stale = false
    private var staleSince = 0L
    private var fixTime = 0L
    /** When Locate's camera view last placed you ([ar]): its tracking beats steps and GPS while it lasts. */
    private var arAt = 0L

    /** Where you are now; null while waiting for a GPS fix to place you again after a pause. */
    val position: Pos? get() = if (users == 0 || stale) null else Pos(fusion.x, fusion.y)

    fun start(context: Context) {
        if (users++ > 0) return
        val app = context.applicationContext
        sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        compass = Compass(app).also { it.start() }
        // You may have walked while it was off: the next fix places you again, else a new frame.
        if (fusion.anchored) { stale = true; staleSince = System.currentTimeMillis() } else newFrame()
    }

    fun stop() {
        if (users == 0 || --users > 0) return
        sm?.unregisterListener(this)
        compass?.stop(); compass = null
    }

    /** Locate's camera view tracks you at [x], [y] (this frame's metres). Main thread. */
    fun ar(x: Double, y: Double) {
        if (users == 0 || stale) return
        fusion.set(x, y)
        arAt = System.currentTimeMillis()
    }

    private fun newFrame() { generation++; fusion.reset(); stale = false }

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        ScanForegroundService.lastFix?.takeIf { it.time != fixTime }?.let { f ->
            fixTime = f.time
            compass?.setLocation(f.latitude, f.longitude, f.altitude)
            if (f.hasAccuracy() && f.accuracy <= MAX_ACCURACY_M && now - f.time < FIX_FRESH_MS && now - arAt > AR_HOLD_MS) {
                fusion.gps(f.latitude, f.longitude, f.accuracy.toDouble(), snap = stale)
                stale = false
            }
        }
        if (stale && now - staleSince > STALE_MS) newFrame()
        val v = e.values
        if (!steps.add(e.timestamp / 1_000_000, sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())) || now - arAt <= AR_HOLD_MS) return
        compass?.degrees?.let { fusion.step(it) }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    /**
     * Dead reckoning fused with GPS (a one-state Kalman filter per axis): each step adds its own doubt, each fix
     * pulls by doubt / (doubt + fix error²). Pure (unit-tested).
     */
    class Fusion {
        var x = 0.0
            private set
        var y = 0.0
            private set
        private var variance = 0.0
        private var lat0 = Double.NaN
        private var lon0 = Double.NaN
        private var lastHeading: Float? = null
        val anchored get() = !lat0.isNaN()

        fun reset() { x = 0.0; y = 0.0; variance = 0.0; lat0 = Double.NaN; lon0 = Double.NaN; lastHeading = null }

        /** Placed by the AR camera: centimetres, so little doubt for the next GPS fix to pull on. */
        fun set(x: Double, y: Double) { this.x = x; this.y = y; variance = AR_VARIANCE }

        /** One step while facing [heading]. Shuffling round on the spot (a sweep) is steps too, but barely moves you. */
        fun step(heading: Float) {
            val turned = lastHeading?.let { abs(Sweep.turn(it, heading)) > TURN_DEG } ?: false
            lastHeading = heading
            val len = if (turned) STEP_M * TURN_STEP else STEP_M
            val r = Math.toRadians(heading.toDouble())
            x += len * sin(r); y += len * cos(r)
            variance += STEP_VARIANCE
        }

        /** A GPS fix [accM] metres sure; [snap] puts you there (after a pause). The first one ties the frame to the map. */
        fun gps(lat: Double, lon: Double, accM: Double, snap: Boolean = false) {
            val acc2 = accM.coerceAtLeast(MIN_ACCURACY_M).let { it * it }
            if (!anchored) {
                lat0 = lat - y / M_PER_DEG_LAT; lon0 = lon - x / mPerDegLon(lat)
                variance = acc2
                return
            }
            val gx = (lon - lon0) * mPerDegLon(lat0)
            val gy = (lat - lat0) * M_PER_DEG_LAT
            val k = if (snap) 1.0 else variance / (variance + acc2)
            x += k * (gx - x); y += k * (gy - y)
            variance = if (snap) acc2 else (1 - k) * variance
        }

        private fun mPerDegLon(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))
    }

    /** Steps from the accelerometer's swing above gravity, at a walking pace at most. Pure (unit-tested). */
    class Steps {
        private var gravity = 9.81
        private var smooth = 9.81
        private var armed = false
        private var last = Long.MIN_VALUE / 2

        /** A reading ([ms] time, [mag] m/s² all axes): true at each step. */
        fun add(ms: Long, mag: Double): Boolean {
            gravity += 0.01 * (mag - gravity)
            smooth += 0.3 * (mag - smooth)
            val a = smooth - gravity
            if (a > RISE) armed = true
            else if (armed && a < FALL) {
                armed = false
                if (ms - last >= MIN_GAP_MS) { last = ms; return true }
            }
            return false
        }
    }

    // shortcut: one step length for everyone; learn it from GPS distance / steps if ranges come out scaled.
    private const val STEP_M = 0.7
    private const val TURN_DEG = 30f
    private const val TURN_STEP = 0.2
    /** Doubt each step adds (m²): length and heading errors. */
    private const val STEP_VARIANCE = 0.25
    private const val MIN_ACCURACY_M = 3.0
    /** The camera view lost tracking this long: steps and GPS take over again. */
    private const val AR_HOLD_MS = 1_000L
    private const val AR_VARIANCE = 0.01
    private const val MAX_ACCURACY_M = 50f
    private const val FIX_FRESH_MS = 5_000L
    /** Resumed and no fix this long: start over. */
    private const val STALE_MS = 10_000L
    private const val M_PER_DEG_LAT = 110_540.0
    private const val RISE = 1.2
    private const val FALL = 0.2
    private const val MIN_GAP_MS = 280L
}
