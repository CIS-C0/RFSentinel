package com.rfsentinel.app.link

import com.rfsentinel.app.detect.AddressType
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** What one Sentinel shares, and its wire format (big-endian, plain JVM so it's unit-tested). */
object LinkCodec {
    // ---- Message types: the first byte of every frame ----
    /** Joiner -> host: ECDH public key + nonce. */
    const val PAIR_HELLO = 1
    /** Host -> joiner: ECDH public key + nonce. */
    const val PAIR_REPLY = 2
    /** Joiner -> host, sealed: the joiner's id and name once its user confirmed the code. */
    const val PAIR_CONFIRM = 3
    /** Host -> joiner, sealed: the group key and the Sentinels in it. */
    const val PAIR_GROUP = 4
    const val PAIR_CANCEL = 5
    /** Connecting Sentinel -> other: nonce + proof of the group key. */
    const val HELLO = 10
    /** Answer to [HELLO]: nonce + proof. */
    const val HELLO_REPLY = 11
    /** Counter + AES-GCM data; inside, one of the inner types below. */
    const val SEALED = 12

    // ---- Inner (sealed) types ----
    const val INFO = 20
    const val BATCH = 21

    /** Who heard it: the phone's own radios, an ESP32 board, a USB WiFi adapter. */
    const val KIND_PHONE = 0
    const val KIND_ESP = 1
    const val KIND_USB = 2

    data class Member(val id: Long, val name: String)

    /** One device heard by a Sentinel, with what its hardware added. */
    class Sighting(
        val kind: Int,
        val advert: Advert,
        /** An ESP32 board's own watchlist matches. */
        val hits: List<Hit> = emptyList(),
        /** Network names it asked for (USB adapter / ESP32). */
        val probed: List<String> = emptyList(),
        val fingerprint: String? = null,
        val hidden: Boolean = false,
    )

    /** One RTL-SDR / HackRF transmission ([com.rfsentinel.app.sdr.RadioWatch.Event]). */
    data class Radio(
        val freqHz: Long, val snrDb: Int, val bandLabel: String, val bandStartHz: Long, val bandEndHz: Long, val kind: String,
        val confidence: Int, val targetLabel: String?, val repeat: Boolean, val tdma: Boolean, val time: Long,
    )

    /** What one Sentinel heard in about a second, passed on by others up to [MAX_HOPS] times. */
    class Batch(
        val origin: Long, val seq: Int, val hops: Int, val time: Long,
        /** Where the Sentinel that heard these was (NaN when it had no fix). */
        val lat: Double, val lon: Double,
        val sightings: List<Sighting>, val radios: List<Radio>,
    ) {
        fun relayed() = Batch(origin, seq, hops + 1, time, lat, lon, sightings, radios)
    }

    const val MAX_HOPS = 2

    /** [a] heard at [timestamp] (on this phone's clock: phones' clocks differ). */
    fun rebased(a: Advert, timestamp: Long) = Advert(a.mac, a.source, a.rssi, a.name, a.manufacturerData, a.serviceUuids,
        a.serviceData, a.rawBytes, a.txPower, a.addressType, a.connectable, a.phy, a.wifi, timestamp)

    // ---- Small messages ----

    fun pairHello(type: Int, pub: ByteArray, nonce: ByteArray): ByteArray = write {
        writeByte(type); bytes(pub); bytes(nonce)
    }

    /** (public key, nonce) of [PAIR_HELLO] / [PAIR_REPLY]. */
    fun readPairHello(b: ByteArray): Pair<ByteArray, ByteArray> = read(b) { readByte(); bytes() to bytes() }

    fun member(m: Member): ByteArray = write { writeLong(m.id); writeUTF(m.name.take(40)) }
    fun readMember(b: ByteArray): Member = read(b) { Member(readLong(), readUTF()) }

    fun group(key: ByteArray, members: List<Member>): ByteArray = write {
        bytes(key); writeShort(members.size); members.forEach { writeLong(it.id); writeUTF(it.name.take(40)) }
    }

    fun readGroup(b: ByteArray): Pair<ByteArray, List<Member>> = read(b) {
        val key = bytes()
        key to List(readUnsignedShort()) { Member(readLong(), readUTF()) }
    }

    fun hello(type: Int, nonce: ByteArray, proof: ByteArray): ByteArray = write { writeByte(type); bytes(nonce); bytes(proof) }
    fun readHello(b: ByteArray): Pair<ByteArray, ByteArray> = read(b) { readByte(); bytes() to bytes() }

    fun sealed(counter: Long, data: ByteArray): ByteArray =
        ByteBuffer.allocate(9 + data.size).put(SEALED.toByte()).putLong(counter).put(data).array()

    /** (counter, data) of a [SEALED] frame. */
    fun readSealed(b: ByteArray): Pair<Long, ByteArray> = ByteBuffer.wrap(b).let { it.get(); it.long to b.copyOfRange(9, b.size) }

    /** [INFO]: who this Sentinel is and every Sentinel it knows (so a new one spreads through the group). */
    fun info(me: Member, members: List<Member>): ByteArray = write {
        writeByte(INFO); writeLong(me.id); writeUTF(me.name.take(40))
        writeShort(members.size); members.forEach { writeLong(it.id); writeUTF(it.name.take(40)) }
    }

    fun readInfo(b: ByteArray): Pair<Member, List<Member>> = read(b) {
        readByte()
        Member(readLong(), readUTF()) to List(readUnsignedShort()) { Member(readLong(), readUTF()) }
    }

    // ---- Batches ----

    fun batch(b: Batch): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(body, Deflater(Deflater.BEST_SPEED))).use { o ->
            o.writeLong(b.origin); o.writeInt(b.seq); o.writeByte(b.hops); o.writeLong(b.time)
            o.writeDouble(b.lat); o.writeDouble(b.lon)
            o.writeShort(b.sightings.size)
            b.sightings.forEach { o.sighting(it, b.time) }
            o.writeShort(b.radios.size)
            b.radios.forEach { r ->
                o.writeLong(r.freqHz); o.writeShort(r.snrDb); o.writeUTF(r.bandLabel); o.writeLong(r.bandStartHz); o.writeLong(r.bandEndHz)
                o.writeUTF(r.kind); o.writeByte(r.confidence); o.optUtf(r.targetLabel); o.writeBoolean(r.repeat); o.writeBoolean(r.tdma)
                o.writeInt((b.time - r.time).toInt())
            }
        }
        return byteArrayOf(BATCH.toByte()) + body.toByteArray()
    }

    /** Null for a batch that doesn't parse (a newer version, or damaged). */
    fun readBatch(bytes: ByteArray): Batch? = runCatching {
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1))).use { i ->
            val origin = i.readLong(); val seq = i.readInt(); val hops = i.readUnsignedByte(); val time = i.readLong()
            val lat = i.readDouble(); val lon = i.readDouble()
            val sightings = List(i.readUnsignedShort()) { i.sighting(time) }
            val radios = List(i.readUnsignedShort()) {
                Radio(i.readLong(), i.readShort().toInt(), i.readUTF(), i.readLong(), i.readLong(), i.readUTF(), i.readUnsignedByte(),
                    i.optUtf(), i.readBoolean(), i.readBoolean(), time - i.readInt())
            }
            Batch(origin, seq, hops, time, lat, lon, sightings, radios)
        }
    }.getOrNull()

    private fun DataOutputStream.sighting(s: Sighting, time: Long) {
        val a = s.advert
        writeByte(s.kind)
        write(macBytes(a.mac))
        writeByte(a.source.ordinal); writeShort(a.rssi); writeInt((time - a.timestamp).toInt())
        optUtf(a.name)
        writeShort(a.manufacturerData.size); a.manufacturerData.forEach { (id, v) -> writeShort(id); bytes(v) }
        writeShort(a.serviceUuids.size); a.serviceUuids.forEach { uuid(it) }
        writeShort(a.serviceData.size); a.serviceData.forEach { (u, v) -> uuid(u); bytes(v) }
        writeBoolean(a.rawBytes != null); a.rawBytes?.let { bytes(it) }
        writeBoolean(a.txPower != null); a.txPower?.let { writeShort(it) }
        writeByte(a.addressType.ordinal)
        writeByte(when (a.connectable) { null -> 2; true -> 1; false -> 0 })
        optUtf(a.phy)
        val w = a.wifi
        writeBoolean(w != null)
        if (w != null) {
            writeInt(w.frequencyMhz); writeUTF(w.capabilities); optUtf(w.standard)
            writeShort(w.infoElements.size); w.infoElements.forEach { (id, v) -> writeByte(id); bytes(v) }
            writeBoolean(w.client); writeBoolean(w.wildcardProbe); writeBoolean(w.flockProbeIe)
            writeBoolean(w.probeSkeleton != null); w.probeSkeleton?.let { writeLong(it) }
        }
        writeShort(s.hits.size)
        s.hits.forEach { h -> writeUTF(h.category.name); writeUTF(h.label); writeByte(h.confidence); writeUTF(h.evidence); writeUTF(h.source) }
        writeShort(s.probed.size); s.probed.forEach { writeUTF(it) }
        optUtf(s.fingerprint)
        writeBoolean(s.hidden)
    }

    private fun DataInputStream.sighting(time: Long): Sighting {
        val kind = readUnsignedByte()
        val mac = ByteArray(6).also { readFully(it) }.joinToString(":") { "%02X".format(it) }
        val source = Advert.Source.entries[readUnsignedByte()]
        val rssi = readShort().toInt()
        val ts = time - readInt()
        val name = optUtf()
        val mfr = LinkedHashMap<Int, ByteArray>().apply { repeat(readUnsignedShort()) { put(readUnsignedShort(), bytes()) } }
        val uuids = List(readUnsignedShort()) { uuid() }
        val sd = LinkedHashMap<UUID, ByteArray>().apply { repeat(readUnsignedShort()) { put(uuid(), bytes()) } }
        val raw = if (readBoolean()) bytes() else null
        val tx = if (readBoolean()) readShort().toInt() else null
        val addressType = AddressType.entries.getOrElse(readUnsignedByte()) { AddressType.UNKNOWN }
        val connectable = when (readUnsignedByte()) { 1 -> true; 0 -> false; else -> null }
        val phy = optUtf()
        val wifi = if (readBoolean()) {
            val freq = readInt(); val caps = readUTF(); val std = optUtf()
            val ies = List(readUnsignedShort()) { readUnsignedByte() to bytes() }
            val client = readBoolean(); val wildcard = readBoolean(); val flock = readBoolean()
            val skeleton = if (readBoolean()) readLong() else null
            Advert.WifiInfo(freq, caps, std, ies, client, wildcard, flock, skeleton)
        } else null
        val hits = List(readUnsignedShort()) {
            val cat = readUTF()
            Hit(Category.entries.firstOrNull { it.name == cat } ?: Category.CUSTOM, readUTF(), readUnsignedByte(), readUTF(), readUTF())
        }
        val probed = List(readUnsignedShort()) { readUTF() }
        val fingerprint = optUtf()
        val hidden = readBoolean()
        return Sighting(kind, Advert(mac, source, rssi, name, mfr, uuids, sd, raw, tx, addressType, connectable, phy, wifi, ts),
            hits, probed, fingerprint, hidden)
    }

    // ---- Helpers ----

    private fun macBytes(mac: String): ByteArray {
        val hex = mac.filter { it.isLetterOrDigit() }.padEnd(12, '0').take(12)
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun DataOutputStream.bytes(b: ByteArray) { writeShort(b.size); write(b) }
    private fun DataInputStream.bytes(): ByteArray = ByteArray(readUnsignedShort()).also { readFully(it) }
    private fun DataOutputStream.optUtf(s: String?) { writeBoolean(s != null); s?.let { writeUTF(it) } }
    private fun DataInputStream.optUtf(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.uuid(u: UUID) { writeLong(u.mostSignificantBits); writeLong(u.leastSignificantBits) }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())

    private fun write(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()

    private fun <T> read(b: ByteArray, block: DataInputStream.() -> T): T = DataInputStream(ByteArrayInputStream(b)).use(block)
}
