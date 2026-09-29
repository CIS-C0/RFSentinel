package com.rfsentinel.app.detect

import com.rfsentinel.app.service.DeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.sin

class IdentificationTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadVendors() {
            VendorDb.load { File("src/main/assets", it).inputStream() }
        }
    }

    private fun hit(c: Category, label: String, conf: Int, source: String = "test") = Hit(c, label, conf, "e", source)

    // ---- Evidence fusion ------------------------------------------------------

    @Test
    fun agreeingRulesStrengthenEachOther() {
        val fused = EvidenceFusion.fuse(listOf(
            hit(Category.PUBLIC_SAFETY, "Zebra mobile printer", 50),
            hit(Category.PUBLIC_SAFETY, "Watchlisted printer", 70)
        ))
        assertEquals("Watchlisted printer", fused.first().label)
        assertTrue(fused.first().confidence in 77..78) // 1 - 0.30 * (1 - 0.25) = 77.5 %
        assertTrue(fused.first().evidence.contains("Corroborated by"))
    }

    @Test
    fun fusionIgnoresOtherCategoriesWeakHitsAndCaps() {
        val other = EvidenceFusion.fuse(listOf(hit(Category.PUBLIC_SAFETY, "A", 60), hit(Category.DRONE, "B", 60)))
        assertEquals(60, other.first().confidence)
        val weak = EvidenceFusion.fuse(listOf(hit(Category.PUBLIC_SAFETY, "A", 60), hit(Category.PUBLIC_SAFETY, "TI block", 20)))
        assertEquals(60, weak.first().confidence)
        val many = EvidenceFusion.fuse((1..6).map { hit(Category.BODY_CAM, "R$it", 85) })
        assertEquals(EvidenceFusion.FUSED_CAP, many.first().confidence)
    }

    @Test
    fun clusterHitIsNeverFusedWithTheDevicesOwnMatch() {
        // A 2-role group scores 48 (weak). The radio's own 45 is what gave it its role,
        // so fusing the two would double-count and push it past the 50% alert line.
        val fused = EvidenceFusion.fuse(listOf(
            hit(Category.PUBLIC_SAFETY, "Motorola Solutions equipment", 45),
            hit(Category.PUBLIC_SAFETY, "Two-way radio in a possible police vehicle", 48, PatrolCluster.SOURCE)
        ))
        assertEquals(48, fused.first().confidence)
        assertTrue(fused.all { it.confidence < 50 })
    }

    // ---- Patrol-vehicle clusters ----------------------------------------------

    private fun wave(start: Long, phase: Double = 0.0, offset: Int = -70) =
        (0 until 60).map { s -> (start + s * 1000L) to (offset + (8 * sin(s / 5.0 + phase)).toInt()) }

    @Test
    fun rolesComeFromHitsVendorsAndNames() {
        assertEquals("mobile printer", PatrolCluster.roleOf(listOf(hit(Category.PUBLIC_SAFETY, "Zebra mobile printer", 50)), "Texas Instruments", null))
        assertEquals("two-way radio", PatrolCluster.roleOf(emptyList(), "Motorola Solutions Inc.", null))
        assertEquals("body camera", PatrolCluster.roleOf(listOf(hit(Category.BODY_CAM, "Axon body camera", 90)), null, null))
        assertEquals("mobile printer", PatrolCluster.roleOf(emptyList(), null, "RJ-4250WB_1234"))
        assertNull(PatrolCluster.roleOf(emptyList(), "Apple, Inc.", "iPhone"))
        // The cluster's own hit never counts as a role (no self-reinforcement).
        assertNull(PatrolCluster.roleOf(listOf(hit(Category.PUBLIC_SAFETY, "Possible police vehicle", 60, PatrolCluster.SOURCE)), null, null))
    }

    @Test
    fun devicesMovingTogetherFormAVehicle() {
        val now = 60_000L
        val members = listOf(
            PatrolCluster.Member("AA:00:00:00:00:01", "mobile printer", 0, now, wave(0)),
            PatrolCluster.Member("AA:00:00:00:00:02", "two-way radio", 0, now, wave(0, offset = -80)),
            PatrolCluster.Member("AA:00:00:00:00:03", "vehicle cellular router", 0, now, wave(0, phase = 3.1)) // opposite motion
        )
        val groups = PatrolCluster.groups(members, now)
        assertEquals(1, groups.size)
        assertEquals(setOf("mobile printer", "two-way radio"), groups[0].roles)
        assertTrue(groups[0].correlated)
        val h = PatrolCluster.hitFor("AA:00:00:00:00:01", groups)!!
        assertEquals(58, h.confidence) // 24 + 12*2 + 10
        assertNull(PatrolCluster.hitFor("AA:00:00:00:00:03", groups))
    }

    @Test
    fun sameRoleOrStaleDevicesDontCluster() {
        val now = 200_000L
        val twoRadios = listOf(
            PatrolCluster.Member("AA:00:00:00:00:01", "two-way radio", 150_000, now, wave(140_000)),
            PatrolCluster.Member("AA:00:00:00:00:02", "two-way radio", 150_000, now, wave(140_000))
        )
        assertTrue(PatrolCluster.groups(twoRadios, now).isEmpty())
        val stale = listOf(
            PatrolCluster.Member("AA:00:00:00:00:01", "two-way radio", 0, 10_000, emptyList()),
            PatrolCluster.Member("AA:00:00:00:00:02", "mobile printer", 0, now, emptyList())
        )
        assertTrue(PatrolCluster.groups(stale, now).isEmpty())
    }

    @Test
    fun aMovingDeviceAndAParkedOneAreNotAVehicle() {
        val now = 60_000L
        val parked = (0 until 60).map { it * 1000L to -60 }
        val members = listOf(
            PatrolCluster.Member("AA:00:00:00:00:01", "two-way radio", 0, now, wave(0)),
            PatrolCluster.Member("AA:00:00:00:00:02", "mobile printer", 5_000, now, parked)
        )
        assertTrue(PatrolCluster.groups(members, now).isEmpty())
        assertEquals(PatrolCluster.Motion.OneFlat, PatrolCluster.motion(wave(0), parked, now))
    }

    @Test
    fun consumerBrandsHaveNoPatrolRole() {
        assertNull(PatrolCluster.roleOf(emptyList(), "JVCKENWOOD Corporation", "KENWOOD CAR"))
        assertNull(PatrolCluster.roleOf(emptyList(), "Panasonic Connect Co., Ltd.", null))
    }

    @Test
    fun matchesFadeAfterTheEvidenceStops() {
        DeviceRegistry.startSession(0)
        val mac = "00:25:DF:00:00:42"
        val a = { t: Long -> Advert(mac, Advert.Source.BLE, -60, null, timestamp = t) }
        val id = DeviceIntel.Identity("x", emptyList())
        val axon = Hit(Category.BODY_CAM, "Axon body camera", 90, "e", "s")
        DeviceRegistry.report(a(1_000), listOf(axon), id, null, null, null, 1_000)
        // Intermittent tag: a packet without it keeps the match for a while...
        DeviceRegistry.report(a(30_000), emptyList(), id, null, null, null, 30_000)
        assertEquals("Axon body camera", DeviceRegistry.get(mac)!!.best?.label)
        // ...but not forever.
        DeviceRegistry.report(a(1_000 + DeviceRegistry.HIT_HOLD_MS + 1), emptyList(), id, null, null, null, 1_000 + DeviceRegistry.HIT_HOLD_MS + 1)
        assertNull(DeviceRegistry.get(mac)!!.best)
    }

    @Test
    fun flatSignalsFallBackToArrivalTime() {
        val flat = (0 until 30).map { it * 1000L to -60 }
        val now = 30_000L
        val together = listOf(
            PatrolCluster.Member("AA:00:00:00:00:01", "two-way radio", 0, now, flat),
            PatrolCluster.Member("AA:00:00:00:00:02", "mobile printer", 10_000, now, flat)
        )
        val g = PatrolCluster.groups(together, now)
        assertEquals(1, g.size)
        assertEquals(48, g[0].confidence)
    }

    // ---- Address rotation -------------------------------------------------------

    private fun axonAdvert(mac: String, time: Long) = Advert(
        mac, Advert.Source.BLE, -65, null, mapOf(0x034D to ByteArray(6)),
        timestamp = time
    )

    @Test
    fun rotatedAddressInheritsMatches() {
        DeviceRegistry.startSession(0)
        val axon = Hit(Category.BODY_CAM, "Axon / TASER equipment", 60, "Company ID 0x034D", "SIG")
        DeviceRegistry.report(axonAdvert("5A:11:22:33:44:01", 1_000), listOf(axon), DeviceIntel.Identity("x", emptyList()), null, null, null, 1_000)
        DeviceRegistry.report(axonAdvert("5A:11:22:33:44:02", 6_000), emptyList(), DeviceIntel.Identity("x", emptyList()), null, null, null, 6_000)
        val inherited = DeviceRegistry.inheritedHits("5A:11:22:33:44:02")
        assertEquals(1, inherited.size)
        assertEquals(55, inherited[0].confidence)
        assertTrue(DeviceRegistry.get("5A:11:22:33:44:02")!!.facts.any { it.first == "Previous address" })
    }

    @Test
    fun ambiguousOrUninformativeAdvertsAreNotLinked() {
        DeviceRegistry.startSession(0)
        val axon = Hit(Category.BODY_CAM, "Axon", 60, "e", "s")
        val id = DeviceIntel.Identity("x", emptyList())
        // Two identical candidates went silent: can't tell which one rotated.
        DeviceRegistry.report(axonAdvert("5A:00:00:00:00:01", 1_000), listOf(axon), id, null, null, null, 1_000)
        DeviceRegistry.report(axonAdvert("5A:00:00:00:00:02", 1_000), listOf(axon), id, null, null, null, 1_000)
        DeviceRegistry.report(axonAdvert("5A:00:00:00:00:03", 5_000), emptyList(), id, null, null, null, 5_000)
        assertTrue(DeviceRegistry.inheritedHits("5A:00:00:00:00:03").isEmpty())
        // Apple Continuity-only adverts carry no fingerprint at all.
        assertNull(AdvertFingerprint.of(Advert("5A:00:00:00:00:09", Advert.Source.BLE, -60, null, mapOf(0x004C to ByteArray(10)))))
    }

    // ---- Identification ----------------------------------------------------------

    @Test
    fun identifiesByServiceAndRegistrant() {
        val hr = DeviceIntel.identify(Advert("5A:00:00:00:00:01", Advert.Source.BLE, -60, null,
            serviceUuids = listOf(Advert.uuid16(0x180D))))
        assertEquals("Heart-rate sensor / fitness wearable", hr.type)
        assertTrue(hr.facts.contains("Identified by" to "advertised service"))
        assertTrue(hr.facts.first { it.first == "Services" }.second.contains("0x180D"))

        val router = DeviceIntel.identify(
            Advert("00:30:44:00:00:01", Advert.Source.WIFI, -60, "NETGEAR-5G",
                wifi = Advert.WifiInfo(2437, "[WPA2-PSK-CCMP]", null, emptyList())),
            VendorDb.macVendor("00:30:44:00:00:01")
        )
        assertEquals("Vehicle / cellular router (WiFi)", router.type)
        assertNotNull(router.facts.firstOrNull { it.first == "Identified by" })
    }
}
