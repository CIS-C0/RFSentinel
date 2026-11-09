package com.rfsentinel.app.link

import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.online.PoliceAircraft
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
 * device (address, radio, signal, age, name, and up to two matches). An aircraft packet (version [AIRCRAFT], which
 * older versions drop) starts the same way, then has one record per aircraft instead.
 */
object MeshCodec {
    /** The Meshtastic channel RF Sentinel adds to the node; its key comes from the group key. */
    const val CHANNEL_NAME = "RFSentinel"
    private const val MAGIC = 0x52
    private const val VERSION = 1
    private const val AIRCRAFT = 2
    private const val HEADER = 18
    private const val GCM_TAG = 16
    /** Room for the body in one packet. */
    const val MAX_BODY = MeshProto.MAX_PAYLOAD - HEADER - GCM_TAG - 3
    private const val NAME_MAX = 24
    private const val LABEL_MAX = 32
    private const val CALLSIGN_MAX = 8
    private const val REG_MAX = 10
    private const val TYPE_MAX = 4
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
        val planes: List<PoliceAircraft.Plane> = emptyList(),
    )

    /** Bytes one device takes in a body. */
    fun size(d: Device): Int =
        6 + 3 + (d.name?.let { 1 + utf8(it, NAME_MAX).size } ?: 0) +
            d.hits.take(2).sumOf { 1 + utf8(it.category.name, 20).size + 1 + 1 + utf8(it.label, LABEL_MAX).size }

    /** Bytes the body takes before the devices. */
    fun baseSize(hasPosition: Boolean, name: String?) = 5 + (if (hasPosition) 8 else 0) + (name?.let { 1 + utf8(it, NAME_MAX).size } ?: 0)

    fun seal(key: ByteArray, origin: Long, counter: Int, timeMs: Long, lat: Double, lon: Double, name: String?,
             devices: List<Device>): ByteArray {
        val body = base(timeMs, lat, lon, name)
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
        return close(key, VERSION, origin, counter, body)
    }

    /** Bytes one aircraft takes in a body; null when its address isn't 6 hex digits (it isn't sent). */
    fun size(p: PoliceAircraft.Plane): Int? {
        if (icao(p.hex) == null) return null
        fun t(s: String?, max: Int) = s?.let { 1 + utf8(it, max).size } ?: 0
        return 12 + (if (p.altitudeFt != null) 2 else 0) + (if (p.speedKt != null) 2 else 0) + (if (p.trackDeg != null) 1 else 0) +
            t(p.callsign, CALLSIGN_MAX) + t(p.registration, REG_MAX) + t(p.type, TYPE_MAX) + t(p.owner, NAME_MAX)
    }

    /** The first of [planes] that fit in [room] bytes. */
    fun fit(planes: List<PoliceAircraft.Plane>, room: Int): List<PoliceAircraft.Plane> {
        var used = 0
        val out = ArrayList<PoliceAircraft.Plane>()
        for (p in planes) {
            val n = size(p) ?: continue
            if (used + n > room) break
            used += n
            out += p
        }
        return out
    }

    /** An aircraft packet with as many of [planes] as fit. */
    fun sealAircraft(key: ByteArray, origin: Long, counter: Int, timeMs: Long, lat: Double, lon: Double, name: String?,
                     planes: List<PoliceAircraft.Plane>): ByteArray {
        val body = base(timeMs, lat, lon, name)
        for (p in planes) {
            val hex = icao(p.hex) ?: continue
            if (body.size() + size(p)!! > MAX_BODY) break
            body.write(byteArrayOf((hex shr 16).toByte(), (hex shr 8).toByte(), hex.toByte()))
            body.write((if (p.military) 1 else 0) or (if (p.callsign != null) 2 else 0) or (if (p.registration != null) 4 else 0) or
                (if (p.type != null) 8 else 0) or (if (p.owner != null) 16 else 0) or (if (p.altitudeFt != null) 32 else 0) or
                (if (p.speedKt != null) 64 else 0) or (if (p.trackDeg != null) 128 else 0))
            body.write(ByteBuffer.allocate(8).putInt(Math.round(p.lat * 1e5).toInt()).putInt(Math.round(p.lon * 1e5).toInt()).array())
            p.altitudeFt?.let { body.write(ByteBuffer.allocate(2).putShort((it / 2).coerceIn(-32768, 32767).toShort()).array()) }
            p.speedKt?.let { body.write(ByteBuffer.allocate(2).putShort(Math.round(it).toInt().coerceIn(0, 32767).toShort()).array()) }
            p.trackDeg?.let { body.write(Math.round(it / 360.0 * 256).toInt() and 0xFF) }
            p.callsign?.let { text(body, it, CALLSIGN_MAX) }
            p.registration?.let { text(body, it, REG_MAX) }
            p.type?.let { text(body, it, TYPE_MAX) }
            p.owner?.let { text(body, it, NAME_MAX) }
        }
        return close(key, AIRCRAFT, origin, counter, body)
    }

    /** Flags, time, position and name: how every body starts. */
    private fun base(timeMs: Long, lat: Double, lon: Double, name: String?): ByteArrayOutputStream {
        val hasPos = !lat.isNaN() && !lon.isNaN()
        val body = ByteArrayOutputStream()
        body.write((if (hasPos) 1 else 0) or (if (name != null) 2 else 0))
        body.write(ByteBuffer.allocate(4).putInt((timeMs / 1000).toInt()).array())
        if (hasPos) body.write(ByteBuffer.allocate(8).putInt(Math.round(lat * 1e5).toInt()).putInt(Math.round(lon * 1e5).toInt()).array())
        name?.let { text(body, it, NAME_MAX) }
        return body
    }

    private fun close(key: ByteArray, version: Int, origin: Long, counter: Int, body: ByteArrayOutputStream): ByteArray {
        val header = ByteBuffer.allocate(HEADER).put(MAGIC.toByte()).put(version.toByte()).put(groupTag(key))
            .putLong(origin).putInt(counter).array()
        return header + cipher(Cipher.ENCRYPT_MODE, key, header).doFinal(body.toByteArray())
    }

    private fun icao(hex: String): Int? = hex.takeIf { it.length == 6 }?.toIntOrNull(16)

    /** Null when it isn't this group's (or was changed on the way). */
    fun open(key: ByteArray, bytes: ByteArray): Message? = runCatching {
        if (bytes.size < HEADER + GCM_TAG || bytes[0].toInt() != MAGIC) return null
        val version = bytes[1].toInt()
        if (version != VERSION && version != AIRCRAFT) return null
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
        if (version == AIRCRAFT) return Message(origin, counter, time, lat, lon, name, emptyList(), planes(body))
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

    private fun planes(body: ByteBuffer): List<PoliceAircraft.Plane> {
        val out = ArrayList<PoliceAircraft.Plane>()
        while (body.remaining() >= 12) {
            val hex = "%02x%02x%02x".format(body.get(), body.get(), body.get())
            val f = body.get().toInt() and 0xFF
            val lat = body.int / 1e5
            val lon = body.int / 1e5
            val alt = if (f and 32 != 0) body.short * 2 else null
            val speed = if (f and 64 != 0) body.short.toDouble() else null
            val track = if (f and 128 != 0) (body.get().toInt() and 0xFF) * 360.0 / 256 else null
            val callsign = if (f and 2 != 0) text(body) else null
            val reg = if (f and 4 != 0) text(body) else null
            val type = if (f and 8 != 0) text(body) else null
            val owner = if (f and 16 != 0) text(body) else null
            out += PoliceAircraft.Plane(hex, callsign, reg, type, owner, lat, lon, alt, speed, track, f and 1 != 0)
        }
        return out
    }

    /** A message as a Sentinel Link batch: seq kept apart from Bluetooth batches' (negative), already one hop away. */
    fun toBatch(m: Message): LinkCodec.Batch = LinkCodec.Batch(
        m.origin, m.counter or Int.MIN_VALUE, 1, m.time, m.lat, m.lon,
        m.devices.map { d ->
            LinkCodec.Sighting(LinkCodec.KIND_PHONE, Advert(d.mac, d.source, d.rssi, name = d.name,
                timestamp = m.time - d.ageSec * 1000L), hits = d.hits)
        },
        emptyList(), m.planes
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
