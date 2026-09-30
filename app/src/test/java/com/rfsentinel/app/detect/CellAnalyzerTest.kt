package com.rfsentinel.app.detect

import com.rfsentinel.app.detect.CellAnalyzer.Cell
import com.rfsentinel.app.detect.CellAnalyzer.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellAnalyzerTest {

    // Synthetic operator codes: 999-99 style values are only used where a test code is intended.
    private fun cell(rat: Rat, reg: Boolean, area: Int = 100, id: Long = 1, mcc: String = "208", mnc: String = "01") =
        Cell(rat, reg, mcc, mnc, area, id, -80)

    private fun ctx(t: Long, stationary: Boolean? = null, sim: String = "20801", roaming: Boolean = false) =
        CellAnalyzer.Context(t, sim, roaming, stationary)

    private fun modern(reg: Boolean = true, area: Int = 100, id: Long = 1) = cell(Rat.LTE, reg, area, id)

    @Test
    fun testNetworkCodeIsStrong() {
        val a = CellAnalyzer().analyze(listOf(cell(Rat.GSM, true, mcc = "001", mnc = "01")), ctx(0))
        assertEquals(85, a.single { it.key.startsWith("test-plmn") }.confidence)
    }

    @Test
    fun forcedDowngradeToGsmWithLteStillVisible() {
        val an = CellAnalyzer()
        assertTrue(an.analyze(listOf(modern(), modern(false, id = 2)), ctx(0)).isEmpty())
        val a = an.analyze(listOf(cell(Rat.GSM, true, id = 9), modern(false, id = 2)), ctx(30_000))
        assertEquals(65, a.single { it.key.startsWith("downgrade") }.confidence)
    }

    @Test
    fun downgradeWithoutModernCoverageIsWeak() {
        val an = CellAnalyzer()
        an.analyze(listOf(modern()), ctx(0))
        val a = an.analyze(listOf(cell(Rat.GSM, true, id = 9)), ctx(30_000))
        assertEquals(40, a.single { it.key.startsWith("downgrade") }.confidence)
    }

    @Test
    fun areaChangeOnlyCountsWhenStandingStill() {
        val moving = CellAnalyzer()
        moving.analyze(listOf(modern(area = 100)), ctx(0, stationary = false))
        assertTrue(moving.analyze(listOf(modern(area = 200)), ctx(15_000, stationary = false)).none { it.key.startsWith("area") })

        val still = CellAnalyzer()
        still.analyze(listOf(modern(area = 100)), ctx(0, stationary = true))
        assertEquals(40, still.analyze(listOf(modern(area = 200)), ctx(15_000, stationary = true)).single { it.key.startsWith("area") }.confidence)
    }

    @Test
    fun foreignCountryButNotSharedOrRoaming() {
        val cells = listOf(cell(Rat.LTE, true, mcc = "262", mnc = "01"))
        assertEquals(55, CellAnalyzer().analyze(cells, ctx(0)).single { it.key.startsWith("foreign") }.confidence)
        assertTrue(CellAnalyzer().analyze(cells, ctx(0, roaming = true)).isEmpty())
        // US networks use several MCCs: 311 with a 310 SIM is normal.
        assertTrue(CellAnalyzer().analyze(listOf(cell(Rat.LTE, true, mcc = "311", mnc = "480")), ctx(0, sim = "310260")).isEmpty())
    }

    @Test
    fun newCellWithNoNeighboursAfterSeveral() {
        val an = CellAnalyzer()
        repeat(3) { i -> an.analyze(listOf(modern(), modern(false, id = 2), modern(false, id = 3)), ctx(i * 15_000L)) }
        val a = an.analyze(listOf(modern(id = 7)), ctx(60_000))
        assertEquals(35, a.single { it.key.startsWith("isolated") }.confidence)
    }
}
