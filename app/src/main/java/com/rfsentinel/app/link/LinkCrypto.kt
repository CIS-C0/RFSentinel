package com.rfsentinel.app.link

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sentinel Link's cryptography, plain JVM so it's unit-tested.
 *
 *  - Linking two phones: ephemeral P-256 ECDH; both screens show a 6-digit code derived from the exchange
 *    (numeric comparison: a phone in the middle would make the codes differ). Confirmed, the host hands
 *    over the group key, sealed with a key from the exchange.
 *  - Linked phones: every connection proves the group key both ways (fresh nonces, HMAC) and derives its own
 *    pair of AES-256-GCM keys, one per direction, with a counter as nonce (replays are refused).
 *  - Adverts carry only tags that change every 15 minutes and mean something only with the group key.
 */
object LinkCrypto {
    private val rng = SecureRandom()

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 16
    /** How long one advert tag lasts. */
    const val SLOT_MS = 15 * 60_000L

    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun randomId(): Long {
        var id = 0L
        while (id == 0L) id = rng.nextLong()
        return id
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t, info.toByteArray(), byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rng) }.generateKeyPair()

    fun publicBytes(kp: KeyPair): ByteArray = kp.public.encoded

    fun publicFrom(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun ecdh(kp: KeyPair, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(kp.private); doPhase(peer, true) }.generateSecret()

    /**
     * The 6 digits both phones show while linking. [joinerPub]/[hostPub] and the nonces fix the order, so both
     * sides compute the same code - and an attacker relaying between them can't make the two codes match.
     */
    fun code(secret: ByteArray, joinerPub: ByteArray, hostPub: ByteArray, joinerNonce: ByteArray, hostNonce: ByteArray): String {
        val h = hmac(secret, "rfs-link-code".toByteArray(), joinerPub, hostPub, joinerNonce, hostNonce)
        val n = ((h[0].toLong() and 0xFF) shl 24 or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)) % 1_000_000L
        return "%06d".format(n)
    }

    /** The key that seals the linking messages, one per direction. */
    fun pairKey(secret: ByteArray, joinerNonce: ByteArray, hostNonce: ByteArray, toHost: Boolean): ByteArray =
        hkdf(secret, joinerNonce + hostNonce, if (toHost) "rfs-pair-j2h" else "rfs-pair-h2j")

    /** Proof that a phone holds the group key, bound to this connection's nonces. */
    fun proof(groupKey: ByteArray, role: String, vararg nonces: ByteArray): ByteArray =
        hmac(groupKey, "rfs-hello-$role".toByteArray(), *nonces).copyOf(16)

    /** A connection's key for one direction ([toServer] = written by the phone that connected). */
    fun sessionKey(groupKey: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray, toServer: Boolean): ByteArray =
        hkdf(groupKey, clientNonce + serverNonce, if (toServer) "rfs-c2s" else "rfs-s2c")

    private fun iv(counter: Long) = ByteBuffer.allocate(12).putInt(0x52465331).putLong(counter).array()

    fun seal(key: ByteArray, counter: Long, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv(counter)))
            doFinal(plain)
        }

    /** Null when the data was altered or sealed with another key / counter. */
    fun open(key: ByteArray, counter: Long, sealed: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv(counter)))
            doFinal(sealed)
        }
    }.getOrNull()

    fun slot(now: Long) = now / SLOT_MS

    private fun long(v: Long) = ByteBuffer.allocate(8).putLong(v).array()

    /** Says "one of ours" in an advert; changes every [SLOT_MS]. */
    fun groupTag(groupKey: ByteArray, slot: Long): ByteArray = hmac(groupKey, "g".toByteArray(), long(slot)).copyOf(4)

    /** Says which phone, to phones of the group only; changes every [SLOT_MS]. */
    fun nodeTag(groupKey: ByteArray, slot: Long, nodeId: Long): ByteArray =
        hmac(groupKey, "n".toByteArray(), long(slot), long(nodeId)).copyOf(4)
}
