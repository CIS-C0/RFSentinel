package com.rfsentinel.app.car

import android.app.Application
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.detect.RemoteId
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.service.ScanForegroundService
import com.rfsentinel.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds every Android Auto screen with the Car App testing library. Template
 * builders enforce the same limits Android Auto does (row counts, action counts,
 * allowed elements), so a template the car would reject fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarScreensTest {

    private lateinit var car: TestCarContext

    private val axonMac = "00:25:DF:11:22:33"
    private val droneMac = "60:60:1F:12:34:56"

    @Before
    fun setUp() {
        car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        SafeScreen.lastError = null
        com.rfsentinel.app.data.AlertLog.resetForTest()
        DeviceRegistry.startSession()
        ScanForegroundService.isRunning = true

        val axon = Advert(axonMac, Advert.Source.BLE, -58, "Axon Body 4")
        DeviceRegistry.report(
            axon,
            listOf(Hit(Category.BODY_CAM, "Axon body camera", 90, "BWCDEVICE tag in advert payload", "test")),
            DeviceIntel.Identity("Body camera", emptyList()), "Axon Enterprise, Inc.", null, null
        )
        val drone = Advert(droneMac, Advert.Source.BLE, -75)
        DeviceRegistry.report(
            drone,
            listOf(Hit(Category.DRONE, "Drone broadcasting Remote ID", 95, "ASTM F3411", "test")),
            DeviceIntel.Identity("Drone", emptyList()), "DJI",
            RemoteId.Info(uasId = "1581F5FJD123", latitude = 10.5, longitude = -20.5, heightM = 60.0), null
        )
        repeat(12) { i ->
            val a = Advert(String.format("C0:00:00:00:00:%02X", i), Advert.Source.BLE, -80 - i, "Device $i")
            DeviceRegistry.report(a, emptyList(), DeviceIntel.Identity("Phone", emptyList()), null, null, null)
        }
    }

    @After
    fun tearDown() {
        ScanForegroundService.isRunning = false
        ScanForegroundService.setLastFixForTest(null)
        com.rfsentinel.app.alpr.AlprStore.setForTest(emptyList())
        DeviceRegistry.clear()
        // A screen that failed to build shows an error message instead of crashing: catch that here.
        org.junit.Assert.assertNull("a car screen failed to render", SafeScreen.lastError)
    }

    @Test
    fun homeScreenShowsStatusNavigationAndButtons() {
        val t = HomeScreen(car).onGetTemplate() as ListTemplate
        val rows = t.singleList!!.items.map { it as Row }
        assertEquals(listOf("Recent alerts (0)", "Nearby devices (14)", "More"),
            listOf(rows[1], rows[3], rows[4]).map { it.title.toString() })
        assertTrue(rows[0].title.toString().startsWith("Strong match"))              // threat headline
        assertTrue(rows[0].texts[0].toString().contains("2 flagged"))
        assertTrue(rows[2].title.toString() in setOf("Live map & navigation", "Map: devices & cameras around me"))
        val actions = t.actionStrip!!.actions
        assertEquals(2, actions.size)
        assertEquals("Stop", actions[0].title.toString())
        assertTrue(actions[1].title == null && actions[1].icon != null)             // icon-only mute
    }

    @Test
    fun muteButtonTogglesAndRelabels() {
        val screen = HomeScreen(car)
        val mute = (screen.onGetTemplate() as ListTemplate).actionStrip!!.actions[1]
        mute.onClickDelegate!!.sendClick(object : OnDoneCallback {})
        assertTrue(Prefs.alertsMuted(car))
        val t = screen.onGetTemplate() as ListTemplate
        assertTrue((t.singleList!!.items[0] as Row).texts[0].toString().contains("muted"))
        val relabeled = t.actionStrip!!.actions[1]
        relabeled.onClickDelegate!!.sendClick(object : OnDoneCallback {})
        assertTrue(!Prefs.alertsMuted(car))
    }

    @Test
    fun listsRespectCarRowLimitAndOrder() {
        val t = DeviceListScreen(car, com.rfsentinel.app.ui.DeviceFilter.ALL).onGetTemplate() as ListTemplate
        val rows = t.singleList!!.items.map { it as Row }
        assertTrue(rows.size <= CarUi.listLimit(car))
        assertEquals("Drone broadcasting Remote ID", rows[0].title.toString()) // strongest evidence (95) first
        assertEquals("Axon body camera", rows[1].title.toString())

        val flagged = DeviceListScreen(car, com.rfsentinel.app.ui.DeviceFilter.FLAGGED).onGetTemplate() as ListTemplate
        assertEquals(2, flagged.singleList!!.items.size)
    }

    @Test
    fun detailPaneHasTwoButtonsAndNavigateForDrones() {
        val axon = DeviceDetailScreen(car, axonMac).onGetTemplate() as PaneTemplate
        assertEquals(listOf("Whitelist", "Watch"), axon.pane.actions.map { it.title.toString() })
        assertTrue(axon.pane.rows.size <= CarUi.paneLimit(car))

        val drone = DeviceDetailScreen(car, droneMac).onGetTemplate() as PaneTemplate
        assertTrue(drone.actionStrip!!.actions.any { it.title?.toString() == "Navigate" })
    }

    @Test
    fun devicesMapShowsFlaggedFirstInListColours() {
        // Synthetic positions around you at 10.5, -20.5: the Axon was heard 100 m north,
        // an ordinary phone 50 m east; the drone has its own Remote ID position.
        val here = DeviceRegistry.GeoSample(System.currentTimeMillis(), 10.5009, -20.5)
        DeviceRegistry.report(
            Advert(axonMac, Advert.Source.BLE, -50, "Axon Body 4"),
            listOf(Hit(Category.BODY_CAM, "Axon body camera", 90, "BWCDEVICE tag in advert payload", "test")),
            DeviceIntel.Identity("Body camera", emptyList()), "Axon Enterprise, Inc.", null, here
        )
        DeviceRegistry.report(
            Advert("C0:00:00:00:00:00", Advert.Source.BLE, -60, "Device 0"), emptyList(),
            DeviceIntel.Identity("Phone", emptyList()), null, null,
            DeviceRegistry.GeoSample(System.currentTimeMillis(), 10.5, -20.49954)
        )
        val items = DevicesMapScreen.around(10.5, -20.5, 6)
        assertEquals(listOf(droneMac, axonMac, "C0:00:00:00:00:00"), items.map { it.mac }) // flagged by evidence, then ordinary
        assertEquals(Category.BODY_CAM.colorArgb, items[1].color)
        assertEquals(DevicesMapScreen.ORDINARY_COLOR, items[2].color)
        assertEquals(100.0, items[1].distanceM, 5.0)

        ScanForegroundService.setLastFixForTest(android.location.Location("test").apply { latitude = 10.5; longitude = -20.5 })
        val t = DevicesMapScreen(car).onGetTemplate() as androidx.car.app.model.PlaceListMapTemplate
        assertEquals(3, t.itemList!!.items.size) // rows really present (each needs a DistanceSpan)
        assertEquals("Cameras", t.actionStrip!!.actions.single().title.toString())
        ScanForegroundService.setLastFixForTest(null)
    }

    @Test
    fun devicesMapMixesBluetoothAndWifiAndMergesOneNetwork() {
        DeviceRegistry.clear(); DeviceRegistry.startSession()
        val at = DeviceRegistry.GeoSample(System.currentTimeMillis(), 10.5, -20.5)
        fun wifi(mac: String, ssid: String, rssi: Int) = Advert(mac, Advert.Source.WIFI, rssi, ssid,
            wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList()))
        // One home network seen as three access points, all stronger than the Bluetooth devices.
        listOf(wifi("A0:00:00:00:00:01", "home", -40), wifi("A0:00:00:00:00:02", "home", -42),
            wifi("A0:00:00:00:00:03", "home", -45), wifi("A0:00:00:00:00:04", "cafe", -70)).forEach {
            DeviceRegistry.report(it, emptyList(), DeviceIntel.Identity("WiFi access point", emptyList()), null, null, at)
        }
        listOf(-60, -65, -75).forEachIndexed { i, rssi ->
            DeviceRegistry.report(Advert("B0:00:00:00:00:0$i", Advert.Source.BLE, rssi, "Watch $i"), emptyList(),
                DeviceIntel.Identity("Watch", emptyList()), null, null, at)
        }
        val macs = DevicesMapScreen.around(10.5, -20.5, 6).map { it.mac }
        assertEquals(
            listOf("B0:00:00:00:00:00", "A0:00:00:00:00:01", "B0:00:00:00:00:01", "A0:00:00:00:00:04", "B0:00:00:00:00:02"),
            macs // Bluetooth / WiFi alternate; "home" appears once (its strongest AP)
        )
    }

    @Test
    fun liveMapBuildsWithPanZoomAndNavigationPanel() {
        val screen = LiveMapScreen(car)
        val t = screen.onGetTemplate() as androidx.car.app.navigation.model.MapWithContentTemplate
        val strip = t.mapController!!.mapActionStrip!!
        assertTrue(strip.actions.any { it.type == androidx.car.app.model.Action.TYPE_PAN })
        assertEquals(4, strip.actions.size)
        val rows = (t.contentTemplate as ListTemplate).singleList!!.items.map { (it as Row).title.toString() }
        assertTrue(rows.contains("Navigate to..."))
    }

    @Test
    fun cameraFocusScreenCentresOnOnePlaceWithNavigate() {
        fun build() = PlaceFocusScreen(car, "Speed camera (50 km/h)", "Mapped in OpenStreetMap", 10.51, -20.5,
            androidx.car.app.model.CarColor.YELLOW, "S").onGetTemplate() as androidx.car.app.model.PlaceListMapTemplate
        // Without a position yet (no distance) and with one (DistanceSpan): both must build.
        ScanForegroundService.setLastFixForTest(null)
        assertEquals(1, build().itemList!!.items.size)
        ScanForegroundService.setLastFixForTest(android.location.Location("test").apply { latitude = 10.5; longitude = -20.5 })
        val t = build()
        assertEquals(1, t.itemList!!.items.size)
        assertEquals("Navigate", t.actionStrip!!.actions.single().title.toString())
        ScanForegroundService.setLastFixForTest(null)
    }

    @Test
    fun nearbyMapListsDronesThenClosestCameras() {
        // Synthetic positions: you at 10.5, -20.5; the test drone is at the same point.
        com.rfsentinel.app.alpr.AlprStore.setForTest(listOf(
            com.rfsentinel.app.alpr.KnownCamera("node/1", 10.503, -20.5, "Flock Safety", "Example PD", 90),
            com.rfsentinel.app.alpr.KnownCamera("node/2", 10.51, -20.5, "Motorola", null, null),
            com.rfsentinel.app.alpr.KnownCamera("node/3", 11.5, -20.5, "Far away", null, null)
        ))
        val items = NearbyMapScreen.nearby(10.5, -20.5, 6)
        assertEquals(3, items.size)                           // the far camera (~110 km) is outside the search radius
        assertTrue(items[0].drone)
        assertEquals("Flock Safety (ALPR)", items[1].title)
        assertEquals("330 m", NearbyMapScreen.distanceText(items[1].distanceM))
        assertEquals("1.1 km", NearbyMapScreen.distanceText(items[2].distanceM))

        // The template (host-drawn map + places) builds within Android Auto's limits - with a
        // location, so the rows are really there (each needs a DistanceSpan or build() throws).
        com.rfsentinel.app.service.ScanForegroundService.setLastFixForTest(
            android.location.Location("test").apply { latitude = 10.5; longitude = -20.5 }
        )
        val t = NearbyMapScreen(car).onGetTemplate() as androidx.car.app.model.PlaceListMapTemplate
        assertEquals(3, t.itemList!!.items.size)
        assertTrue(t.itemList!!.items.size <= CarUi.listLimit(car))
        com.rfsentinel.app.service.ScanForegroundService.setLastFixForTest(null)
        com.rfsentinel.app.alpr.AlprStore.setForTest(emptyList())
    }

    @Test
    fun goneDeviceShowsMessage() {
        val t = DeviceDetailScreen(car, "AA:BB:CC:DD:EE:FF").onGetTemplate()
        assertTrue(t is MessageTemplate)
    }

    @Test
    fun notScanningHomeOffersStart() {
        ScanForegroundService.isRunning = false
        val t = HomeScreen(car).onGetTemplate() as ListTemplate
        assertEquals("Start", t.actionStrip!!.actions[0].title.toString())
        assertEquals("Not scanning", (t.singleList!!.items[0] as Row).title.toString())
    }
}
