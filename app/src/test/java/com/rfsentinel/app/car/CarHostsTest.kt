package com.rfsentinel.app.car

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarHostsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun hexToBytesReadsTheCertificateDigest() {
        val bytes = CarHosts.hexToBytes(CarHosts.GEARSLIP_CERT_SHA256)
        assertArrayEquals(byteArrayOf(0x4c, 0xc5.toByte(), 0x6e), bytes.copyOf(3))
        assert(bytes.size == 32)
    }

    @Test
    fun otherPackagesAreNotGearslip() {
        assertFalse(CarHosts.isGearslip(app, "com.example.reader", 10_123))
    }

    @Test
    fun gearslipNameFromAnotherUidIsRefused() {
        install(CarHosts.GEARSLIP_PACKAGES[0], 10_200, Signature("00"))
        assertFalse(CarHosts.isGearslip(app, CarHosts.GEARSLIP_PACKAGES[0], 10_201))
    }

    @Test
    fun gearslipNameWithAnotherKeyIsRefused() {
        install(CarHosts.GEARSLIP_PACKAGES[1], 10_300, Signature("00"))
        assertFalse(CarHosts.isGearslip(app, CarHosts.GEARSLIP_PACKAGES[1], 10_300))
    }

    @Test
    fun matchingKeyIsAcceptedAndOtherKeyRefused() {
        val signature = Signature("0a0b0c")
        install("com.example.host", 10_400, signature)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        assertTrue(CarHosts.signedWith(app.packageManager, "com.example.host", digest))
        assertFalse(CarHosts.signedWith(app.packageManager, "com.example.host", ByteArray(32)))
    }

    private fun install(name: String, uid: Int, signature: Signature) {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = name
            @Suppress("DEPRECATION")
            signatures = arrayOf(signature)
            signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                shadowOf(it).setSignatures(arrayOf(signature))
            }
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = name; this.uid = uid }
        })
        pm.setPackagesForUid(uid, name)
    }
}
