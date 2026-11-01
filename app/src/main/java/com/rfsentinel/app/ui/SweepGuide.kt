package com.rfsentinel.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Locate guide for the chest sweep, in three steps: how to hold the phone (an animated demo), sweeping (a ring
 * that fills with the signal heard each way) and locked (an arrow to the device that turns as you turn). Locate goes
 * on when it is closed; the live read card and the radar keep the locked direction.
 */
class SweepGuide(private val activity: Activity) {

    private enum class Step { HOW, SWEEP, LOCKED }

    private val main = Handler(Looper.getMainLooper())
    private val view = SweepView(activity)
    private val density = activity.resources.displayMetrics.density
    private val muted = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF9AA5AD.toInt())
    private val label = text(13f, muted)
    private val title = text(18f, null).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private val detail = text(14f, muted)
    private var dialog: AlertDialog? = null
    private var step = Step.HOW

    private fun text(sp: Float, color: Int?) = TextView(activity).apply {
        textSize = sp
        gravity = Gravity.CENTER
        color?.let { setTextColor(it) }
        setPadding(0, (4 * density).toInt(), 0, 0)
    }

    fun show() {
        val pad = (20 * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, 0)
            addView(label)
            val side = min((200 * density).toInt(), (activity.resources.displayMetrics.widthPixels * 0.55f).toInt())
            addView(view, LinearLayout.LayoutParams(side, side).apply { topMargin = (8 * density).toInt() })
            addView(title); addView(detail)
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(content)
            .setPositiveButton("Start sweep", null)
            .setNegativeButton("Not now", null)
            .setNeutralButton(" ", null)
            .setOnDismissListener { main.removeCallbacks(update) }
            .create().also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (step == Step.HOW) { LiveRead.sweepAgain(); go(Step.SWEEP) } else d.dismiss()
                    }
                    d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { LiveRead.sweepAgain(); go(Step.SWEEP) }
                    go(Step.HOW)
                }
                d.show()
            }
    }

    fun dismiss() = dialog?.dismiss()

    private fun go(s: Step) {
        step = s
        view.step = s.ordinal
        val d = dialog ?: return
        d.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = if (s == Step.SWEEP) View.GONE else View.VISIBLE
            text = if (s == Step.HOW) "Start sweep" else "Done"
        }
        d.getButton(AlertDialog.BUTTON_NEGATIVE).text = if (s == Step.SWEEP) "Cancel" else "Not now"
        d.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = if (s == Step.LOCKED) View.GONE else View.VISIBLE
        d.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            text = "Sweep again"
            visibility = if (s == Step.LOCKED) View.VISIBLE else View.GONE
        }
        main.removeCallbacks(update)
        update.run()
    }

    /** The words under the picture, four times a second; moves on to Locked when the sweep locks. */
    private val update = object : Runnable {
        override fun run() {
            val st = LiveRead.sweepState() ?: run { dismiss(); return } // Locate ended
            if (step == Step.SWEEP && st.locked != null) { go(Step.LOCKED); return }
            when (step) {
                Step.HOW -> {
                    label.text = "1 · How to hold it"
                    title.text = "Phone against your chest"
                    detail.text = "Screen facing you. Turn slowly on the spot, all the way round.\n\n" +
                        "Your body blocks the signal from behind, so it's strongest when you face the device."
                }
                Step.SWEEP -> {
                    label.text = "2 · Sweeping"
                    title.text = when {
                        !st.upright -> "Hold it upright on your chest"
                        st.covered >= Sweep.MIN_SECTORS -> "Once more round, a little slower"
                        else -> "Keep turning"
                    }
                    val rssi = LiveRead.current()?.rssi
                    detail.text = (rssi?.let { "$it dBm · " } ?: "Not heard right now · ") +
                        "the ring fills with the signal heard each way, warmer = stronger" +
                        (st.estimate?.let { "\nSure so far: ${(it.confidence * 100).toInt()}%" } ?: "")
                }
                Step.LOCKED -> {
                    val l = st.locked ?: run { go(Step.SWEEP); return }
                    label.text = "3 · Locked"
                    title.text = st.heading?.let { LiveRead.turnText(Sweep.turn(it, l.bearing)).replaceFirstChar { c -> c.uppercase() } } ?: "Locked"
                    detail.text = "Locked ${Sweep.compassPoint(l.bearing)} · ${(l.confidence * 100).toInt()}% sure. " +
                        "The arrow turns as you turn, like N on a compass. Also on the radar, with a line." +
                        (st.moved?.takeIf { it > LiveRead.SWEEP_STALE_M }?.let { "\n\nYou moved ${it.toInt()} m: sweep again here." } ?: "")
                }
            }
            view.contentDescription = "${label.text}. ${title.text}"
            main.postDelayed(this, 250L)
        }
    }

    /** The picture: the demo, the filling ring, or the arrow. Redraws every frame while shown. */
    private class SweepView(context: Context) : View(context) {
        var step = 0
        private val d = resources.displayMetrics.density
        private val scope = MaterialColors.getColor(context, R.attr.rfScopeColor, 0xFF0B5C63.toInt())
        private val scopeText = MaterialColors.getColor(context, R.attr.rfScopeTextColor, 0xAA0B5C63.toInt())
        private val you = MaterialColors.getColor(context, R.attr.rfTraceColor, 0xFFE0622D.toInt())
        private val accent = ChipStyle.accent(context).first
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = scopeText }
        private val arc = RectF()
        private val path = Path()
        private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = min(cx, cy) - 12 * d
            when (step) {
                0 -> drawDemo(canvas, cx, cy, r)
                1 -> drawRing(canvas, cx, cy, r)
                else -> drawArrow(canvas, cx, cy, r)
            }
            if (isShown) postInvalidateOnAnimation()
        }

        /** Seen from above: you, the phone on your chest and the way it faces, turning; the device off to one side. */
        private fun drawDemo(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            dashed.color = alpha(scope, 0x77); dashed.strokeWidth = 1.5f * d
            canvas.drawCircle(cx, cy, r, dashed)
            // The device, with its signal.
            val dx = cx + r * 0.78f; val dy = cy - r * 0.78f
            fill.color = you
            canvas.drawCircle(dx, dy, 6 * d, fill)
            line.color = you; line.strokeWidth = 2 * d
            for (i in 1..2) {
                arc.set(dx - 10 * d * i, dy - 10 * d * i, dx + 10 * d * i, dy + 10 * d * i)
                canvas.drawArc(arc, 100f, 80f, false, line)
            }
            val turn = (System.currentTimeMillis() % 8_000L) / 8_000f * 360f
            canvas.save()
            canvas.rotate(turn, cx, cy)
            fill.color = alpha(scope, 0x66)
            arc.set(cx - r * 0.5f, cy - r * 0.2f, cx + r * 0.5f, cy + r * 0.2f)
            canvas.drawOval(arc, fill) // shoulders
            fill.color = alpha(scope, 0xAA)
            canvas.drawCircle(cx, cy, r * 0.17f, fill) // head
            fill.color = accent
            arc.set(cx - r * 0.13f, cy - r * 0.33f, cx + r * 0.13f, cy - r * 0.22f)
            canvas.drawRoundRect(arc, 2 * d, 2 * d, fill) // the phone, on the chest
            line.color = accent
            canvas.drawLine(cx, cy - r * 0.38f, cx, cy - r * 0.72f, dashed.apply { color = accent; strokeWidth = 2 * d })
            path.reset()
            path.moveTo(cx - 6 * d, cy - r * 0.64f); path.lineTo(cx, cy - r * 0.76f); path.lineTo(cx + 6 * d, cy - r * 0.64f)
            canvas.drawPath(path, line)
            canvas.restore()
        }

        /** North up: each sector heard so far in its heat colour, and a needle the way you face. */
        private fun drawRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val now = System.currentTimeMillis()
            val st = LiveRead.sweepState(now) ?: return
            val w = 16 * d
            val rr = r - w / 2
            arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
            line.strokeCap = Paint.Cap.BUTT; line.strokeWidth = w
            for ((i, dbm) in st.sectors.withIndex()) {
                line.color = dbm?.let { VisualMode.heat(VisualMode.dbmLevel(it.toInt())) } ?: alpha(scope, 0x33)
                canvas.drawArc(arc, Sweep.centre(i) - 90f - Sweep.SECTOR_DEG / 2 + 1f, Sweep.SECTOR_DEG - 2f, false, line)
            }
            line.strokeCap = Paint.Cap.ROUND
            text.textSize = 11 * d
            canvas.drawText("N", cx, cy - r - 2 * d, text)
            st.heading?.let { h ->
                val a = Math.toRadians(h - 90.0)
                line.color = scopeText; line.strokeWidth = 2.5f * d
                canvas.drawLine(cx, cy, cx + (rr - w) * cos(a).toFloat(), cy + (rr - w) * sin(a).toFloat(), line)
            }
            text.textSize = 22 * d
            canvas.drawText("${st.covered * 360 / Sweep.SECTORS}°", cx, cy - 2 * d, text)
            text.textSize = 11 * d
            canvas.drawText("of 360°", cx, cy + 14 * d, text)
        }

        /** The way you face is up: the arrow points to the device and turns as you turn, N with it. */
        private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val st = LiveRead.sweepState() ?: return
            val l = st.locked ?: return
            val h = st.heading ?: 0f
            line.color = alpha(scope, 0x66); line.strokeWidth = 2 * d
            canvas.drawCircle(cx, cy, r, line)
            val n = Math.toRadians(-h - 90.0)
            text.textSize = 12 * d
            canvas.drawText("N", cx + (r - 12 * d) * cos(n).toFloat(), cy + (r - 12 * d) * sin(n).toFloat() + 4 * d, text)
            canvas.save()
            canvas.rotate(l.bearing - h, cx, cy)
            line.color = you; line.strokeWidth = 6 * d
            canvas.drawLine(cx, cy, cx, cy - r * 0.75f, line)
            path.reset()
            path.moveTo(cx - 12 * d, cy - r * 0.6f); path.lineTo(cx, cy - r * 0.78f); path.lineTo(cx + 12 * d, cy - r * 0.6f)
            canvas.drawPath(path, line)
            canvas.restore()
            fill.color = you
            canvas.drawCircle(cx, cy, 7 * d, fill)
            // You, facing up.
            fill.color = accent
            path.reset()
            path.moveTo(cx - 8 * d, cy + r - 4 * d); path.lineTo(cx, cy + r - 18 * d); path.lineTo(cx + 8 * d, cy + r - 4 * d); path.close()
            canvas.drawPath(path, fill)
        }
    }
}
