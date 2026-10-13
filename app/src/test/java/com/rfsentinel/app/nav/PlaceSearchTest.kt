package com.rfsentinel.app.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Place search: putting results in the order a person expects, reading both servers' answers, and the submitted race. */
class PlaceSearchTest {

    @After
    fun tearDown() { Places.fetcher = null }

    private fun p(name: String, lat: Double = 48.0, lon: Double = 11.0, address: String = "") = Place(name, address, lat, lon)

    // ---- reading words ----

    @Test
    fun accentsCaseAndPunctuationAreIgnored() {
        assertEquals("cafe bar zurich", PlaceRank.normalize("Café-Bar  Zürich"))
        assertEquals("strasse", PlaceRank.normalize("Straße"))
        assertEquals("lodz", PlaceRank.normalize("Łódź"))
        assertEquals("orsted", PlaceRank.normalize("Ørsted"))
        assertEquals("", PlaceRank.normalize(" - "))
    }

    @Test
    fun howWellAPlaceAnswersTheWords() {
        assertEquals(0, PlaceRank.matchClass("olympiapark", p("Olympiapark")))
        assertEquals(1, PlaceRank.matchClass("olympia", p("Olympiapark")))
        assertEquals(2, PlaceRank.matchClass("park", p("Olympia Park")))
        assertEquals("inside a word", 3, PlaceRank.matchClass("park", p("Olympiapark")))
        assertEquals("every word is somewhere in name or address", 3, PlaceRank.matchClass("cafe munich", p("Cafe Luitpold", address = "Munich")))
        assertEquals(4, PlaceRank.matchClass("zoo", p("Olympiapark")))
        assertEquals(4, PlaceRank.matchClass("  ", p("Olympiapark")))
    }

    @Test
    fun typingWithoutAccentsFindsTheAccentedName() {
        assertEquals(1, PlaceRank.matchClass("cafe lu", p("Café Luitpold")))
    }

    // ---- order ----

    @Test
    fun theNameYouTypedBeatsAPlaceThatOnlyMentionsIt() {
        val list = listOf(p("Park Hotel"), p("Central Park"), p("Park"), p("Parkside Mall"))
        val ranked = PlaceRank.rank("park", list, null, null).map { it.name }
        assertEquals("Park", ranked[0])
        assertEquals(setOf("Park Hotel", "Parkside Mall"), ranked.subList(1, 3).toSet())
        assertEquals("Central Park", ranked[3])
    }

    @Test
    fun amongEqualsTheNearestComesFirst() {
        val far = p("Starbucks", lat = 48.50)
        val near = p("Starbucks", lat = 48.001)
        val mid = p("Starbucks", lat = 48.10)
        assertEquals(listOf(near, mid, far), PlaceRank.rank("starbucks", listOf(far, mid, near), 48.0, 11.0))
    }

    @Test
    fun aNearbyPlaceBeatsTheSameNameFarAway() {
        // Looking for "biodome" from (10, 10): the one 20 km off, then one 500 km off, before two on another continent.
        val here = 10.0 to 10.0
        val farExact = p("Biodome", lat = 55.0, lon = 12.0)
        val farExact2 = p("Biodome", lat = -30.0, lon = 100.0)
        val nearStart = p("Biodome de Ville", lat = 10.15, lon = 10.1)
        val nearWord = p("Parc Biodome", lat = 10.1, lon = 10.2)
        val midExact = p("Biodome", lat = 14.0, lon = 10.0) // ~440 km
        val ranked = PlaceRank.rank("biodome", listOf(farExact, nearWord, farExact2, midExact, nearStart), here.first, here.second)
        assertEquals(listOf(nearStart, nearWord, midExact), ranked.take(3))
        assertEquals("the far ones keep their name order, nearest first among equals", setOf(farExact, farExact2), ranked.drop(3).toSet())
    }

    @Test
    fun whenEverythingIsFarTheBestNameStillWins() {
        val far = listOf(p("Park Hotel", lat = 55.0, lon = 12.0), p("Park", lat = 56.0, lon = 13.0), p("Central Park", lat = 55.5, lon = 12.5))
        assertEquals("Park", PlaceRank.rank("park", far, 10.0, 10.0).first().name)
    }

    @Test
    fun theDistanceStepsAreFiftyTwoFiftyAndAThousandKilometres() {
        assertEquals(0, PlaceRank.distanceStep(49_000.0)); assertEquals(1, PlaceRank.distanceStep(51_000.0))
        assertEquals(1, PlaceRank.distanceStep(249_000.0)); assertEquals(2, PlaceRank.distanceStep(251_000.0))
        assertEquals(2, PlaceRank.distanceStep(999_000.0)); assertEquals(3, PlaceRank.distanceStep(1_001_000.0))
    }

    @Test
    fun withoutAPositionTheServersOrderStays() {
        val list = listOf(p("Cafe A", lat = 49.0), p("Cafe B", lat = 48.0), p("Cafe C", lat = 50.0))
        assertEquals(list, PlaceRank.rank("cafe", list, null, null))
    }

    @Test
    fun theSamePlaceListedTwiceCountsOnce() {
        val a = p("Olympiapark", 48.1687, 11.5503)
        val twin = p("olympiapark", 48.1690, 11.5510) // an area and a point for one park
        val farAway = p("Olympiapark", 52.5, 13.4)
        val other = p("Olympiapark Nord", 48.1688, 11.5504)
        assertEquals(listOf(a, farAway, other), PlaceRank.dedupe(listOf(a, twin, farAway, other)))
    }

    @Test
    fun aLongerQueryKeepsWhatStillFits() {
        val earlier = listOf(p("Olympiapark"), p("Olympia Mall"), p("Odeon"))
        assertEquals(listOf("Olympiapark"), PlaceRank.stillMatching("olympiap", earlier).map { it.name })
        assertEquals(listOf("Olympiapark", "Olympia Mall"), PlaceRank.stillMatching("olympia", earlier).map { it.name })
    }

    // ---- your own places ----

    @Test
    fun yourOwnPlacesAreFoundByNameAddressAndByHomeAndWork() {
        val home = Place("12 Example Street", "Exampletown", 48.0, 11.0)
        val work = Place("Office", "Business Road, Workville", 48.5, 11.5)
        val fav = Place("Café Luitpold", "Munich", 48.14, 11.57)
        val recent = Place("Library", "1 Main Street", 48.2, 11.6)
        val none = emptyList<Place>()

        assertEquals("Home", PlaceRank.mine("ho", home, work, none, none).first().name)
        assertEquals("the address is shown for it", "Exampletown", PlaceRank.mine("home", home, work, none, none).first().address)
        assertEquals(listOf("Home"), PlaceRank.mine("exam", home, work, none, none).map { it.name })
        assertEquals(listOf("Work"), PlaceRank.mine("work", home, work, none, none).map { it.name })
        assertEquals(listOf("Café Luitpold"), PlaceRank.mine("cafe", home, work, listOf(fav), listOf(recent)).map { it.name })
        assertEquals(listOf("Library"), PlaceRank.mine("main", null, null, listOf(fav), listOf(recent)).map { it.name })
        assertTrue(PlaceRank.mine("zzz", home, work, listOf(fav), listOf(recent)).isEmpty())
        assertTrue(PlaceRank.mine("", home, work, listOf(fav), listOf(recent)).isEmpty())
    }

    @Test
    fun aStarredPlaceThatIsAlsoRecentIsListedOnceAndAtMostFourShow() {
        val fav = p("Cafe One", 48.0, 11.0)
        val many = (1..8).map { p("Cafe ${it + 1}", 48.0 + it, 11.0) }
        val found = PlaceRank.mine("cafe", null, null, listOf(fav), listOf(fav) + many)
        assertEquals(4, found.size)
        assertEquals(1, found.count { it.name == "Cafe One" })
    }

    // ---- coordinates ----

    @Test
    fun aPairOfCoordinatesIsAPlace() {
        val c = PlaceRank.coordinates("48.137, 11.575")!!
        assertEquals(48.137, c.lat, 1e-9); assertEquals(11.575, c.lon, 1e-9)
        assertEquals("Coordinates", c.address)
        assertEquals(11.575, PlaceRank.coordinates("48.137 11.575")!!.lon, 1e-9)
        assertEquals(11.575, PlaceRank.coordinates("48.137;11.575")!!.lon, 1e-9)
        val south = PlaceRank.coordinates("-33.8688, 151.2093")!!
        assertEquals(-33.8688, south.lat, 1e-9); assertEquals(151.2093, south.lon, 1e-9)
    }

    @Test
    fun otherNumbersAreNotCoordinates() {
        assertNull("out of range", PlaceRank.coordinates("91.5, 10.5"))
        assertNull("out of range", PlaceRank.coordinates("45.5, 190.5"))
        assertNull("a house number and a postcode", PlaceRank.coordinates("10 20"))
        assertNull(PlaceRank.coordinates("12 Main Street"))
        assertNull(PlaceRank.coordinates("1.5, 2.5, 3.5"))
        assertNull(PlaceRank.coordinates("Cafe 48.137, 11.575"))
    }

    @Test
    fun anAnswerIsRememberedPerWordsAndPerKilometreOfPosition() {
        assertEquals(PlaceRank.cacheKey("Café  Bar", 48.1371, 11.5721), PlaceRank.cacheKey("cafe-bar", 48.1374, 11.5719))
        assertNotEquals(PlaceRank.cacheKey("cafe", 48.13, 11.57), PlaceRank.cacheKey("cafe", 48.25, 11.57))
        assertNotEquals(PlaceRank.cacheKey("cafe", 48.13, 11.57), PlaceRank.cacheKey("cafe", null, null))
    }

    @Test
    fun onlyLanguagesPhotonAcceptsAreAsked() {
        // Found against the live server: "it", "es" and "pt" are answered HTTP 400, which broke search on those phones.
        assertEquals("en", Places.language("en")); assertEquals("de", Places.language("de")); assertEquals("fr", Places.language("fr"))
        for (l in listOf("it", "es", "pt", "ja", "")) assertNull(l, Places.language(l))
    }

    // ---- reading the servers' answers ----

    @Test
    fun photonSaysWhatKindOfPlaceItIs() {
        val found = Places.parse(javaClass.getResource("/photon_search.json")!!.readText())
        assertEquals("Park · Munich, Bavaria", found[0].address)
        assertEquals("a house or a street keeps just its address", null, Places.kindLabel("building", "yes"))
        assertEquals("Petrol station", Places.kindLabel("amenity", "fuel"))
        assertEquals("Cafe", Places.kindLabel("amenity", "cafe"))
        assertEquals("Train station", Places.kindLabel("railway", "station"))
        assertEquals("Hot spring", Places.kindLabel("natural", "hot_spring"))
        assertNull(Places.kindLabel(null, "park"))
    }

    @Test
    fun nominatimAnswersBecomePlacesToo() {
        val json = """[
          {"place_id":1,"lat":"48.1687059","lon":"11.5503638","category":"leisure","type":"park","name":"Olympiapark",
           "display_name":"Olympiapark, Milbertshofen, Munich, Bavaria, Germany",
           "address":{"leisure":"Olympiapark","suburb":"Milbertshofen","city":"Munich","state":"Bavaria","country":"Germany"}},
          {"place_id":2,"lat":"48.1","lon":"11.5","category":"building","type":"house","name":"",
           "display_name":"12, Example Street, Exampletown, Region, Country",
           "address":{"house_number":"12","road":"Example Street","town":"Exampletown","state":"Region"}},
          {"place_id":3,"lat":"x","lon":"11.5","name":"Broken"}
        ]"""
        val found = Places.parseNominatim(json)
        assertEquals(2, found.size)
        assertEquals("Olympiapark", found[0].name)
        assertEquals("Park · Munich, Bavaria", found[0].address)
        assertEquals(48.1687059, found[0].lat, 1e-6) // lat first: not mixed up with Photon's lon, lat
        assertEquals(11.5503638, found[0].lon, 1e-6)
        assertEquals("12 Example Street", found[1].name)
        assertEquals("Exampletown, Region", found[1].address)
        assertTrue(Places.parseNominatim("[]").isEmpty())
    }

    // ---- the submitted search: both servers at once ----

    private val photon = """{"features":[{"geometry":{"coordinates":[11.55,48.17]},"properties":{"name":"Olympiapark","city":"Munich","osm_key":"leisure","osm_value":"park"}},
        {"geometry":{"coordinates":[13.4,52.5]},"properties":{"name":"Olympia Stadium","city":"Berlin","osm_key":"leisure","osm_value":"stadium"}}]}"""
    private val nominatim = """[{"lat":"48.1701","lon":"11.5511","category":"leisure","type":"park","name":"Olympiapark","address":{"city":"Munich"}},
        {"lat":"48.2","lon":"11.6","category":"tourism","type":"attraction","name":"Olympia Tower","address":{"city":"Munich"}}]"""

    private fun fake(photonMs: Long, nominatimMs: Long, photonOk: Boolean = true, nominatimOk: Boolean = true) {
        Places.fetcher = { url, _, _ ->
            if (url.startsWith(Places.NOMINATIM_URL)) {
                delay(nominatimMs); if (!nominatimOk) error("nominatim down"); nominatim
            } else {
                delay(photonMs); if (!photonOk) error("photon down"); photon
            }
        }
    }

    @Test
    fun theFirstAnswerShowsAtOnceAndTheOtherIsMergedIn() = runBlocking {
        fake(photonMs = 300, nominatimMs = 20)
        val shown = ArrayList<List<String>>()
        val any = Places.searchSubmitted("olympia", 48.0, 11.0, "test") { shown += it.map { p -> p.name } }
        assertTrue(any)
        assertEquals("one update per answer", 2, shown.size)
        assertEquals("Nominatim's answer first", setOf("Olympiapark", "Olympia Tower"), shown[0].toSet())
        assertTrue("then Photon's joined in", shown[1].contains("Olympia Stadium"))
        assertEquals("the same park from both is one row", 1, shown[1].count { it == "Olympiapark" })
        assertEquals("nearest first among equals", "Olympiapark", shown[1][0])
    }

    @Test
    fun aSlowSecondServerIsNotWaitedForBeyondTheGrace() = runBlocking {
        val cancelled = AtomicBoolean(false)
        Places.fetcher = { url, _, _ ->
            if (url.startsWith(Places.NOMINATIM_URL)) { delay(10); nominatim }
            else try { delay(10_000); photon } catch (e: CancellationException) { cancelled.set(true); throw e }
        }
        val started = System.currentTimeMillis()
        var updates = 0
        val any = Places.searchSubmitted("olympia", null, null, "test", graceMs = 150) { updates++ }
        val took = System.currentTimeMillis() - started
        assertTrue(any)
        assertEquals(1, updates)
        assertTrue("returned after the grace, not 10 s ($took ms)", took < 3_000)
        assertTrue("and the slow request was stopped", cancelled.get())
    }

    @Test
    fun oneServerDownIsNotAnError() = runBlocking {
        fake(photonMs = 10, nominatimMs = 10, photonOk = false)
        var last = emptyList<Place>()
        assertTrue(Places.searchSubmitted("olympia", null, null, "test") { last = it })
        assertEquals(2, last.size)
    }

    @Test
    fun bothDownIsAnErrorTheCallerCanShow() = runBlocking {
        fake(photonMs = 10, nominatimMs = 10, photonOk = false, nominatimOk = false)
        var updates = 0
        val failed = runCatching { Places.searchSubmitted("olympia", null, null, "test") { updates++ } }.exceptionOrNull()
        assertTrue(failed is IllegalStateException)
        assertEquals(0, updates)
    }

    @Test
    fun aNewerSearchStopsTheOlderOnesRequests() = runBlocking {
        val photonStopped = AtomicBoolean(false)
        val nominatimStopped = AtomicBoolean(false)
        Places.fetcher = { url, _, _ ->
            val flag = if (url.startsWith(Places.NOMINATIM_URL)) nominatimStopped else photonStopped
            try { delay(10_000); "[]" } catch (e: CancellationException) { flag.set(true); throw e }
        }
        val job = launch { Places.searchSubmitted("x", null, null, "test") { } }
        delay(100)
        job.cancel(); job.join()
        assertTrue(photonStopped.get()); assertTrue(nominatimStopped.get())
        assertFalse(job.isActive)
    }
}
