package com.rfsentinel.app.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rfsentinel.app.detect.Advert

/**
 * Drone Remote ID over Wi-Fi Aware (NAN), the WiFi transport many EU drones and newer DJI models
 * use, which a phone can't hear by ordinary WiFi scanning (after OpenDroneID's receiver-android,
 * Apache-2.0: written anew here). A passive subscription to the "org.opendroneid.remoteid"
 * service: listen only, nothing is published or sent to the drone.
 *
 * Each discovery's service info is [message counter][message pack], the same bytes that follow
 * the OUI and type in a Remote ID WiFi beacon, so each one is handed on as a WiFi advert carrying
 * that vendor element and goes through the usual WiFi Remote ID decoding. Wi-Fi Aware gives no
 * address or signal strength: each peer gets a stable made-up local address, and a nominal signal.
 */
class NanRemoteId(private val context: Context, private val onAdvert: (Advert) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var session: WifiAwareSession? = null
    private var subscription: SubscribeDiscoverySession? = null
    private var running = false
    private var receiverOn = false
    /** Made-up addresses per peer (peer handles only mean something within a subscription). */
    private val peers = HashMap<Int, String>()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (running) { close(); attach() } }
    }

    fun start() {
        if (running || !supported(context)) return
        running = true
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(context, stateReceiver,
                IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
        }
        attach()
    }

    fun stop() {
        running = false
        if (receiverOn) { runCatching { context.unregisterReceiver(stateReceiver) }; receiverOn = false }
        close()
    }

    private fun close() {
        runCatching { subscription?.close() }; subscription = null
        runCatching { session?.close() }; session = null
        peers.clear()
    }

    @SuppressLint("MissingPermission") // location (and nearby-WiFi on 13+) checked by the caller and on attach
    private fun attach() {
        val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager ?: return
        if (!mgr.isAvailable) return // WiFi off: the state broadcast retries
        try {
            mgr.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) {
                    if (!running) { s.close(); return }
                    session = s
                    subscribe(s)
                }
                override fun onAttachFailed() { Log.w(TAG, "Wi-Fi Aware attach failed") }
            }, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi Aware not allowed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(s: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        try {
            s.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(sub: SubscribeDiscoverySession) {
                    subscription = sub
                    Log.i(TAG, "Listening for drone Remote ID over Wi-Fi Aware")
                }
                override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray?, filter: List<ByteArray>?) {
                    info?.let { onInfo(peer, it) }
                }
                override fun onSessionTerminated() { subscription = null }
            }, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi Aware subscribe not allowed: ${e.message}")
        }
    }

    private fun onInfo(peer: PeerHandle, info: ByteArray) {
        if (info.size < 1 + com.rfsentinel.app.detect.RemoteId.MESSAGE_SIZE) return
        val mac = peers.getOrPut(peer.hashCode()) { madeUpAddress(peer.hashCode()) }
        onAdvert(advert(mac, info, System.currentTimeMillis()))
    }

    companion object {
        private const val TAG = "NanRemoteId"
        /** The ASTM F3411 Wi-Fi NAN service name. */
        const val SERVICE = "org.opendroneid.remoteid"
        /** Wi-Fi Aware discovery is on channel 6. */
        private const val NAN_MHZ = 2437
        /** No signal strength comes with a discovery. */
        private const val NOMINAL_RSSI = -75

        fun supported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

        /** A WiFi advert carrying [info] as a Remote ID vendor element (OUI FA:0B:BC, type 0x0D). */
        internal fun advert(mac: String, info: ByteArray, now: Long): Advert {
            val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D) + info
            return Advert(mac = mac, source = Advert.Source.WIFI, rssi = NOMINAL_RSSI, name = null,
                wifi = Advert.WifiInfo(NAN_MHZ, "", com.rfsentinel.app.detect.DeviceIntel.WIFI_AWARE, listOf(221 to ie)), timestamp = now)
        }

        /** A locally administered address for a peer, stable while its handle is. */
        internal fun madeUpAddress(id: Int): String =
            "0E:4E:" + (0 until 4).joinToString(":") { "%02X".format((id ushr (24 - 8 * it)) and 0xFF) }
    }
}
