package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.sdr.SdrWaterfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The radio waterfall on made-up rows: colours per chunk, gaps for no reading, tap, pinch, drag, screen reader. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class WaterfallViewTest {

    private val w = 800
    private val h = 600
    private var tapped: Long? = null

    private fun view() = WaterfallView(ApplicationProvider.getApplicationContext<Context>()).apply {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
        onTap = { tapped = it }
    }

    private fun seg(start: Long, noise: Float, bins: Int = 801, at: (Int) -> Float = { 0f }) =
        SdrWaterfall.Segment(start, start + 1_600_000L, FloatArray(bins) { noise + (it % 3) * 0.5f + at(it) }, null)

    private fun draw(v: WaterfallView) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    private fun brightness(c: Int) = Color.red(c) + Color.green(c) + Color.blue(c)

    // ---- the sweep's colours ----

    @Test fun eachChunkIsColouredAgainstItsOwnNoise() {
        val v = view()
        // A noisy VHF chunk next to a quiet UHF one, each with one signal 30 dB up in its middle.
        val sig = { k: Int -> if (k in 395..405) 30f else 0f }
        repeat(150) { v.add(SdrWaterfall.Row(listOf(seg(150_000_000L, -45f, at = sig), seg(151_600_000L, -62f, at = sig)), tuned = false, at = 0)) }
        val bmp = draw(v)
        val y = h - 10
        val vhfNoise = brightness(bmp.getPixel(w / 8, y)); val uhfNoise = brightness(bmp.getPixel(w * 7 / 8 - 20, y))
        // One scale for both made VHF glow 17 dB brighter than UHF; now both are noise-dark alike.
        assertTrue("VHF $vhfNoise vs UHF $uhfNoise", abs(vhfNoise - uhfNoise) < 60)
        // The signal still stands out in both.
        assertTrue(brightness(bmp.getPixel(w / 4, y)) > vhfNoise + 150)
        assertTrue(brightness(bmp.getPixel(w * 3 / 4, y)) > uhfNoise + 150)
    }

    @Test fun aChunkWithNoReadingIsAGapNotAFlatLine() {
        val v = view()
        val dead = SdrWaterfall.Segment(151_600_000L, 153_200_000L, FloatArray(801) { SdrWaterfall.NO_DATA }, null)
        repeat(150) { v.add(SdrWaterfall.Row(listOf(seg(150_000_000L, -50f), dead, seg(153_200_000L, -50f)), tuned = false, at = 0)) }
        val bmp = draw(v)
        // Waterfall: black under the dead chunk.
        assertEquals(Color.BLACK, bmp.getPixel(w / 2, h - 10))
        // Spectrum on top: nothing drawn over the dead chunk (it drew a flat line along the bottom).
        val top = (h * 0.28f).toInt()
        for (x in w / 3 + 10 until w * 2 / 3 - 10) for (y in 0 until top - 2) assertEquals("x $x y $y", 0, Color.alpha(bmp.getPixel(x, y)))
        // ...but over the live chunks it is.
        assertTrue((0 until top).any { Color.alpha(bmp.getPixel(w / 6, it)) > 0 })
    }

    // ---- touch ----

    private val start = 100_000_000L
    private val span = 2_048_000L
    private fun tunedRow(center: Long = start + span / 2, spikeAt: Int = -1) =
        SdrWaterfall.Row(listOf(SdrWaterfall.Segment(center - span / 2, center + span / 2,
            FloatArray(1024) { if (it == spikeAt) -20f else -60f + (it % 5) }, null)), tuned = true, at = 0)
    private fun hzAt(x: Float) = start + (span * (x + 0.5) / w).toLong()

    private var t0 = 0L
    private fun ev(action: Int, vararg xs: Float, index: Int = 0): MotionEvent {
        val props = Array(xs.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(xs.size) { MotionEvent.PointerCoords().apply { x = xs[it]; y = 400f; pressure = 1f; size = 1f } }
        val a = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        t0 += 16
        return MotionEvent.obtain(1000L, 1000L + t0, a, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0x1002, 0)
    }
    private fun tap(v: WaterfallView, x: Float): Long? {
        tapped = null
        v.onTouchEvent(ev(MotionEvent.ACTION_DOWN, x)); v.onTouchEvent(ev(MotionEvent.ACTION_UP, x))
        return tapped
    }
    /** Two fingers around [cx], [from] to [to] pixels apart. */
    private fun pinch(v: WaterfallView, cx: Float, from: Float, to: Float) {
        v.onTouchEvent(ev(MotionEvent.ACTION_DOWN, cx - from / 2))
        v.onTouchEvent(ev(MotionEvent.ACTION_POINTER_DOWN, cx - from / 2, cx + from / 2, index = 1))
        for (i in 1..10) { val d = from + (to - from) * i / 10; v.onTouchEvent(ev(MotionEvent.ACTION_MOVE, cx - d / 2, cx + d / 2)) }
        v.onTouchEvent(ev(MotionEvent.ACTION_POINTER_UP, cx - to / 2, cx + to / 2, index = 1))
        v.onTouchEvent(ev(MotionEvent.ACTION_UP, cx - to / 2))
    }

    @Test fun aTapTunesToTheFrequencyUnderTheFinger() {
        val v = view(); v.add(tunedRow())
        assertEquals(hzAt(400f).toDouble(), tap(v, 400f)!!.toDouble(), 3_000.0)
    }

    @Test fun pinchZoomsAroundTheFingersAndNeverTaps() {
        val v = view(); v.add(tunedRow())
        tapped = null
        pinch(v, 200f, 180f, 700f)
        assertNull("a pinch is not a tap", tapped)
        // The frequency under the fingers stayed under them...
        assertEquals(hzAt(200f).toDouble(), tap(v, 200f)!!.toDouble(), 5_000.0)
        // ...and the screen now shows less: its right edge is much nearer than the whole span.
        val edge = tap(v, w - 1f)!!
        assertTrue("edge ${edge - start}", edge - hzAt(200f) < (hzAt(w - 1f) - hzAt(200f)) / 2)
    }

    @Test fun dragMovesTheZoomedPictureAndPinchingOutShowsAll() {
        val v = view(); v.add(tunedRow())
        pinch(v, 400f, 180f, 700f)
        val before = tap(v, 400f)!!
        // One finger dragged right: the picture follows, so the middle now shows a lower frequency.
        v.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 300f))
        for (x in 310..500 step 10) v.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x.toFloat()))
        tapped = null; v.onTouchEvent(ev(MotionEvent.ACTION_UP, 500f))
        assertNull("a drag is not a tap", tapped)
        assertTrue(tap(v, 400f)!! < before - 10_000)
        // Pinching all the way out: the whole span again.
        pinch(v, 400f, 500f, 40f); pinch(v, 400f, 500f, 40f)
        assertEquals(hzAt(0f).toDouble(), tap(v, 0f)!!.toDouble(), 3_000.0)
    }

    @Test fun tuningNearbyKeepsTheZoomCentred() {
        val v = view(); v.add(tunedRow())
        pinch(v, 400f, 180f, 700f)
        val moved = start + span / 2 + 300_000L
        v.add(tunedRow(center = moved))
        assertEquals(moved.toDouble(), tap(v, w / 2f)!!.toDouble(), 5_000.0)
        assertTrue("still zoomed in", tap(v, w - 1f)!! - moved < span / 4)
    }

    // ---- screen reader ----

    @Test fun screenReaderDoubleTapTunesToTheStrongestSignal() {
        val v = view(); v.add(tunedRow(spikeAt = 700))
        val node = AccessibilityNodeInfo().also { v.onInitializeAccessibilityNodeInfo(it) }
        assertNotNull(node.actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label == "zoom in on the strongest signal" })
        tapped = null
        assertTrue(v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals((start + span * 700 / 1024).toDouble(), tapped!!.toDouble(), 5_000.0)
    }
}
