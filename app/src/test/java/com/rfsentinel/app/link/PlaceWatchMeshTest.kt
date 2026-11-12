package com.rfsentinel.app.link

import android.app.Application
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.PlaceWatch
import com.rfsentinel.app.util.Spoken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Watch this place from afar: a "New device" match crosses the mesh first and reads the same on the other phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaceWatchMeshTest {

    private val place = Hit(Category.CUSTOM, "New device: Galaxy Watch6 Classic (47mm)", 70, "stayed 10 min", "Watch this place on Pixel")
    private val cam = Hit(Category.BODY_CAM, "Axon body camera", 80, "x", "y")

    @Test
    fun aNewDeviceAtTheWatchedPlaceGoesOutFirstAndReadsTheSameOverThere() {
        val box = MeshOutbox()
        fun adv(mac: String) = Advert(mac, Advert.Source.BLE, -60, timestamp = 0)
        box.add(adv("00:00:00:00:00:01"), emptyList(), 1_000)
        box.add(adv("00:00:00:00:00:02"), listOf(cam), 1_000)
        box.add(adv("00:00:00:00:00:03"), listOf(place), 1_000)
        assertTrue(box.urgent(1_000))
        val out = box.take(2_000, MeshCodec.MAX_BODY)
        assertEquals("00:00:00:00:00:03", out.first().mac)

        val key = MeshCodec.messageKey(LinkCrypto.random(32))
        val m = MeshCodec.open(key, MeshCodec.seal(key, 9L, 1, 1_700_000_000_000L, Double.NaN, Double.NaN, "Pixel", out))!!
        val hit = MeshCodec.toBatch(m).sightings.first { it.advert.mac == "00:00:00:00:00:03" }.hits.single()
        assertEquals(Category.CUSTOM, hit.category)
        assertTrue(hit.label, hit.label.startsWith("New device: Galaxy"))
        assertTrue(hit.label.toByteArray().size <= 32)
        assertEquals("New device", Spoken.shortWord(hit))
        assertEquals("Watchlist", Spoken.shortWord(cam.copy(category = Category.CUSTOM, label = "My tag")))
    }

    private val min = 60_000L
    private fun dev(mac: String, t: Long) = PlaceWatch.Device(mac, false, -60, t, "BLE")

    /** Learns [w] with nothing around, then returns when the watch starts. */
    private fun learned(w: PlaceWatch): Long {
        var t = 0L
        while (t < PlaceWatch.LEARN_MS) { w.check(t, null, emptyList(), { null }); t += 10_000 }
        return t
    }

    @Test
    fun aBreakOfMoreThanThreeMinutesStartsTheStayOver() {
        val w = PlaceWatch(); w.start(0L, null)
        var t = learned(w)
        val out = ArrayList<PlaceWatch.Event>()
        // 6 min, gone 4, back 6: never 10 min in a row.
        val s = t
        while (t < s + 16 * min) {
            val there = t - s < 6 * min || t - s >= 10 * min
            out += w.check(t, null, if (there) listOf(dev("V", t)) else emptyList(), { null }); t += 10_000
        }
        assertTrue(out.isEmpty())
        // Staying on: it is reported once its new stay reaches 10 min.
        while (t < s + 21 * min) { out += w.check(t, null, listOf(dev("V", t)), { null }); t += 10_000 }
        assertEquals(listOf("V"), out.map { it.mac })
    }

    @Test
    fun aRestartKeepsWhatItLearnedAndWhatItReported() {
        val home = 45.0 to -73.0
        val w = PlaceWatch(); w.start(0L, home)
        var t = 0L
        while (t < PlaceWatch.LEARN_MS) { w.check(t, home, listOf(dev("TV", t)), { null }); t += 10_000 }
        val s = t
        val out = ArrayList<PlaceWatch.Event>()
        while (t < s + 11 * min) { out += w.check(t, home, listOf(dev("TV", t), dev("V", t)), { null }); t += 10_000 }
        assertEquals(listOf("V"), out.map { it.mac })
        val kept = w.save()

        // The app comes back 2 hours later: no learning again, V not new again, the TV not "gone" for the time it was down.
        t += 120 * min
        val r = PlaceWatch()
        assertTrue(r.restore(kept, t))
        assertTrue(r.on && !r.learning(t) && r.place == home)
        out.clear()
        val s2 = t
        while (t < s2 + 11 * min) { out += r.check(t, home, listOf(dev("TV", t), dev("V", t), dev("X", t)), { null }); t += 10_000 }
        assertEquals(listOf("X"), out.map { it.mac })
        assertTrue(r.restore(PlaceWatch().also { it.start(5L, null) }.save(), 10L))
        assertTrue(r.learning(10L) && r.place == null)

        // Not a saved watch: off.
        assertTrue(!r.restore("garbage", t) && !r.on)
    }

    @Test
    fun turningItOffAndOnLearnsAgain() {
        val w = PlaceWatch(); w.start(0L, null)
        var t = learned(w)
        val s = t
        val out = ArrayList<PlaceWatch.Event>()
        while (t < s + 11 * min) { out += w.check(t, null, listOf(dev("V", t)), { null }); t += 10_000 }
        assertEquals(1, out.size)
        w.stop()
        assertTrue(w.check(t, null, listOf(dev("V", t)), { null }).isEmpty()) // off: nothing
        // On again with it there: learned, never new.
        w.start(t, null)
        val s2 = t
        while (t < s2 + 30 * min) { assertTrue(w.check(t, null, listOf(dev("V", t)), { null }).isEmpty()); t += 10_000 }
    }
}
