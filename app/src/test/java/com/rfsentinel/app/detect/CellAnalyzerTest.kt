package com.rfsentinel.app.detect

import com.rfsentinel.app.detect.CellAnalyzer.Cell
import com.rfsentinel.app.detect.CellAnalyzer.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellAnalyzerTest {

    // Synthetic operator codes: 999-99 style values are only used where a test code is intended.
    private fun cell(rat: Rat, reg: Boolean, area: Int = 100, id: Long = 1, mcc: String = "208", mnc: String = "01", dbm: Int = -80) =
        Cell(rat, reg, mcc, mnc, area, id, dbm)

    private fun ctx(t: Long, stationary: Boolean? = null, sim: String = "20801", roaming: Boolean = false, inCall: Boolean = false) =
        CellAnalyzer.Context(t, sim, roaming, stationary, inCall)

    private fun modern(reg: Boolean = true, area: Int = 100, id: Long = 1, dbm: Int = -80) = cell(Rat.LTE, reg, area, id, dbm = dbm)

    private fun List<CellAnalyzer.Anomaly>.of(prefix: String) = filter { it.key.startsWith(prefix) }

    @Test
    fun testNetworkCodeIsStrongAndImmediate() {
        val a = CellAnalyzer().analyze(listOf(cell(Rat.GSM, true, mcc = "001", mnc = "01")), ctx(0))
        assertEquals(85, a.of("test-plmn").single().confidence)
    }

    @Test
    fun signsNeedTwoSnapshotsInARow() {
        val cells = listOf(cell(Rat.LTE, true, mcc = "262", mnc = "01"))
        val an = CellAnalyzer()
        assertTrue(an.analyze(cells, ctx(0)).isEmpty()) // one glitchy reading: nothing yet
        assertEquals(55, an.analyze(cells, ctx(15_000)).of("foreign").single().confidence)
    }

    @Test
    fun suddenSwitchToGsmWithStrongLteIsTheRedirectSign() {
        val an = CellAnalyzer()
        an.analyze(listOf(modern(), modern(false, id = 2)), ctx(0))
        an.analyze(listOf(modern(), modern(false, id = 2)), ctx(15_000))
        // Straight to 2G with a strong LTE cell still there; confirmed on the next snapshot.
        assertTrue(an.analyze(listOf(cell(Rat.GSM, true, id = 9), modern(false, id = 2, dbm = -90)), ctx(30_000)).isEmpty())
        val a = an.analyze(listOf(cell(Rat.GSM, true, id = 9), modern(false, id = 2, dbm = -90)), ctx(45_000))
        assertEquals(70, a.of("downgrade").single().confidence)
    }

    @Test
    fun slowSlideToGsmWithoutCoverageStaysBelowTheAlertLevel() {
        val an = CellAnalyzer()
        an.analyze(listOf(modern(dbm = -118)), ctx(0))
        an.analyze(listOf(cell(Rat.WCDMA, true, id = 5)), ctx(15_000))
        an.analyze(listOf(cell(Rat.WCDMA, true, id = 5)), ctx(60_000))
        an.analyze(listOf(cell(Rat.GSM, true, id = 9)), ctx(120_000))
        val a = an.analyze(listOf(cell(Rat.GSM, true, id = 9)), ctx(135_000))
        assertEquals(35, a.of("downgrade").single().confidence)
    }

    @Test
    fun dropToGsmDuringACallIsIgnored() {
        val an = CellAnalyzer()
        an.analyze(listOf(modern()), ctx(0))
        an.analyze(listOf(cell(Rat.GSM, true, id = 9), modern(false, id = 2)), ctx(15_000, inCall = true))
        // Still on 2G just after the call, then again: the call explains it.
        an.analyze(listOf(cell(Rat.GSM, true, id = 9), modern(false, id = 2)), ctx(30_000))
        assertTrue(an.analyze(listOf(cell(Rat.GSM, true, id = 9), modern(false, id = 2)), ctx(45_000)).of("downgrade").isEmpty())
    }

    @Test
    fun areaChangeOnlyCountsWhenStandingStill() {
        val moving = CellAnalyzer()
        moving.analyze(listOf(modern(area = 100)), ctx(0, stationary = false))
        moving.analyze(listOf(modern(area = 200)), ctx(15_000, stationary = false))
        assertTrue(moving.analyze(listOf(modern(area = 200)), ctx(30_000, stationary = false)).of("area").isEmpty())

        val still = CellAnalyzer()
        still.analyze(listOf(modern(area = 100)), ctx(0, stationary = true))
        still.analyze(listOf(modern(area = 200)), ctx(15_000, stationary = true))
        assertEquals(40, still.analyze(listOf(modern(area = 200)), ctx(30_000, stationary = true)).of("area").single().confidence)
    }

    @Test
    fun areaFlippingBackAtABorderIsNormal() {
        val an = CellAnalyzer()
        an.analyze(listOf(modern(area = 200)), ctx(0, stationary = true))
        an.analyze(listOf(modern(area = 100)), ctx(15_000, stationary = true))
        an.analyze(listOf(modern(area = 200)), ctx(30_000, stationary = true))
        assertTrue(an.analyze(listOf(modern(area = 200)), ctx(45_000, stationary = true)).of("area").isEmpty())
    }

    @Test
    fun foreignCountryButNotSharedOrRoaming() {
        val cells = listOf(cell(Rat.LTE, true, mcc = "262", mnc = "01"))
        CellAnalyzer().also { an -> an.analyze(cells, ctx(0, roaming = true)); assertTrue(an.analyze(cells, ctx(15_000, roaming = true)).isEmpty()) }
        // US networks use several MCCs: 311 with a 310 SIM is normal.
        val us = listOf(cell(Rat.LTE, true, mcc = "311", mnc = "480"))
        CellAnalyzer().also { an -> an.analyze(us, ctx(0, sim = "310260")); assertTrue(an.analyze(us, ctx(15_000, sim = "310260")).isEmpty()) }
    }

    @Test
    fun newCellWithNoNeighboursAfterSeveral() {
        val an = CellAnalyzer()
        repeat(3) { i -> an.analyze(listOf(modern(), modern(false, id = 2), modern(false, id = 3)), ctx(i * 15_000L)) }
        an.analyze(listOf(modern(id = 7)), ctx(45_000))
        assertEquals(35, an.analyze(listOf(modern(id = 7)), ctx(60_000)).of("isolated").single().confidence)
    }

    @Test
    fun reservedAreaCodeIsAWeakSign() {
        val an = CellAnalyzer()
        an.analyze(listOf(modern(area = 0xFFFE)), ctx(0))
        assertEquals(45, an.analyze(listOf(modern(area = 0xFFFE)), ctx(15_000)).of("reserved").single().confidence)
    }

    @Test
    fun knownCellNumberInAnotherAreaThenAdopted() {
        val known = LinkedHashMap<String, Int>()
        CellAnalyzer(known).analyze(listOf(modern(area = 100, id = 42)), ctx(0)) // learned yesterday
        val an = CellAnalyzer(known)
        an.analyze(listOf(modern(area = 300, id = 42)), ctx(1_000_000))
        assertEquals(50, an.analyze(listOf(modern(area = 300, id = 42)), ctx(1_015_000)).of("clone").single().confidence)
        // A real re-plan keeps going: the new area is adopted and the sign stops.
        var t = 1_030_000L
        repeat(CellAnalyzer.ADOPT_AFTER) { an.analyze(listOf(modern(area = 300, id = 42)), ctx(t)); t += 15_000 }
        assertEquals(300, known.values.single())
        an.analyze(listOf(modern(area = 300, id = 42)), ctx(t))
        assertTrue(an.analyze(listOf(modern(area = 300, id = 42)), ctx(t + 15_000)).of("clone").isEmpty())
    }

    @Test
    fun severalSignsTogetherWeighMore() {
        // A cloned cell number that also jumps area while you stand still.
        val known = LinkedHashMap<String, Int>()
        val an = CellAnalyzer(known)
        an.analyze(listOf(modern(area = 100, id = 42)), ctx(0, stationary = true))
        an.analyze(listOf(modern(area = 300, id = 42)), ctx(15_000, stationary = true))
        val a = an.analyze(listOf(modern(area = 300, id = 42)), ctx(30_000, stationary = true))
        assertEquals(65, a.of("combined").single().confidence)
    }
}
