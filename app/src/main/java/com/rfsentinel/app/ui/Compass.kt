package com.rfsentinel.app.ui

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

/**
 * Which way the phone faces, in true compass degrees: the rotation-vector sensor (gyroscope, accelerometer and
 * magnetometer fused by the phone), turned for the screen's rotation and, held upright, for the way the back camera
 * looks. Magnetic north becomes true north with the declination at the last GPS fix ([setLocation]).
 * Main thread: [start] when shown, [stop] when hidden.
 */
class Compass(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val m = FloatArray(9)
    private val r = FloatArray(9)
    private val o = FloatArray(3)
    private var upright = false
    private var declination = 0f
    private var running = false

    /** True compass degrees the phone faces, null without a compass or before its first reading. */
    var degrees: Float? = null
        private set
    /** The magnetometer says it needs calibrating (wave the phone in a figure 8). */
    var unreliable = false
        private set
    val available get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
    }

    fun stop() {
        if (!running) return
        sm?.unregisterListener(this)
        running = false
        degrees = null
    }

    fun setLocation(lat: Double, lon: Double, altitudeM: Double) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), altitudeM.toFloat(), System.currentTimeMillis()).declination
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(m, e.values)
        // m[8]: how much the screen faces up. Upright (in a mount, held up to look ahead): the back camera's direction.
        upright = abs(m[8]) < if (upright) 0.8f else 0.6f
        @Suppress("DEPRECATION")
        val rotation = (app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val (x, y) = axes(rotation, upright)
        SensorManager.remapCoordinateSystem(m, x, y, r)
        SensorManager.getOrientation(r, o)
        val now = HeadingFusion.norm(Math.toDegrees(o[0].toDouble()).toFloat() + declination)
        // Light smoothing (the gyroscope already keeps it steady), the short way round through north.
        degrees = degrees?.let { HeadingFusion.norm(it + SMOOTHING * HeadingFusion.diff(now, it)) } ?: now
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
        unreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    private fun axes(rotation: Int, upright: Boolean): Pair<Int, Int> {
        val y = if (upright) SensorManager.AXIS_Z else SensorManager.AXIS_Y
        val minusY = if (upright) SensorManager.AXIS_MINUS_Z else SensorManager.AXIS_MINUS_Y
        return when (rotation) {
            Surface.ROTATION_90 -> y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to minusY
            Surface.ROTATION_270 -> minusY to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to y
        }
    }

    private companion object { const val SMOOTHING = 0.3f }
}

/**
 * The radar's heading-up: the phone's [Compass], set right by the GPS course while driving (a car's metal pulls
 * the magnetometer off, and a phone in a cup holder faces anywhere). The difference between the two is learned at
 * each fix at driving speed, so turns still show at once from the gyroscope. Without a compass: the GPS course
 * while moving. Pure (unit-tested).
 */
class HeadingFusion {
    private var compass: Float? = null
    private var course: Float? = null
    private var moving = false
    private var offset: Float? = null
    private var offsetAt = 0L
    /** The compass when the GPS last set [offset]: a big turn since then while stopped means the phone was moved. */
    private var anchor: Float? = null

    fun compass(deg: Float?) {
        compass = deg
        val a = anchor
        if (!moving && deg != null && a != null && abs(diff(deg, a)) > MOVED_DEG) { offset = null; anchor = null }
    }

    /** A GPS fix: [course] its bearing (null when it has none), [speed] in m/s. */
    fun fix(now: Long, course: Float?, speed: Float) {
        moving = course != null && speed >= if (moving) STOP_MS else MOVE_MS
        this.course = course.takeIf { moving }
        val c = compass
        if (course != null && c != null && speed >= DRIVE_MS) {
            val d = diff(course, c)
            offset = offset?.let { norm180(it + GAIN * diff(d, it)) } ?: d
            offsetAt = now
            anchor = c
        }
    }

    /** Compass degrees at the top of the radar, null for north-up. */
    fun heading(now: Long): Float? {
        val c = compass ?: return course
        val o = offset?.takeIf { now - offsetAt <= HOLD_MS } ?: 0f
        return norm(c + o)
    }

    /** The GPS course is setting the compass right (driving, or stopped a short while since). */
    fun gpsSet(now: Long) = offset != null && now - offsetAt <= HOLD_MS

    companion object {
        /** GPS course counts from walking speed, and stops counting below [STOP_MS] (no flipping at the edge). */
        const val MOVE_MS = 1.5f
        const val STOP_MS = 1.0f
        /** The GPS course sets the compass right from this speed up (about 15 km/h). */
        const val DRIVE_MS = 4f
        const val GAIN = 0.2f
        /** Stopped (a red light) the correction holds this long, unless the phone turns [MOVED_DEG] on its own. */
        const val HOLD_MS = 120_000L
        const val MOVED_DEG = 45f

        fun norm(d: Float) = (d % 360f + 360f) % 360f
        private fun norm180(d: Float) = (norm(d) + 180f) % 360f - 180f
        /** [a] - [b] the short way round, -180..180. */
        fun diff(a: Float, b: Float) = norm180(a - b)
    }
}
