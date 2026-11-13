package com.rfsentinel.app.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFeaturesTest {

    @Test
    fun settingsLinesParse() {
        val r = RadioSettings.ranges("462-469 business band\nnonsense\n151,8 - 152.0\n")
        assertEquals(2, r.size)
        assertEquals(462_000_000L..469_000_000L, r[0].first)
        assertEquals("business band", r[0].second)
        val t = RadioSettings.targets("154.4300 County fire\n460.125\n462-469 not a target\n")
        assertEquals(listOf(154_430_000L, 460_125_000L), t.map { it.freqHz })
        assertEquals("County fire", t[0].label)
        val bands = RadioSettings.customBands("380-400 TETRA\n100-300 too much on top")
        assertEquals(1, bands.size) // the second would exceed the custom-span cap
        assertEquals(RadioWatch.Kind.CUSTOM, bands[0].kind)
    }

    @Test
    fun importedListJoinsWatchedFrequencies() {
        fun n(hz: Long, name: String) = FreqNames.Name(hz, name, "t")
        val (text, added, left) = RadioSettings.addTargets("154.4300 County fire\n",
            listOf(n(154_430_000L, "same"), n(851_006_250L, "Fine step"), n(155_000_000L, "Whole"), n(155_000_000L, "Twice"), n(1_790_000_000L, "Too high")))
        assertEquals(2, added)
        assertEquals(0, left)
        val t = RadioSettings.targets(text)
        assertEquals(listOf(154_430_000L, 851_006_250L, 155_000_000L), t.map { it.freqHz })
        assertEquals(listOf("County fire", "Fine step", "Whole"), t.map { it.label })

        val many = (0 until RadioSettings.MAX_TARGETS + 5).map { n(150_000_000L + it * 12_500L, "Ch $it") }
        val (full, fit, over) = RadioSettings.addTargets("", many)
        assertEquals(RadioSettings.MAX_TARGETS, fit)
        assertEquals(5, over)
        assertEquals(RadioSettings.MAX_TARGETS, RadioSettings.targets(full).size)
        assertEquals(0, RadioSettings.addTargets(full, many).second)
    }

    @Test
    fun trendIsCalledOnTheSetStep() {
        RadioLog.clear()
        val band = RadioWatch.BANDS[1]
        fun ev(snr: Int) = RadioWatch.Event(807_312_500L, snr, band, 60)
        assertNull(RadioLog.observe(ev(30), null, 6, 0))            // new
        assertNull(RadioLog.observe(ev(33), null, 6, 1_000))         // +3: not yet
        assertEquals(RadioLog.Trend.CLOSER, RadioLog.observe(ev(37), "Fire", 6, 2_000))
        assertEquals(RadioLog.Trend.FARTHER, RadioLog.observe(ev(30), null, 6, 3_000))
        val e = RadioLog.current(3_000).single()
        assertEquals("Fire", e.name)
        assertEquals(37, e.peakSnrDb)
        // A long gap starts over.
        assertNull(RadioLog.observe(ev(45), null, 6, 200_000))
        assertEquals(RadioLog.Trend.NEW, RadioLog.current(200_000).single().trend)
    }

    @Test
    fun frequencyListsFromRadioReferenceChirpAndPlainFiles() {
        val rr = "Frequency Output,Frequency Input,FCC Callsign,Agency/Category,Description,Alpha Tag,PL Tone,Mode,Class Station Code,Tag\n" +
            "154.43000,,KQB123,County Fire,Fire Dispatch,FD DISP,CSQ,FM,BM,Fire Dispatch\n" +
            "\"155.47500\",,,State Police,\"Car-to-car, statewide\",SP C2C,,FM,M,Law Tac\n"
        val a = FreqNames.parseCsv(rr, "rr")
        assertEquals(2, a.size)
        assertEquals(154_430_000L, a[0].hz)
        assertEquals("Fire Dispatch · FD DISP", a[0].name)
        assertEquals("Car-to-car, statewide · SP C2C", a[1].name)
        val chirp = "Location,Name,Frequency,Duplex,Offset\n0,PD MAIN,460.125000,,0.000000\n"
        assertEquals("PD MAIN", FreqNames.parseCsv(chirp, "c").single().name)
        val plain = "453.2500,Public works\n"
        assertEquals(453_250_000L, FreqNames.parseCsv(plain, "p").single().hz)
        val near = FreqNames.closest(a, 154_436_000L, 7_000)
        assertEquals("Fire Dispatch · FD DISP", near!!.name)
        assertNull(FreqNames.closest(a, 154_450_000L, 7_000))
    }

    @Test
    fun radioReferenceAnswersParse() {
        val prox = """<SOAP-ENV:Body><ns1:fccGetProxCallsignsResponse><return xsi:type="SOAP-ENC:Array">
            <item xsi:type="tns:proxCallsignResult"><callsign xsi:type="xsd:string">WQAB123</callsign><licensee xsi:type="xsd:string">CITY OF SAMPLE &amp; CO</licensee><lat>10.5</lat><lon>-20.5</lon><distance xsi:type="xsd:decimal">1.25</distance></item>
            <item><callsign>KNXY456</callsign><licensee>COUNTY SHERIFF</licensee><distance>0.4</distance></item>
            </return></ns1:fccGetProxCallsignsResponse></SOAP-ENV:Body>"""
        val calls = RadioReference.parseProx(prox)
        assertEquals(listOf("KNXY456", "WQAB123"), calls.map { it.callsign })
        assertEquals("CITY OF SAMPLE & CO", calls[1].licensee)
        val details = """<frequencies><item><locationNumber>1</locationNumber><frequency xsi:type="xsd:decimal">155.475</frequency></item>
            <item><frequency>460.125</frequency></item><item><frequency>3.0</frequency></item></frequencies>"""
        assertEquals(listOf(155_475_000L, 460_125_000L), RadioReference.parseFrequencies(details))
        assertEquals("12-31-2027", RadioReference.parseExpiry("<return><username xsi:type=\"xsd:string\">a</username><subExpireDate xsi:type=\"xsd:string\">12-31-2027</subExpireDate></return>"))
        assertEquals(null, RadioReference.parseExpiry("<return><subExpireDate xsi:type=\"xsd:string\"></subExpireDate></return>"))
        assertEquals("Invalid Username or Password.", RadioReference.fault(
            "<SOAP-ENV:Fault><faultcode>AUTH</faultcode><faultstring xsi:type=\"xsd:string\">Invalid Username or Password.</faultstring></SOAP-ENV:Fault>"))
        assertNull(RadioReference.fault(prox))
        assertEquals(10.5 to -20.5, RadioReference.snap(10.5012, -20.4987))
    }

    @Test
    fun radioReferenceTalkgroups() {
        val systems = """<return SOAP-ENC:arrayType="ns1:TrsList[2]"><item><sid xsi:type="xsd:int">1234</sid><sName>Sample County P25</sName></item>
            <item><sid xsi:type="xsd:int">5678</sid><sName></sName></item></return>"""
        assertEquals(listOf(RadioReference.TrsSystem(1234, "Sample County P25"), RadioReference.TrsSystem(5678, "System 5678")),
            RadioReference.parseSystems(systems))
        // Each talkgroup's tags are a nested list: names must not leak into the next one.
        val tgs = """<return><item><tgId>1</tgId><tgDec xsi:type="xsd:int">100</tgDec><tgAlpha>SO DISP</tgAlpha><tgDescr>Sheriff &amp; dispatch</tgDescr>
            <tags><item><tagId>2</tagId></item></tags></item>
            <item><tgId>2</tgId><tgDec>200</tgDec><tgAlpha>FD TAC</tgAlpha><tgDescr></tgDescr><tags><item><tagId>3</tagId></item></tags></item>
            <item><tgId>3</tgId><tgDec>300</tgDec><tgAlpha></tgAlpha><tgDescr></tgDescr></item></return>"""
        assertEquals(mapOf(100 to "Sheriff & dispatch", 200 to "FD TAC"), RadioReference.parseTalkgroups(tgs))
        val names = P25Log.watched(mapOf(100 to "Sheriff & dispatch", 200 to "FD TAC").entries.joinToString("\n") { "${it.key} ${it.value}" })
        assertEquals("FD TAC", names[200])
    }
}
