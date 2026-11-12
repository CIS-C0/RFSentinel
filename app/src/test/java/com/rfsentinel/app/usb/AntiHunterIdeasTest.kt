package com.rfsentinel.app.usb

import com.rfsentinel.app.data.ProbeBook
import com.rfsentinel.app.service.PlaceWatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ideas from AntiHunter (the ideas, not code): the deauth flood's tool named from its frames, a random WiFi address linked to
 * the one before by its frame count, and Watch this place.
 */
class AntiHunterIdeasTest {

    @Before
    fun setUp() { DeauthWatch.clear(); DeauthWatch.onAttack = null }

    @After
    fun tearDown() = setUp()

    private fun mac(s: String) = s.split(":").map { it.toInt(16).toByte() }.toByteArray()

    /** A deauth (0xC0) or disassoc (0xA0) from [from] to [to] on network [bssid] with [reason]. */
    private fun kick(disassoc: Boolean, to: String, from: String, bssid: String, reason: Int): ByteArray {
        val f = ByteArray(26); f[0] = (if (disassoc) 0xA0 else 0xC0).toByte()
        mac(to).copyInto(f, 4); mac(from).copyInto(f, 10); mac(bssid).copyInto(f, 16)
        f[24] = reason.toByte(); f[25] = (reason shr 8).toByte()
        return f
    }

    private val ap = "02:00:00:00:00:aa"
    private val phone = "02:00:00:00:00:01"

    private fun flood(frames: List<ByteArray>): DeauthWatch.Attack? {
        val mf = MonitorFrames()
        var t = 0L; var alarm: DeauthWatch.Attack? = null
        DeauthWatch.onAttack = { alarm = alarm ?: it }
        repeat(40) { frames.forEach { f -> mf.frame(f, 0, f.size, 6, -50, now = t); t += 20 } }
        return alarm
    }

    @Test
    fun aFloodsToolIsNamedFromItsFrames() {
        val aireplay = flood(listOf(kick(false, phone, ap, ap, 7), kick(false, ap, phone, ap, 7)))
        assertEquals(DeauthWatch.Tool.AIREPLAY, aireplay?.tool)
        DeauthWatch.clear()
        val ghost = flood(listOf(kick(false, phone, ap, ap, 7), kick(true, phone, ap, ap, 7)))
        assertEquals(DeauthWatch.Tool.GHOST_ESP, ghost?.tool)
        DeauthWatch.clear()
        assertEquals(DeauthWatch.Tool.MARAUDER, flood(listOf(kick(false, "ff:ff:ff:ff:ff:ff", ap, ap, 2)))?.tool)
        DeauthWatch.clear()
        assertEquals(DeauthWatch.Tool.ESP8266_DEAUTHER, flood(listOf(kick(false, phone, ap, ap, 1), kick(true, phone, ap, ap, 1)))?.tool)
        DeauthWatch.clear()
        // MDK4: both ways, both kinds, "leaving" from the device and "unspecified" / "AP full" from the access point.
        val mdk4 = flood(listOf(kick(false, ap, phone, ap, 3), kick(true, ap, phone, ap, 8), kick(false, phone, ap, ap, 1), kick(true, phone, ap, ap, 5)))
        assertEquals(DeauthWatch.Tool.MDK4, mdk4?.tool)
    }

    @Test
    fun noToolWhenTheFramesMatchNone() {
        DeauthWatch.clear()
        // A mix no tool sends, or too few frames to say.
        assertNull(DeauthWatch.toolOf(List(20) { DeauthWatch.Frame(0L, false, it % 2 == 0, if (it % 3 == 0) 7 else 4) }))
        assertNull(DeauthWatch.toolOf(List(5) { DeauthWatch.Frame(0L, false, false, 7) }))
        assertNull(DeauthWatch.toolOf(List(20) { DeauthWatch.Frame(0L, false, false, -1) }))
        // Only reason 1, no disassociation: could be anything.
        assertNull(DeauthWatch.toolOf(List(20) { DeauthWatch.Frame(0L, false, false, 1) }))
    }

    /** A wildcard probe request from [from] with 802.11 sequence number [seq]. */
    private val ies = byteArrayOf(0, 0, 1, 4, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(),
        0x32, 4, 0x0C, 0x12, 0x18, 0x60, 0x2D, 2, 0x11, 0x22)
    private fun probe(from: String, seq: Int, body: ByteArray = ies): ByteArray {
        val f = ByteArray(24); f[0] = 0x40
        mac("ff:ff:ff:ff:ff:ff").copyInto(f, 4); mac(from).copyInto(f, 10); mac("ff:ff:ff:ff:ff:ff").copyInto(f, 16)
        f[22] = (seq shl 4).toByte(); f[23] = (seq shr 4).toByte()
        return f + body
    }

    @Test
    fun aRandomAddressIsLinkedToTheOneBeforeByItsFrameCount() {
        val mf = MonitorFrames()
        fun hear(from: String, seq: Int, t: Long) = probe(from, seq).let { mf.frame(it, 0, it.size, 6, -50, now = t) }
        hear("02:aa:00:00:00:01", 4094, 0L)
        hear("06:bb:00:00:00:02", 2, 5_000L) // the counter went round: 4094 -> 2
        val now = mf.drain(5_000L, 10_000L).associateBy { it.mac }
        assertEquals("02:aa:00:00:00:01", now["06:bb:00:00:00:02"]!!.previousMac)
        assertNull(now["02:aa:00:00:00:01"]!!.previousMac)
    }

    @Test
    fun noLinkOnAFarCountALongGapAnotherModelOrTwoCandidates() {
        fun linked(setup: (MonitorFrames) -> Unit): String? {
            val mf = MonitorFrames(); setup(mf)
            return mf.drain(60_000L, 120_000L).first { it.mac == "06:bb:00:00:00:02" }.previousMac
        }
        fun MonitorFrames.hear(from: String, seq: Int, t: Long, body: ByteArray = ies) = probe(from, seq, body).let { frame(it, 0, it.size, 6, -50, now = t) }
        assertNull(linked { it.hear("02:aa:00:00:00:01", 100, 0L); it.hear("06:bb:00:00:00:02", 400, 5_000L) })
        assertNull(linked { it.hear("02:aa:00:00:00:01", 100, 0L); it.hear("06:bb:00:00:00:02", 103, 45_000L) })
        assertNull(linked { it.hear("02:aa:00:00:00:01", 100, 0L); it.hear("06:bb:00:00:00:02", 103, 5_000L, ies + byteArrayOf(0x7F, 1, 4)) })
        assertNull(linked { it.hear("02:aa:00:00:00:01", 100, 0L); it.hear("0a:cc:00:00:00:03", 99, 0L); it.hear("06:bb:00:00:00:02", 103, 5_000L) })
        // A fixed (maker) address is never linked: it doesn't rotate.
        val mf = MonitorFrames()
        mf.hear("02:aa:00:00:00:01", 100, 0L); mf.hear("00:1b:00:00:00:02", 103, 5_000L)
        assertNull(mf.drain(5_000L, 10_000L).first { it.mac == "00:1b:00:00:00:02" }.previousMac)
    }

    @Test
    fun theRequestedNetworksListGroupsALinkedAddressWithItsEarlierOne() {
        val book = ProbeBook()
        book.recordDevice("02:AA:00:00:00:01", listOf("Home"), -60, 0L)
        book.recordDevice("06:BB:00:00:00:02", listOf("Work"), -60, 5_000L, linkedFrom = "02:aa:00:00:00:01")
        val g = book.groups({ true }).single()
        assertTrue(g.likelySame)
        assertEquals(2, g.devices.size)
    }

    // ---- Watch this place (AntiHunter's rules and numbers) ---------------------------------------------------------

    private val min = 60_000L
    private fun dev(mac: String, t: Long, rssi: Int = -50) = PlaceWatch.Device(mac, rssi, t, "BLE")

    /** Starts [w] at [home] and learns [around] (heard every 10 s) for its learning time; returns when the watch starts. */
    private fun learn(w: PlaceWatch, home: Pair<Double, Double>?, around: List<String>): Long {
        w.start(0L, home)
        var t = 0L
        while (t < PlaceWatch.LEARN_MS) { assertTrue(w.check(t, home, around.map { dev(it, t) }, { null }).isEmpty()); t += 10_000 }
        return t
    }

    @Test
    fun aNewDeviceHeardTwiceWithinAMinuteIsReportedOnceAndAWeakOneNever() {
        val w = PlaceWatch()
        var t = learn(w, null, listOf("TV"))
        assertEquals(5 * min, PlaceWatch.LEARN_MS)
        val out = ArrayList<PlaceWatch.Event>()
        val s = t
        while (t < s + 5 * min) { out += w.check(t, null, listOf(dev("TV", t), dev("V", t), dev("WEAK", t, rssi = -61)), { null }); t += 10_000 }
        assertEquals(listOf("V"), out.map { it.mac })
        assertEquals(PlaceWatch.Kind.NEW, out.single().kind)
        // The second look, 10 s after the first.
        assertEquals(10, out.single().value)
        assertEquals(1, w.reports)
    }

    @Test
    fun heardOnceThenAMinuteLaterIsntNew() {
        val w = PlaceWatch()
        var t = learn(w, null, emptyList())
        assertTrue(w.check(t, null, listOf(dev("V", t)), { null }).isEmpty())
        t += 70_000 // the window's past: this one counts as a first sighting again
        assertTrue(w.check(t, null, listOf(dev("V", t)), { null }).isEmpty())
        t += 10_000
        assertEquals(listOf(PlaceWatch.Kind.NEW), w.check(t, null, listOf(dev("V", t)), { null }).map { it.kind })
    }

    @Test
    fun aLinkedNewAddressIsntNewAndTheOneItLeftIsntGone() {
        val w = PlaceWatch()
        var t = learn(w, null, listOf("P0"))
        val out = ArrayList<PlaceWatch.Event>()
        val s = t
        // P0 heard 1 minute, then the phone goes on as P1, linked to P0.
        while (t < s + 6 * min) {
            val a = if (t - s < min) "P0" else "P1"
            out += w.check(t, null, listOf(dev(a, t)), { m -> if (m == "P1") "P0" else null }); t += 10_000
        }
        assertTrue(out.isEmpty())
        // An address linked to nothing is a new device, as in AntiHunter.
        out += w.check(t, null, listOf(dev("R", t))) { null }; t += 10_000
        out += w.check(t, null, listOf(dev("R", t))) { null }
        assertEquals(listOf("R"), out.map { it.mac })
    }

    @Test
    fun goneAfterTwoMinutesBackWithinFiveAndNotLater() {
        val w = PlaceWatch()
        var t = learn(w, null, listOf("TV"))
        val out = ArrayList<PlaceWatch.Event>()
        out += w.check(t, null, listOf(dev("TV", t)), { null })
        // Quiet: gone once past 2 minutes, and only once.
        val quiet = t
        while (t < quiet + 4 * min) { t += 10_000; out += w.check(t, null, emptyList(), { null }) }
        assertEquals(listOf(PlaceWatch.Kind.GONE), out.map { it.kind })
        assertTrue(out.single().value in 120..140)
        // Back 4 minutes after it was last heard (under 5 after going): reported.
        out += w.check(t, null, listOf(dev("TV", t)), { null })
        assertEquals(listOf(PlaceWatch.Kind.GONE, PlaceWatch.Kind.BACK), out.map { it.kind })
        assertEquals(240, out.last().value)
        // Gone again, and back 8 minutes after going: just there again.
        out.clear()
        val quiet2 = t
        while (t < quiet2 + 10 * min) { t += 10_000; out += w.check(t, null, emptyList(), { null }) }
        out += w.check(t, null, listOf(dev("TV", t)), { null })
        assertEquals(listOf(PlaceWatch.Kind.GONE), out.map { it.kind })
    }

    @Test
    fun aSignalThatJumpsTwentyDbIsMovingTheThirdTime() {
        val w = PlaceWatch()
        var t = learn(w, null, listOf("CAM"))
        val out = ArrayList<PlaceWatch.Event>()
        // -35, -58, -35, -58...: a jump of 23 dB each look; small changes don't count.
        val levels = listOf(-50, -45, -35, -58, -50, -35, -58, -35)
        for (r in levels) { out += w.check(t, null, listOf(dev("CAM", t, r)), { null }); t += 10_000 }
        assertEquals(listOf(PlaceWatch.Kind.MOVED), out.map { it.kind })
        assertEquals(-58, out.single().value)
        assertEquals(-35, out.single().rssi)
    }

    @Test
    fun awayFromThePlaceNothingIsChecked() {
        val w = PlaceWatch(); val home = 45.0 to -73.0
        var t = learn(w, home, listOf("TV"))
        val away = 45.009 to -73.0 // 1 km
        repeat(60) { assertTrue(w.check(t, away, listOf(dev("X", t)), { null }).isEmpty()); t += 10_000 }
        assertTrue(w.away)
        assertTrue(w.check(t, home, listOf(dev("TV", t)), { null }).isEmpty())
        assertTrue(!w.away)
    }
}
