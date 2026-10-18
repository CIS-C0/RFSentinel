package com.rfsentinel.app.usb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaCheckTest {

    @After fun tearDown() = AntennaCheck.close()

    /** A beacon from [bssid] naming network [ssid] on [ch] (DS parameter set). */
    private fun beacon(bssid: Int, ssid: String, ch: Int): ByteArray {
        val name = ssid.toByteArray()
        val b = ByteArray(36 + 2 + name.size + 3)
        b[0] = 0x80.toByte()
        for (k in 0 until 6) { b[4 + k] = 0xff.toByte(); b[10 + k] = (if (k == 0) 0x02 else if (k == 5) bssid else 0).toByte() }
        System.arraycopy(b, 10, b, 16, 6)
        var i = 36
        b[i++] = 0; b[i++] = name.size.toByte(); name.copyInto(b, i); i += name.size
        b[i++] = 3; b[i++] = 1; b[i] = ch.toByte()
        return b
    }

    private fun mac(last: Int) = "02:00:00:00:00:%02x".format(last)

    @Test
    fun listsAccessPointsOnlyWhileOpen() {
        val f = MonitorFrames()
        beacon(1, "Home", 6).let { f.frame(it, 0, it.size, 6, -50) }
        assertTrue(AntennaCheck.aps(System.currentTimeMillis()).isEmpty())
        AntennaCheck.open()
        beacon(1, "Home", 6).let { f.frame(it, 0, it.size, 4, -50) } // heard while tuned next door: its own channel wins
        beacon(2, "Shop", 36).let { f.frame(it, 0, it.size, 36, -70) }
        val aps = AntennaCheck.aps(System.currentTimeMillis())
        assertEquals(listOf(mac(1), mac(2)), aps.map { it.bssid })
        assertEquals("Home", aps[0].ssid); assertEquals(6, aps[0].channel)
    }

    @Test
    fun keepsOnlyTheReferencesFramesWithBothChains() {
        val f = MonitorFrames()
        AntennaCheck.open()
        AntennaCheck.lock(mac(1), 6)
        val now = System.currentTimeMillis()
        repeat(10) { beacon(1, "Home", 6).let { f.frame(it, 0, it.size, 6, -50, now, pathA = -50, pathB = -54) } }
        repeat(10) { beacon(2, "Shop", 6).let { f.frame(it, 0, it.size, 6, -40, now, pathA = -40, pathB = -40) } }
        val s = AntennaCheck.stats(now - 1000, now + 1000)
        assertEquals(10, s.frames); assertEquals(10, s.both)
        assertEquals(-50.0, s.a!!, 0.01); assertEquals(-54.0, s.b!!, 0.01); assertEquals(4.0, s.delta!!, 0.01)
        AntennaCheck.unlock()
        assertEquals(0, AntennaCheck.stats(now - 1000, now + 1000).frames)
    }

    @Test
    fun holdsTheLockedChannelOnlyWhenTheAdapterHasIt() {
        val hop = intArrayOf(1, 6, 11, 36)
        assertEquals(-1, AntennaCheck.lockIndex(hop))
        AntennaCheck.lock(mac(1), 36)
        assertEquals(3, AntennaCheck.lockIndex(hop))
        AntennaCheck.lock(mac(1), 52)
        assertEquals("not a channel it tunes: keeps hopping", -1, AntennaCheck.lockIndex(hop))
        AntennaCheck.close()
        assertEquals(0, AntennaCheck.lockChannel)
    }

    private fun stats(a: Double, b: Double, n: Int = 20) = AntennaCheck.Stats(n, maxOf(a, b), a, b, n, a - b)

    @Test
    fun verdicts() {
        assertTrue(AntennaCheck.chainVerdict(0.6).startsWith("Both antennas"))
        assertTrue(AntennaCheck.chainVerdict(-2.0).startsWith("Chain 1 is a little stronger"))
        assertTrue(AntennaCheck.chainVerdict(5.5).startsWith("Chain 1 is 5.5 dB weaker"))
        assertTrue(AntennaCheck.chainVerdict(-5.5).startsWith("Chain 0 is 5.5 dB weaker"))

        assertTrue(AntennaCheck.identify(stats(-50.0, -52.0), stats(-50.5, -64.0)).startsWith("Chain 1 is the port you covered"))
        assertTrue(AntennaCheck.identify(stats(-50.0, -52.0), stats(-62.0, -53.0)).startsWith("Chain 0 is the port you covered"))
        assertTrue("both dropped alike", AntennaCheck.identify(stats(-50.0, -52.0), stats(-60.0, -61.0)).startsWith("Couldn't tell"))
        assertTrue("no frames", AntennaCheck.identify(stats(-50.0, -52.0, n = 2), stats(-60.0, -52.0)).startsWith("Couldn't read"))

        assertEquals("A and B hear the reference about the same (within 1 dB).", AntennaCheck.compare(-50.0, -50.8))
        assertEquals("Antenna B is 3.0 dB stronger.", AntennaCheck.compare(-50.0, -47.0))
        assertEquals("Chain 0: antenna A is 2.0 dB stronger.", AntennaCheck.compare(-50.0, -52.0, "Chain 0: "))
        assertEquals("6 GHz ch 37", AntennaCheck.channelName(6135))
        assertEquals("5 GHz ch 36", AntennaCheck.channelName(36))
    }

    @Test
    fun realtekPathPower() {
        assertEquals(-60, Rtl8812auMonitor.pathPower(byteArrayOf(50), 0))
        assertEquals("TRSW bit ignored", -60, Rtl8812auMonitor.pathPower(byteArrayOf((0x80 or 50).toByte()), 0))
        assertNull("empty path", Rtl8812auMonitor.pathPower(byteArrayOf(0), 0))
    }

    @Test
    fun historyAveragesEachSliceOldestFirst() {
        val now = 100_000L
        val list = listOf(
            AntennaCheck.Sample(now - 29_900, -60, -60, -70), // oldest slice
            AntennaCheck.Sample(now - 29_600, -62, -62, -72),
            AntennaCheck.Sample(now - 100, -50, -50, null),    // newest slice, chain 1 missing
            AntennaCheck.Sample(now - 40_000, -40, -40, -40),  // older than the window: left out
        )
        val h = AntennaCheck.history(list, now, 30_000, 60)
        assertEquals(-61f, h.a[0], 0.01f)
        assertEquals(-71f, h.b[0], 0.01f)
        assertEquals(-50f, h.a[59], 0.01f)
        assertTrue(h.b[59].isNaN())
        assertTrue(h.a[30].isNaN())
    }

    @Test
    fun autoWaitsForTheChannelsThenTakesTheStrongestFreshOne() {
        val since = 0L
        val aps = listOf(
            AntennaCheck.Ap("02:00:00:00:00:01", "a", 1, -70, 11_000),
            AntennaCheck.Ap("02:00:00:00:00:02", "b", 36, -40, 2_000),   // strongest, but not heard for a while
            AntennaCheck.Ap("02:00:00:00:00:03", "c", 6, -55, 12_500),
        )
        assertNull(AntennaCheck.strongest(aps, 11_000, since))
        assertEquals("02:00:00:00:00:03", AntennaCheck.strongest(aps, 13_000, since)?.bssid)
        assertNull(AntennaCheck.strongest(emptyList(), 13_000, since))
    }
}
