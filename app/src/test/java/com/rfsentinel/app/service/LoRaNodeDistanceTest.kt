package com.rfsentinel.app.service

import android.app.Application
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.esp.isLoRaNode
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

/** A Meshtastic node heard over LoRa: distance from the GPS position it shares, never from its signal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoRaNodeDistanceTest {

    private val t0 = 1_000_000L
    private val id = DeviceIntel.Identity("Device", emptyList())
    private val mac = "06:4D:C6:16:D9:DD"
    private val name = "Meshtastic: !c616d9dd"

    @Before fun setUp() { DeviceRegistry.clear(); DeviceRegistry.startSession(t0) }
    @After fun tearDown() = DeviceRegistry.clear()

    @Test
    fun recognisesOnlyTheNodesAMeshRadioReports() {
        assertTrue(isLoRaNode(mac, name))
        assertTrue(isLoRaNode("06:43:01:02:03:04", "MeshCore: base"))
        assertFalse(isLoRaNode("F0:05:04:B9:DB:9A", "Meshtastic_db9a")) // the node's own Bluetooth advert
        assertFalse(isLoRaNode(mac, null))
        assertFalse(isLoRaNode("06:4D:00:00:00:01", "Headphones"))
    }

    @Test
    fun distanceIsFromTheSharedPositionAndTheNodeIsPlacedThere() {
        val here = DeviceRegistry.GeoSample(t0, 10.5, -20.5)
        val node = DeviceRegistry.GeoSample(t0, 10.52, -20.5) // about 2.2 km north
        DeviceRegistry.report(Advert(mac, Advert.Source.BLE, -60, name), emptyList(), id, null, null, null, t0,
            broadcast = node, here = here, lora = true)
        val s = DeviceRegistry.get(mac)!!
        assertEquals(DeviceRegistry.metersBetween(10.5, -20.5, 10.52, -20.5), s.distanceM, 1.0)
        assertEquals(10.52, s.place!!.lat, 1e-9)
    }

    @Test
    fun withoutAPositionTheDistanceIsUnknownAndItIsNotOnTheMap() {
        DeviceRegistry.report(Advert(mac, Advert.Source.BLE, -49, name), emptyList(), id, null, null, null, t0,
            here = DeviceRegistry.GeoSample(t0, 10.5, -20.5), lora = true)
        val s = DeviceRegistry.get(mac)!!
        assertTrue(s.distanceM.isNaN())
        assertNull(s.place)
        assertEquals("distance unknown", DeviceIntel.formatDistance(s.distanceM))
    }

    @Test
    fun aDeviceAnotherSentinelHeardIsAsFarAsThatSentinel() {
        val here = DeviceRegistry.GeoSample(t0, 10.5, -20.5)
        val peer = DeviceRegistry.GeoSample(t0, 10.5, -20.53) // the other Sentinel, about 3.3 km west
        val ble = "AA:00:00:00:00:07"
        DeviceRegistry.report(Advert(ble, Advert.Source.BLE, -65, null), emptyList(), id, null, null, peer, t0, here = here, via = true)
        assertEquals(DeviceRegistry.metersBetween(10.5, -20.5, 10.5, -20.53), DeviceRegistry.get(ble)!!.distanceM, 1.0)

        // A LoRa node the other Sentinel's node heard: its position doesn't travel, so its distance is unknown...
        DeviceRegistry.report(Advert(mac, Advert.Source.BLE, -48, name), emptyList(), id, null, null, null, t0, here = here, lora = true, via = true)
        assertTrue(DeviceRegistry.get(mac)!!.distanceM.isNaN())
        // ...until this phone's own node hears it, and the other Sentinel's report then keeps that distance.
        val node = DeviceRegistry.GeoSample(t0, 10.52, -20.5)
        DeviceRegistry.report(Advert(mac, Advert.Source.BLE, -60, name), emptyList(), id, null, null, null, t0 + 1_000,
            broadcast = node, here = here, lora = true)
        DeviceRegistry.report(Advert(mac, Advert.Source.BLE, -48, name), emptyList(), id, null, null, null, t0 + 2_000, here = here, lora = true, via = true)
        assertEquals(DeviceRegistry.metersBetween(10.5, -20.5, 10.52, -20.5), DeviceRegistry.get(mac)!!.distanceM, 1.0)
    }
}
