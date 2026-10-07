package com.rfsentinel.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CctvTest {

    private val json = """
        {"elements":[
          {"type":"node","id":1,"lat":10.5,"lon":-20.5,"tags":{"man_made":"surveillance","surveillance:type":"camera",
            "surveillance":"public","camera:type":"fixed","camera:direction":"NE","camera:angle":"40","height":"6 m",
            "operator":"City police","camera:mount":"pole","surveillance:zone":"town"}},
          {"type":"node","id":2,"lat":10.5004,"lon":-20.5008,"tags":{"surveillance":"outdoor","camera:type":"dome"}},
          {"type":"way","id":3,"center":{"lat":10.5009,"lon":-20.5018},"tags":{"surveillance":"indoor"}},
          {"type":"node","id":4,"lat":10.5014,"lon":-20.5028},
          {"type":"node","id":5,"tags":{"surveillance":"public"}}
        ]}
    """.trimIndent()

    @Test
    fun parsesOverpassAnswer() {
        val cams = Cctv.parse(json)
        assertEquals(4, cams.size) // the one without a position is skipped
        val c = cams[0]
        assertEquals("node/1", c.osmId)
        assertEquals("public", c.zone)
        assertEquals(45, c.direction)
        assertEquals(40, c.angle)
        assertEquals(6.0, c.height!!, 0.0)
        assertEquals("pole", c.mount)
        assertEquals("town", c.area)
        assertFalse(c.isPrivate)
        assertTrue(cams[1].isPrivate)
        assertEquals("way/3", cams[2].osmId)
        assertTrue(cams[2].isPrivate)
        assertNull(cams[3].zone)
        assertFalse(cams[3].isPrivate) // unmapped counts as public: shown by default
    }

    @Test
    fun viewConeAndTitles() {
        val c = Cctv.parse(json)
        assertEquals(30.0, Cctv.viewRangeM(c[0]), 0.0)   // 6 m high -> 30 m
        assertEquals(25.0, Cctv.viewRangeM(c[1]), 0.0)   // height unknown
        assertEquals(40, Cctv.viewAngle(c[0]))
        assertEquals(90, Cctv.viewAngle(c[1]))            // dome default
        assertEquals("Public fixed camera", Cctv.title(c[0]))
        assertEquals("Private dome camera (facing outside)", Cctv.title(c[1]))
        assertEquals("Surveillance camera", Cctv.title(c[3]))
        assertEquals(90, Cctv.parseAngle("40-90"))
        assertEquals(4.5, Cctv.parseHeight("4,5")!!, 0.0)
        assertNull(Cctv.parseAngle("0"))
    }

    @Test
    fun downloadBoxStaysSmall() {
        val (s, w, n, e) = CctvStore.expand(10.50, -20.51, 10.51, -20.50)
        assertTrue(n - s <= CctvStore.MAX_SPAN_DEG + 1e-9 && e - w <= CctvStore.MAX_SPAN_DEG + 1e-9)
        assertTrue(n - s >= 0.02)
        assertTrue(Cctv.query(s, w, n, e).contains("\"surveillance:type\"=\"camera\""))
    }
}
