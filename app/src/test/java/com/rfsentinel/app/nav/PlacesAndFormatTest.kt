package com.rfsentinel.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Place search replies (real ones, trimmed), distance / time wording and the arrow chosen for a turn. */
class PlacesAndFormatTest {

    @Test
    fun searchResultsBecomePlaces() {
        val found = Places.parse(javaClass.getResource("/photon_search.json")!!.readText())
        assertEquals(2, found.size)
        assertEquals("Olympiapark", found[0].name)
        assertEquals("Park \u00b7 Munich, Bavaria", found[0].address)
        assertEquals(48.1687, found[0].lat, 0.001) // Photon gives lon, lat: not mixed up
        assertEquals(11.5503, found[0].lon, 0.001)
    }

    @Test
    fun aDroppedPinIsNamedByWhatIsThere() {
        val p = Places.parse(javaClass.getResource("/photon_reverse.json")!!.readText()).single()
        assertEquals("Marienplatz", p.name)
        assertTrue(p.address, p.address.contains("Munich"))
    }

    @Test
    fun aHouseWithoutANameIsCalledByItsAddress() {
        val json = """{"features":[{"geometry":{"coordinates":[11.5,48.1]},"properties":{"housenumber":"12","street":"Example Street","city":"Exampletown","state":"Region"}},
            {"geometry":{"coordinates":[11.6,48.2]},"properties":{}},
            {"properties":{"name":"No position"}}]}"""
        val found = Places.parse(json)
        assertEquals("only the one that can be told apart and placed", 1, found.size)
        assertEquals("12 Example Street", found[0].name)
        assertEquals("Exampletown, Region", found[0].address)
    }

    @Test
    fun noFeaturesIsNoPlaces() {
        assertTrue(Places.parse("""{"features":[]}""").isEmpty())
        assertTrue(Places.parse("""{}""").isEmpty())
    }

    @Test
    fun distancesAreRoundedTheWayADriverReadsThem() {
        assertEquals("40 m", NavFormat.distance(38.0, false))
        assertEquals("300 m", NavFormat.distance(310.0, false))
        assertEquals("950 m", NavFormat.distance(951.0, false))
        assertEquals("1.2 km", NavFormat.distance(1240.0, false))
        assertEquals("12 km", NavFormat.distance(12_345.0, false))
        assertEquals("100 ft", NavFormat.distance(30.0, true))
        assertEquals("0.6 mi", NavFormat.distance(1000.0, true))
        assertEquals("8.0 mi", NavFormat.distance(12_875.0, true))
        assertEquals("20 mi", NavFormat.distance(32_187.0, true))
        assertEquals("0 m", NavFormat.distance(-5.0, false))
    }

    @Test
    fun timesAreShortAndNeverZero() {
        assertEquals("1 min", NavFormat.duration(10.0))
        assertEquals("18 min", NavFormat.duration(1090.0))
        assertEquals("a half minute rounds up", "19 min", NavFormat.duration(1110.0))
        assertEquals("1 h 05 min", NavFormat.duration(3900.0))
        assertEquals("2 h 00 min", NavFormat.duration(7200.0))
    }

    @Test
    fun speed() {
        assertEquals("90 km/h", NavFormat.speed(25.0, false))
        assertEquals("56 mph", NavFormat.speed(25.0, true))
    }

    @Test
    fun manoeuvresGetTheRightArrow() {
        assertEquals(NavFormat.Icon.LEFT, NavFormat.iconFor("turn", "left"))
        assertEquals(NavFormat.Icon.SLIGHT_RIGHT, NavFormat.iconFor("turn", "slight right"))
        assertEquals(NavFormat.Icon.SHARP_LEFT, NavFormat.iconFor("end of road", "sharp left"))
        assertEquals(NavFormat.Icon.STRAIGHT, NavFormat.iconFor("continue", "straight"))
        assertEquals(NavFormat.Icon.STRAIGHT, NavFormat.iconFor("depart", null))
        assertEquals(NavFormat.Icon.FORK_RIGHT, NavFormat.iconFor("fork", "slight right"))
        assertEquals(NavFormat.Icon.RAMP_LEFT, NavFormat.iconFor("off ramp", "slight left"))
        assertEquals(NavFormat.Icon.ROUNDABOUT, NavFormat.iconFor("roundabout", "right"))
        assertEquals(NavFormat.Icon.ARRIVE, NavFormat.iconFor("arrive", null))
        assertEquals(NavFormat.Icon.U_TURN_LEFT, NavFormat.iconFor("turn", "uturn"))
        assertEquals(NavFormat.Icon.MERGE, NavFormat.iconFor("merge", "left"))
    }

    @Test
    fun anActiveLaneShowsTheDirectionToTake() {
        val two = Step.Lane(listOf("straight", "right"), active = true, activeDirection = "right")
        assertEquals("right", NavFormat.laneDirection(two))
        assertEquals("straight", NavFormat.laneDirection(Step.Lane(listOf("straight", "right"), active = false)))
        assertEquals(NavFormat.Icon.SLIGHT_LEFT, NavFormat.laneIcon("slight left"))
    }

    @Test
    fun handOffLinks() {
        assertEquals("google.navigation:q=48.137400,11.575500&mode=d", HandOff.googleMapsUri(48.1374, 11.5755))
        assertEquals("waze://?ll=48.137400,11.575500&navigate=yes", HandOff.wazeUri(48.1374, 11.5755))
        assertEquals("geo:48.137400,11.575500?q=48.137400,11.575500(Mari%20Platz)", HandOff.geoUri(48.1374, 11.5755, "Mari Platz"))
    }
}
