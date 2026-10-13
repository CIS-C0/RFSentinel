package com.rfsentinel.app.nav

import android.app.Application
import android.app.Notification
import android.location.Location
import android.os.Looper
import com.rfsentinel.app.online.AheadOnRoad
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.pickAttrs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Bugs found in a review of the navigation code: each has a test that fails without its fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavQaTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val lat0 = 10.0; private val lon0 = 20.0
    private val mPerDeg = 111_194.9
    private fun lat(y: Double) = lat0 + y / mPerDeg
    private fun lon(x: Double) = lon0 + x / (mPerDeg * Math.cos(Math.toRadians(lat0)))

    private var t = 1_000_000L
    private val spoken = ArrayList<String>()
    private var plannerCalls = 0

    @Before
    fun setUp() {
        Navigator.stop()
        Prefs.setNavVoice(ctx, false)
        t = 1_000_000L
        spoken.clear(); plannerCalls = 0
        Navigator.clock = { t }
        Navigator.speaker = { text, _ -> spoken += text }
        Navigator.planner = null
        Navigator.scanStarter = null
        ScanForegroundService.isRunning = false
        NavigationService.up = false
    }

    @After
    fun tearDown() {
        Navigator.scanStarter = null
        ScanForegroundService.isRunning = false
        Navigator.stop()
        Navigator.clock = { System.currentTimeMillis() }
        Navigator.speaker = null
        Navigator.planner = null
    }

    // ---- helpers ----

    private fun step(type: String, startM: Double, endM: Double, durationS: Double, leg: Int = 0) =
        Step(type, null, "", null, "", 0.0, 0.0, endM - startM, durationS, startM, endM, leg, emptyList(), emptyList())

    /** A road north of [km] kilometres that the route plans to drive at 16.7 m/s, in one-kilometre steps. */
    private fun northRoad(km: Int): Route {
        val ys = (0..km * 10).map { it * 100.0 }
        val lats = ys.map { lat(it) }.toDoubleArray(); val lons = DoubleArray(ys.size) { lon0 }
        val along = DoubleArray(ys.size)
        for (i in 1 until ys.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        val total = along.last()
        val steps = (0 until km).map { step("depart", it * 1000.0, (it + 1) * 1000.0, 60.0) } + step("arrive", total, total, 0.0)
        return Route(lats, lons, along, total, km * 60.0, steps, "", lats.last() to lons.last())
    }

    private fun fix(y: Double, x: Double = 0.0, speed: Float = 15f, bearing: Float = 0f) = Location("test").apply {
        latitude = lat(y); longitude = lon(x); this.speed = speed; this.bearing = bearing; accuracy = 5f
    }

    private fun feed(l: Location) {
        val m = Navigator::class.java.getDeclaredMethod("onLocation", Location::class.java)
        m.isAccessible = true
        m.invoke(Navigator, l)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val dest = Place("Library", "1 Main Street", lat(2000.0), lon0)

    private fun begin(r: Route, from: Location? = fix(0.0)) =
        Navigator.begin(ctx, dest, r, Navigator.defaultOptions(ctx), emptyList(), from)

    // ---- a route that passes close by itself further on does not move you along it ----

    private fun outAndBack(): Route {
        val out = (0..10).map { 0.0 to it * 100.0 }
        val back = (0..10).map { 30.0 to 1000.0 - it * 100.0 }
        val pts = out + back
        val lats = pts.map { lat(it.second) }.toDoubleArray(); val lons = pts.map { lon(it.first) }.toDoubleArray()
        val along = DoubleArray(pts.size)
        for (i in 1 until pts.size) along[i] = along[i - 1] + DeviceRegistry.metersBetween(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return Route(lats, lons, along, along.last(), 200.0, listOf(step("depart", 0.0, along.last(), 200.0)), "", lats.last() to lons.last())
    }

    @Test
    fun standingBesideALaterStretchOfTheRouteDoesNotJumpYouOntoIt() {
        val r = outAndBack()
        val before = RouteProgress.locate(r, lat(800.0), lon(0.0), null, null)
        assertEquals(800.0, before.alongM, 5.0)
        // Parked 25 m to the east: the way back (30 m east) is closer than the way out (0 m)... a moment later.
        val parked = RouteProgress.locate(r, lat(800.0), lon(22.0), null, before, advanceLimitM = 130.0)
        assertEquals("still on the way out", 800.0, parked.alongM, 10.0)
        // Without the limit the later stretch wins, which put you 400 m on and skipped the turn: the bug.
        val old = RouteProgress.locate(r, lat(800.0), lon(22.0), null, before)
        assertTrue("it would have jumped to the way back (${old.alongM})", old.alongM > 1_000.0)
    }

    @Test
    fun theAllowanceGrowsWithTimeAndSpeedSoARealJumpAfterATunnelStillWorks() {
        val r = outAndBack()
        val before = RouteProgress.locate(r, lat(100.0), lon(0.0), null, null)
        // 90 s in a tunnel at 15 m/s: you really could be 1.4 km on, on the way back.
        val after = RouteProgress.locate(r, lat(600.0), lon(30.0), 180.0, before, advanceLimitM = (15.0 + 10.0) * 90 * 1.5 + 50)
        assertTrue(after.alongM > 1_400.0)
    }

    // ---- a late answer from an earlier trip is ignored ----

    @Test
    fun aRouteForAnEarlierTripDoesNotReplaceTheCurrentOne() {
        val a = northRoad(2); val b = northRoad(3); val late = northRoad(1)
        begin(a)
        val asked = Navigator.generation
        begin(b, fix(0.0)) // another trip begins while a new route for the first was being looked for
        Navigator.finishReplan(asked, late, emptyList())
        assertTrue("trip B keeps its route", Navigator.route === b)
        Navigator.finishReplan(Navigator.generation, late, emptyList())
        assertTrue("an answer to the current trip is taken", Navigator.route === late)
        Navigator.stop()
        Navigator.finishReplan(Navigator.generation, northRoad(2), emptyList())
        assertNull("nothing comes back from the dead", Navigator.route)
    }

    // ---- no nagging, no hammering when there is no signal ----

    @Test
    fun withNoSignalTheNewRouteIsAskedForLessAndLessOftenAndSaidOnce() {
        Navigator.planner = { _, _, _, _, _ -> plannerCalls++; throw java.io.IOException("offline") }
        begin(northRoad(2))
        repeat(60) { t += 1_000; feed(fix(800.0, x = 300.0)); idle() } // a minute, 300 m off the road
        assertEquals("asked at 15 s, then after waiting twice as long", 2, plannerCalls)
        assertEquals("said once", 1, spoken.count { it == "Re-routing" })
        assertTrue(Navigator.status.orEmpty(), Navigator.status!!.contains("keeping the old one"))
    }

    @Test
    fun aFailedRerouteNeverShowsAHostNameOrStackText() {
        Navigator.planner = { _, _, _, _, _ -> throw java.net.UnknownHostException("Unable to resolve host \"valhalla1.openstreetmap.de\"") }
        begin(northRoad(2))
        repeat(20) { t += 1_000; feed(fix(800.0, x = 300.0)); idle() }
        assertEquals("No new route (no connection) - keeping the old one", Navigator.status)
        assertEquals("No route found between these places", Navigator.failureText(Valhalla.NoRouteException("No route found between these places")))
    }

    @Test
    fun backOnTheRouteTheNextTimeOffItIsSaidAfresh() {
        Navigator.planner = { _, _, _, _, _ -> plannerCalls++; throw java.io.IOException("offline") }
        begin(northRoad(2))
        repeat(20) { t += 1_000; feed(fix(800.0, x = 300.0)); idle() }
        assertEquals(1, spoken.count { it == "Re-routing" })
        t += 1_000; feed(fix(850.0)); idle() // back on the road
        assertNull(Navigator.status)
        repeat(60) { t += 1_000; feed(fix(900.0, x = 300.0)); idle() }
        assertEquals("a new episode is told again", 2, spoken.count { it == "Re-routing" })
    }

    // ---- arriving when you stop near the end ----

    @Test
    fun stoppingCloseToTheEndIsArriving() {
        begin(northRoad(2))
        // Parked in a bay 80 m short of the end of the line (the fix never gets inside 35 m).
        repeat(12) { t += 1_000; feed(fix(1_920.0, speed = 0f)) }
        assertTrue(Navigator.arrived)
        assertFalse(Navigator.active)
        assertTrue(spoken.any { it.startsWith("You have arrived") })
    }

    @Test
    fun slowingCloseToTheEndIsNotArrivingAndNeitherIsStoppingFarFromIt() {
        begin(northRoad(2))
        repeat(12) { t += 1_000; feed(fix(1_920.0, speed = 6f)) } // still driving
        assertTrue(Navigator.active)
        repeat(12) { t += 1_000; feed(fix(1_200.0, speed = 0f)) } // a red light 800 m before it
        assertTrue(Navigator.active)
        assertFalse(Navigator.arrived)
    }

    // ---- the arrival time is not inflated by waiting before you set off ----

    private fun drive(fromM: Double, toM: Double, speed: Float = 16.7f) {
        var m = fromM
        while (m < toM) { t += 1_000; m += speed; feed(fix(m.coerceAtMost(toM), speed = speed)) }
    }

    @Test
    fun sittingParkedBeforeYouSetOffDoesNotMakeTheTripLookSlow() {
        begin(northRoad(10), fix(0.0, speed = 0f))
        repeat(200) { t += 1_000; feed(fix(0.0, speed = 0f)) } // over three minutes with the route chosen, the engine on
        drive(0.0, 4_200.0)
        assertEquals("driven at the planned speed: as planned", 1.0, Navigator.pace, 0.06)
    }

    @Test
    fun aLongStopOnTheWayIsNotCountedAgainstThePace() {
        begin(northRoad(10))
        drive(0.0, 2_000.0)
        repeat(300) { t += 1_000; feed(fix(2_000.0, speed = 0f)) } // five minutes parked
        drive(2_000.0, 4_300.0)
        assertTrue("the stop counted in full would make it ${Navigator.pace}", Navigator.pace < 1.3)
    }

    @Test
    fun aSlowDriveStillShowsAsSlow() {
        begin(northRoad(10))
        drive(0.0, 4_200.0, speed = 11f) // two thirds of the planned speed
        assertTrue(Navigator.pace > 1.3)
    }

    // ---- the foreground service must always become one ----

    @Test
    fun theNavigationServiceGoesToTheForegroundEvenIfTheRouteHasAlreadyEnded() {
        Navigator.stop()
        val controller = Robolectric.buildService(NavigationService::class.java).create()
        val service = controller.get()
        controller.startCommand(0, 1) // as startForegroundService would, when arriving ended the trip before it started
        assertNotNull("startForeground was called", shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun guidanceEndingAtOnceDoesNotStopTheServiceBeforeItHasStartedForeground() {
        // Starting next to the destination: arriving on the first fix ends the trip while the service is still
        // starting. A stopService now crashes the app ("did not then call startForeground"); the service ends
        // itself when it starts (see the test above).
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStoppedService != null) { /* drain */ }
        begin(northRoad(2))
        Navigator.stop()
        assertNull("no stopService while the start is pending", app.nextStoppedService)
    }

    @Test
    fun onceInTheForegroundTheServiceIsStoppedWithTheTrip() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStoppedService != null) { /* drain */ }
        begin(northRoad(2))
        val controller = Robolectric.buildService(NavigationService::class.java).create()
        controller.startCommand(0, 1)
        assertTrue(NavigationService.up)
        Navigator.stop()
        val stopped = app.nextStoppedService
        assertNotNull("the running service is stopped", stopped)
        assertTrue(stopped!!.component!!.className.endsWith("NavigationService"))
    }

    @Test
    fun discreetModeKeepsTheTripOffTheNotification() {
        Prefs.setDiscreetMode(ctx, true)
        try {
            begin(northRoad(2))
            val controller = Robolectric.buildService(NavigationService::class.java).create()
            controller.startCommand(0, 1)
            val n = shadowOf(controller.get()).lastForegroundNotification
            assertEquals("Navigation active", n.extras.getString(Notification.EXTRA_TITLE))
        } finally {
            Prefs.setDiscreetMode(ctx, false)
        }
    }

    // ---- places are kept out of the cloud backup ----

    @Test
    fun placesLiveInTheirOwnFileAndLeaveTheMainOneAlone() {
        val a = Place("Home street", "1 Example Road", 10.0, 20.0)
        Prefs.setNavHome(ctx, a)
        Prefs.addRecentDestination(ctx, Place("Somewhere", "", 10.1, 20.1))
        val main = ctx.getSharedPreferences("rf_sentinel_prefs", 0)
        val own = ctx.getSharedPreferences("nav_places", 0)
        assertTrue(own.contains("nav_home") && own.contains("recent_destinations"))
        assertFalse(main.contains("nav_home") || main.contains("recent_destinations"))
        Prefs.clearNavPlaces(ctx); Prefs.clearRecentDestinations(ctx)
    }

    @Test
    fun recentsFromTheMainFileAreMovedOverOnce() {
        val main = ctx.getSharedPreferences("rf_sentinel_prefs", 0)
        main.edit().putString("recent_destinations", com.google.gson.Gson().toJson(listOf(Place("Old one", "x", 1.0, 2.0)))).commit()
        assertEquals("Old one", Prefs.recentDestinations(ctx).single().name)
        assertFalse("gone from the backed-up file", main.contains("recent_destinations"))
        assertTrue(ctx.getSharedPreferences("nav_places", 0).contains("recent_destinations"))
        Prefs.clearRecentDestinations(ctx)
    }

    @Test
    fun bothBackupRuleFilesLeaveThePlacesOut() {
        val dir = File("src/main/res/xml")
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = File(dir, name).readText()
            assertTrue("$name excludes nav_places.xml", xml.contains("""<exclude domain="sharedpref" path="nav_places.xml" />"""))
        }
        // On a direct phone-to-phone transfer (your own new phone) they may go along; the cloud section is the one that matters.
        val cloud = File(dir, "data_extraction_rules.xml").readText().substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        assertTrue(cloud.contains("nav_places.xml"))
    }

    // ---- avoid options are remembered for the next destination ----

    @Test
    fun aTickedAvoidOptionIsTheStartingChoiceForTheNextDestination() {
        Prefs.setNavAvoidTolls(ctx, false); Prefs.setNavAvoidHighways(ctx, false); Prefs.setNavAvoidFerries(ctx, false)
        assertFalse(Navigator.defaultOptions(ctx).avoidTolls)
        // Ticked while planning (the preview's chips)...
        Navigator.remember(ctx, Valhalla.Options(avoidTolls = true, avoidHighways = true))
        val next = Navigator.defaultOptions(ctx)
        assertTrue(next.avoidTolls); assertTrue(next.avoidHighways); assertFalse(next.avoidFerries)
        // ...and un-ticked again.
        Navigator.remember(ctx, Valhalla.Options(avoidTolls = true))
        assertFalse(Navigator.defaultOptions(ctx).avoidHighways)
        Navigator.remember(ctx, Valhalla.Options())
    }

    @Test
    fun changingOptionsOnTheTripMenuIsRememberedToo() {
        Navigator.remember(ctx, Valhalla.Options())
        Navigator.planner = { _, _, _, _, _ -> plannerCalls++; listOf(northRoad(2)) }
        begin(northRoad(2))
        Navigator.changeOptions(ctx, Valhalla.Options(avoidFerries = true))
        idle()
        assertEquals("a new route was asked for with them", 1, plannerCalls)
        assertTrue(Navigator.defaultOptions(ctx).avoidFerries)
        Navigator.remember(ctx, Valhalla.Options())
    }

    // ---- the scan starts with the guidance ----

    private fun startedServices(): List<String> {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        val names = ArrayList<String>()
        while (true) { names += (app.nextStartedService ?: break).component?.className.orEmpty() }
        return names
    }

    @Test
    fun startingADestinationStartsTheScanByDefault() {
        assertTrue("the default is on", Prefs.navStartScan(ctx))
        var started = 0
        Navigator.scanStarter = { started++ }
        begin(northRoad(2))
        assertEquals(1, started)
        assertTrue(Navigator.active)
    }

    @Test
    fun turnedOffInSettingsGuidanceLeavesTheScanAlone() {
        Prefs.setNavStartScan(ctx, false)
        var started = 0
        Navigator.scanStarter = { started++ }
        begin(northRoad(2))
        assertEquals(0, started)
        assertTrue("guidance itself is unaffected", Navigator.active)
    }

    @Test
    fun aScanAlreadyRunningIsNotStartedAgain() {
        ScanForegroundService.isRunning = true
        var started = 0
        Navigator.scanStarter = { started++ }
        begin(northRoad(2))
        assertEquals(0, started)
    }

    @Test
    fun withoutThePermissionsGuidanceStartsAndTheScanIsSkipped() {
        startedServices() // what earlier steps started
        begin(northRoad(2))
        assertTrue(Navigator.active)
        val started = startedServices()
        assertTrue("guidance service started: $started", started.any { it.endsWith("NavigationService") })
        assertFalse("the scan was not: $started", started.any { it.endsWith("ScanForegroundService") })
    }

    @Test
    fun withThePermissionsTheRealScanServiceIsStarted() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(*com.rfsentinel.app.util.Permissions.required().toTypedArray())
        startedServices()
        begin(northRoad(2))
        val started = startedServices()
        assertTrue("scan service started: $started", started.any { it.endsWith("ScanForegroundService") })
    }

    @Test
    fun theLastGuidanceFixIsForgottenWhenTheTripEnds() {
        begin(northRoad(2))
        assertNotNull("guidance has a position", Navigator.lastFix)
        Navigator.stop()
        assertNull("it must not stand in for a newer position afterwards", Navigator.lastFix)
    }

    // ---- smaller things ----

    @Test
    fun eachKindOfSpeechUsesItsOwnAudioSettings() {
        assertEquals("nav", pickAttrs(true, "alert", "nav", "latest"))
        assertEquals("alert", pickAttrs(false, "alert", "nav", "latest"))
        assertEquals("latest", pickAttrs(true, "alert", null, "latest"))
        assertEquals("latest", pickAttrs(false, null, "nav", "latest"))
    }

    @Test
    fun theOldRoadIsKeptThroughAGapInTheMapButNotThroughATurn() {
        assertTrue(AheadOnRoad.keepOldPath(movedM = 30.0, ageMs = 4_000, headingDiffDeg = 5.0))
        assertFalse("after a turn it describes a road you left", AheadOnRoad.keepOldPath(30.0, 4_000, 60.0))
        assertFalse("too long ago", AheadOnRoad.keepOldPath(30.0, 20_000, 5.0))
        assertFalse("too far", AheadOnRoad.keepOldPath(150.0, 4_000, 5.0))
    }

    @Test
    fun aLongRouteIsDrawnThinnedOutButKeepsItsEnds() {
        val r = northRoad(10)
        assertEquals(101, r.pointsFrom(0.0).size) // short enough: every point
        val thin = r.pointsFrom(0.0, maxPoints = 30)
        assertTrue(thin.size in 20..32)
        assertEquals(r.end, thin.last())
        assertEquals(lat(0.0), thin.first().first, 1e-6)
        assertTrue(r.pointsFrom(5_000.0).first().first > lat(4_900.0))
    }

    @Test
    fun theEndOfAnEarlierLegIsAStopNotTheDestination() {
        val steps = listOf(
            Step("depart", null, "", null, "", 0.0, 0.0, 1000.0, 60.0, 0.0, 1000.0, 0,
                listOf(Step.Voice(40.0, "You have arrived at your destination.")), emptyList()),
            Step("arrive", null, "", null, "", 0.0, 0.0, 0.0, 0.0, 1000.0, 1000.0, 0, emptyList(), emptyList()),
            Step("depart", null, "", null, "", 0.0, 0.0, 1000.0, 60.0, 1000.0, 2000.0, 1,
                listOf(Step.Voice(40.0, "You have arrived at your destination.")), emptyList()),
            Step("arrive", null, "", null, "", 0.0, 0.0, 0.0, 0.0, 2000.0, 2000.0, 1, emptyList(), emptyList()))
        val ys = (0..20).map { it * 100.0 }
        val lats = ys.map { lat(it) }.toDoubleArray(); val lons = DoubleArray(ys.size) { lon0 }
        val along = DoubleArray(ys.size) { it * 100.0 }
        val route = Route(lats, lons, along, 2000.0, 120.0, steps, "", lats.last() to lons.last())
        assertEquals("You have arrived at your stop.", Prompts().next(route, 0, 30.0, 10.0)?.text)
        assertEquals("You have arrived at your destination.", Prompts().next(route, 2, 30.0, 10.0)?.text)
    }
}
