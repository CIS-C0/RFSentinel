package com.rfsentinel.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakCotTest {
    @Test
    fun addressesParse() {
        assertEquals("239.2.3.1" to 6969, TakCot.parse(TakCot.DEFAULT_TARGET))
        assertEquals("tak.local" to 6969, TakCot.parse(" tak.local "))
        assertEquals("10.0.0.5" to 8087, TakCot.parse("10.0.0.5:8087"))
        assertNull(TakCot.parse("10.0.0.5:99999"))
        assertNull(TakCot.parse("http://x"))
        assertNull(TakCot.parse(""))
    }

    @Test
    fun eventIsValidCotWithEscapedText() {
        val xml = TakCot.event("AA:BB:CC:00:11:22", "Axon <body> & cam", "BODY_CAM", 80, 10.5, 20.25, air = false, now = 0L)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val e = doc.documentElement
        assertEquals("event", e.tagName); assertEquals("a-u-G", e.getAttribute("type"))
        assertEquals("1970-01-01T00:00:00.000Z", e.getAttribute("time")); assertEquals("1970-01-01T00:10:00.000Z", e.getAttribute("stale"))
        val p = e.getElementsByTagName("point").item(0) as org.w3c.dom.Element
        assertEquals("10.500000", p.getAttribute("lat")); assertEquals("20.250000", p.getAttribute("lon"))
        assertEquals("Axon <body> & cam", (e.getElementsByTagName("contact").item(0) as org.w3c.dom.Element).getAttribute("callsign"))
        assertTrue(TakCot.event("x", "p", "AIRCRAFT", 90, 1.0, 2.0, air = true, now = 0L).contains("type=\"a-u-A\""))
    }
}
