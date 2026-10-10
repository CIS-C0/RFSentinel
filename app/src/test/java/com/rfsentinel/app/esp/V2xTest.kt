package com.rfsentinel.app.esp

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2X (ETSI ITS-G5) messages from a V2X2MAP board. The CAM / DENM bodies are golden vectors encoded
 * by asn1tools from ETSI's own ASN.1 (CAM v1.4.1 + CDD v1.3.1, CAM v1.3.2 + CDD v1.2.1, DENM v1.3.1),
 * with synthetic coordinates.
 */
class V2xTest {
    private class V(val name: String, hex: String) {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private val vectors = listOf(
        V("v2_police_lights_siren", "0202000003e9109260a77ce2a80be2389800c806470836db2c7f384124e26302f09a50280081fda0d01bc20948093c08aee6b28017d7840000000269a045bfffeffffb19c00189ff878013d8cfdff0f8027d8d100254ff4bc01dec696ff0fc027ec6a001f2be020180"),
        V("v2_ambulance_no_siren", "0202000003ea109260a5d944c80f034eb800c806470836db2c00708120006302f09a502ffe81fda0cb0345ffff7fffd8ce000c4ffc3c009ec67eff87c013ec688012a7fa5e00ef634b7f87e013f635000f93fb4f00c7b1abbfa5f00efb1ae00ae9fcb7808bd8d8dfc3f809fd8da00704fde3c059ec6defda7c063ec6e8044a7eb5e036f637b7e97e03bf638002893f3cf0207b1c3bf2df022fb1c601769f8f7812bd8e4df87f813fd8e600d44fc03c0a9ec73efbc7c0b3ec748076a7dc5e05ef63ab7da7e063f63b004193ec4f0347b1dbbeb5f036fb1de023e9f53781cbd8f0df4bf81dfd8f201384fa23c0f9ec79ef9e7c103ec7a80a8a7cd5e086f63db7cb7e08bf63e005a93e4cf0487b1f3be3df04afb1f603069f177826bd8fcdf0ff827fd8fe019c4f843c149ec7fef807c153ec8080daa7be5e0aef640b7bc7e0b3f641007393dd4f05c7b20bbdc5f05efb20e03ce9edb7830bd908a4"),
        V("v2_ordinary_car", "0202000003eb10924057997ee00bc59c6000c806470836db2c00e10122b6e302f09a502bff81fda0c10200"),
        V("v2_car_hf_only", "0202000003ec10920057ac91b00bb2899000c806470836db2c7f1c21212c6302f09a502a0b81fda0d01bc20948093c08aee6b28017d7840000000268"),
        V("v2_safety_car", "0202000003ed109260a7bfa4800b9f76c000c806470836db2c00000125dc6302f09a502bff81fda0cf021dffff7fffd8ce000c4ffc3c009ec67eff87c013ec688012ac53b0"),
        V("v2_roadworks", "0202000003ee109260a7d2b7500b8c63f000c806470836db2c00a8c120326302f09a502bff81fda0c9020dffff7fffd8ce000c4e0380"),
        V("v2_rsu", "0202000003ef109200f7e5ca200b79512000c806470836db2c80"),
        V("v1_police_lights_siren", "0102000007d1109260a77ce2a80be2389800c806470836db2c7f384124e26302f09a502800040fed0680de104a4049e046ee6b28017d7840000000269a045bfffeffffb19c00189ff878013d8cfdff0f8027d8d100254ff4bc01dec696ff0fc027ec6a001f2a60"),
        V("denm_emergency_approaching", "020100000bb980000005dc80038e8d4a510003a352944323be715405f11c4c00640323841b6d960a10be02"),
        V("denm_all_optional", "020100000bb98f800005dc80038e8d4a510003a352944325fe373c02d98f9400320191c20db6cb3400f003185385f020100010004c0012c67180"),
        V("denm_accident", "020100000bb98f800005dc80038e8d4a510003a352944325e65fb802f1671800320191c20db6cb3400f0031850802000"),
        V("denm_no_situation", "020100000bb900000005dc80038e8d4a510003a352944323ccbf7005e2ce3000640323841b6d960a"),
    ).associateBy { it.name }

    private fun cam(name: String) = V2x.cam(vectors.getValue(name).bytes, 0)!!
    private fun denm(name: String) = V2x.denm(vectors.getValue(name).bytes, 0)!!

    @Test
    fun aPoliceCarWithLightsAndSiren() {
        val m = cam("v2_police_lights_siren")
        assertEquals(10, m.stationType)
        assertEquals(10.5, m.lat!!, 1e-7); assertEquals(-20.5, m.lon!!, 1e-7)
        assertEquals(25.0, m.speedMps!!, 1e-9); assertEquals(90.0, m.headingDeg!!, 1e-9)
        assertEquals(6, m.role); assertEquals(5, m.special)
        assertEquals(true, m.lightBar); assertEquals(true, m.siren)
        val hit = V2x.hit(m)!!
        assertEquals(Category.V2X, hit.category)
        assertEquals("Emergency vehicle - lights and siren on", hit.label)
        assertEquals(Tier.STRONG, hit.tier)
    }

    @Test
    fun theOlderCamVersionToo() {
        val m = cam("v1_police_lights_siren")
        assertEquals(6, m.role); assertEquals(true, m.lightBar); assertEquals(true, m.siren)
        assertEquals(10.5, m.lat!!, 1e-7)
    }

    @Test
    fun aLongPathHistoryIsSkippedCorrectly() {
        // 40 path points (the most a CAM can carry) before the rescue container.
        val m = cam("v2_ambulance_no_siren")
        assertEquals(5, m.role); assertEquals(4, m.special)
        assertEquals(true, m.lightBar); assertEquals(false, m.siren)
        assertEquals(-11.5, m.lat!!, 1e-7); assertEquals(0.0, m.speedMps!!, 1e-9)
        assertEquals("Rescue vehicle - lights on", V2x.hit(m)!!.label)
    }

    @Test
    fun aSafetyCar() {
        val m = cam("v2_safety_car")
        assertEquals(7, m.role); assertEquals(false, m.lightBar); assertEquals(true, m.siren)
        assertEquals("Safety car - siren on", V2x.hit(m)!!.label)
    }

    @Test
    fun ordinaryTrafficIsNotFlagged() {
        val car = cam("v2_ordinary_car")
        assertEquals(5, car.stationType); assertEquals(0, car.role); assertEquals(13.89, car.speedMps!!, 1e-9)
        assertNull(V2x.hit(car))
        val hfOnly = cam("v2_car_hf_only")
        assertEquals(13.0, hfOnly.lat!!, 1e-7); assertEquals(45.0, hfOnly.headingDeg!!, 1e-9); assertNull(hfOnly.role)
        assertNull(V2x.hit(hfOnly))
        val works = cam("v2_roadworks")
        assertEquals(4, works.role); assertEquals(3, works.special); assertEquals(true, works.lightBar)
        assertNull("road works are not emergency vehicles", V2x.hit(works))
        val rsu = cam("v2_rsu")
        assertEquals(15, rsu.stationType); assertEquals(16.0, rsu.lat!!, 1e-7)
        assertNull(V2x.hit(rsu))
    }

    @Test
    fun denmEmergencyVehicleApproaching() {
        val m = denm("denm_emergency_approaching")
        assertEquals(95, m.cause); assertEquals(1, m.subCause)
        assertEquals(10.5, m.lat!!, 1e-7); assertEquals(-20.5, m.lon!!, 1e-7)
        assertEquals("Emergency vehicle approaching", V2x.hit(m)!!.label)
        val all = denm("denm_all_optional")
        assertEquals(95, all.cause); assertEquals(2, all.subCause); assertEquals(17.0, all.lat!!, 1e-7)
        assertEquals("Priority vehicle approaching", V2x.hit(all)!!.label)
    }

    @Test
    fun otherDenmsAreNotEmergencyVehicles() {
        val accident = denm("denm_accident")
        assertEquals(2, accident.cause); assertEquals(12.0, accident.lat!!, 1e-7)
        assertNull(V2x.hit(accident))
        val none = denm("denm_no_situation")
        assertNull(none.cause); assertEquals(12.0, none.lat!!, 1e-7)
    }

    // ---- The whole frame, as the board sends it

    /** 802.11 data header + LLC/SNAP + GeoNetworking (basic, common, single-hop broadcast) + BTP-B + [body]. */
    private fun frame(body: ByteArray, port: Int, mac: Int = 0x42): ByteArray {
        val wifi = ByteArray(24).also {
            it[0] = 0x08 // data
            for (i in 4..9) it[i] = 0xFF.toByte()
            for (i in 10..15) it[i] = (if (i == 15) mac else 0x02).toByte()
        }
        val llc = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0, 0, 0, 0x89.toByte(), 0x47)
        val basic = byteArrayOf(0x11, 0, 0x1A, 1) // next header: common
        val common = byteArrayOf(0x20, 0x50, 0, 0, 0, (body.size + 4).toByte(), 1, 0) // BTP-B, single-hop broadcast
        val lpv = ByteArray(24).also { it[0] = (10 shl 2).toByte() } // station type 10 in the GN address
        val shb = lpv + ByteArray(4)
        val btp = byteArrayOf((port shr 8).toByte(), port.toByte(), 0, 0)
        return wifi + llc + basic + common + shb + btp + body
    }

    @Test
    fun aWholeFrameFromTheBoard() {
        val m = V2x.decode(frame(vectors.getValue("v2_police_lights_siren").bytes, 2001))!!
        assertEquals(V2x.Kind.CAM, m.kind)
        assertEquals("02:02:02:02:02:42", m.mac)
        assertEquals(true, m.siren)
        assertEquals(10.5, m.lat!!, 1e-7)
        val d = V2x.decode(frame(vectors.getValue("denm_emergency_approaching").bytes, 2002))!!
        assertEquals(V2x.Kind.DENM, d.kind); assertEquals(95, d.cause)
    }

    @Test
    fun theStreamIsSplitAndResyncs() {
        val payload = frame(vectors.getValue("v2_ordinary_car").bytes, 2001)
        fun record(p: ByteArray) = "ITS5".toByteArray() + ByteArray(8) + byteArrayOf(p.size.toByte(), (p.size shr 8).toByte()) + p
        val stream = "boot log line\r\n".toByteArray() + record(payload) + "ITS5".toByteArray() + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0x7F) +
            record(payload)
        val s = V2x.Stream()
        assertTrue(V2x.looksLikeStream(stream, stream.size))
        // Fed in awkward pieces.
        val out = stream.toList().chunked(7).flatMap { c -> s.feed(c.toByteArray(), c.size) }
        assertEquals(2, out.size)
        assertNotNull(V2x.decode(out[0]))
        assertEquals(5, V2x.decode(out[1])!!.stationType)
    }

    @Test
    fun garbageNeverThrows() {
        val r = java.util.Random(7)
        repeat(500) {
            val b = ByteArray(r.nextInt(120)).also { r.nextBytes(it) }
            V2x.decode(b); V2x.cam(b, 0); V2x.denm(b, 0)
        }
    }
}
