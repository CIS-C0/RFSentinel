package com.rfsentinel.app.link

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Sentinel Link over the internet: the same sealed packets as the Meshtastic node ([MeshCodec], AES-GCM with a key
 * from the group key), published to an MQTT broker the user chooses (their own Mosquitto, say) on a topic made from
 * the group key. The broker sees encrypted bytes, the phone's IP and when it sends; nothing else. Reconnects on its own.
 */
object MqttRelay {
    private const val TAG = "MqttRelay"
    private const val KEEPALIVE_S = 60
    /** A ping this long after the last packet out; the broker answers, so silence past [DEAD_MS] means the link is gone. */
    private const val PING_MS = 30_000L
    private const val DEAD_MS = 90_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val RETRY_MIN_MS = 5_000L
    private const val RETRY_MAX_MS = 120_000L

    /** Where to connect: mqtts:// (TLS, port 8883 by default) or mqtt:// (port 1883). */
    data class Broker(val tls: Boolean, val host: String, val port: Int)

    /** "mqtts://host:8883", "mqtt://host" or a bare "host" (TLS); null when it isn't one. */
    fun parse(text: String): Broker? = runCatching {
        val t = text.trim()
        if (t.isEmpty()) return null
        val u = URI(if ("://" in t) t else "mqtts://$t")
        val tls = when (u.scheme?.lowercase()) { "mqtts", "ssl", "tls" -> true; "mqtt", "tcp" -> false; else -> return null }
        val host = u.host?.takeIf { it.isNotBlank() } ?: return null
        Broker(tls, host, if (u.port > 0) u.port else if (tls) 8883 else 1883)
    }.getOrNull()

    /** The group's topic: from the group key, so it says nothing about the group and others can't guess it. */
    fun topic(groupKey: ByteArray) =
        "rfsentinel/" + LinkCrypto.hmac(groupKey, "mqtt topic".toByteArray()).copyOf(8).joinToString("") { "%02x".format(it) }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** One line for the screens, "" while off. */
    @Volatile var status = ""; private set
    /** [status] without the broker's name or an IP address, for the log's export. */
    val safeStatus get() = com.rfsentinel.app.util.HwLog.redact(status, config?.broker?.host)
    /** Connected and subscribed. */
    @Volatile var ready = false; private set
    @Volatile var lastSent = 0L; private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }
    private fun setStatus(s: String) {
        if (s == status) return
        // The log never names the broker (it may be the user's own server).
        com.rfsentinel.app.util.HwLogs.relay.logStatus("Internet relay", if (s.isEmpty()) "" else "Internet relay: " + com.rfsentinel.app.util.HwLog.redact(s, config?.broker?.host))
        status = s; changed()
    }

    private data class Config(val broker: Broker, val user: String?, val password: String?, val topic: String, val checkCert: Boolean)

    private var config: Config? = null
    private var thread: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var lastWrite = 0L

    /** Connects (again, when anything changed); [packets] gets the group's payloads, on the relay's thread. */
    @Synchronized
    fun start(broker: Broker, user: String?, password: String?, checkCert: Boolean, groupKey: ByteArray, packets: (ByteArray) -> Unit) {
        val c = Config(broker, user?.takeIf { it.isNotBlank() }, password?.takeIf { it.isNotEmpty() }, topic(groupKey), checkCert)
        if (c == config && thread?.isAlive == true) return
        stop()
        config = c
        thread = Thread({ run(c, packets) }, "rf-mqtt").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        config = null
        thread?.interrupt()
        thread = null
        closeSocket(socket)
        setStatus("")
    }

    /** Publishes one packet; false while not connected (the packet is dropped: the outbox sends newer ones later). */
    fun send(payload: ByteArray): Boolean {
        val c = config ?: return false
        if (!ready) return false
        return write(MqttCodec.publish(c.topic, payload)).also { if (it) lastSent = System.currentTimeMillis() }
    }

    /** Called every few seconds: a ping when nothing went out for a while, so the broker keeps the session. */
    fun keepAlive(now: Long) {
        if (ready && now - lastWrite >= PING_MS) write(MqttCodec.PINGREQ)
    }

    // One lock (this object) for the connection's fields, start / stop and writes: a thread being replaced
    // never touches the new connection.
    private fun write(bytes: ByteArray): Boolean = synchronized(this) {
        val o = out ?: return false
        try { o.write(bytes); o.flush(); lastWrite = System.currentTimeMillis(); true }
        catch (e: IOException) { closeSocket(socket); false } // the reader sees it and reconnects
    }

    /** Closes [s]; the shared fields are cleared only while they're still [s]'s. */
    private fun closeSocket(s: Socket?) {
        synchronized(this) { if (socket === s) { ready = false; out = null; socket = null } }
        runCatching { s?.close() }
    }

    private fun current(c: Config) = synchronized(this) { config === c }

    private fun run(c: Config, packets: (ByteArray) -> Unit) {
        var retry = RETRY_MIN_MS
        while (current(c) && !Thread.currentThread().isInterrupted) {
            var s: Socket? = null
            try {
                setStatus("Connecting to ${c.broker.host}…")
                s = open(c.broker, c.checkCert)
                s.soTimeout = DEAD_MS.toInt()
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val o = s.getOutputStream()
                // A fresh client id each time: nothing ties two sessions (or the phone) together on the broker.
                val id = "rfs-" + LinkCrypto.random(6).joinToString("") { "%02x".format(it) }
                o.write(MqttCodec.connect(id, c.user, c.password, KEEPALIVE_S)); o.flush()
                val ack = MqttCodec.read(input)
                if (ack.type != MqttCodec.CONNACK || ack.body.size < 2) throw IOException("not an MQTT broker")
                when (val code = ack.body[1].toInt()) {
                    0 -> Unit
                    4, 5 -> throw Refused("The broker refused the username or password")
                    else -> throw Refused("The broker refused the connection (code $code)")
                }
                o.write(MqttCodec.subscribe(1, c.topic)); o.flush()
                synchronized(this) {
                    if (!current(c)) throw IOException("stopped")
                    socket = s; out = o; lastWrite = System.currentTimeMillis(); ready = true
                }
                retry = RETRY_MIN_MS
                setStatus("Connected to ${c.broker.host}")
                while (current(c)) {
                    val p = MqttCodec.read(input)
                    if (p.type == MqttCodec.PUBLISH) MqttCodec.payload(p)?.let { (topic, data) -> if (topic == c.topic) packets(data) }
                }
            } catch (e: Refused) {
                if (!current(c)) break
                setStatus(e.message ?: "Refused")
                retry = RETRY_MAX_MS
            } catch (e: Exception) {
                if (!current(c)) break
                Log.d(TAG, "connection ended: ${e.javaClass.simpleName}")
                setStatus("Can't reach ${c.broker.host} - ${e.message?.take(60) ?: e.javaClass.simpleName}, retrying")
            } finally {
                closeSocket(s)
            }
            try { Thread.sleep(retry) } catch (e: InterruptedException) { break }
            retry = (retry * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    private class Refused(message: String) : IOException(message)

    /**
     * Any certificate, for a broker with its own (self-signed) one, when the user turns the check off: the packets are
     * sealed with the group key either way, only the broker's login could be read by someone in the middle.
     */
    private val anyCert by lazy { anyCertFactory() }

    @android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun anyCertFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            }), null)
        }.socketFactory

    private fun open(b: Broker, checkCert: Boolean): Socket {
        val plain = Socket().apply { connect(InetSocketAddress(b.host, b.port), CONNECT_TIMEOUT_MS) }
        if (!b.tls) return plain
        val factory = if (checkCert) SSLSocketFactory.getDefault() as SSLSocketFactory else anyCert
        val s = factory.createSocket(plain, b.host, b.port, true) as SSLSocket
        s.soTimeout = CONNECT_TIMEOUT_MS
        s.startHandshake()
        // An SSLSocket checks the certificate chain but not that it's for this host: done here.
        if (checkCert && !HttpsURLConnection.getDefaultHostnameVerifier().verify(b.host, s.session)) {
            s.close()
            throw IOException("the certificate isn't for ${b.host}")
        }
        return s
    }
}

/** The few MQTT 3.1.1 packets the relay uses (QoS 0 only). Plain JVM, so it's unit-tested. */
object MqttCodec {
    const val CONNACK = 2
    const val PUBLISH = 3
    private const val MAX_PACKET = 64 * 1024
    val PINGREQ = byteArrayOf(0xC0.toByte(), 0)

    class Packet(val type: Int, val flags: Int, val body: ByteArray)

    fun connect(clientId: String, user: String?, password: String?, keepAliveS: Int): ByteArray {
        val b = ByteArrayOutputStream()
        str(b, "MQTT")
        b.write(4) // protocol level: 3.1.1
        b.write(0x02 or (if (user != null) 0x80 else 0) or (if (user != null && password != null) 0x40 else 0)) // clean session
        b.write(keepAliveS shr 8); b.write(keepAliveS and 0xFF)
        str(b, clientId)
        if (user != null) { str(b, user); password?.let { str(b, it) } }
        return packet(0x10, b.toByteArray())
    }

    fun subscribe(packetId: Int, topic: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(packetId shr 8); b.write(packetId and 0xFF)
        str(b, topic)
        b.write(0) // QoS 0
        return packet(0x82, b.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        str(b, topic)
        b.write(payload)
        return packet(0x30, b.toByteArray())
    }

    /** A PUBLISH's topic and payload (its packet id skipped when the broker sent it above QoS 0). */
    fun payload(p: Packet): Pair<String, ByteArray>? {
        if (p.body.size < 2) return null
        val n = ((p.body[0].toInt() and 0xFF) shl 8) or (p.body[1].toInt() and 0xFF)
        var at = 2 + n
        if ((p.flags shr 1) and 3 != 0) at += 2
        if (at > p.body.size) return null
        return String(p.body, 2, n, Charsets.UTF_8) to p.body.copyOfRange(at, p.body.size)
    }

    /** The next packet; throws when the stream ends or doesn't look like MQTT. */
    fun read(input: DataInputStream): Packet {
        val head = input.readUnsignedByte()
        var len = 0
        var shift = 0
        while (true) {
            val d = input.readUnsignedByte()
            len = len or ((d and 0x7F) shl shift)
            if (d and 0x80 == 0) break
            shift += 7
            if (shift > 21) throw IOException("bad length")
        }
        if (len > MAX_PACKET) throw IOException("packet too big")
        return Packet(head shr 4, head and 0x0F, ByteArray(len).also { input.readFully(it) })
    }

    private fun packet(head: Int, body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(head)
        var n = body.size
        do {
            var d = n and 0x7F
            n = n shr 7
            if (n > 0) d = d or 0x80
            b.write(d)
        } while (n > 0)
        b.write(body)
        return b.toByteArray()
    }

    private fun str(b: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        b.write(bytes.size shr 8); b.write(bytes.size and 0xFF)
        b.write(bytes)
    }
}
