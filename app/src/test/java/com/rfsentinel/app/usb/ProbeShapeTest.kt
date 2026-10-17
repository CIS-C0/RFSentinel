package com.rfsentinel.app.usb

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.SignatureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class ProbeShapeTest {

    /** Wildcard name, 802.11b rates, extended rates, Wi-Fi Alliance vendor element. */
    private val ies = byteArrayOf(0, 0, 1, 4, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(),
        0x32, 4, 0x0C, 0x12, 0x18, 0x60, 0xDD.toByte(), 9, 0x50, 0x6F, 0x9A.toByte(), 0x16, 3, 1, 3, 0xAA.toByte(), 0xBB.toByte())

    private fun probe(fcs: Boolean): ByteArray {
        val f = ByteArray(24).also { it[0] = 0x40 } + ies
        if (!fcs) return f
        val c = CRC32().apply { update(f) }.value
        return f + ByteArray(4) { ((c shr (8 * it)) and 0xff).toByte() }
    }

    @Test
    fun matchesFlipDeFlocksHash() {
        // Reference value from a line-by-line port of FlipDeFlock's ie_skeleton_hash().
        assertEquals(0xDFAE8CC6L, ProbeIntel.skeletonHash(ies, 0, ies.size))
        // Vendor element contents past OUI + type don't count; a truncated element ends it.
        val other = ies.copyOf().also { it[23] = 0x11; it[24] = 0x22 } + byteArrayOf(0xDD.toByte(), 40)
        assertEquals(0xDFAE8CC6L, ProbeIntel.skeletonHash(other, 0, other.size))
        assertNull(ProbeIntel.skeletonHash(ies, 0, 0))
    }

    @Test
    fun aKeptChecksumIsLeftOut() {
        val plain = probe(false); val withFcs = probe(true)
        assertEquals(plain.size, ProbeIntel.withoutFcs(plain, 0, plain.size))
        assertEquals(plain.size, ProbeIntel.withoutFcs(withFcs, 0, withFcs.size))
    }

    private fun client(mac: String, shape: Long) = Advert(mac = mac, source = Advert.Source.WIFI, rssi = -70, name = null,
        wifi = Advert.WifiInfo(2437, "", null, emptyList(), client = true, probeSkeleton = shape), timestamp = 0L)

    @Test
    fun aCandidateFlockShapeIsAWeakHit() {
        val random = SignatureEngine.classify(client("DA:11:22:33:44:55", 0x42D75CD1L))
            .single { it.label == "Possible Flock camera (probe shape)" }
        assertEquals(Category.ALPR, random.category)
        assertEquals(35, random.confidence)
        assertTrue(random.evidence.contains("42d75cd1"))
        val onModule = SignatureEngine.classify(client("24:B2:B9:33:44:55", 0xD0BBEC4CL))
            .single { it.label == "Possible Flock camera (probe shape)" }
        assertEquals(50, onModule.confidence)
    }

    @Test
    fun commonShapesAreNeverACamera() {
        for (shape in listOf(0x96FCD1B2L, 0x89C3DEBFL, 0xBA9FAFA0L, 0xDFAE8CC6L))
            assertTrue(SignatureEngine.classify(client("DA:11:22:33:44:55", shape)).none { it.category == Category.ALPR })
        assertEquals("common to phones and ESP32 boards", SignatureEngine.probeShapeNote(0x7C923B53L))
        assertNull(SignatureEngine.probeShapeNote(0xDFAE8CC6L))
    }
}
