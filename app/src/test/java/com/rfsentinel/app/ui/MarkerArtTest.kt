package com.rfsentinel.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The position marker: every style in every colour draws, points up, and can be told apart. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class MarkerArtTest {

    private val blue = MarkerArt.colorOf("blue").argb

    private fun count(b: Bitmap, rows: IntRange, match: (Int) -> Boolean): Int {
        var n = 0
        for (y in rows) for (x in 0 until b.width) if (match(b.getPixel(x, y))) n++
        return n
    }

    private fun opaque(b: Bitmap) = count(b, 0 until b.height) { Color.alpha(it) > 200 }

    @Test
    fun everyStyleInEveryColourDrawsAtTheSizeAsked() {
        for (s in MarkerArt.Style.entries) for (c in MarkerArt.colors) {
            val b = MarkerArt.render(s, c.argb, 120)
            assertEquals("${s.key}/${c.key}", 120, b.height); assertEquals(60, b.width)
            assertTrue("${s.key}/${c.key} has something drawn", opaque(b) > 600)
            assertEquals("${s.key}: the corners are clear", 0, Color.alpha(b.getPixel(0, 0)))
            assertEquals(0, Color.alpha(b.getPixel(b.width - 1, b.height - 1)))
        }
    }

    @Test
    fun theFrontIsAtTheTopOfEveryCarAndTheBackAtTheBottom() {
        fun headlight(p: Int) = Color.red(p) > 235 && Color.green(p) > 225 && Color.blue(p) in 150..215 && Color.alpha(p) > 200
        fun tail(p: Int) = Color.red(p) in 180..230 && Color.green(p) < 80 && Color.blue(p) < 80 && Color.alpha(p) > 200
        for (s in MarkerArt.Style.entries.filter { it !in listOf(MarkerArt.Style.DOT, MarkerArt.Style.ARROW, MarkerArt.Style.ARROW_3D, MarkerArt.Style.CHEVRON) }) {
            val b = MarkerArt.render(s, blue, 300)
            val top = 0 until b.height / 4; val bottom = b.height * 3 / 4 until b.height
            assertTrue("${s.key}: headlights at the front", count(b, top, ::headlight) > 20)
            assertEquals("${s.key}: none at the back", 0, count(b, bottom, ::headlight))
            assertTrue("${s.key}: tail lights at the back", count(b, bottom, ::tail) > 20)
            assertEquals("${s.key}: none at the front", 0, count(b, top, ::tail))
        }
    }

    @Test
    fun anArrowPointsUp() {
        for (s in listOf(MarkerArt.Style.ARROW, MarkerArt.Style.ARROW_3D, MarkerArt.Style.CHEVRON)) {
            val b = MarkerArt.render(s, blue, 200)
            // The tip is at the top, in the middle; the top corners are empty and the base is wide.
            assertTrue(Color.alpha(b.getPixel(b.width / 2, 40)) > 200)
            assertEquals(0, Color.alpha(b.getPixel(4, 40)))
            assertTrue("wider at the back than at the tip", count(b, 150..150) { Color.alpha(it) > 200 } > count(b, 40..40) { Color.alpha(it) > 200 })
        }
    }

    @Test
    fun theClassicDotSitsOnYourPositionAndItsBeamPointsAhead() {
        val b = MarkerArt.render(MarkerArt.Style.DOT, blue, 200)
        val mid = b.getPixel(b.width / 2, b.height / 2)
        assertTrue("a solid blue dot in the middle", Color.alpha(mid) > 240 && Color.blue(mid) > Color.red(mid) + 80)
        assertTrue("a white ring around it", Color.red(b.getPixel(b.width / 2, b.height / 2 - 27)) > 240)
        val ahead = Color.alpha(b.getPixel(b.width / 2, 55))
        assertTrue("a see-through beam ahead", ahead in 10..200)
        assertEquals("nothing behind", 0, Color.alpha(b.getPixel(b.width / 2, 170)))
    }

    @Test
    fun theColourChangesTheMarker() {
        val a = MarkerArt.render(MarkerArt.Style.SEDAN, MarkerArt.colorOf("red").argb, 200)
        val b = MarkerArt.render(MarkerArt.Style.SEDAN, MarkerArt.colorOf("green").argb, 200)
        val side = a.width / 2 - 14 // on the body, beside the cabin
        assertNotEquals(a.getPixel(side, 100), b.getPixel(side, 100))
        assertTrue("red is red", Color.red(a.getPixel(side, 100)) > Color.green(a.getPixel(side, 100)) + 60)
        assertTrue("green is green", Color.green(b.getPixel(side, 100)) > Color.red(b.getPixel(side, 100)) + 30)
    }

    @Test
    fun theStylesLookDifferent() {
        val seen = HashSet<Int>()
        for (s in MarkerArt.Style.entries) {
            val b = MarkerArt.render(s, blue, 100)
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            assertTrue("${s.key} differs from the others", seen.add(px.contentHashCode()))
        }
        assertEquals(17, MarkerArt.Style.entries.size)
        assertEquals(16, MarkerArt.colors.size)
    }

    @Test
    fun keysFindTheirStyleAndColourAndFallBackToTheDefaults() {
        assertEquals(MarkerArt.Style.SPORTS, MarkerArt.Style.of("sports"))
        assertEquals(MarkerArt.Style.ARROW, MarkerArt.Style.of("something else"))
        assertEquals(MarkerArt.Style.ARROW, MarkerArt.Style.of(null))
        assertEquals("blue", MarkerArt.colorOf("nope").key)
        assertEquals("pink", MarkerArt.colorOf("pink").key)
        assertEquals(MarkerArt.Style.entries.size, MarkerArt.Style.entries.map { it.key }.toSet().size)
        assertEquals(MarkerArt.colors.size, MarkerArt.colors.map { it.key }.toSet().size)
    }

    @Test
    fun theChoiceIsSavedAndTheSizeStaysInRange() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("arrow", Prefs.markerStyle(ctx)); assertEquals("blue", Prefs.markerColor(ctx)); assertEquals(1.0f, Prefs.markerSize(ctx), 0f)
        Prefs.setMarker(ctx, "taxi", "yellow", 1.4f)
        assertEquals("taxi", Prefs.markerStyle(ctx)); assertEquals("yellow", Prefs.markerColor(ctx)); assertEquals(1.4f, Prefs.markerSize(ctx), 1e-6f)
        Prefs.setMarker(ctx, "taxi", "yellow", 9f)
        assertEquals(2.0f, Prefs.markerSize(ctx), 0f)
        Prefs.setMarker(ctx, "taxi", "yellow", 0.1f)
        assertEquals(0.6f, Prefs.markerSize(ctx), 0f)
        Prefs.setMarker(ctx, "taxi", "yellow", 1.0f)
        val h = MarkerPicker.current(ctx).height
        assertEquals(MarkerPicker.heightPx(ctx, 1.0f), h)
        assertTrue(MarkerPicker.heightPx(ctx, 2.0f) > MarkerPicker.heightPx(ctx, 1.0f))
        assertEquals("Taxi · Yellow", MarkerPicker.summary(ctx))
    }
}
