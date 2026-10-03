package com.rfsentinel.app.car

import android.app.Application
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.alpr.AlprStore
import com.rfsentinel.app.alpr.KnownCamera
import com.rfsentinel.app.alpr.KnownCameras
import com.rfsentinel.app.data.AlertLog
import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.CellAnalyzer
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.RemoteId
import com.rfsentinel.app.receiver.AlertActionReceiver
import com.rfsentinel.app.service.CellTowerStore
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.ui.DeviceFilter
import com.rfsentinel.app.util.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reworked Android Auto screens: home rows and limits, the new screens
 * (recent alerts, nearby filters, cell towers, hardware, more, tracker ignore),
 * screen depth, change-only refreshes, alert buttons and robustness with no
 * data or a lot of it. Every test fails if any screen fell back to its error page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarReworkTest {

    private lateinit var car: TestCarContext
    private val axonMac = "00:25:DF:11:22:33"
    private val droneMac = "60:60:1F:12:34:56"

    @Before
    fun setUp() {
        car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        SafeScreen.lastError = null
        AlertLog.resetForTest()
        DeviceRegistry.startSession()
        ScanForegroundService.isRunning = true
        DeviceRegistry.report(
            Advert(axonMac, Advert.Source.BLE, -58, "Axon Body 4"),
            listOf(Hit(Category.BODY_CAM, "Axon body camera", 90, "BWCDEVICE tag in advert payload", "test")),
            DeviceIntel.Identity("Body camera", emptyList()), "Axon Enterprise, Inc.", null, null
        )
        DeviceRegistry.report(
            Advert(droneMac, Advert.Source.BLE, -75),
            listOf(Hit(Category.DRONE, "Drone broadcasting Remote ID", 95, "ASTM F3411", "test")),
            DeviceIntel.Identity("Drone", emptyList()), "DJI",
            RemoteId.Info(uasId = "1581F5FJD123", latitude = 10.5, longitude = -20.5, heightM = 60.0), null
        )
        repeat(12) { i ->
            DeviceRegistry.report(Advert(String.format("C0:00:00:00:00:%02X", i), Advert.Source.BLE, -80 - i, "Device $i"),
                emptyList(), DeviceIntel.Identity("Phone", emptyList()), null, null, null)
        }
    }

    @After
    fun tearDown() {
        ScanForegroundService.isRunning = false
        ScanForegroundService.setLastFixForTest(null)
        AlprStore.setForTest(emptyList())
        Prefs.setAlertsSnoozedUntil(car, 0L)
        DeviceRegistry.clear()
        assertNull("a car screen failed to render", SafeScreen.lastError)
    }

    private val done = object : OnDoneCallback {}
    private fun click(row: Row) = row.onClickDelegate!!.sendClick(done)
    private fun rows(t: Template) = (t as ListTemplate).singleList!!.items.map { it as Row }
    private fun screens(c: TestCarContext = car) = c.getCarService(TestScreenManager::class.java)
    private fun fix(lat: Double, lon: Double, bearing: Float? = null, speed: Float? = null) =
        ScanForegroundService.setLastFixForTest(android.location.Location("test").apply {
            latitude = lat; longitude = lon
            bearing?.let { this.bearing = it }
            speed?.let { this.speed = it }
        })

    @Test
    fun homeShowsNextCameraAheadAndKeepsMoreWithinTheRowLimit() {
        AlprStore.setForTest(listOf(
            KnownCamera("node/9", 10.504, -20.5, null, "Example DOT", null, KnownCamera.Kind.SPEED, 50)
        ))
        fix(10.5, -20.5, bearing = 0f, speed = 15f) // driving north, the camera ~440 m ahead
        val r = rows(HomeScreen(car).onGetTemplate())
        assertTrue(r.size <= CarUi.listLimit(car))
        assertEquals("Speed camera (50 km/h) ahead", r[1].title.toString())
        assertTrue(r[1].texts[0].toString().startsWith("440 m"))
        assertEquals("More", r.last().title.toString())
        click(r[1])
        assertTrue(screens().top is PlaceFocusScreen)
    }

    @Test
    fun cameraAheadFollowsTheHeading() {
        val cams = listOf(
            KnownCamera("node/n", 10.505, -20.5, null, null, null),
            KnownCamera("node/s", 10.4975, -20.5, null, null, null)
        )
        assertEquals("node/n", KnownCameras.ahead(cams, 10.5, -20.5, 0f)!!.first.osmId)
        assertEquals("node/s", KnownCameras.ahead(cams, 10.5, -20.5, 180f)!!.first.osmId)
        assertEquals("node/s", KnownCameras.ahead(cams, 10.5, -20.5, null)!!.first.osmId) // parked: nearest (~280 m)
        assertNull(KnownCameras.ahead(cams, 10.5, -20.5, 90f))                            // nothing to the east
        assertNull(KnownCameras.ahead(cams, 10.5, -20.5, 180f) { it.osmId == "node/s" })  // silenced
        assertEquals(90.0, KnownCameras.bearingTo(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    @Test
    fun cameraViewTogglesItsAlerts() {
        val cam = KnownCamera("node/7", 10.503, -20.5, "Flock Safety", null, null)
        fix(10.5, -20.5)
        val screen = PlaceFocusScreen.forCamera(car, cam)
        val row = (screen.onGetTemplate() as PlaceListMapTemplate).itemList!!.items.single() as Row
        click(row)
        assertTrue(com.rfsentinel.app.alpr.IgnoredCameras.contains(car, "node/7"))
        val again = (screen.onGetTemplate() as PlaceListMapTemplate).itemList!!.items.single() as Row
        assertTrue(again.texts[0].toString().contains("Alerts off"))
        click(again)
        assertFalse(com.rfsentinel.app.alpr.IgnoredCameras.contains(car, "node/7"))
    }

    @Test
    fun recentAlertsStayAfterTheDeviceLeaves() {
        val gone = "D4:00:00:00:00:01"
        AlertLog.add(car, gone, gone, Hit(Category.PUBLIC_SAFETY, "Police vehicle equipment", 85, "Router and printer together", "test"),
            -70, following = false, lat = 10.51, lon = -20.5)
        AlertLog.add(car, "alpr:node/1", null, Hit(Category.ALPR, "Known plate camera ahead", 80, "Plate reader, ~200 m away", "test"),
            null, following = false, lat = 10.503, lon = -20.5)
        val r = rows(RecentAlertsScreen(car).onGetTemplate())
        assertEquals(listOf("Known plate camera ahead", "Police vehicle equipment"), r.map { it.title.toString() })

        // Out of range: its details come from the last alert, with Whitelist + Navigate there.
        val pane = DeviceDetailScreen(car, gone).onGetTemplate() as PaneTemplate
        assertEquals(listOf("Whitelist", "Navigate there"), pane.pane.actions.map { it.title.toString() })
        assertTrue(pane.pane.rows[0].texts[0].toString().startsWith("Out of range"))

        click(r[1])
        assertTrue(screens().top is DeviceDetailScreen)
    }

    @Test
    fun alertLogKeepsNewestFirstWithoutDuplicates() {
        val hit = Hit(Category.BODY_CAM, "Axon body camera", 90, "x", "test")
        repeat(50) { i -> AlertLog.add(car, "K$i", "K$i", hit, -60, false, null, null, now = 1_000L + i) }
        AlertLog.add(car, "K3", "K3", hit, -55, true, null, null, now = 9_999L)
        val all = AlertLog.recent(car)
        assertEquals(AlertLog.MAX, all.size)
        assertEquals("K3", all[0].key)
        assertEquals(1, all.count { it.key == "K3" })
        assertTrue(all[0].following)
    }

    @Test
    fun nearbyDevicesOffersThePhoneFiltersWithCounts() {
        val r = rows(NearbyDevicesScreen(car).onGetTemplate())
        assertTrue(r.size <= CarUi.listLimit(car))
        val titles = r.map { it.title.toString() }
        assertEquals("All nearby (14)", titles[0])
        assertEquals("Flagged nearby (2)", titles[1])
        assertTrue("Drones (1)" in titles)
        click(r[1])
        val list = screens().top as DeviceListScreen
        assertEquals(2, rows(list.onGetTemplate()).size)
    }

    @Test
    fun cellsHardwareAndMoreScreensBuild() {
        assertEquals(1, rows(CellsScreen(car).onGetTemplate()).size) // status row only
        val serving = CellAnalyzer.Cell(CellAnalyzer.Rat.LTE, true, "001", "01", 100, 12345L, -95, operator = "Test Net")
        val neighbour = CellAnalyzer.Cell(CellAnalyzer.Rat.LTE, false, null, null, null, null, -110, pci = 7)
        CellTowerStore.record(car, listOf(serving, neighbour), null)
        val c = rows(CellsScreen(car).onGetTemplate())
        assertEquals(3, c.size)
        assertTrue(c[1].title.toString().endsWith("serving"))
        assertTrue(CellsScreen.summary()!!.contains("1 neighbour"))
        // With cells known and no camera ahead, home lists them too.
        assertTrue(rows(HomeScreen(car).onGetTemplate()).any { it.title.toString() == "Cell towers" })
        CellTowerStore.forget(car)

        val hw = rows(HardwareScreen(car).onGetTemplate()).map { it.title.toString() }
        assertEquals(listOf("Phone radios", "ESP32 on USB", "OUI-SPY over Bluetooth", "USB WiFi adapter"), hw)

        val more = rows(MoreScreen(car).onGetTemplate())
        assertTrue(more.size <= CarUi.listLimit(car))
        assertEquals("Snooze alerts 30 min", more[0].title.toString())
    }

    @Test
    fun snoozeSilencesAlertsAndResumes() {
        val screen = MoreScreen(car)
        click(rows(screen.onGetTemplate())[0])
        val now = System.currentTimeMillis()
        assertTrue(Prefs.alertsSilenced(car))
        assertFalse(Prefs.alertsMuted(car)) // a snooze, not the mute switch
        assertTrue(Prefs.alertsSilenced(car, now + 29 * 60_000L))
        assertFalse(Prefs.alertsSilenced(car, now + 31 * 60_000L))
        val r = rows(screen.onGetTemplate())
        assertEquals("Resume alert sound", r[0].title.toString())
        assertTrue(rows(HomeScreen(car).onGetTemplate())[0].texts[0].toString().contains("snoozed"))
        click(r[0])
        assertFalse(Prefs.alertsSilenced(car))
    }

    @Test
    fun trackerIgnoreOffersThePhonesThreeChoices() {
        val tag = "D1:22:33:44:55:66"
        DeviceRegistry.report(
            Advert(tag, Advert.Source.BLE, -60, addressType = AddressType.RANDOM_STATIC),
            listOf(Hit(Category.TRACKER, "Apple Find My tracker away from its owner (AirTag or compatible)", 70, "x", "test")),
            DeviceIntel.Identity("Tracker", emptyList()), null, null, null
        )
        val detail = DeviceDetailScreen(car, tag).onGetTemplate() as PaneTemplate
        assertEquals("Ignore...", detail.pane.actions[0].title.toString())
        val sm = screens()
        sm.push(DeviceDetailScreen(car, tag))
        sm.push(TrackerIgnoreScreen(car, tag))
        val r = rows(sm.top.onGetTemplate())
        assertEquals(3, r.size)
        click(r[1]) // today only
        assertTrue(com.rfsentinel.app.data.TrackerMutes.isMuted(tag))
        assertTrue(sm.top is DeviceDetailScreen) // back to the device
        com.rfsentinel.app.data.TrackerMutes.unmute(car, tag)
    }

    @Test
    fun deepestPathsStayWithinTheFiveScreenLimit() {
        // Home > Nearby devices > Flagged > a device: the longest list path (+ tracker Ignore = 5).
        val sm = screens()
        sm.push(HomeScreen(car))
        click(rows(sm.top.onGetTemplate()).first { it.title.toString().startsWith("Nearby devices") })
        click(rows(sm.top.onGetTemplate())[1])
        click(rows(sm.top.onGetTemplate())[0])
        assertTrue(sm.top is DeviceDetailScreen)
        assertTrue(sm.stackSize <= 4)

        // Home > devices map > its Cameras > a camera: the car-drawn map path (+ live map = 5).
        AlprStore.setForTest(listOf(KnownCamera("node/1", 10.503, -20.5, "Flock Safety", null, null)))
        fix(10.5, -20.5)
        val car2 = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        val sm2 = screens(car2)
        sm2.push(HomeScreen(car2))
        sm2.push(DevicesMapScreen(car2))
        (sm2.top.onGetTemplate() as PlaceListMapTemplate).actionStrip!!.actions[0].onClickDelegate!!.sendClick(done)
        val camRow = (sm2.top.onGetTemplate() as PlaceListMapTemplate).itemList!!.items
            .map { it as Row }.first { it.title.toString().contains("Flock") }
        click(camRow)
        assertTrue(sm2.top is PlaceFocusScreen)
        assertTrue(sm2.stackSize <= 4)
    }

    @Test
    fun screensOnlyRefreshWhenTheirContentChanges() {
        val home = HomeScreen(car)
        home.onGetTemplate()
        assertFalse(home.needsRefresh())
        DeviceRegistry.report(Advert("C1:00:00:00:00:01", Advert.Source.BLE, -70, "New"), emptyList(),
            DeviceIntel.Identity("Phone", emptyList()), null, null, null)
        assertTrue(home.needsRefresh())

        val list = DeviceListScreen(car, DeviceFilter.FLAGGED)
        list.onGetTemplate()
        assertFalse(list.needsRefresh())
    }

    @Test
    fun everyScreenBuildsWhenEmptyNotScanningAndWithoutLocation() {
        ScanForegroundService.isRunning = false
        DeviceRegistry.clear()
        val all = listOf(
            HomeScreen(car), NearbyDevicesScreen(car), RecentAlertsScreen(car), CellsScreen(car), HardwareScreen(car),
            MoreScreen(car), DevicesMapScreen(car), NearbyMapScreen(car), LiveMapScreen(car),
            DeviceDetailScreen(car, axonMac), TrackerIgnoreScreen(car, axonMac)
        ) + DeviceFilter.entries.map { DeviceListScreen(car, it) }
        all.forEach { it.onGetTemplate() }
    }

    @Test
    fun manyDevicesStayWithinLimits() {
        repeat(500) { i ->
            val a = Advert(String.format("E0:00:00:00:%02X:%02X", i / 256, i % 256),
                if (i % 2 == 0) Advert.Source.BLE else Advert.Source.WIFI, -40 - (i % 60), "Dev $i")
            val hits = if (i % 7 == 0) listOf(Hit(Category.BODY_CAM, "Body cam $i", 50 + i % 50, "x", "test")) else emptyList()
            DeviceRegistry.report(a, hits, DeviceIntel.Identity("Phone", emptyList()), null, null,
                DeviceRegistry.GeoSample(System.currentTimeMillis(), 10.5 + i * 1e-5, -20.5))
        }
        fix(10.5, -20.5)
        val limit = CarUi.listLimit(car)
        assertTrue(rows(HomeScreen(car).onGetTemplate()).size <= limit)
        assertTrue(rows(NearbyDevicesScreen(car).onGetTemplate()).size <= limit)
        assertEquals(limit, rows(DeviceListScreen(car, DeviceFilter.ALL).onGetTemplate()).size)
        val map = DevicesMapScreen(car).onGetTemplate() as PlaceListMapTemplate
        assertTrue(map.itemList!!.items.size <= 6)
    }

    @Test
    fun alertButtonsSnoozeAndIgnore() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        AlertActionReceiver().onReceive(ctx, android.content.Intent(AlertActionReceiver.ACTION_SNOOZE))
        assertTrue(Prefs.alertsSilenced(ctx))
        Prefs.setAlertsSnoozedUntil(ctx, 0L)

        val msg = runBlocking { AlertActionReceiver.ignore(ctx, axonMac) }
        assertTrue(msg.startsWith("Whitelisted"))
        val wl = runBlocking { com.rfsentinel.app.data.AppDatabase.getInstance(ctx).whitelistDao().all().first() }
        assertTrue(wl.any { it.mac == axonMac })
    }

    @Test
    fun alertNotificationIsLoggedAndHasCarButtons() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        com.rfsentinel.app.util.NotificationHelper.createChannels(ctx)
        val hit = Hit(Category.BODY_CAM, "Axon body camera", 90, "BWCDEVICE tag", "test")
        com.rfsentinel.app.util.NotificationHelper.sendAlert(ctx, axonMac, hit, -58)
        assertEquals(axonMac, AlertLog.recent(ctx).first().mac)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val posted = org.robolectric.Shadows.shadowOf(nm).allNotifications.last()
        val actions = androidx.car.app.notification.CarAppExtender(posted).actions
        assertEquals(listOf("Mute 30 min", "Ignore"), actions.map { it.title.toString() })
    }
}
