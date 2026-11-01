package com.rfsentinel.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.rfsentinel.app.R
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.util.applySystemBarInsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.tan

/**
 * Locate through the camera: the same sweep (phone upright in front of you, turn round), with ARCore tracking where
 * the phone is to the centimetre while it looks around, so walking toward the device keeps its direction and gives
 * its distance. A marker floats where it is when it's ahead; the arrow points the way otherwise. Locate goes on
 * when this closes. Main thread, except the GL renderer.
 */
class ArLocateActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    /** What the camera saw last frame (GL thread to main thread). */
    private class Shot(val tracking: Boolean, val failure: TrackingFailureReason, val x: Float, val z: Float,
                       val arHeading: Float, val level: Boolean, val focal: Float)

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var boundTo: Session? = null
    private var installAsked = false
    private var cameraAsked = false
    @Volatile private var shot: Shot? = null
    private val frame = ArFrame()
    private val background = Background()
    private lateinit var surface: GLSurfaceView
    private lateinit var marker: Marker
    private lateinit var picture: SweepGuide.SweepView
    private lateinit var chip: TextView
    private lateinit var prompt: TextView
    private lateinit var info: TextView

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) { Toast.makeText(this, "The camera view needs the camera", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val muted = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF9AA5AD.toInt())
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArLocateActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        marker = Marker(this)
        picture = SweepGuide.SweepView(this)
        chip = text(14f, null)
        prompt = text(18f, null).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        info = text(14f, muted)
        val top = MaterialCardView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, 0, 0)
                addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_nav_close)
                    background = null
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
                    contentDescription = "Close the camera view"
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener { finish() }
                })
            })
        }
        val side = min((140 * d).toInt(), (resources.displayMetrics.widthPixels * 0.4f).toInt())
        val bottom = MaterialCardView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad / 2)
                addView(picture, LinearLayout.LayoutParams(side, side))
                addView(prompt); addView(info)
                addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Sweep again"
                    setOnClickListener { LiveRead.sweepAgain() }
                })
            })
        }
        val overlay = FrameLayout(this).apply {
            setPadding(pad, pad, pad, pad)
            addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        overlay.applySystemBarInsets()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface); addView(marker); addView(overlay)
        })
    }

    private fun text(sp: Float, color: Int?) = TextView(this).apply {
        textSize = sp
        gravity = Gravity.CENTER
        color?.let { setTextColor(it) }
        setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    override fun onResume() {
        super.onResume()
        if (LiveRead.locating == null) { finish(); return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraAsked) { cameraAsked = true; askCamera.launch(Manifest.permission.CAMERA) }
            return
        }
        val s = session ?: try {
            // Google Play Services for AR: offered once, then back here.
            if (ArCoreApk.getInstance().requestInstall(this, !installAsked) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installAsked = true; return
            }
            Session(this).also { session = it }
        } catch (e: Exception) {
            Toast.makeText(this, "The camera view doesn't work on this phone", Toast.LENGTH_LONG).show()
            finish(); return
        }
        try { s.resume() } catch (e: Exception) {
            Toast.makeText(this, "The camera is busy", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        frame.reset()
        surface.onResume()
        main.post(update)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(update)
        // The renderer stops before the session it draws from.
        surface.onPause()
        session?.pause()
        shot = null
    }

    override fun onDestroy() {
        session?.close(); session = null
        super.onDestroy()
    }

    // ---- GL thread: the camera picture, and where the camera is ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background.create()
        boundTo = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        @Suppress("DEPRECATION")
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, width, height)
    }

    private val projection = FloatArray(16)

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (boundTo !== s) { s.setCameraTextureName(background.texture); boundTo = s }
        val f = try { s.update() } catch (e: Exception) { return }
        background.draw(f)
        val c = f.camera
        val pose = c.pose
        val z = pose.zAxis
        // The camera looks along its -z.
        val fx = -z[0]; val fy = -z[1]; val fz = -z[2]
        c.getProjectionMatrix(projection, 0, 0.1f, 100f)
        shot = Shot(c.trackingState == TrackingState.TRACKING, c.trackingFailureReason, pose.tx(), pose.tz(),
            ArFrame.arHeading(fx, fz), abs(fy) < LEVEL && hypot(fx, fz) > 0.1f, projection[0])
    }

    // ---- Main thread: tie the camera to Motion, and the words ----

    private val update = object : Runnable {
        override fun run() {
            main.postDelayed(this, UPDATE_MS)
            val st = LiveRead.sweepState() ?: run { finish(); return } // Locate ended
            val sh = shot
            val tracking = sh?.tracking == true
            if (sh != null && !tracking) frame.reset()
            val here = if (tracking) frame.place(sh!!.x, sh.z, sh.arHeading, LiveRead.heading.takeIf { sh.level && st.upright }, Motion.position) else null
            here?.let { Motion.placed(it.x, it.y) }
            val heading = sh?.takeIf { tracking }?.let { frame.heading(it.arHeading) } ?: st.heading

            val r = LiveRead.current()
            chip.text = (r?.title ?: "Device") + " · " + (r?.rssi?.let { "$it dBm" } ?: "not heard")
            val l = st.locked
            picture.step = if (l != null) 2 else 1
            val now = System.currentTimeMillis()
            val place = LiveRead.locating?.let { RadarTrack.place(it, now) }
            marker.show(if (l != null && heading != null && sh != null) Sweep.turn(heading, l.bearing).toFloat() else null,
                sh?.focal ?: 0f, place?.distanceM)
            prompt.text = when {
                sh == null -> "Starting the camera"
                !tracking -> when (sh.failure) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> "More light"
                    TrackingFailureReason.EXCESSIVE_MOTION -> "Slower"
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point it at something with detail"
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera is busy"
                    else -> "Move the phone side to side"
                }
                !st.upright -> "Hold it upright, camera ahead"
                l == null && st.covered >= Sweep.MIN_SECTORS -> "Once more round, a little slower"
                l == null -> "Turn round slowly"
                heading != null -> LiveRead.turnText(Sweep.turn(heading, l.bearing)).replaceFirstChar { it.uppercase() }
                else -> "Locked"
            }
            info.text = if (l == null) {
                "${st.covered * 360 / Sweep.SECTORS}° of 360°" + (st.estimate?.let { " · ${(it.confidence * 100).toInt()}% sure" } ?: "")
            } else {
                Sweep.compassPoint(l.bearing) + (place?.let { " · ~" + DeviceIntel.formatDistance(it.distanceM.toDouble()) } ?: "") +
                    " · ${(l.confidence * 100).toInt()}% sure" + (if (place == null) "\nWalk a few steps for its distance" else "")
            }
        }
    }

    /** The device where it is in the picture, while it's ahead: a diamond and its distance. */
    private class Marker(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val you = MaterialColors.getColor(context, R.attr.rfTraceColor, 0xFFE0622D.toInt())
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = you }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * d }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; color = Color.WHITE; textSize = 15 * d; setShadowLayer(4 * d, 0f, 0f, Color.BLACK)
        }
        private val path = Path()
        private var turn: Float? = null
        private var focal = 0f
        private var distance: Float? = null

        /** [turn]: degrees from straight ahead (right positive), null to hide; [focal]: the projection's x scale. */
        fun show(turn: Float?, focal: Float, distance: Float?) {
            this.turn = turn; this.focal = focal; this.distance = distance
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val t = turn ?: return
            if (abs(t) >= 80f || focal <= 0f) return
            val ndc = focal * tan(Math.toRadians(t.toDouble())).toFloat()
            if (abs(ndc) > 1f) return
            // shortcut: drawn at the picture's middle height (the device's height isn't known), and phone roll ignored.
            val x = (ndc + 1f) / 2f * width
            val y = height * 0.42f
            val s = 14 * d
            path.reset()
            path.moveTo(x, y - s); path.lineTo(x + s, y); path.lineTo(x, y + s); path.lineTo(x - s, y); path.close()
            canvas.drawPath(path, fill); canvas.drawPath(path, edge)
            distance?.let { canvas.drawText("~" + DeviceIntel.formatDistance(it.toDouble()), x, y + s + 20 * d, text) }
        }
    }

    /** The camera picture behind everything: ARCore's external texture on a full-screen quad. GL thread. */
    private class Background {
        var texture = 0
            private set
        private var program = 0
        private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val uv = floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        fun create() {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texture = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            program = GLES20.glCreateProgram().also { p ->
                GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
                GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
                GLES20.glLinkProgram(p)
            }
        }

        fun draw(f: com.google.ar.core.Frame) {
            if (f.hasDisplayGeometryChanged()) {
                quad.position(0); uv.position(0)
                f.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, uv)
            }
            if (f.timestamp == 0L) return
            quad.position(0); uv.position(0)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            val pos = GLES20.glGetAttribLocation(program, "a_pos")
            val tex = GLES20.glGetAttribLocation(program, "a_uv")
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, uv)
            GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(tex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(tex)
        }

        private fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }

        private companion object {
            fun floats(vararg v: Float): FloatBuffer =
                ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

            const val VERTEX = "attribute vec4 a_pos; attribute vec2 a_uv; varying vec2 v_uv;\n" +
                "void main() { gl_Position = a_pos; v_uv = a_uv; }"
            const val FRAGMENT = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 v_uv; uniform samplerExternalOES u_tex;\n" +
                "void main() { gl_FragColor = texture2D(u_tex, v_uv); }"
        }
    }

    private companion object {
        const val UPDATE_MS = 50L
        /** The camera's forward this close to level (sine of the tilt) counts as held upright. */
        const val LEVEL = 0.4f
    }
}
