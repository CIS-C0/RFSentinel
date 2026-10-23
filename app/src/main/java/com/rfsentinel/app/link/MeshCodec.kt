package com.rfsentinel.app.link

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sentinel Link over Meshtastic: what one Sentinel heard, packed into one LoRa packet (233 bytes at most).
 *
 * Clear header `[0x52][version][group tag 4][origin 8][counter 4]`, then AES-256-GCM of the body with a key made
 * from the group key (nonce = origin + counter, which never repeats: the counter is kept across restarts).
 * Body: flags, time, the Sentinel's position once for all its devices, its name now and then, then one record per
 * device (address, radio, signal, age, name, and up to two matches).
 */
object MeshCodec {
    /** The Meshtastic channel RF Sentinel adds to the node; its key comes from the group key. */
    const val CHANNEL_NAME = "RFSentinel"
    private const val MAGIC = 0x52
    private const val VERSION = 1
    private const val HEADER = 18
    private const val GCM_TAG = 16
    /** Room for the body in one packet. */
    const val MAX_BODY = MeshProto.MAX_PAYLOAD - HEADER - GCM_TAG - 3
    private const val NAME_MAX = 24
    private const val LABEL_MAX = 32
    private const val NO_POS = Int.MIN_VALUE

    private val SALT = "RF Sentinel mesh".toByteArray()
    fun messageKey(groupKey: ByteArray) = LinkCrypto.hkdf(groupKey, SALT, "message key")
    fun channelKey(groupKey: ByteArray) = LinkCrypto.hkdf(groupKey, SALT, "channel key")
    private fun groupTag(key: ByteArray) = LinkCrypto.hmac(key, "group".toByteArray()).copyOf(4)

    /** One device as it goes over the mesh. */
    class Device(val mac: String, val source: Advert.Source, val rssi: Int, val ageSec: Int, val name: String?, val hits: List<Hit>)

    class Message(
        val origin: Long, val counter: Int, val time: Long,
        /** Where that Sentinel was, NaN when unknown. */
        val lat: Double, val lon: Double,
        val name: String?, val devices: List<Device>,
    )

    /** Bytes one device takes in a body. */
    fun size(d: Device): Int =
        6 + 3 + (d.name?.let { 1 + utf8(it, NAME_MAX).size } ?: 0) +
            d.hits.take(2).sumOf { 1 + utf8(it.category.name, 20).size + 1 + 1 + utf8(it.label, LABEL_MAX).size }

    /** Bytes the body takes before the devices. */
    fun baseSize(hasPosition: Boolean, name: String?) = 5 + (if (hasPosition) 8 else 0) + (name?.let { 1 + utf8(it, NAME_MAX).size } ?: 0)

    fun seal(key: ByteArray, origin: Long, counter: Int, timeMs: Long, lat: Double, lon: Double, name: String?,
             devices: List<Device>): ByteArray {
        val hasPos = !lat.isNaN() && !lon.isNaN()
        val body = ByteArrayOutputStream()
        body.write((if (hasPos) 1 else 0) or (if (name != null) 2 else 0))
        body.write(ByteBuffer.allocate(4).putInt((timeMs / 1000).toInt()).array())
        if (hasPos) body.write(ByteBuffer.allocate(8).putInt(Math.round(lat * 1e5).toInt()).putInt(Math.round(lon * 1e5).toInt()).array())
        name?.let { text(body, it, NAME_MAX) }
        for (d in devices) {
            val mac = macBytes(d.mac) ?: continue
            if (body.size() + size(d) > MAX_BODY) break
            body.write(mac)
            val hits = d.hits.take(2)
            body.write((if (d.source == Advert.Source.WIFI) 1 else 0) or (if (d.name != null) 2 else 0) or (hits.size shl 2))
            body.write(d.rssi.coerceIn(-128, 127))
            body.write(d.ageSec.coerceIn(0, 255))
            d.name?.let { text(body, it, NAME_MAX) }
            for (h in hits) {
                text(body, h.category.name, 20)
                body.write(h.confidence.coerceIn(0, 100))
                text(body, h.label, LABEL_MAX)
            }
        }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC.toByte()).put(VERSION.toByte()).put(groupTag(key))
            .putLong(origin).putInt(counter).array()
        return header + cipher(Cipher.ENCRYPT_MODE, key, header).doFinal(body.toByteArray())
    }

    /** Null when it isn't this group's (or was changed on the way). */
    fun open(key: ByteArray, bytes: ByteArray): Message? = runCatching {
        if (bytes.size < HEADER + GCM_TAG || bytes[0].toInt() != MAGIC || bytes[1].toInt() != VERSION) return null
        val header = bytes.copyOfRange(0, HEADER)
        if (!header.copyOfRange(2, 6).contentEquals(groupTag(key))) return null
        val hb = ByteBuffer.wrap(header, 6, 12)
        val origin = hb.long
        val counter = hb.int
        val body = ByteBuffer.wrap(cipher(Cipher.DECRYPT_MODE, key, header).doFinal(bytes, HEADER, bytes.size - HEADER))
        val flags = body.get().toInt()
        val time = (body.int.toLong() and 0xFFFFFFFFL) * 1000
        var lat = Double.NaN
        var lon = Double.NaN
        if (flags and 1 != 0) {
            val la = body.int
            val lo = body.int
            if (la != NO_POS) { lat = la / 1e5; lon = lo / 1e5 }
        }
        val name = if (flags and 2 != 0) text(body) else null
        val devices = ArrayList<Device>()
        while (body.remaining() >= 9) {
            val mac = ByteArray(6).also { body.get(it) }.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            val b = body.get().toInt()
            val rssi = body.get().toInt()
            val age = body.get().toInt() and 0xFF
            val dName = if (b and 2 != 0) text(body) else null
            val hits = (0 until ((b shr 2) and 3)).mapNotNull {
                val cat = text(body)
                val conf = body.get().toInt() and 0xFF
                val label = text(body)
                runCatching { Category.valueOf(cat) }.getOrNull()?.let {
                    Hit(it, label, conf.coerceAtMost(100), "Matched by a linked Sentinel", "Sentinel Link")
                }
            }
            devices += Device(mac, if (b and 1 != 0) Advert.Source.WIFI else Advert.Source.BLE, rssi, age, dName, hits)
        }
        Message(origin, counter, time, lat, lon, name, devices)
    }.getOrNull()

    /** A message as a Sentinel Link batch: seq kept apart from Bluetooth batches' (negative), already one hop away. */
    fun toBatch(m: Message): LinkCodec.Batch = LinkCodec.Batch(
        m.origin, m.counter or Int.MIN_VALUE, 1, m.time, m.lat, m.lon,
        m.devices.map { d ->
            LinkCodec.Sighting(LinkCodec.KIND_PHONE, Advert(d.mac, d.source, d.rssi, name = d.name,
                timestamp = m.time - d.ageSec * 1000L), hits = d.hits)
        },
        emptyList()
    )

    private fun cipher(mode: Int, key: ByteArray, header: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG * 8, header.copyOfRange(6, HEADER)))
            updateAAD(header)
        }

    private fun macBytes(mac: String): ByteArray? {
        val parts = mac.split(':')
        if (parts.size != 6) return null
        return ByteArray(6) { i -> parts[i].toIntOrNull(16)?.toByte() ?: return null }
    }

    /** UTF-8, cut to [max] bytes without splitting a character. */
    fun utf8(s: String, max: Int): ByteArray {
        var t = s
        var b = t.toByteArray(Charsets.UTF_8)
        while (b.size > max) {
            t = t.dropLast(if (t.length >= 2 && t[t.length - 1].isLowSurrogate()) 2 else 1)
            b = t.toByteArray(Charsets.UTF_8)
        }
        return b
    }

    private fun text(out: ByteArrayOutputStream, s: String, max: Int) {
        val b = utf8(s, max)
        out.write(b.size)
        out.write(b)
    }

    private fun text(b: ByteBuffer): String {
        val n = b.get().toInt() and 0xFF
        return String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8)
    }
}
