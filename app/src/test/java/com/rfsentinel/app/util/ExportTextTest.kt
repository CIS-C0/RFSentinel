package com.rfsentinel.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class ExportTextTest {

    @Test
    fun csvNeutralisesFormulas() {
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", ExportText.csv("=HYPERLINK(\"x\")"))
        assertEquals("\"'+1\"", ExportText.csv("+1"))
        assertEquals("\"'-2\"", ExportText.csv("-2"))
        assertEquals("\"'@SUM(A1)\"", ExportText.csv("@SUM(A1)"))
        assertEquals("\"NETGEAR-5G\"", ExportText.csv("NETGEAR-5G"))
        assertEquals("", ExportText.csv(null))
    }

    @Test
    fun xmlDropsForbiddenCharactersAndStaysParseable() {
        val hostile = "Evil\u0001SSID\u001B<b>&\"'￾\uD800 ok 📡"
        val escaped = ExportText.xml(hostile)
        assertFalse(escaped.contains('\u0001'))
        assertFalse(escaped.contains('￾'))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("<a v=\"$escaped\">$escaped</a>".byteInputStream())
        assertEquals("EvilSSID<b>&\"' ok 📡", doc.documentElement.textContent)
        assertEquals("EvilSSID<b>&\"' ok 📡", doc.documentElement.getAttribute("v"))
    }
}
