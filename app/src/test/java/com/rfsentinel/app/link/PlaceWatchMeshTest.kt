package com.rfsentinel.app.link

import android.app.Application
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.PlaceWatch
import com.rfsentinel.app.util.Spoken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Watch this place from afar: its reports cross the mesh and read the same on the other phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaceWatchMeshTest {

    private val place = Hit(Category.CUSTOM, "New device: Galaxy Watch6 Classic (47mm)", 70, "stayed", "Watch this place on Pixel")
    private val cam = Hit(Category.BODY_CAM, "Axon body camera", 80, "x", "y")
    private val key = MeshCodec.messageKey(LinkCrypto.random(32))

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

        val m = MeshCodec.open(key, MeshCodec.seal(key, 9L, 1, 1_700_000_000_000L, Double.NaN, Double.NaN, "Pixel", out))!!
        val hit = MeshCodec.toBatch(m).sightings.first { it.advert.mac == "00:00:00:00:00:03" }.hits.single()
        assertEquals(Category.CUSTOM, hit.category)
        assertTrue(hit.label, hit.label.startsWith("New device: Galaxy"))
        assertTrue(hit.label.toByteArray().size <= 32)
        assertEquals("New device", Spoken.shortWord(hit))
        assertEquals("Device back", Spoken.shortWord(hit.copy(label = "Back: TV")))
        assertEquals("Device moving", Spoken.shortWord(hit.copy(label = "Moving: Tag")))
        assertEquals("Watchlist", Spoken.shortWord(cam.copy(category = Category.CUSTOM, label = "My tag")))
    }

    @Test
    fun placeReportsRoundTripInOnePacketAndOlderVersionsAreUntouched() {
        val notes = listOf(
            MeshCodec.PlaceNote(MeshCodec.PLACE_GONE, "AA:BB:CC:00:11:22", -55, 130, "Living room TV"),
            MeshCodec.PlaceNote(MeshCodec.PLACE_BACK, "AA:BB:CC:00:11:23", -48, 240, null),
            MeshCodec.PlaceNote(MeshCodec.PLACE_MOVED, "AA:BB:CC:00:11:24", -58, -35, "A name much longer than thirty-two bytes, cut"),
            MeshCodec.PlaceNote(MeshCodec.PLACE_STATUS, null, 0, 0, "Watching, 23 devices learned"),
        )
        val sealed = MeshCodec.sealPlace(key, 7L, 3, 1_700_000_000_000L, 10.5, -20.25, "Pixel", notes)
        assertTrue(sealed.size <= MeshProto.MAX_PAYLOAD)
        val m = MeshCodec.open(key, sealed)!!
        assertEquals("Pixel", m.name)
        assertTrue(m.devices.isEmpty() && m.hardware == null)
        val back = m.place!!
        assertEquals(notes.map { it.kind }, back.map { it.kind })
        assertEquals(notes.map { it.mac }, back.map { it.mac })
        assertEquals(listOf(-55, -48, -58, 0), back.map { it.rssi })
        assertEquals(listOf(130, 240, -35, 0), back.map { it.value })
        assertEquals("Living room TV", back[0].text)
        assertNull(back[1].text)
        assertTrue(back[2].text!!.toByteArray().size <= 32)
        assertEquals("Watching, 23 devices learned", back[3].text)
        // Another group can't read it; a device packet isn't a place packet.
        assertNull(MeshCodec.open(MeshCodec.messageKey(LinkCrypto.random(32)), sealed))
        assertNull(MeshCodec.open(key, MeshCodec.seal(key, 7L, 4, 1_700_000_000_000L, Double.NaN, Double.NaN, null, emptyList()))!!.place)
    }

    @Test
    fun eachWaySendsEachReportOnceWhileFreshUrgentOnesFirstToBeAsked() {
        val box = PlaceOutbox()
        val gone = MeshCodec.PlaceNote(MeshCodec.PLACE_GONE, "AA:BB:CC:00:11:22", -55, 130, "TV")
        val moved = MeshCodec.PlaceNote(MeshCodec.PLACE_MOVED, "AA:BB:CC:00:11:24", -58, -35, null)
        box.add(gone, false, 0L)
        assertFalse(box.urgent(PlaceOutbox.MESH, 0L))
        box.add(moved, true, 0L)
        box.add(MeshCodec.PlaceNote(MeshCodec.PLACE_GONE, "not an address", 0, 0, null), false, 0L) // can't go: dropped
        assertTrue(box.urgent(PlaceOutbox.MESH, 0L))
        assertEquals(listOf(gone, moved), box.take(PlaceOutbox.MESH, MeshCodec.MAX_BODY, 1_000L))
        assertFalse(box.waiting(PlaceOutbox.MESH, 1_000L))
        // Bluetooth still has them; room for one only: the oldest.
        assertEquals(listOf(gone), box.take(PlaceOutbox.BLUETOOTH, MeshCodec.size(gone)!!, 1_000L))
        assertEquals(listOf(moved), box.take(PlaceOutbox.BLUETOOTH, MeshCodec.MAX_BODY, 1_000L))
        // Too old for the internet relay that came on late.
        assertFalse(box.waiting(PlaceOutbox.NET, PlaceOutbox.KEEP_MS + 1))
    }

    private val min = 60_000L
    private fun dev(mac: String, t: Long) = PlaceWatch.Device(mac, -50, t, "BLE")

    @Test
    fun aRestartKeepsWhatItLearnedAndWhatItReported() {
        val home = 45.0 to -73.0
        val w = PlaceWatch(); w.start(0L, home)
        var t = 0L
        while (t < PlaceWatch.LEARN_MS) { w.check(t, home, listOf(dev("TV", t)), { null }); t += 10_000 }
        val out = ArrayList<PlaceWatch.Event>()
        repeat(3) { out += w.check(t, home, listOf(dev("TV", t), dev("V", t)), { null }); t += 10_000 }
        assertEquals(listOf("V"), out.map { it.mac })
        val kept = w.save()

        // The app comes back 2 hours later: no learning again, V not new again.
        t += 120 * min
        val r = PlaceWatch()
        assertTrue(r.restore(kept, t))
        assertTrue(r.on && !r.learning(t) && r.place == home)
        out.clear()
        repeat(3) { out += r.check(t, home, listOf(dev("TV", t), dev("V", t), dev("X", t)), { null }); t += 10_000 }
        assertEquals(listOf("X"), out.map { it.mac })
        // Saved while learning: still learning after.
        assertTrue(r.restore(PlaceWatch().also { it.start(5L, null) }.save(), 10L))
        assertTrue(r.learning(10L) && r.place == null)
        // Not a saved watch, or the old format: off.
        assertTrue(!r.restore("garbage", t) && !r.on)
        assertTrue(!r.restore("1\n\nA B\nC=BLE\n\n", t) && !r.on)
    }

    @Test
    fun turningItOffAndOnLearnsAgain() {
        val w = PlaceWatch(); w.start(0L, null)
        var t = 0L
        while (t < PlaceWatch.LEARN_MS) { w.check(t, null, emptyList(), { null }); t += 10_000 }
        val out = ArrayList<PlaceWatch.Event>()
        repeat(3) { out += w.check(t, null, listOf(dev("V", t)), { null }); t += 10_000 }
        assertEquals(1, out.size)
        w.stop()
        assertTrue(w.check(t, null, listOf(dev("V", t)), { null }).isEmpty()) // off: nothing
        // On again with it there: learned, never new.
        w.start(t, null)
        val s2 = t
        while (t < s2 + 30 * min) { assertTrue(w.check(t, null, listOf(dev("V", t)), { null }).isEmpty()); t += 10_000 }
    }

    @Test
    fun theSettingsChangeWhatCountsAndAreKeptInBounds() {
        val c = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        assertEquals(PlaceWatch.Settings(), com.rfsentinel.app.util.Prefs.placeWatchSettings(c)) // AntiHunter's by default
        com.rfsentinel.app.util.Prefs.setPlaceWatchValue(c, "learn_min", 1)
        com.rfsentinel.app.util.Prefs.setPlaceWatchValue(c, "min_rssi", -80)
        com.rfsentinel.app.util.Prefs.setPlaceWatchValue(c, "jumps", 1)
        com.rfsentinel.app.util.Prefs.setPlaceWatchValue(c, "radius_m", 999_999) // too far: the highest
        com.rfsentinel.app.util.Prefs.setPlaceWatchValue(c, "gone_min", null) // blank: the default
        val s = com.rfsentinel.app.util.Prefs.placeWatchSettings(c)
        assertEquals(PlaceWatch.Settings(learnMs = min, minRssi = -80, movedAfter = 1, radiusM = 20_000.0), s)

        val w = PlaceWatch(); w.settings = s; w.start(0L, null)
        var t = 0L
        while (t < min) { w.check(t, null, listOf(dev("TV", t)), { null }); t += 10_000 }
        assertFalse(w.learning(t))
        // A -75 dBm device now counts, and one jump of 20 dB is enough.
        val out = ArrayList<PlaceWatch.Event>()
        repeat(2) { out += w.check(t, null, listOf(dev("TV", t), PlaceWatch.Device("FAR", -75, t)), { null }); t += 10_000 }
        out += w.check(t, null, listOf(PlaceWatch.Device("TV", -20, t)), { null })
        assertEquals(listOf(PlaceWatch.Kind.NEW, PlaceWatch.Kind.MOVED), out.map { it.kind })
    }
}
