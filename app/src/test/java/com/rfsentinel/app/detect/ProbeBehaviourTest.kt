package com.rfsentinel.app.detect

import com.rfsentinel.app.usb.MonitorFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Non-stop wildcard probing (FlipDeFlock): a supporting clue for a fixed camera, never a match alone. */
class ProbeBehaviourTest {

    private fun counted(vararg at: Long) = ProbeBehaviour.Counter().apply { at.forEach { probe(it) } }.run()!!

    @Test
    fun countsTheWindowsThatHeldAProbe() {
        // Probes every 5 s for 3 minutes, as a pole camera does; a few missed while the adapter hopped.
        val times = (0L..180_000L step 5_000L).filter { it % 35_000L != 0L }
        val run = counted(*times.toLongArray())
        assertEquals(times.size, run.probes)
        assertTrue(run.coverage.toString(), run.coverage > 0.8)
        assertTrue(run.nonStop)
        assertTrue(run.describe(), run.describe().contains("per 8 s") && run.describe().contains("2 min"))
    }

    @Test
    fun aPhoneThatProbesInBurstsIsNotNonStop() {
        // A burst every 40 s: the run goes on, but most 8 s windows stay empty.
        val times = (0L..240_000L step 40_000L).flatMap { listOf(it, it + 200, it + 400) }
        val run = counted(*times.toLongArray())
        assertFalse(run.coverage.toString(), run.nonStop)
    }

    @Test
    fun aLongSilenceStartsANewRun() {
        val run = counted(0L, 5_000L, 10_000L, 10_000L + ProbeBehaviour.BREAK_MS + 1, 10_000L + ProbeBehaviour.BREAK_MS + 2_000)
        assertEquals(2, run.probes)
        assertFalse("a short run is never non-stop", run.nonStop)
    }

    private fun camera(mac: String, run: ProbeBehaviour.Run?, at: Long) = Advert(mac = mac, source = Advert.Source.WIFI, rssi = -70,
        wifi = Advert.WifiInfo(2437, "", null, emptyList(), client = true, wildcardProbe = true, probeRun = run), timestamp = at)

    private val flock = Hit(Category.ALPR, "Possible Flock camera (probe request)", 60, "module", "test")
    private val steady = ProbeBehaviour.Run(probes = 60, firstAt = 0L, lastAt = 180_000L, windows = 22)

    @Test
    fun nonStopProbingBacksUpAFixedCameraMatch() {
        val a = camera("24:B2:B9:33:44:55", steady, 181_000L)
        val support = ProbeBehaviour.supportHit(a, listOf(flock))
        assertNotNull(support)
        assertEquals(Category.ALPR, support!!.category)
        assertEquals(ProbeBehaviour.CONFIDENCE, support.confidence)
        val fused = EvidenceFusion.fuse(listOf(flock, support)).first()
        assertTrue("${fused.confidence}", fused.confidence > flock.confidence)
    }

    @Test
    fun itIsNeverAMatchAlone() {
        val a = camera("24:B2:B9:33:44:55", steady, 181_000L)
        assertNull("no camera match to back up", ProbeBehaviour.supportHit(a, emptyList()))
        assertNull("a tracker isn't a fixed device", ProbeBehaviour.supportHit(a, listOf(flock.copy(category = Category.TRACKER))))
        assertNull("random addresses: a phone", ProbeBehaviour.supportHit(camera("DA:11:22:33:44:55", steady, 181_000L), listOf(flock)))
        assertNull("the run ended long ago", ProbeBehaviour.supportHit(camera("24:B2:B9:33:44:55", steady, 600_000L), listOf(flock)))
        assertNull("not steady", ProbeBehaviour.supportHit(camera("24:B2:B9:33:44:55", steady.copy(windows = 4), 181_000L), listOf(flock)))
    }

    @Test
    fun theAdapterReportsHowSteadilyAClientProbes() {
        val f = MonitorFrames()
        // A wildcard probe request with extended rates and a vendor element (not a bare scanner probe).
        val ies = byteArrayOf(0, 0, 1, 4, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(),
            0x32, 4, 0x0C, 0x12, 0x18, 0x60, 0xDD.toByte(), 7, 0x50, 0x6F, 0x9A.toByte(), 0x16, 3, 1, 3)
        val frame = ByteArray(24).also {
            it[0] = 0x40
            byteArrayOf(0x24, 0xB2.toByte(), 0xB9.toByte(), 0x33, 0x44, 0x55).copyInto(it, 10)
        } + ies
        for (t in 0L..150_000L step 4_000L) f.frame(frame, 0, frame.size, 6, -60, now = t)
        val s = f.drain(150_000L, 10_000L).single()
        val run = s.probeRun!!
        assertTrue(s.wildcardProbe)
        assertEquals(38, run.probes)
        assertTrue(run.nonStop)
    }
}
