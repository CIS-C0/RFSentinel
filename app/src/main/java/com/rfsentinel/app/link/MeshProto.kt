package com.rfsentinel.app.link

import java.io.ByteArrayOutputStream

/**
 * The few messages of the Meshtastic phone API (ToRadio / FromRadio protobufs) Sentinel Link uses, written by hand
 * with the field numbers of meshtastic/protobufs (mesh.proto, channel.proto, admin.proto, config.proto), so the
 * official firmware needs nothing added. Unknown fields are skipped, as protobuf readers do.
 */
object MeshProto {
    /** Meshtastic's port for apps' own data: RF Sentinel's messages, which other apps leave alone. */
    const val PORT_PRIVATE_APP = 256
    const val PORT_ADMIN_APP = 6
    const val BROADCAST = 0xFFFFFFFFL
    /** want_config_id asking for the node's config and channels without its node list (much shorter). */
    const val CONFIG_ONLY = 69420
    /** Largest payload of one packet (Constants.DATA_PAYLOAD_LEN). */
    const val MAX_PAYLOAD = 233
    const val PRIORITY_BACKGROUND = 10
    const val PRIORITY_DEFAULT = 64
    const val PRIORITY_RELIABLE = 70
    const val ROLE_DISABLED = 0
    const val ROLE_SECONDARY = 2

    // ------------------------------------------------------------------ to the node

    fun wantConfig(nonce: Int): ByteArray = W().apply { uint(3, nonce.toLong() and 0xFFFFFFFFL) }.bytes()

    /** A MeshPacket for the node to send (ToRadio.packet). */
    fun packet(to: Long, channel: Int, port: Int, payload: ByteArray, id: Long, hopLimit: Int, priority: Int): ByteArray =
        W().apply {
            msg(1) {
                fixed32(2, to)
                uint(3, channel.toLong())
                msg(4) { uint(1, port.toLong()); bytes(2, payload) }
                fixed32(6, id)
                uint(9, hopLimit.toLong())
                uint(11, priority.toLong())
            }
        }.bytes()

    /** AdminMessage.set_channel: a secondary channel with this name and key, no MQTT. */
    fun setChannel(index: Int, name: String, psk: ByteArray): ByteArray = W().apply {
        msg(33) {
            uint(1, index.toLong())
            msg(2) { bytes(2, psk); string(3, name) }
            uint(3, ROLE_SECONDARY.toLong())
        }
    }.bytes()

    // ------------------------------------------------------------------ from the node

    sealed class From {
        data class MyInfo(val nodeNum: Long) : From()
        class ChannelInfo(val index: Int, val role: Int, val name: String, val psk: ByteArray) : From()
        /** LoRaConfig: hop limit (0 = the firmware's default), region (0 = not set: the radio stays off). */
        data class Lora(val hopLimit: Int, val region: Int, val txEnabled: Boolean) : From()
        data class ConfigComplete(val id: Long) : From()
        class Packet(val from: Long, val to: Long, val channel: Int, val port: Int, val payload: ByteArray, val rxRssi: Int) : From()
        data class Queue(val res: Int, val free: Int) : From()
        object Rebooted : From()
        object Other : From()
    }

    fun readFromRadio(b: ByteArray): From {
        var out: From = From.Other
        val r = R(b)
        r.fields { f, w ->
            when {
                f == 2 && w == 2 -> { out = readPacket(r.bytes()); true }
                f == 3 && w == 2 -> { out = readMyInfo(r.bytes()); true }
                f == 5 && w == 2 -> { out = readConfig(r.bytes()); true }
                f == 7 && w == 0 -> { out = From.ConfigComplete(r.varint() and 0xFFFFFFFFL); true }
                f == 8 && w == 0 -> { if (r.varint() != 0L) out = From.Rebooted; true }
                f == 10 && w == 2 -> { out = readChannel(r.bytes()); true }
                f == 11 && w == 2 -> { out = readQueue(r.bytes()); true }
                else -> false
            }
        }
        return out
    }

    private fun readPacket(b: ByteArray): From {
        var from = 0L; var to = 0L; var channel = 0; var port = -1; var payload = ByteArray(0); var rssi = 0
        val r = R(b)
        r.fields { f, w ->
            when {
                f == 1 && w == 5 -> { from = r.fixed32(); true }
                f == 2 && w == 5 -> { to = r.fixed32(); true }
                f == 3 && w == 0 -> { channel = r.varint().toInt(); true }
                f == 4 && w == 2 -> {
                    val d = R(r.bytes())
                    d.fields { df, dw ->
                        when {
                            df == 1 && dw == 0 -> { port = d.varint().toInt(); true }
                            df == 2 && dw == 2 -> { payload = d.bytes(); true }
                            else -> false
                        }
                    }
                    true
                }
                f == 12 && w == 0 -> { rssi = r.varint().toInt(); true }
                else -> false
            }
        }
        return From.Packet(from, to, channel, port, payload, rssi)
    }

    private fun readMyInfo(b: ByteArray): From {
        var num = 0L
        val r = R(b)
        r.fields { f, w -> if (f == 1 && w == 0) { num = r.varint() and 0xFFFFFFFFL; true } else false }
        return From.MyInfo(num)
    }

    private fun readConfig(b: ByteArray): From {
        var out: From = From.Other
        val r = R(b)
        r.fields { f, w ->
            if (f == 6 && w == 2) {
                var hop = 0; var region = 0; var tx = false
                val l = R(r.bytes())
                l.fields { lf, lw ->
                    when {
                        lf == 7 && lw == 0 -> { region = l.varint().toInt(); true }
                        lf == 8 && lw == 0 -> { hop = l.varint().toInt(); true }
                        lf == 9 && lw == 0 -> { tx = l.varint() != 0L; true }
                        else -> false
                    }
                }
                out = From.Lora(hop, region, tx)
                true
            } else false
        }
        return out
    }

    private fun readChannel(b: ByteArray): From {
        var index = 0; var role = 0; var name = ""; var psk = ByteArray(0)
        val r = R(b)
        r.fields { f, w ->
            when {
                f == 1 && w == 0 -> { index = r.varint().toInt(); true }
                f == 2 && w == 2 -> {
                    val st = R(r.bytes())
                    st.fields { sf, sw ->
                        when {
                            sf == 2 && sw == 2 -> { psk = st.bytes(); true }
                            sf == 3 && sw == 2 -> { name = String(st.bytes(), Charsets.UTF_8); true }
                            else -> false
                        }
                    }
                    true
                }
                f == 3 && w == 0 -> { role = r.varint().toInt(); true }
                else -> false
            }
        }
        return From.ChannelInfo(index, role, name, psk)
    }

    private fun readQueue(b: ByteArray): From {
        var res = 0; var free = 0
        val r = R(b)
        r.fields { f, w ->
            when {
                f == 1 && w == 0 -> { res = r.varint().toInt(); true }
                f == 2 && w == 0 -> { free = r.varint().toInt(); true }
                else -> false
            }
        }
        return From.Queue(res, free)
    }

    // ------------------------------------------------------------------ wire format

    /** Protobuf writer: zero / false / empty values are left out, as proto3 does. */
    class W {
        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()
        private fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun uint(field: Int, v: Long) { if (v != 0L) { tag(field, 0); varint(v) } }
        /** A signed int32 (negative ones take 10 bytes, sign-extended). */
        fun int32(field: Int, v: Int) { if (v != 0) { tag(field, 0); varint(v.toLong()) } }
        fun bool(field: Int, v: Boolean) { if (v) { tag(field, 0); varint(1) } }
        fun fixed32(field: Int, v: Long) {
            tag(field, 5)
            for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) { if (s.isNotEmpty()) bytes(field, s.toByteArray(Charsets.UTF_8)) }
        fun msg(field: Int, block: W.() -> Unit) = bytes(field, W().apply(block).bytes())
    }

    /** Protobuf reader; throws on malformed input. */
    class R(private val b: ByteArray) {
        private var pos = 0
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                require(pos < b.size && shift < 64) { "bad varint" }
                val x = b[pos++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        fun fixed32(): Long {
            require(pos + 4 <= b.size) { "short fixed32" }
            var v = 0L
            for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }
        fun bytes(): ByteArray {
            val n = varint()
            require(n >= 0 && pos + n <= b.size) { "short bytes" }
            return b.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() }
        }
        private fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("wire type $wire")
            }
            require(pos <= b.size) { "short field" }
        }
        /** Each field in turn: [f] reads it and returns true, or returns false to skip it. */
        fun fields(f: (field: Int, wire: Int) -> Boolean) {
            while (pos < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val wire = (key and 7).toInt()
                if (!f(field, wire)) skip(wire)
            }
        }
    }
}
