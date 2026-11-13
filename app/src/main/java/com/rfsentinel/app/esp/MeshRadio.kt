package com.rfsentinel.app.esp

import com.rfsentinel.app.link.MeshProto
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** LoRa nodes report no RSSI of their own in a node list: the weakest a LoRa radio still hears. */
private const val NOMINAL_RSSI = -120
/** A node in the radio's list counts as heard here only this recently: the list keeps nodes for days. */
private const val RECENT_S = 30 * 60L
private const val MAX_FRAME = 512

/** A mesh node has no MAC on the air: a locally administered address made from its node number or key. */
private fun nodeMac(kind: Int, id: ByteArray) =
    (listOf(0x06, kind) + id.take(4).map { it.toInt() and 0xFF }).joinToString(":") { "%02X".format(it) }

/**
 * A Meshtastic / MeshCore node a mesh radio heard over LoRa ([MeshtasticLog] / [MeshCoreLog] give it this made-up
 * address and name): it can be kilometres away, so its signal says nothing about distance.
 */
fun isLoRaNode(mac: String, name: String?) = name != null &&
    (mac.startsWith("06:4D:") && name.startsWith("Meshtastic: ") || mac.startsWith("06:43:") && name.startsWith("MeshCore: "))

/** What a Meshtastic node last said about itself, and how the radio heard it: shown on its details page. */
data class NodeStats(val metrics: MeshProto.Metrics = MeshProto.Metrics(), val snr: Float? = null, val hops: Int? = null, val at: Long = 0)

/** Each LoRa node's [NodeStats], by its made-up address. */
object LoRaStats {
    /** The paired node's own telemetry (channel use, its own air time), for the automatic send pace; and when. */
    @Volatile var own: MeshProto.Metrics? = null; @Volatile var ownAt = 0L
    fun noteOwn(metrics: MeshProto.Metrics?, now: Long) { metrics ?: return; own = own?.with(metrics) ?: metrics; ownAt = now }
    private val byMac = java.util.concurrent.ConcurrentHashMap<String, NodeStats>()
    fun of(mac: String): NodeStats? = byMac[mac.uppercase()]
    fun note(mac: String, metrics: MeshProto.Metrics?, snr: Float?, hops: Int?, now: Long) {
        if (metrics == null && snr == null && hops == null) return
        byMac.merge(mac.uppercase(), NodeStats(metrics ?: MeshProto.Metrics(), snr, hops, now)) { old, new ->
            NodeStats(metrics?.let { old.metrics.with(it) } ?: old.metrics, snr ?: old.snr, hops ?: old.hops, now)
        }
        // Only telemetry reports go in the history: a node's other packets (positions, texts) would push them out.
        if (metrics != null) {
            val h = history.getOrPut(mac.uppercase()) { ArrayDeque() }
            synchronized(h) {
                h.addLast(Sample(now, metrics, snr))
                while (h.size > HISTORY_MAX || h.first().at < now - HISTORY_MS) h.removeFirst()
            }
        }
    }

    /** One telemetry report and the signal it came in on: a node's page plots them ([com.rfsentinel.app.ui.DeviceSections.nodeRows]). */
    class Sample(val at: Long, val metrics: MeshProto.Metrics, val snr: Float?)
    private val history = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Sample>>()
    /** [mac]'s telemetry reports of the last day, oldest first. */
    fun historyOf(mac: String): List<Sample> = history[mac.uppercase()]?.let { synchronized(it) { it.toList() } }.orEmpty()
    /** A day of reports at the default every 30 min, more for nodes set to report faster. */
    private const val HISTORY_MS = 24 * 3_600_000L
    private const val HISTORY_MAX = 96

    fun clear() { byMac.clear(); history.clear(); own = null; ownAt = 0L }
}

/** What a Meshtastic radio tells its phone, as sightings: only nodes it heard over LoRa, never over MQTT. */
class MeshtasticLog(private val nowS: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private var me = 0L
    private val names = HashMap<Long, String>()
    private val spots = HashMap<Long, Pair<Double, Double>>()
    val heard = HashSet<Long>()

    fun feed(m: MeshProto.From): EspSighting? {
        when (m) {
            is MeshProto.From.MyInfo -> me = m.nodeNum
            is MeshProto.From.NodeInfo -> {
                if (m.name.isNotEmpty()) names[m.num] = m.name
                if (m.num == me) LoRaStats.noteOwn(m.metrics, nowS() * 1000)
                m.position?.let { spots[m.num] = it }
                if (m.num != me && !m.viaMqtt && nowS() - m.lastHeard < RECENT_S) {
                    LoRaStats.note(mac(m.num), m.metrics, m.snr, m.hops, nowS() * 1000)
                    return sighting(m.num, NOMINAL_RSSI)
                }
            }
            is MeshProto.From.Packet -> {
                if (m.from == me && m.port == MeshProto.PORT_TELEMETRY_APP)
                    LoRaStats.noteOwn(runCatching { MeshProto.telemetry(m.payload) }.getOrNull(), nowS() * 1000)
                if (m.from == 0L || m.from == me || m.viaMqtt) return null
                when (m.port) {
                    MeshProto.PORT_NODEINFO_APP -> MeshProto.userName(m.payload).takeIf { it.isNotEmpty() }?.let { names[m.from] = it }
                    MeshProto.PORT_POSITION_APP -> MeshProto.position(m.payload)?.let { spots[m.from] = it }
                }
                val metrics = if (m.port == MeshProto.PORT_TELEMETRY_APP) runCatching { MeshProto.telemetry(m.payload) }.getOrNull() else null
                LoRaStats.note(mac(m.from), metrics, m.snr, m.hops, nowS() * 1000)
                return sighting(m.from, m.rxRssi.takeIf { it != 0 } ?: NOMINAL_RSSI)
            }
            else -> Unit
        }
        return null
    }

    private fun mac(num: Long) = nodeMac(0x4D, ByteArray(4) { (num ushr (24 - 8 * it)).toByte() })

    private fun sighting(num: Long, rssi: Int): EspSighting {
        heard += num
        return EspSighting(mac(num), rssi, ble = true, name = "Meshtastic: ${names[num] ?: "!%08x".format(num)}", position = spots[num])
    }
}

/**
 * MeshCore's companion protocol ("Companion Radio" firmware): the radio pushes every advert it hears.
 * Its contact list only supplies names and positions: contacts can be months old. Read-only: no message is
 * fetched or sent and the radio's settings stay as they are.
 */
class MeshCoreLog(private val nowMs: () -> Long = System::currentTimeMillis) {
    private class Contact(val name: String, val position: Pair<Double, Double>?)
    private val contacts = HashMap<String, Contact>()
    private var rssi = NOMINAL_RSSI
    private var rssiAt = 0L
    val heard = HashSet<String>()

    fun feed(f: ByteArray): EspSighting? {
        when (f.firstOrNull()?.toInt()?.and(0xFF)) {
            RESP_CONTACT -> if (f.size >= CONTACT_LEN) contacts[key(f)] = contact(f)
            PUSH_NEW_ADVERT -> if (f.size >= CONTACT_LEN) { contacts[key(f)] = contact(f); return sighting(f) }
            PUSH_ADVERT -> if (f.size >= 33) return sighting(f)
            PUSH_LOG_RX -> if (f.size >= 3) { rssi = f[2].toInt(); rssiAt = nowMs() } // comes just before the advert it carried
        }
        return null
    }

    private fun key(f: ByteArray) = f.copyOfRange(1, 33).joinToString("") { "%02x".format(it) }

    private fun contact(f: ByteArray): Contact {
        val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        val end = (100 until 132).firstOrNull { f[it].toInt() == 0 } ?: 132
        val lat = b.getInt(136); val lon = b.getInt(140)
        return Contact(MeshProto.cleanName(String(f, 100, end - 100, Charsets.UTF_8)), MeshProto.spot(lat / 1e6, lon / 1e6))
    }

    private fun sighting(f: ByteArray): EspSighting {
        val k = key(f)
        heard += k
        val c = contacts[k]
        val level = if (nowMs() - rssiAt < 2_000) rssi else NOMINAL_RSSI
        return EspSighting(nodeMac(0x43, f.copyOfRange(1, 5)), level, ble = true, name = "MeshCore: ${c?.name?.ifEmpty { null } ?: k.take(8)}", position = c?.position)
    }

    companion object {
        const val RESP_SELF_INFO = 5
        private const val RESP_CONTACT = 3
        private const val PUSH_ADVERT = 0x80
        private const val PUSH_LOG_RX = 0x88
        private const val PUSH_NEW_ADVERT = 0x8A
        private const val CONTACT_LEN = 148
        /** CMD_APP_START: the command, 7 reserved bytes, the app's name. */
        val APP_START = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0) + "RF Sentinel".toByteArray()
        val GET_CONTACTS = byteArrayOf(4)
        fun isSelfInfo(f: ByteArray) = f.size >= 36 && f[0].toInt() == RESP_SELF_INFO
    }
}

/** Frames on a mesh radio's USB serial line, between its log text: Meshtastic's or MeshCore's framing. */
class MeshFrames(private val meshtastic: Boolean) {
    private var buf = ByteArray(0)
    private val header = if (meshtastic) 4 else 3

    private fun u8(i: Int) = buf[i].toInt() and 0xFF
    private fun startsAt(i: Int) =
        if (meshtastic) u8(i) == 0x94 && (i + 1 >= buf.size || u8(i + 1) == 0xC3) else u8(i) == '>'.code

    fun feed(b: ByteArray, n: Int): List<ByteArray> {
        buf += b.copyOf(n)
        val out = ArrayList<ByteArray>()
        var i = 0
        while (true) {
            while (i < buf.size && !startsAt(i)) i++
            if (i + header > buf.size) break
            val len = if (meshtastic) (u8(i + 2) shl 8) or u8(i + 3) else u8(i + 1) or (u8(i + 2) shl 8)
            if (len == 0 || len > MAX_FRAME) { i++; continue }
            if (i + header + len > buf.size) break
            out += buf.copyOfRange(i + header, i + header + len)
            i += header + len
        }
        buf = buf.copyOfRange(i, buf.size)
        return out
    }

    companion object {
        fun meshtastic(p: ByteArray) = byteArrayOf(0x94.toByte(), 0xC3.toByte(), (p.size ushr 8).toByte(), p.size.toByte()) + p
        fun meshCore(p: ByteArray) = byteArrayOf('<'.code.toByte(), p.size.toByte(), (p.size ushr 8).toByte()) + p
    }
}
