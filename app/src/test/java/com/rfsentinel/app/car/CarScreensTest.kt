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
        DeviceRegistry.clear()
    }

    @Test
    fun homeScreenShowsStatusNavigationAndButtons() {
        val t = HomeScreen(car).onGetTemplate() as ListTemplate
        val rows = t.singleList!!.items.map { it as Row }
        assertEquals(6, rows.size)
        assertTrue(rows[0].title.toString().startsWith("Strong match"))              // threat headline
        assertTrue(rows[1].title.toString().startsWith("Flagged nearby (2)"))
        assertTrue(rows[2].title.toString().startsWith("Drones & trackers (1)"))
        assertTrue(rows[3].title.toString().startsWith("All nearby devices (14)"))
        assertEquals("Map: cameras, devices & drones", rows[4].title.toString())
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
        assertTrue((t.singleList!!.items[5] as Row).texts[0].toString().contains("muted"))
        val relabeled = t.actionStrip!!.actions[1]
        relabeled.onClickDelegate!!.sendClick(object : OnDoneCallback {})
        assertTrue(!Prefs.alertsMuted(car))
    }

    @Test
    fun listsRespectCarRowLimitAndOrder() {
        val t = DeviceListScreen(car, DeviceListScreen.Filter.ALL).onGetTemplate() as ListTemplate
        val rows = t.singleList!!.items.map { it as Row }
        assertTrue(rows.size <= CarUi.listLimit(car))
        assertEquals("Drone broadcasting Remote ID", rows[0].title.toString()) // strongest evidence (95) first
        assertEquals("Axon body camera", rows[1].title.toString())

        val flagged = DeviceListScreen(car, DeviceListScreen.Filter.FLAGGED).onGetTemplate() as ListTemplate
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

        // The template (host-drawn map + places) builds within Android Auto's limits.
        val t = NearbyMapScreen(car).onGetTemplate() as androidx.car.app.model.PlaceListMapTemplate
        assertTrue(t.itemList!!.items.size <= CarUi.listLimit(car))
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
