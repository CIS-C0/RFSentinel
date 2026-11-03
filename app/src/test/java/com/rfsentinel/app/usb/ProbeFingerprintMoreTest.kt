package com.rfsentinel.app.usb

import com.rfsentinel.app.data.ProbeBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeFingerprintMoreTest {

    private fun ie(id: Int, vararg v: Int) = id to v.map { it.toByte() }.toByteArray()

    /** A WPS attribute list holding only a UUID-E. */
    private fun uuidAttr(vararg u: Int) = byteArrayOf(0x10, 0x47, 0, 16) + u.map { it.toByte() }.toByteArray()

    @Test
    fun traitsSayWhatTheFingerprintStandsFor() {
        val ies = listOf(
            ie(0), ie(1, 0x82, 0x84),
            ie(45, *IntArray(26).also { it[3] = 0xFF; it[4] = 0xFF }),                 // HT, 2 streams
            ie(127, 0, 0, 0x08, 0x80),                                                // bits 19 + 31
            ie(221, 0x50, 0x6F, 0x9A, 0x16, 0x01))                                    // MBO
        val t = ProbeIntel.traits(ies)!!
        assertTrue(t, t.startsWith("WiFi 4 · 2x2"))
        assertTrue(t, t.contains("802.11v") && t.contains("Passpoint") && t.endsWith("MBO"))
        // Only a name, rates and channel: nothing worth saying.
        assertNull(ProbeIntel.traits(listOf(ie(0), ie(1, 0x82), ie(3, 6))))
    }

    @Test
    fun wpsUuidIsReadAndBlankOnesAreDropped() {
        val a = uuidAttr(*IntArray(16) { it + 1 })
        assertEquals("0102030405060708090a0b0c0d0e0f10", ProbeIntel.parseWps(a, 0, a.size)!!.uuid)
        val zero = uuidAttr(*IntArray(16))
        assertNull(ProbeIntel.parseWps(zero, 0, zero.size))
    }

    @Test
    fun aLaterWpsBlockWithoutUuidKeepsIt() {
        ProbeIntel.clear()
        ProbeIntel.note("02:00:00:00:00:01", ProbeIntel.Wps(null, null, null, null, "abc"), null, emptyList(), traits = "WiFi 6")
        ProbeIntel.note("02:00:00:00:00:01", ProbeIntel.Wps("Acme", null, null, null), null, emptyList())
        val r = ProbeIntel.of("02:00:00:00:00:01")!!
        assertEquals("abc", r.wps?.uuid); assertEquals("Acme", r.wps?.manufacturer); assertEquals("WiFi 6", r.traits)
        ProbeIntel.clear()
    }

    @Test
    fun addressesWithTheSameUuidAreOneDevice() {
        val book = ProbeBook()
        book.recordDevice("02:00:00:00:00:01", listOf("Home"), -60, 1, wpsUuid = "u1")
        book.recordDevice("06:00:00:00:00:02", listOf("Work"), -60, 2, wpsUuid = "u1")
        book.recordDevice("0A:00:00:00:00:03", listOf("Cafe"), -60, 3, wpsUuid = "u2")
        val groups = book.groups({ true })
        assertEquals(2, groups.size)
        val same = groups.single { it.likelySame }
        assertEquals(setOf("02:00:00:00:00:01", "06:00:00:00:00:02"), same.devices.map { it.mac }.toSet())
        assertFalse(groups.single { !it.likelySame }.devices.single().wpsUuid == "u1")
    }
}
