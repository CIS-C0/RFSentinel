package com.rfsentinel.app.sdr

/**
 * P25 Phase 1 data packets (PDU frames, DUID 0xC): a radio's GPS position sent as Motorola LRRP. The frame's header
 * block (rate-1/2 trellis, CRC-CCITT, as a TSBK), then its data blocks: unconfirmed ones rate 1/2 (12 bytes),
 * confirmed ones rate 3/4 (a 7-bit serial, a CRC-9, 16 bytes). The blocks' bytes end with a CRC-32 over the packet;
 * inside, an optional SNDCP header, then IPv4 and UDP; LRRP is UDP port 4001, its point and circle tokens a position.
 * Layouts as in SDRTrunk (Dennis Sheirer, GPL-3.0: PDUHeader, PacketHeader, PacketMessage, ConfirmedDataBlock,
 * P25_3_4_Node, CRCP25.checkCRC9, PacketMessageFactory, LRRPPacket, TokenType, Point2d/3d, Circle2d/3d); the packet
 * CRC-32 as OP25 computes it (GPL-3.0, p25p1_fdma.cc). SDRTrunk doesn't check the packet CRC: checked here, with the
 * IPv4 header checksum, so noise can't make up a position.
 * Positions are for the screen only: nothing here logs them. Pure: unit-tested.
 */
object P25Pdu {

    const val DUID_PDU = 0xC

    /** How far a data frame got, for the stage counts in the log. */
    enum class Stage { HEADER, PACKET, LRRP }

    /** [stage] reached; [positions] the LRRP points it carried. [nac] the frame's network access code. */
    class Decoded(val nac: Int, val stage: Stage, val positions: List<P25Tsbk.Position> = emptyList())

    /** The frame (status symbols in, as [P25Demod] hands it) is a data packet: worth collecting whole. */
    fun isPdu(frame: IntArray): Boolean = P25Frame.nid(P25Frame.stripStatus(frame.copyOf(NID_SPAN)))?.duid == DUID_PDU

    /** Null when [frame] isn't a data packet or its header doesn't check. */
    fun decode(frame: IntArray, soft: DoubleArray? = null): Decoded? {
        val d = P25Frame.stripStatus(frame)
        val sd = soft?.takeIf { it.size == frame.size }?.let { P25Frame.stripStatus(it) }
        val nid = P25Frame.nid(d)?.takeIf { it.duid == DUID_PDU } ?: return null
        val h = block(d, sd, 0)?.takeIf { P25Frame.crcOk(it) } ?: return null
        val header = Decoded(nid.nac, Stage.HEADER)
        val confirmed = h[0].u() and 0x40 != 0
        val outbound = h[0].u() and 0x20 != 0
        if (h[0].u() and 0x1F != FORMAT_PACKET_DATA) return header
        val sap = h[1].u() and 0x3F
        val llid = h[3].u() shl 16 or (h[4].u() shl 8) or h[5].u()
        val blocks = h[6].u() and 0x7F
        val pad = h[7].u() and 0x1F
        val dataOffset = h[9].u() and 0x3F
        if (blocks == 0) return header
        val per = if (confirmed) 16 else 12
        val data = ByteArray(blocks * per)
        for (i in 0 until blocks) {
            val b = if (confirmed) confirmedBlock(d, i + 1) else block(d, sd, i + 1)
            b?.copyInto(data, i * per) ?: return header
        }
        val end = data.size - 4
        if (crc32(data, end) != (data[end].u() shl 24 or (data[end + 1].u() shl 16) or (data[end + 2].u() shl 8) or data[end + 3].u())) return header
        val packet = Decoded(nid.nac, Stage.PACKET)
        if (sap != SAP_USER_DATA && sap != SAP_PACKET_DATA) return packet
        // SNDCP header (2 bytes) when the data starts 2 bytes in: compressed IP headers aren't read (as SDRTrunk).
        if (dataOffset == 2 && data[1].u() shr 4 != 0) return packet
        val ip = data.copyOfRange(minOf(dataOffset, end), maxOf(minOf(dataOffset, end), end - pad))
        val lrrp = udp4001(ip) ?: return packet
        val points = lrrp(lrrp).map { P25Tsbk.Position(llid, outbound, it[0], it[1], it[2].takeIf { r -> r >= 0 }, it[3].takeIf { a -> a >= 0 }) }
        return Decoded(nid.nac, Stage.LRRP, points)
    }

    /** Block [n] (0 the header) at rate 1/2: 12 bytes, or null. */
    private fun block(d: IntArray, sd: DoubleArray?, n: Int): ByteArray? {
        val at = NID_DIBITS + n * BLOCK_DIBITS
        if (at + BLOCK_DIBITS > d.size) return null
        return P25Frame.trellis(P25Frame.deinterleave(d, at), sd?.let { P25Frame.deinterleaveSoft(it, at) })
    }

    /** Block [n] at rate 3/4: its 16 data bytes when its CRC-9 checks, else null. */
    private fun confirmedBlock(d: IntArray, n: Int): ByteArray? {
        val at = NID_DIBITS + n * BLOCK_DIBITS
        if (at + BLOCK_DIBITS > d.size) return null
        val b = trellis34(P25Frame.deinterleave(d, at)) ?: return null
        if (!crc9Ok(b)) return null
        return b.copyOfRange(2, 18)
    }

    /** The UDP payload of an IPv4 packet sent to port 4001 (LRRP), or null. */
    internal fun udp4001(p: ByteArray): ByteArray? {
        if (p.size < 28 || p[0].u() shr 4 != 4) return null
        val ihl = (p[0].u() and 0xF) * 4
        val total = p[2].u() shl 8 or p[3].u()
        if (ihl < 20 || total < ihl + 8 || total > p.size || p[9].u() != 17) return null
        var sum = 0
        for (i in 0 until ihl step 2) sum += p[i].u() shl 8 or p[i + 1].u()
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        if (sum != 0xFFFF) return null
        val dst = p[ihl + 2].u() shl 8 or p[ihl + 3].u()
        val len = p[ihl + 4].u() shl 8 or p[ihl + 5].u()
        if (dst != LRRP_PORT || len < 8 || ihl + len > total) return null
        return p.copyOfRange(ihl + 8, ihl + len)
    }

    /**
     * An LRRP packet's positions: each [lat, lon, radius m or -1, altitude m or -1]. Responses only (requests carry
     * none). Tokens walked by their lengths (SDRTrunk's TokenType); an unknown token ends the walk.
     */
    internal fun lrrp(b: ByteArray): List<DoubleArray> {
        if (b.size < 2 || b[0].u() !in RESPONSE_TYPES) return emptyList()
        val end = minOf(b.size, 2 + b[1].u())
        val out = ArrayList<DoubleArray>()
        var at = 2
        while (at < end) {
            val id = b[at].u()
            val size = when (id) {
                0x22 -> if (at + 1 < end) b[at + 1].u() + 2 else return out // identity: variable length
                0x37 -> if (at + 1 < end && b[at + 1].u() and 0x80 != 0) 3 else 2 // response code, 1 or 2 bytes
                else -> (TOKEN_LENGTH[id] ?: return out) + 1
            }
            if (at + size > end) break
            if (id in POINTS) point(b, at, id)?.let { out += it }
            at += size
        }
        return out
    }

    /** Point2d 0x66, Point3d 0x69, Circle2d 0x51, Circle3d 0x55 at [at]. */
    private fun point(b: ByteArray, at: Int, id: Int): DoubleArray? {
        fun u32(i: Int) = (b[i].u().toLong() shl 24) or (b[i + 1].u().toLong() shl 16) or (b[i + 2].u().toLong() shl 8) or b[i + 3].u().toLong()
        // Latitude: a hemisphere flag then 31 bits; longitude: 32 bits two's complement (SDRTrunk's Point2d).
        val latRaw = u32(at + 1)
        val lat = (latRaw and 0x7FFFFFFF) * 180.0 / 4294967295.0 * (if (latRaw and 0x80000000L != 0L) -1 else 1)
        val lon = u32(at + 5).toInt() * 360.0 / 4294967295.0
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        val u16 = { i: Int -> b[i].u() shl 8 or b[i + 1].u() }
        val radius = if (id == 0x51 || id == 0x55) u16(at + 9) / 100.0 else -1.0
        val alt = when (id) {
            0x69 -> (b[at + 9].u() shl 16 or u16(at + 10)) / 100.0
            0x55 -> u16(at + 11) / 100.0
            else -> -1.0
        }
        return doubleArrayOf(lat, lon, radius, alt)
    }

    /** CRC-32 of the first [bytes] of [b], MSB first, polynomial 0x04C11DB7, inverted (OP25's crc32). */
    internal fun crc32(b: ByteArray, bytes: Int): Int {
        var crc = 0L
        for (i in 0 until bytes * 8) {
            val top = (crc ushr 31) and 1
            crc = (crc shl 1) and 0xFFFFFFFFL
            if (top xor (b[i / 8].u() shr (7 - i % 8) and 1).toLong() != 0L) crc = crc xor 0x04C11DB7L
        }
        return (crc xor 0xFFFFFFFFL).toInt()
    }

    /** A confirmed block's 144 bits: serial (bits 0-6) and data (16-143) against the CRC-9 in bits 7-15 (SDRTrunk's table). */
    internal fun crc9Ok(b: ByteArray): Boolean {
        var sent = 0
        for (i in 7..15) sent = sent shl 1 or (b[i / 8].u() shr (7 - i % 8) and 1)
        val r = crc9(b) xor sent
        return r == 0 || r == 0x1FF
    }

    /** Rate-3/4 trellis: the symbol sent for input tribit [in] from state (the previous input) [s]: NEXT34[s][in]. */
    private val NEXT34 = arrayOf(
        intArrayOf(2, 13, 14, 1, 7, 8, 11, 4), intArrayOf(14, 1, 7, 8, 11, 4, 2, 13),
        intArrayOf(10, 5, 6, 9, 15, 0, 3, 12), intArrayOf(6, 9, 15, 0, 3, 12, 10, 5),
        intArrayOf(15, 0, 3, 12, 10, 5, 6, 9), intArrayOf(3, 12, 10, 5, 6, 9, 15, 0),
        intArrayOf(7, 8, 11, 4, 2, 13, 14, 1), intArrayOf(11, 4, 2, 13, 14, 1, 7, 8))

    /** Viterbi decode of 49 symbols into 48 tribits (18 bytes; the 49th input is the flush). Null when too damaged. */
    internal fun trellis34(sym: IntArray): ByteArray? {
        val inf = Int.MAX_VALUE / 4
        var metric = IntArray(8) { if (it == 0) 0 else inf }
        val from = Array(sym.size) { IntArray(8) }
        for (t in sym.indices) {
            val next = IntArray(8) { inf }
            for (s in 0 until 8) if (metric[s] < inf) for (input in 0 until 8) {
                val m = metric[s] + Integer.bitCount(NEXT34[s][input] xor sym[t])
                if (m < next[input]) { next[input] = m; from[t][input] = s }
            }
            metric = next
        }
        if (metric[0] > MAX_TRELLIS_ERRORS) return null
        val tri = IntArray(sym.size)
        var s = 0
        for (t in sym.indices.reversed()) { tri[t] = s; s = from[t][s] }
        val out = ByteArray(18)
        for (i in 0 until 144) if (tri[i / 3] shr (2 - i % 3) and 1 != 0) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        return out
    }

    /** Sent order, for the tests: 48 tribits (from 18 bytes) and the flush to 49 symbols. */
    internal fun encode34(b: ByteArray): IntArray {
        var s = 0
        return IntArray(49) { t ->
            val input = if (t == 48) 0 else (0 until 3).fold(0) { a, k -> val i = 3 * t + k; a shl 1 or (b[i / 8].toInt() shr (7 - i % 8) and 1) }
            NEXT34[s][input].also { s = input }
        }
    }

    /** The CRC-9 a confirmed block's bits 7-15 should hold. */
    internal fun crc9(b: ByteArray): Int {
        fun bit(i: Int) = b[i / 8].u() shr (7 - i % 8) and 1
        var calc = 0
        for (i in 0 until 144) if (bit(i) == 1) { if (i < 7) calc = calc xor CRC9[i] else if (i > 15) calc = calc xor CRC9[i - 9] }
        return calc
    }

    private fun Byte.u() = toInt() and 0xFF

    private const val FORMAT_PACKET_DATA = 0x16
    private const val SAP_USER_DATA = 0
    private const val SAP_PACKET_DATA = 4
    private const val LRRP_PORT = 4001
    private const val NID_DIBITS = 32
    private const val BLOCK_DIBITS = 98
    /** Enough of a frame for its NID, status symbols included. */
    private const val NID_SPAN = 40
    private const val MAX_TRELLIS_ERRORS = 20
    /** Frame length [P25Demod] collects for a data packet: the header and up to 12 blocks, status symbols included. */
    const val PDU_DIBITS = 1350

    /** LRRP responses: immediate, triggered start, triggered, triggered stop, protocol version. */
    private val RESPONSE_TYPES = setOf(0x07, 0x0B, 0x0D, 0x11, 0x15)
    private val POINTS = setOf(0x51, 0x55, 0x66, 0x69)
    /** Fixed-length tokens: id -> bytes after the id (SDRTrunk's TokenType). */
    private val TOKEN_LENGTH = mapOf(0x23 to 1, 0x31 to 1, 0x34 to 5, 0x36 to 1, 0x38 to 0, 0x3A to 0, 0x42 to 0, 0x4A to 1,
        0x50 to 0, 0x51 to 10, 0x52 to 0, 0x54 to 0, 0x55 to 15, 0x56 to 1, 0x57 to 0, 0x61 to 1, 0x62 to 0, 0x64 to 0,
        0x66 to 8, 0x69 to 11, 0x6C to 2, 0x73 to 1, 0x78 to 1)

    /** Each of the 135 checked bits' share of the CRC-9 (polynomial 0x259), from SDRTrunk's CRCP25.CRC9_CHECKSUMS. */
    private val CRC9 = intArrayOf(
        0x1E7, 0x1F3, 0x1F9, 0x1FC, 0x0D2, 0x045, 0x122, 0x0BD, 0x15E, 0x083,
        0x141, 0x1A0, 0x0FC, 0x052, 0x005, 0x102, 0x0AD, 0x156, 0x087, 0x143,
        0x1A1, 0x1D0, 0x0C4, 0x04E, 0x00B, 0x105, 0x182, 0x0ED, 0x176, 0x097,
        0x14B, 0x1A5, 0x1D2, 0x0C5, 0x162, 0x09D, 0x14E, 0x08B, 0x145, 0x1A2,
        0x0FD, 0x17E, 0x093, 0x149, 0x1A4, 0x0FE, 0x053, 0x129, 0x194, 0x0E6,
        0x05F, 0x12F, 0x197, 0x1CB, 0x1E5, 0x1F2, 0x0D5, 0x16A, 0x099, 0x14C,
        0x08A, 0x069, 0x134, 0x0B6, 0x077, 0x13B, 0x19D, 0x1CE, 0x0CB, 0x165,
        0x1B2, 0x0F5, 0x17A, 0x091, 0x148, 0x088, 0x068, 0x018, 0x020, 0x03C,
        0x032, 0x035, 0x11A, 0x0A1, 0x150, 0x084, 0x06E, 0x01B, 0x10D, 0x186,
        0x0EF, 0x177, 0x1BB, 0x1DD, 0x1EE, 0x0DB, 0x16D, 0x1B6, 0x0F7, 0x17B,
        0x1BD, 0x1DE, 0x0C3, 0x161, 0x1B0, 0x0F4, 0x056, 0x007, 0x103, 0x181,
        0x1C0, 0x0CC, 0x04A, 0x009, 0x104, 0x0AE, 0x07B, 0x13D, 0x19E, 0x0E3,
        0x171, 0x1B8, 0x0F0, 0x054, 0x006, 0x02F, 0x117, 0x18B, 0x1C5, 0x1E2,
        0x0DD, 0x16E, 0x09B, 0x14D, 0x1A6)
}
