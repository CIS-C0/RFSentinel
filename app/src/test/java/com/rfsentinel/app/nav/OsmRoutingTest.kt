package com.rfsentinel.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmRoutingTest {

    // Synthetic responses (made-up places and coordinates).
    private val search = """
        [{"lat":"10.5100","lon":"-20.5000","name":"Example Library","display_name":"Example Library, 1 Main Street, Exampletown"},
         {"lat":"10.6000","lon":"-20.4000","name":"","display_name":"Main Street, Othertown"},
         {"lat":"bad","lon":"-20.4","display_name":"skipped"}]
    """.trimIndent()

    // A straight road north, then a left turn west, then arrival.
    private val route = """
        {"code":"Ok","routes":[{"distance":2200.0,"duration":180.0,
          "geometry":{"coordinates":[[-20.5,10.50],[-20.5,10.505],[-20.5,10.51],[-20.505,10.51],[-20.51,10.51]]},
          "legs":[{"steps":[
            {"name":"Main Street","maneuver":{"type":"depart","location":[-20.5,10.50]}},
            {"name":"Oak Road","maneuver":{"type":"turn","modifier":"left","location":[-20.5,10.51]}},
            {"name":"","maneuver":{"type":"arrive","location":[-20.51,10.51]}}
          ]}]}]}
    """.trimIndent()

    @Test
    fun parsesSearchResults() {
        val r = OsmRouting.parseSearch(search)
        assertEquals(2, r.size)
        assertEquals("Example Library", r[0].name)
        assertEquals("1 Main Street, Exampletown", r[0].address) // first two parts only
        assertEquals("Main Street", r[1].name) // no name: first part of the address
        assertEquals(10.51, r[0].lat, 1e-9)
    }

    @Test
    fun parsesRouteAndWordsInstructions() {
        val r = OsmRouting.parseRoute(route)
        assertEquals(5, r.points.size)
        assertEquals(10.505 to -20.5, r.points[1])
        assertEquals(3, r.steps.size)
        assertEquals("Turn left onto Oak Road", r.steps[1].instruction)
        assertEquals("Arrive at your destination", r.steps[2].instruction)
        assertEquals("At the roundabout, take exit 2 onto A Road", OsmRouting.instructionFor("roundabout", "right", "A Road", 2))
        assertEquals("Keep slightly right", OsmRouting.instructionFor("continue", "slight right", "", null))
    }

    @Test
    fun noRouteIsAnError() {
        assertTrue(runCatching { OsmRouting.parseRoute("""{"code":"NoRoute","routes":[]}""") }.isFailure)
    }

    @Test
    fun progressFindsNextTurnRemainingAndOffRoute() {
        val r = OsmRouting.parseRoute(route)
        // Halfway up the first road: next is the left turn ~550 m ahead.
        val p = Navigator.progressOf(r, 10.505, -20.5)
        assertEquals("Turn left onto Oak Road", p.nextStep?.instruction)
        assertEquals(556.0, p.toNextStepM, 15.0)
        assertTrue(p.offRouteM < 1.0)
        assertFalse(p.arrived)
        // 300 m beside the road: off route.
        assertTrue(Navigator.progressOf(r, 10.505, -20.4973).offRouteM > Navigator.OFF_ROUTE_M)
        // At the end: arrived.
        assertTrue(Navigator.progressOf(r, 10.51, -20.51).arrived)
        assertEquals("1.5 kilometres", Navigator.roundDistance(1500.0))
        assertEquals("250 metres", Navigator.roundDistance(260.0))
    }
}
