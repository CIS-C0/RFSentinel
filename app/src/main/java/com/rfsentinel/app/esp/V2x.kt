package com.rfsentinel.app.esp

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit

/**
 * European V2X (ETSI ITS-G5, 802.11p at 5.9 GHz) heard by an ESP32-C5 running the V2X2MAP
 * firmware (pit711/V2X2MAP, MIT): the board streams every frame it hears over USB as
 * `"ITS5" | sec u32 | usec u32 | len u16 | 802.11 frame`, all little-endian.
 *
 * Cars, emergency vehicles and roadside units broadcast where they are (CAM) and warnings
 * (DENM). RF Sentinel only looks for emergency vehicles: a CAM whose vehicle role is
 * emergency / rescue / safety car, its light bar and siren, and DENM "emergency vehicle
 * approaching" warnings. Ordinary cars are counted but not listed.
 *
 * The 802.11 / GeoNetworking / BTP layout follows V2X2MAP's ItsG5Decoder (MIT); the CAM and
 * DENM bodies are read here from ETSI's own ASN.1 (CAM EN 302 637-2 v1.3.2 / v1.4.1, DENM
 * EN 302 637-3 v1.3.1), checked against messages encoded by asn1tools. Receive-only.
 */
object V2x {

    const val MAGIC = "ITS5"
    private const val HEADER_LEN = 14
    private const val MAX_PAYLOAD = 4096
    private const val ETHERTYPE_GN = 0x8947
    /** The ITS-G5 control channel the firmware listens on (for .pcap recordings). */
    const val CCH_MHZ = 5900

    /** Splits the board's byte stream into frames, skipping anything that isn't one (boot text, logs). */
    class Stream {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray, n: Int): List<ByteArray> {
            buf += chunk.copyOf(n)
            val out = ArrayList<ByteArray>()
            while (true) {
                val m = indexOfMagic(buf)
                if (m < 0) { buf = buf.copyOfRange(maxOf(0, buf.size - 3), buf.size); return out }
                if (m > 0) buf = buf.copyOfRange(m, buf.size)
                if (buf.size < HEADER_LEN) return out
                val len = (buf[12].toInt() and 0xff) or ((buf[13].toInt() and 0xff) shl 8)
                if (len > MAX_PAYLOAD) { buf = buf.copyOfRange(4, buf.size); continue } // not a real header: resync
                if (buf.size < HEADER_LEN + len) return out
                out += buf.copyOfRange(HEADER_LEN, HEADER_LEN + len)
                buf = buf.copyOfRange(HEADER_LEN + len, buf.size)
            }
        }

        private fun indexOfMagic(b: ByteArray): Int {
            for (i in 0..b.size - 4) if (b[i] == 'I'.code.toByte() && b[i + 1] == 'T'.code.toByte() &&
                b[i + 2] == 'S'.code.toByte() && b[i + 3] == '5'.code.toByte()) return i
            return -1
        }
    }

    /** True when [b] (first [n] bytes) holds the start of the V2X board's stream. */
    fun looksLikeStream(b: ByteArray, n: Int): Boolean {
        for (i in 0..n - 4) if (b[i] == 'I'.code.toByte() && b[i + 1] == 'T'.code.toByte() &&
            b[i + 2] == 'S'.code.toByte() && b[i + 3] == '5'.code.toByte()) return true
        return false
    }

    enum class Kind { CAM, DENM, OTHER }

    /** One decoded frame. Position: the CAM's or DENM's own (the sender's GPS), else the GeoNetworking one. */
    data class Message(
        val mac: String,
        val kind: Kind,
        val stationType: Int?,
        val lat: Double?,
        val lon: Double?,
        val speedMps: Double? = null,
        val headingDeg: Double? = null,
        val role: Int? = null,
        /** Which special-vehicle container it carried (0 public transport .. 6 safety car). */
        val special: Int? = null,
        val lightBar: Boolean? = null,
        val siren: Boolean? = null,
        val cause: Int? = null,
        val subCause: Int? = null,
        val secured: Boolean = false
    )

    // ---- 802.11 / LLC / GeoNetworking / BTP ---------------------------------------------------------

    private val HDR_LENGTHS = intArrayOf(24, 26, 30, 32)

    fun decode(p: ByteArray): Message? {
        if (p.size < 24) return null
        val mac = (10 until 16).joinToString(":") { "%02X".format(p[it].toInt() and 0xff) } // transmitter (addr2)
        for (hdr in HDR_LENGTHS) {
            if (p.size < hdr + 8 + 4 + 8) continue
            if (p[hdr] != 0xAA.toByte() || p[hdr + 1] != 0xAA.toByte() || p[hdr + 2] != 0x03.toByte() ||
                p[hdr + 3] != 0.toByte() || p[hdr + 4] != 0.toByte() || p[hdr + 5] != 0.toByte()) continue
            val et = ((p[hdr + 6].toInt() and 0xff) shl 8) or (p[hdr + 7].toInt() and 0xff)
            if (et != ETHERTYPE_GN) return null
            val basic = hdr + 8
            val common = basic + 4
            // Basic header next-header 2 = secured (IEEE 1609.2 / TS 103 097): signed, not encrypted;
            // the common header sits a few bytes into the envelope.
            val secured = (p[basic].toInt() and 0x0f) == 2
            val inner = if (secured) findCommonHeader(p, common, minOf(common + 20, p.size - 36)).takeIf { it >= 0 } ?: continue
                else common
            if (p.size < inner + 8) continue
            val ht = (p[inner + 1].toInt() shr 4) and 0x0f
            val hst = p[inner + 1].toInt() and 0x0f
            val ext = when (ht) { 1 -> 4; 2 -> 48; 3 -> 56; 4 -> 44; 5 -> 28; 6 -> 36; else -> 28 }
            val lpv = inner + 8 + if (ht == 5 && hst == 0) 0 else 4
            val btp = inner + 8 + ext
            if (p.size < btp + 4 || p.size < lpv + 24) continue
            // Source position vector: station type in the address, then position, speed, heading.
            val gnType = ((p[lpv].toInt() and 0xff) shr 2) and 0x1f
            val gnLat = be32(p, lpv + 12) / 1e7
            val gnLon = be32(p, lpv + 16) / 1e7
            val gnOk = gnLat in -90.0..90.0 && gnLon in -180.0..180.0 && (gnLat != 0.0 || gnLon != 0.0)
            val port = ((p[btp].toInt() and 0xff) shl 8) or (p[btp + 1].toInt() and 0xff)
            val body = btp + 4
            val decoded = when (port) {
                2001 -> cam(p, body)
                2002 -> denm(p, body)
                else -> null
            }
            val base = Message(mac, when (port) { 2001 -> Kind.CAM; 2002 -> Kind.DENM; else -> Kind.OTHER },
                gnType, if (gnOk) gnLat else null, if (gnOk) gnLon else null, secured = secured)
            if (decoded == null) return base
            return decoded.copy(mac = mac, secured = secured,
                stationType = decoded.stationType ?: gnType,
                lat = decoded.lat ?: base.lat, lon = decoded.lon ?: base.lon)
        }
        return null
    }

    private fun findCommonHeader(p: ByteArray, start: Int, end: Int): Int {
        for (off in start until end) {
            if (off + 8 > p.size) break
            val nh = (p[off].toInt() and 0xff) ushr 4
            val ht = (p[off + 1].toInt() and 0xff) ushr 4
            val plen = ((p[off + 4].toInt() and 0xff) shl 8) or (p[off + 5].toInt() and 0xff)
            if (nh in 0..2 && ht in 4..6 && plen in 1..999) return off
        }
        return -1
    }

    private fun be32(p: ByteArray, i: Int) = ((p[i].toInt() and 0xff) shl 24) or ((p[i + 1].toInt() and 0xff) shl 16) or
        ((p[i + 2].toInt() and 0xff) shl 8) or (p[i + 3].toInt() and 0xff)

    // ---- CAM (UPER) ----------------------------------------------------------------------------------

    /** A CAM starting at [at] (the ITS PDU header). Null when it isn't one this reader knows. */
    fun cam(p: ByteArray, at: Int): Message? = runCatching { readCam(p, at) }.getOrNull()

    private fun readCam(p: ByteArray, at: Int): Message? {
        val b = Bits(p, at)
        val version = b.int(8)
        if (b.int(8) != 2 || version !in 1..2) return null // messageID 2 = CAM; version 1 (CDD 1.2.1) or 2 (CDD 1.3.1)
        b.skip(32 + 16) // stationID, generationDeltaTime
        b.skip(1) // CamParameters extension bit
        val hasLow = b.bit(); val hasSpecial = b.bit()
        // BasicContainer (extensible)
        val basicExt = b.bit()
        val stationType = b.int(8)
        val (lat, lon) = referencePosition(b)
        if (basicExt) b.skipExtensions()
        // HighFrequencyContainer: vehicle (0) or roadside unit (1)
        if (b.bit()) return Message("", Kind.CAM, stationType, lat, lon)
        if (b.int(1) == 1) return Message("", Kind.CAM, stationType, lat, lon)
        val opt = b.int(7)
        val heading = b.int(12); b.skip(7)
        val speed = b.int(14); b.skip(7)
        b.skip(2) // drive direction
        b.skip(10 + 3) // vehicle length
        b.skip(6) // width
        b.skip(9 + 7) // longitudinal acceleration
        b.skip(if (version == 1) 16 else 11); b.skip(3) // curvature (its range changed in CDD 1.3.1)
        if (b.bit()) return null else b.skip(2) // curvature calculation mode (extensible enum)
        b.skip(16 + 4) // yaw rate
        if (opt and 0x40 != 0) b.skip(7) // acceleration control
        if (opt and 0x20 != 0) b.skip(4) // lane position
        if (opt and 0x10 != 0) b.skip(10 + 7) // steering wheel angle
        if (opt and 0x08 != 0) b.skip(9 + 7) // lateral acceleration
        if (opt and 0x04 != 0) b.skip(9 + 7) // vertical acceleration
        if (opt and 0x02 != 0) b.skip(3) // performance class
        if (opt and 0x01 != 0) { // CEN DSRC tolling zone (extensible from CDD 1.3.1)
            val ext = version >= 2 && b.bit()
            val id = b.bit(); b.skip(31 + 32); if (id) b.skip(27)
            if (ext) b.skipExtensions()
        }
        var role: Int? = null
        if (hasLow) {
            if (b.bit()) return msg(stationType, lat, lon, speed, heading, null) // an extension we don't know
            role = b.int(4)
            b.skip(8) // exterior lights
            val points = b.int(6)
            repeat(points) {
                val dt = b.bit()
                b.skip(18 + 18 + 15)
                if (dt) { if (b.bit()) b.skipLength() else b.skip(16) }
            }
        }
        var special: Int? = null; var light: Boolean? = null; var siren: Boolean? = null
        if (hasSpecial && !b.bit()) {
            special = b.int(3)
            val lb: Int? = when (special) {
                1 -> { b.skip(4); b.int(2) } // special transport
                3 -> { val sub = b.bit(); b.bit(); if (sub) b.skip(8); b.int(2) } // road works
                4 -> b.int(2) // rescue
                5 -> { b.skip(2); b.int(2) } // emergency
                6 -> { b.skip(3); b.int(2) } // safety car
                else -> null
            }
            if (lb != null) { light = lb and 0x2 != 0; siren = lb and 0x1 != 0 }
        }
        return msg(stationType, lat, lon, speed, heading, role).copy(special = special, lightBar = light, siren = siren)
    }

    private fun msg(type: Int, lat: Double?, lon: Double?, speed: Int, heading: Int, role: Int?) = Message(
        "", Kind.CAM, type, lat, lon,
        speedMps = if (speed == 16383) null else speed / 100.0,
        headingDeg = if (heading == 3601) null else heading / 10.0,
        role = role
    )

    /** ReferencePosition; null coordinates when the sender marks them unavailable. */
    private fun referencePosition(b: Bits): Pair<Double?, Double?> {
        val lat = b.long(31) - 900_000_000L
        val lon = b.long(32) - 1_800_000_000L
        b.skip(12 + 12 + 12 + 20 + 4) // confidence ellipse, altitude
        val ok = lat in -900_000_000L..900_000_000L && lon in -1_800_000_000L..1_800_000_000L
        return if (ok) lat / 1e7 to lon / 1e7 else null to null
    }

    // ---- DENM (UPER, EN 302 637-3 v1.3.1) ----------------------------------------------------------------

    fun denm(p: ByteArray, at: Int): Message? = runCatching { readDenm(p, at) }.getOrNull()

    private fun readDenm(p: ByteArray, at: Int): Message? {
        val b = Bits(p, at)
        val version = b.int(8)
        if (b.int(8) != 1 || version != 2) return null // messageID 1 = DENM, v1.3.1
        b.skip(32)
        val hasSituation = b.bit(); b.bit(); b.bit() // situation, location, à-la-carte
        // ManagementContainer (extensible)
        val mgmtExt = b.bit()
        val term = b.bit(); val relDist = b.bit(); val relDir = b.bit(); val validity = b.bit(); val interval = b.bit()
        b.skip(32 + 16) // action ID
        b.skip(42 + 42) // detection and reference time
        if (term) b.skip(1)
        val (lat, lon) = referencePosition(b)
        if (relDist) b.skip(3)
        if (relDir) b.skip(2)
        if (validity) b.skip(17)
        if (interval) b.skip(14)
        val stationType = b.int(8)
        if (mgmtExt) b.skipExtensions()
        if (!hasSituation) return Message("", Kind.DENM, stationType, lat, lon)
        // SituationContainer (extensible): information quality, then the cause (extensible in CDD 1.3.1)
        b.bit(); b.bit(); b.bit()
        b.skip(3)
        b.bit()
        val cause = b.int(8)
        val sub = b.int(8)
        return Message("", Kind.DENM, stationType, lat, lon, cause = cause, subCause = sub)
    }

    // ---- What RF Sentinel makes of it ----------------------------------------------------------------

    private val ROLES = mapOf(1 to "public transport", 2 to "special transport", 3 to "dangerous goods", 4 to "road works",
        5 to "rescue", 6 to "emergency", 7 to "safety car", 8 to "agriculture", 9 to "commercial", 10 to "military",
        11 to "road operator", 12 to "taxi")
    private val TYPES = mapOf(0 to "unknown", 1 to "pedestrian", 2 to "cyclist", 3 to "moped", 4 to "motorcycle",
        5 to "car", 6 to "bus", 7 to "light truck", 8 to "heavy truck", 9 to "trailer", 10 to "special vehicle",
        11 to "tram", 15 to "roadside unit")

    fun typeName(t: Int?) = t?.let { TYPES[it] ?: "type $it" } ?: "unknown"

    /** True for an emergency, rescue or safety car (its role, its special container or its warning). */
    fun isEmergency(m: Message): Boolean =
        m.role in setOf(5, 6, 7) || m.special in setOf(4, 5, 6) || (m.kind == Kind.DENM && m.cause == 95)

    /**
     * The match for an emergency vehicle, or null for everything else. Lights and siren on is the
     * strongest; a DENM "emergency vehicle approaching" is the vehicle's own warning to traffic ahead.
     */
    fun hit(m: Message): Hit? {
        if (!isEmergency(m)) return null
        val active = m.lightBar == true || m.siren == true
        val what = when {
            m.kind == Kind.DENM && m.subCause == 2 -> "Priority vehicle approaching"
            m.kind == Kind.DENM -> "Emergency vehicle approaching"
            m.role == 5 || m.special == 4 -> "Rescue vehicle"
            m.role == 7 || m.special == 6 -> "Safety car"
            else -> "Emergency vehicle"
        }
        val state = when {
            m.lightBar == true && m.siren == true -> " - lights and siren on"
            m.lightBar == true -> " - lights on"
            m.siren == true -> " - siren on"
            else -> ""
        }
        val evidence = buildString {
            append(if (m.kind == Kind.DENM) "V2X warning (ETSI DENM, cause ${m.cause}/${m.subCause})" else "V2X status broadcast (ETSI CAM)")
            m.role?.let { append(" · vehicle role: ${ROLES[it] ?: "default"}") }
            append(" · station: ${typeName(m.stationType)}")
            m.lightBar?.let { append(" · light bar ${if (it) "on" else "off"}") }
            m.siren?.let { append(" · siren ${if (it) "on" else "off"}") }
            m.speedMps?.let { append(" · %.0f km/h".format(java.util.Locale.US, it * 3.6)) }
            m.headingDeg?.let { append(" · heading %.0f°".format(java.util.Locale.US, it)) }
            if (m.secured) append(" · signed message")
            append(". Position is the vehicle's own GPS.")
        }
        val confidence = when {
            m.kind == Kind.DENM || active -> 97
            else -> 85
        }
        return Hit(Category.V2X, what + state, confidence, evidence, "V2X")
    }

    // ---- Unaligned PER bit reader --------------------------------------------------------------------

    private class Bits(private val b: ByteArray, startByte: Int) {
        private var pos = startByte * 8L
        fun bit(): Boolean {
            val i = (pos / 8).toInt()
            if (i >= b.size) throw IndexOutOfBoundsException("end of message")
            val v = (b[i].toInt() shr (7 - (pos % 8).toInt())) and 1
            pos++
            return v == 1
        }
        fun long(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 1) or (if (bit()) 1L else 0L) }; return v }
        fun int(n: Int): Int = long(n).toInt()
        fun skip(n: Int) { repeat(n) { bit() } }
        /** An unconstrained length determinant (up to 16383) followed by that many bytes. */
        fun skipLength() {
            val len = if (!bit()) int(7) else { if (bit()) throw IllegalStateException("fragmented"); int(14) }
            skip(len * 8)
        }
        /** The extension additions of an extensible SEQUENCE: a bitmap, then each present one as an open type. */
        fun skipExtensions() {
            val n = if (!bit()) int(6) + 1 else throw IllegalStateException("too many extensions")
            val present = (0 until n).count { bit() }
            repeat(present) { skipLength() }
        }
    }
}
