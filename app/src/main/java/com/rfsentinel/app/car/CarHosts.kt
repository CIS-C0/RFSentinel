package com.rfsentinel.app.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Car hosts besides Android Auto. Gearslip (github.com/twinksoftware/Gearslip) is an
 * open-source Android Auto client that runs car apps without Google Play. Both of its
 * builds are signed with one key, checked against the official release APK.
 */
object CarHosts {

    val GEARSLIP_PACKAGES = listOf("app.seb3thehacker.gearslip", "app.seb3thehacker.gearslip.dev")
    const val GEARSLIP_CERT_SHA256 = "4cc56ebdd13a54d260eca9b7e2b2ae6b30e2f225bfda8f508f0709edf084948b"

    /** True when [packageName] is Gearslip, runs as [uid] and carries Gearslip's signing key. */
    fun isGearslip(context: Context, packageName: String, uid: Int): Boolean {
        if (packageName !in GEARSLIP_PACKAGES) return false
        val pm = context.packageManager
        if (pm.getPackagesForUid(uid)?.contains(packageName) != true) return false
        return try {
            signedWith(pm, packageName, hexToBytes(GEARSLIP_CERT_SHA256))
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Same rule as PackageManager.hasSigningCertificate: with several signers all must match,
     * with one key any key in its rotation history counts.
     */
    @SuppressLint("PackageManagerGetSignatures") // Every signer is compared, not just the first.
    @Suppress("DEPRECATION")
    internal fun signedWith(pm: PackageManager, packageName: String, sha256: ByteArray): Boolean {
        fun Signature.matches() =
            MessageDigest.getInstance("SHA-256").digest(toByteArray()).contentEquals(sha256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo ?: return false
            return if (info.hasMultipleSigners()) {
                info.apkContentsSigners.let { it.isNotEmpty() && it.all { s -> s.matches() } }
            } else {
                info.signingCertificateHistory.orEmpty().any { it.matches() }
            }
        }
        // Android 8 only knows the v1/v2 signers; all of them must be Gearslip's key.
        val signatures = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            ?: return false
        return signatures.isNotEmpty() && signatures.all { it.matches() }
    }

    internal fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
