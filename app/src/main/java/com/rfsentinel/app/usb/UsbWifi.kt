package com.rfsentinel.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat

/** One device heard by a USB WiFi adapter in monitor mode. */
data class MonitorSighting(
    val mac: String,
    /** Network name (access points), null for client devices and hidden networks. */
    val ssid: String?,
    val rssi: Int,
    val frequencyMhz: Int,
    /** Access point (beacon / probe response) or a client device (probe request, data). */
    val isAccessPoint: Boolean,
    /** The access point a client was talking to, when known. */
    val bssid: String? = null,
    /** Network names a client device asked for by name (probe requests). */
    val probedSsids: List<String> = emptyList(),
    /** Maker / model / device name from its WPS block. */
    val wps: ProbeIntel.Wps? = null,
    /** How it builds its probe requests: marks a device model / OS ([ProbeIntel.fingerprint]). */
    val fingerprint: String? = null,
    /** What its probe requests say about it in words ([ProbeIntel.traits]). */
    val probeTraits: String? = null,
    /** FlipDeFlock's probe skeleton hash ([ProbeIntel.skeletonHash]). */
    val probeSkeleton: Long? = null,
    /** An access point hiding its name; [ssid] is then the name revealed by a device joining or asking for it. */
    val hiddenNetwork: Boolean = false,
    /** A client that sent wildcard probe requests (no network name). */
    val wildcardProbe: Boolean = false,
    /** Its probe requests carried the Wi-Fi Alliance element Flock cameras send ([MonitorFrames.Ies.flockIe]). */
    val flockProbeIe: Boolean = false,
    /** Its latest run of wildcard probes: how steadily it probes ([com.rfsentinel.app.detect.ProbeBehaviour]). */
    val probeRun: com.rfsentinel.app.detect.ProbeBehaviour.Run? = null,
    /** An access point's beacon elements, security text and WiFi generation, read like the phone's own scan. */
    val infoElements: List<Pair<Int, ByteArray>> = emptyList(),
    val capabilities: String = "",
    val standard: String? = null,
    /** When its WiFi came up, from the beacon clock. */
    val upSince: Long? = null,
    /** Data bytes it sent while heard. */
    val dataBytes: Long = 0
)

/**
 * USB WiFi adapters on an OTG cable, put in receive-only monitor mode while
 * scanning: no Android scan throttling, longer range, and client devices (laptops,
 * phones, cameras talking to a router) that the phone's own WiFi scan never sees.
 * Android asks once per adapter for USB permission.
 */
object UsbWifi {

    private const val TAG = "UsbWifi"
    internal const val ACTION_PERMISSION = "com.rfsentinel.app.USB_WIFI_PERMISSION"

    /** Driver families. */
    enum class Driver { RTL88XXAU, RTL8812AU, RTL88X2BU, RTL8187, RT3070, RTL8814AU, MT7612U, MT7610U, MT7921U, AR9271 }

    /** A supported adapter: the chip name shown to the user and the driver that runs it. */
    data class Chip(val name: String, val driver: Driver)

    private val AU = Chip("RTL8811AU / 8821AU", Driver.RTL88XXAU)
    private val AU12 = Chip("RTL8812AU", Driver.RTL8812AU)
    private val BU = Chip("RTL8812BU / 8822BU", Driver.RTL88X2BU)
    private val L8187 = Chip("RTL8187L", Driver.RTL8187)
    private val B8187 = Chip("RTL8187B", Driver.RTL8187)
    private val RT3070 = Chip("RT3070", Driver.RT3070)
    private val RT53 = Chip("RT5370", Driver.RT3070)
    private val AU14 = Chip("RTL8814AU", Driver.RTL8814AU)
    private val MT = Chip("MT7612U", Driver.MT7612U)
    private val MT10 = Chip("MT7610U", Driver.MT7610U)
    private val ATH = Chip("AR9271", Driver.AR9271)
    private val MT21 = Chip("MT7921AU", Driver.MT7921U)

    /**
     * USB IDs with a working driver: vendor:product -> chip. 88x2bu and 8814au IDs from the
     * Linux rtw88 driver, RTL8187 IDs from rtl8187, RT3070 IDs from rt2800usb (the RT3070
     * driver checks the chip and declines other Ralink chips sharing an ID), MT76x2U IDs from
     * mt76x2u, MT7921AU IDs from mt7921u (not its MT7902, which needs other firmware), AR9271 IDs
     * from ath9k_htc (not its AR7010 ones, which need other firmware).
     */
    private val SUPPORTED: Map<Pair<Int, Int>, Chip> = mapOf(
        (0x0bda to 0x0811) to AU, // ALFA AWUS036ACS and other 88xxau dongles
        (0x0bda to 0x8812) to AU12, // ALFA AWUS036ACH and other RTL8812AU dongles (IDs from the rtl8812au driver)
        (0x0bda to 0x881a) to AU12, (0x0bda to 0x881b) to AU12, (0x0bda to 0x881c) to AU12,
        (0x0409 to 0x0408) to AU12, (0x0411 to 0x025d) to AU12, (0x04bb to 0x0952) to AU12, // NEC, Buffalo WI-U3-866D, I-O DATA
        (0x050d to 0x1106) to AU12, (0x050d to 0x1109) to AU12, (0x0586 to 0x3426) to AU12, (0x0789 to 0x016e) to AU12, // Belkin, ZyXEL, Logitec
        (0x07b8 to 0x8812) to AU12, (0x0846 to 0x9051) to AU12, (0x0b05 to 0x17d2) to AU12, (0x0df6 to 0x0074) to AU12, // Abocom, Netgear A6200v2, ASUS
        (0x0e66 to 0x0022) to AU12, (0x1058 to 0x0632) to AU12, (0x13b1 to 0x003f) to AU12, (0x148f to 0x9097) to AU12, // Hawking, WD, Linksys WUSB6300
        (0x1740 to 0x0100) to AU12, (0x2001 to 0x330e) to AU12, (0x2001 to 0x3313) to AU12, (0x2001 to 0x3315) to AU12, // EnGenius, D-Link
        (0x2001 to 0x3316) to AU12, (0x2019 to 0xab30) to AU12, (0x20f4 to 0x805b) to AU12, // Planex, TRENDnet
        (0x2357 to 0x0101) to AU12, (0x2357 to 0x0103) to AU12, (0x2357 to 0x010d) to AU12, // TP-Link Archer T4U / T4UH
        (0x2357 to 0x010e) to AU12, (0x2357 to 0x010f) to AU12, (0x2357 to 0x0122) to AU12,
        (0x2604 to 0x0012) to AU12, (0x7392 to 0xa822) to AU12, // Tenda U12, Edimax
        (0x0bda to 0xb812) to Chip("RTL8812BU", Driver.RTL88X2BU), // generic AC1200 dongles (e.g. Wise Tiger)
        (0x0bda to 0xb82c) to Chip("RTL8822BU", Driver.RTL88X2BU),
        (0x0bda to 0x2102) to BU, (0x0bda to 0xb81a) to BU,
        (0x7392 to 0xb822) to BU, (0x7392 to 0xc822) to BU, (0x7392 to 0xd822) to BU, // Edimax EW-7822U*
        (0x7392 to 0xe822) to BU, (0x7392 to 0xf822) to BU,
        (0x0b05 to 0x1841) to BU, (0x0b05 to 0x184c) to BU, (0x0b05 to 0x19aa) to BU, // ASUS USB-AC53 / AC55 / AC58
        (0x0b05 to 0x1870) to BU, (0x0b05 to 0x1874) to BU,
        (0x2001 to 0x331e) to BU, (0x2001 to 0x331c) to BU, (0x2001 to 0x331f) to BU, (0x2001 to 0x3322) to BU, // D-Link
        (0x13b1 to 0x0043) to BU, (0x13b1 to 0x0045) to BU, // Linksys WUSB6400M / WUSB3600 v2
        (0x2357 to 0x012d) to BU, (0x2357 to 0x0138) to BU, (0x2357 to 0x0115) to BU, // TP-Link Archer T3U / T4U v3
        (0x2357 to 0x012e) to BU, (0x2357 to 0x0116) to BU, (0x2357 to 0x0117) to BU,
        (0x0846 to 0x9055) to BU, // Netgear A6150
        (0x0e66 to 0x0025) to BU, (0x04ca to 0x8602) to BU,
        (0x20f4 to 0x808a) to BU, (0x20f4 to 0x805a) to BU, // TRENDnet
        (0x056e to 0x4011) to BU, (0x2c4e to 0x0107) to BU, (0x2c4e to 0x010a) to BU,
        (0x0411 to 0x03d1) to BU, (0x0411 to 0x03d0) to BU, // Buffalo
        (0x0bda to 0x8187) to L8187, // ALFA AWUS036H and other RTL8187L dongles
        (0x0b05 to 0x171d) to L8187, (0x0769 to 0x11f2) to L8187, (0x0789 to 0x010c) to L8187,
        (0x0846 to 0x6100) to L8187, (0x0846 to 0x6a00) to L8187, (0x03f0 to 0xca02) to L8187, // Netgear WG111v2, HP
        (0x0df6 to 0x000d) to L8187, (0x114b to 0x0150) to L8187, (0x1371 to 0x9401) to L8187,
        (0x13d1 to 0xabe6) to L8187, (0x18e8 to 0x6232) to L8187, (0x1b75 to 0x8187) to L8187,
        (0x0bda to 0x8189) to B8187, (0x0bda to 0x8197) to B8187, (0x0bda to 0x8198) to B8187,
        (0x050d to 0x705e) to B8187, (0x0846 to 0x4260) to B8187, (0x0df6 to 0x0028) to B8187, // Belkin, Netgear WG111v3
        (0x0df6 to 0x0029) to B8187, (0x1737 to 0x0073) to B8187,
        (0x148f to 0x3070) to RT3070, // ALFA AWUS036NH / AWUS036NEH and other RT3070 dongles
        (0x148f to 0x2070) to RT3070, (0x7392 to 0x7711) to RT3070, (0x1737 to 0x0077) to RT3070, (0x2019 to 0xab25) to RT3070,
        (0x148f to 0x5370) to RT53, (0x148f to 0x5372) to Chip("RT5372", Driver.RT3070), // generic Ralink dongles
        (0x043e to 0x7a12) to RT53, (0x0b05 to 0x17e8) to RT53, (0x13d3 to 0x3329) to RT53, (0x13d3 to 0x3365) to RT53,
        (0x2001 to 0x3c15) to RT53, (0x2001 to 0x3c19) to RT53, (0x2001 to 0x3c1c) to RT53, (0x2001 to 0x3c1d) to RT53,
        (0x2001 to 0x3c1e) to RT53, (0x2001 to 0x3c20) to RT53, (0x2001 to 0x3c22) to RT53, (0x2001 to 0x3c23) to RT53,
        (0x043e to 0x7a22) to RT53, (0x043e to 0x7a42) to RT53, (0x04da to 0x1801) to RT53, (0x04da to 0x1800) to RT53,
        (0x04da to 0x23f6) to RT53, (0x0471 to 0x2104) to RT53, (0x0471 to 0x2180) to RT53, (0x0471 to 0x2181) to RT53,
        (0x0471 to 0x2182) to RT53,
        (0x148f to 0x7610) to MT10, (0x13b1 to 0x003e) to MT10, (0x0e8d to 0x7610) to MT10, (0x7392 to 0xa711) to MT10,
        (0x148f to 0x761a) to MT10, (0x148f to 0x760a) to MT10, (0x0b05 to 0x17d1) to MT10, (0x0b05 to 0x17db) to MT10,
        (0x0df6 to 0x0075) to MT10, (0x2019 to 0xab31) to MT10, (0x2001 to 0x3d02) to MT10, (0x0586 to 0x3425) to MT10,
        (0x07b8 to 0x7610) to MT10, (0x04bb to 0x0951) to MT10, (0x057c to 0x8502) to MT10, (0x293c to 0x5702) to MT10,
        (0x20f4 to 0x806b) to MT10, (0x7392 to 0xc711) to MT10, (0x0df6 to 0x0079) to MT10, (0x2357 to 0x0123) to MT10,
        (0x2357 to 0x010b) to MT10, (0x2357 to 0x0105) to MT10,
        (0x0bda to 0x8813) to AU14, // ALFA AWUS1900
        (0x056e to 0x400b) to AU14, (0x056e to 0x400d) to AU14, (0x0846 to 0x9054) to AU14, // ELECOM, Netgear A7000
        (0x0b05 to 0x1817) to AU14, (0x0b05 to 0x1852) to AU14, (0x0b05 to 0x1853) to AU14, // ASUS USB-AC68
        (0x0e66 to 0x0026) to AU14, (0x2001 to 0x331a) to AU14, (0x20f4 to 0x809a) to AU14, (0x20f4 to 0x809b) to AU14,
        (0x2357 to 0x0106) to AU14, (0x7392 to 0xa834) to AU14, (0x7392 to 0xa833) to AU14, // TP-Link Archer T9UH, Edimax
        (0x0e8d to 0x7612) to MT, // ALFA AWUS036ACM, Aukey USB-AC1200
        (0x0b05 to 0x1833) to MT, (0x0b05 to 0x17eb) to MT, (0x0b05 to 0x180b) to MT, // ASUS USB-AC54 / AC55 / N53 B1
        (0x057c to 0x8503) to MT, (0x7392 to 0xb711) to MT, (0x056e to 0x400a) to MT, (0x0e8d to 0x7632) to MT,
        (0x0471 to 0x2126) to MT, (0x0471 to 0x7600) to MT, (0x2c4e to 0x0103) to MT,
        (0x0846 to 0x9014) to MT, (0x0846 to 0x9053) to MT, // Netgear WNDA3100v3, A6210
        (0x045e to 0x02e6) to MT, (0x045e to 0x02fe) to MT, (0x2357 to 0x0137) to MT, // Xbox One wireless adapter, TP-Link
        (0x0e8d to 0x7961) to MT21, // ALFA AWUS036AXML and other MT7921AU (WiFi 6E) dongles
        (0x3574 to 0x6211) to MT21, (0x0846 to 0x9060) to MT21, (0x0846 to 0x9065) to MT21, // Comfast CF-952AX, Netgear A8000 / A7500
        (0x35bc to 0x0107) to MT21, // TP-Link TXE50UH
        (0x0cf3 to 0x9271) to ATH, // ALFA AWUS036NHA, TP-Link TL-WN722N v1
        (0x0cf3 to 0x1006) to ATH, (0x0846 to 0x9030) to ATH, (0x07b8 to 0x9271) to ATH, (0x07d1 to 0x3a10) to ATH,
        (0x13d3 to 0x3327) to ATH, (0x13d3 to 0x3328) to ATH, (0x13d3 to 0x3346) to ATH, (0x13d3 to 0x3348) to ATH,
        (0x13d3 to 0x3349) to ATH, (0x13d3 to 0x3350) to ATH, (0x04ca to 0x4605) to ATH, (0x040d to 0x3801) to ATH,
        (0x0cf3 to 0xb003) to ATH, (0x0cf3 to 0xb002) to ATH, (0x057c to 0x8403) to ATH, (0x0471 to 0x209e) to ATH,
        (0x1eda to 0x2315) to ATH
    )
    /** Known monitor-mode chips without a driver here yet (named in the status so users know why). */
    private val KNOWN = mapOf(
        (0x0bda to 0xc811) to "RTL8811CU / 8821CU", (0x0bda to 0xc820) to "RTL8821CU", (0x0bda to 0xc812) to "RTL8812CU",
        (0x0cf3 to 0x7010) to "AR7010", (0x0cf3 to 0x7015) to "AR7010",
        (0x148f to 0x3071) to "RT3071", (0x148f to 0x3072) to "RT3072"
    )

    private var receiver: BroadcastReceiver? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** Debug builds: driver commands from adb, e.g.
     *  `adb shell am broadcast -a com.rfsentinel.app.USB_WIFI_CMD -p com.rfsentinel.app.debug --es cmd "park 11"` */
    private var cmdReceiver: BroadcastReceiver? = null
    private const val ACTION_CMD = "com.rfsentinel.app.USB_WIFI_CMD"
    private var onSightings: ((List<MonitorSighting>) -> Unit)? = null

    /**
     * One running adapter. Several run side by side, each with its own driver instance and thread, and share the
     * channels between them ([share]): two adapters visit every channel twice as often, four four times.
     */
    private class Session(val device: String, val chip: Chip) {
        @Volatile var status = ""
        /** The channels its driver can tune (the number; MHz on 6 GHz), known once the driver asked for its hops. */
        @Volatile var channels: Set<Int> = emptySet()
        @Volatile var stopped = false
        @Volatile var abortDriver: (() -> Unit)? = null
        /** Every channel its driver planned to visit since the last per-channel log line ([takePlanned]). */
        val planned: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        fun stop() { stopped = true; abortDriver?.invoke() }
    }
    private val sessions = java.util.concurrent.CopyOnWriteArrayList<Session>()
    /** The session of the driver thread asking. */
    private val current = ThreadLocal<Session>()
    /** Each device's last thread, so a quick stop and start waits for the old session to let go of the adapter. */
    private val lastThread = HashMap<String, Thread>()
    /** What to show while no adapter runs: waiting for access, firmware, an unsupported chip, the last error. */
    @Volatile private var idle = ""

    /** The first running adapter's chip name ("MT7612U"), for the antenna check; null when none is running. */
    val runningChip: String? get() = sessions.firstOrNull()?.chip?.name

    /** How many adapters are running. */
    val running: Int get() = sessions.size

    /** Latest adapter status for the main screen and Settings ("" when none is plugged in); one part per running adapter. */
    val status: String get() = sessions.map { it.status }.filter { it.isNotBlank() }.joinToString(" + ").ifEmpty { idle }

    /**
     * The part of [all] (channel numbers; MHz on 6 GHz) that is this adapter's to visit. Alone: all of it. With others:
     * each channel belongs to one of the adapters that can tune it, in turn, so a 2.4 GHz-only adapter next to a
     * dual-band one leaves all of 5 GHz to the other and shares 2.4 GHz with it. Asked again every hop round, so the
     * split follows adapters being plugged in and out; never empty.
     */
    fun share(all: List<Int>): List<Int> {
        val me = current.get() ?: return all
        me.channels = all.toSet()
        val live = sessions.filter { !it.stopped && it.channels.isNotEmpty() }
        val at = live.indexOf(me)
        return if (live.size < 2 || at < 0) all else split(all, at, live.map { it.channels })
    }

    /** [all] for adapter [me] of the adapters whose tunable channels are [caps]: see [share]. */
    internal fun split(all: List<Int>, me: Int, caps: List<Set<Int>>): List<Int> {
        val order = caps.flatten().distinct().sorted()
        val mine = all.filter { c -> caps.indices.filter { c in caps[it] }.let { can -> can[order.indexOf(c) % can.size] == me } }
        return mine.ifEmpty { all }
    }

    /** Settings > WiFi bands, read as a session starts. */
    @Volatile var band = com.rfsentinel.app.util.Prefs.WifiBand.ALL
    /** True when a channel of the 5 / 6 GHz bands ([high]) or of 2.4 GHz is in the chosen band. */
    fun inBand(high: Boolean) = when (band) {
        com.rfsentinel.app.util.Prefs.WifiBand.LOW -> !high
        com.rfsentinel.app.util.Prefs.WifiBand.HIGH -> high
        else -> true
    }
    /** [all]'s channels in the chosen band (2.4 GHz is 1-14); all of them when none are (a 2.4 GHz-only adapter on 5 GHz). */
    fun hops(all: IntArray): IntArray = (share(all.toList()).filter { inBand(it > 14) }.takeIf { it.isNotEmpty() }
        ?: all.filter { inBand(it > 14) }.takeIf { it.isNotEmpty() } ?: all.toList()).let { p -> mostlyLow(p) { it <= 14 } }.toIntArray()
        .also { p -> current.get()?.planned?.addAll(p.asList()) }

    /** Settings › Log everything the USB WiFi adapter hears: each device as it comes and goes ([MonitorFrames]). */
    @Volatile var logAll = false
    /** The asking driver thread's chip name, for its log lines. */
    internal val label: String get() = current.get()?.chip?.name ?: "USB WiFi"
    /** The channels the asking driver planned since the last call ([hops]). */
    internal fun takePlanned(): Set<Int> = current.get()?.planned?.let { p -> p.toSet().also { p.removeAll(it) } }.orEmpty()

    /**
     * Mostly 2.4 GHz: [plan] with its 2.4 GHz hops ([isLow]) woven between the 5 / 6 GHz ones, [LOW_PER_HIGH] to one,
     * so 2.4 GHz gets about three quarters of the time and every 5 / 6 GHz channel is still visited once a round.
     * Every 2.4 GHz channel is visited at least once. Any other band setting: [plan] as it is.
     */
    fun <T> mostlyLow(plan: List<T>, isLow: (T) -> Boolean): List<T> {
        if (band != com.rfsentinel.app.util.Prefs.WifiBand.MOSTLY_LOW) return plan
        val (low, high) = plan.partition(isLow)
        if (low.isEmpty() || high.isEmpty()) return plan
        val per = maxOf(LOW_PER_HIGH, (low.size + high.size - 1) / high.size)
        var i = 0
        return high.flatMap { h -> List(per) { low[i++ % low.size] } + h }
    }
    private const val LOW_PER_HIGH = 3

    /**
     * The 2.4 GHz channels in hop order. Neighbouring channels overlap (a beacon on 6 is heard from 5 and 7 too), so
     * visiting them far apart in time halves the longest wait before an access point is heard again, at no cost.
     */
    val HOP_24 = intArrayOf(1, 6, 11, 3, 9, 2, 7, 13, 4, 10, 5, 8, 12)

    /** The adapter waiting for its chip firmware (FOSS build, not downloaded yet), for [FirmwarePrompt]; null when none. */
    @Volatile var firmwareNeeded: Chip? = null
        private set

    /** The USB WiFi adapter's own log ([HwLogs.usbWifi]); the USB drivers write to it. */
    fun log(line: String) = com.rfsentinel.app.util.HwLogs.usbWifi.log(line)

    /** An error for a log: its type, message and where it happened. */
    fun describe(e: Throwable): String = com.rfsentinel.app.util.HwLog.describe(e)

    /** The USB devices plugged in now, for a hardware log's export. No nearby devices' addresses or network names. */
    fun usbDevices(context: Context): String {
        val mgr = context.applicationContext.getSystemService(UsbManager::class.java)
        val devices = runCatching { mgr?.deviceList?.values?.toList() }.getOrNull().orEmpty()
        val sb = StringBuilder("USB devices plugged in now:\n")
        if (devices.isEmpty()) sb.append("  none\n")
        for (d in devices) sb.append(describe(mgr, d))
        return sb.toString()
    }

    private fun describe(mgr: UsbManager?, d: UsbDevice): String {
        val sb = StringBuilder()
        val what = chipOf(d)?.let { "supported (${it.name})" }
            ?: knownOnly(d)?.let { "$it, not supported yet" }
            ?: if (com.rfsentinel.app.sdr.RtlSdr.isRtlSdr(d.vendorId, d.productId)) "RTL-SDR (radio activity detector)"
            else if (com.rfsentinel.app.sdr.HackRf.isHackRf(d.vendorId, d.productId)) "HackRF (radio activity detector, receive only)"
            else "not a supported WiFi adapter"
        val allowed = runCatching { mgr?.hasPermission(d) }.getOrNull() == true
        // Names are only readable once USB access is allowed.
        val name = listOfNotNull(runCatching { d.manufacturerName }.getOrNull(), runCatching { d.productName }.getOrNull())
            .joinToString(" ").ifBlank { "(name shown once USB access is allowed)" }
        sb.append("  %04x:%04x %s - %s - USB access %s\n".format(d.vendorId, d.productId, name, what,
            if (allowed) "allowed" else "not allowed yet"))
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            val eps = (0 until intf.endpointCount).joinToString(", ") { e ->
                val ep = intf.getEndpoint(e)
                val type = when (ep.type) { 0 -> "control"; 1 -> "iso"; 2 -> "bulk"; else -> "interrupt" }
                val dir = if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "in" else "out"
                "0x%02x %s %s %d".format(ep.address, type, dir, ep.maxPacketSize)
            }
            sb.append("    interface %d class %02x/%02x: %s\n".format(i, intf.interfaceClass, intf.interfaceSubclass, eps.ifBlank { "no endpoints" }))
        }
        return sb.toString()
    }


    fun chipOf(d: UsbDevice): Chip? = SUPPORTED[d.vendorId to d.productId]

    /** The chip behind a USB ID, for tests and the status line. */
    fun chipOf(vendorId: Int, productId: Int): Chip? = SUPPORTED[vendorId to productId]
    private fun knownOnly(d: UsbDevice): String? = KNOWN[d.vendorId to d.productId]

    @Synchronized
    fun start(context: Context, sightings: (List<MonitorSighting>) -> Unit) {
        val app = context.applicationContext
        onSightings = sightings
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        // Just plugged in: Android's "Open RF Sentinel?" prompt comes first; ask ourselves only if it didn't help.
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            connect(app, ask = false)
                            main.postDelayed({ if (receiver != null) connect(app) }, UsbAttachActivity.ATTACH_GRACE_MS)
                        }
                        ACTION_PERMISSION, UsbAttachActivity.ACTION_READY -> connect(app)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val d = deviceOf(intent)
                            if (d != null && sessions.any { it.device == d.deviceName }) stopAdapter(d.deviceName, "USB WiFi adapter unplugged")
                            else if (firmwareNeeded != null && d != null && chipOf(d) == firmwareNeeded) {
                                firmwareNeeded = null; idle = ""; FirmwarePrompt.cancel(app)
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_PERMISSION)
                addAction(UsbAttachActivity.ACTION_READY)
            }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        if (com.rfsentinel.app.BuildConfig.DEBUG && cmdReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val cmd = intent.getStringExtra("cmd") ?: return
                    log("adb command: $cmd")
                    Rtl8822buMonitor.commands.add(cmd)
                }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(ACTION_CMD), ContextCompat.RECEIVER_EXPORTED)
            cmdReceiver = r
        }
        connect(app)
    }

    /** Tries the plugged-in adapters again (firmware was just downloaded). */
    fun retry(context: Context) = connect(context.applicationContext)

    @Synchronized
    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        cmdReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        cmdReceiver = null
        stopAdapter(null, "")
        onSightings = null
    }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /**
     * Starts every supported adapter that is plugged in and not running yet: all of them on Auto, only the one picked
     * in Settings otherwise. [ask]: false right after a plug-in, while Android's own prompt may still give access.
     */
    @Synchronized
    private fun connect(context: Context, ask: Boolean = true) {
        val mgr = context.getSystemService(UsbManager::class.java) ?: return
        val all = runCatching { mgr.deviceList.values.toList() }.getOrDefault(emptyList())
        val saved = com.rfsentinel.app.util.Prefs.usbPick(context, UsbPick.Kind.WIFI.key)
        val wanted = UsbPick.candidates(UsbPick.Kind.WIFI, all).filter { saved == null || UsbPick.id(it) == saved }
        if (wanted.isEmpty()) {
            if (sessions.isNotEmpty()) return
            UsbPick.waitingFor(context, UsbPick.Kind.WIFI, all)?.let { idle = "USB WiFi: waiting for $it, picked in Settings"; return }
            val known = all.firstOrNull { knownOnly(it) != null }
            val newStatus = known?.let { "USB WiFi adapter (${knownOnly(it)}) found - this chip isn't supported yet" }.orEmpty()
            if (known != null && newStatus != idle) log("%04x:%04x (%s) plugged in: not supported yet".format(known.vendorId, known.productId, knownOnly(known)))
            idle = newStatus
            return
        }
        var asked = false
        for (d in wanted) {
            if (sessions.any { it.device == d.deviceName }) continue
            if (!mgr.hasPermission(d)) {
                idle = "USB WiFi adapter found - allow USB access on the prompt"
                // One prompt at a time: Android shows the next adapter's after this one is answered.
                if (ask && !asked) {
                    asked = true
                    log("%04x:%04x (%s) plugged in - asking for USB access".format(d.vendorId, d.productId, chipOf(d)?.name))
                    val pi = PendingIntent.getBroadcast(context, 1, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                        PendingIntent.FLAG_MUTABLE)
                    runCatching { mgr.requestPermission(d, pi) }
                }
                continue
            }
            val chip = chipOf(d)!!
            val missing = Firmware.missing(context, chip.driver)
            if (missing.isNotEmpty()) {
                if (firmwareNeeded != chip) {
                    log("${chip.name} needs firmware that isn't downloaded yet: ${missing.joinToString { it.name }}")
                    FirmwarePrompt.notify(context, chip.name)
                }
                firmwareNeeded = chip
                idle = "USB WiFi (${chip.name}) needs its chip firmware: tap the notification, or this line in Settings, to download it"
                continue
            }
            if (firmwareNeeded == chip) firmwareNeeded = null
            launch(context, d, chip)
        }
    }

    private fun launch(context: Context, d: UsbDevice, chip: Chip) {
        val s = Session(d.deviceName, chip)
        s.status = "USB WiFi (${chip.name}) · starting…"
        sessions += s
        log("Starting ${chip.name} (${"%04x:%04x".format(d.vendorId, d.productId)}) - RF Sentinel ${com.rfsentinel.app.BuildConfig.VERSION_NAME}, " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}" +
            if (sessions.size > 1) " - adapter ${sessions.size} of ${sessions.size}, sharing the channels" else "")
        val previous = lastThread[d.deviceName]
        lastThread[d.deviceName] = Thread({
            current.set(s)
            var last = ""
            runCatching {
                // A quick stop and start: the last session switches the adapter off as it ends; let it finish first.
                previous?.join(3_000)
                if (previous?.isAlive == true) { last = "USB WiFi (${chip.name}): the last session is still stopping - unplug and replug the adapter"; return@runCatching }
                band = com.rfsentinel.app.util.Prefs.wifiBand(context)
                logAll = com.rfsentinel.app.util.Prefs.usbWifiLogAll(context)
                UsbCtl.reset()
                val onStatus: (String) -> Unit = { text -> s.status = text; last = text }
                val onFrames: (List<MonitorSighting>) -> Unit = { list -> onSightings?.invoke(list) }
                /** Runs one driver instance, stoppable through the session. */
                fun go(abort: () -> Unit, run: () -> Unit) { s.abortDriver = abort; if (!s.stopped) run() }
                when (chip.driver) {
                    Driver.RTL88XXAU -> {
                        Rtl8821auMonitor.scan5g = com.rfsentinel.app.util.Prefs.rtl8821au5g(context)
                        Rtl8821auMonitor.lightEnabled = com.rfsentinel.app.util.Prefs.usbAdapterLight(context)
                        Rtl8821auMonitor().let { m -> go(m::abort) { m.run(context, d, onStatus, onFrames) } }
                    }
                    Driver.RTL8812AU -> Rtl8812auMonitor().let { m -> go(m::abort) { m.run(context, d, onStatus, onFrames) } }
                    Driver.RTL88X2BU -> Rtl8822buMonitor().let { m -> go(m::abort) { m.run(context, d, chip.name, onStatus, onFrames) } }
                    Driver.RTL8187 -> Rtl8187Monitor().let { m -> go(m::abort) { m.run(context, d, chip.name, onStatus, onFrames) } }
                    Driver.RT3070 -> Rt3070Monitor().let { m -> go(m::abort) { m.run(context, d, chip.name, onStatus, onFrames) } }
                    Driver.RTL8814AU -> Rtl8814auMonitor().let { m -> go(m::abort) { m.run(context, d, chip.name, onStatus, onFrames) } }
                    Driver.MT7612U -> Mt7612uMonitor().let { m -> go(m::abort) { m.run(context, d, chip.name, onStatus, onFrames) } }
                    Driver.MT7610U -> Mt7612uMonitor().let { m -> go(m::abort) { m.run(context, d, chip.name, onStatus, onFrames, x0 = true) } }
                    Driver.MT7921U -> Mt7921uMonitor().let { m -> go(m::abort) { m.run(context, d, chip.name, onStatus, onFrames) } }
                    Driver.AR9271 -> Ar9271Monitor().let { m -> go(m::abort) { m.run(context, d, "${chip.name} (experimental)", onStatus, onFrames) } }
                }
            }.onFailure {
                log("Driver stopped: ${describe(it)}"); last = "USB WiFi adapter error: ${it.message}"
                // The firmware file vanished or failed to load after the check above: offer the download again.
                if (Firmware.missing(context, chip.driver).isNotEmpty()) { firmwareNeeded = chip; FirmwarePrompt.notify(context, chip.name) }
            }
            synchronized(this) {
                // Never leave the last "live" line behind when the adapter stopped answering on its own.
                if (UsbCtl.gone && !s.stopped) {
                    log("${chip.name}: the adapter stopped answering (${UsbCtl.run} register accesses failed in a row)")
                    last = "USB WiFi (${chip.name}): the adapter stopped answering - unplug it and plug it back in"
                }
                sessions -= s
                // It ended on its own (an error, a failed start): its last word stays up while nothing else runs.
                if (!s.stopped && sessions.isEmpty()) idle = last
                if (!s.stopped && sessions.isNotEmpty()) log("${chip.name} stopped; ${sessions.size} adapter${if (sessions.size == 1) "" else "s"} still running, taking over its channels")
            }
        }, "usb-wifi-${sessions.size}").also { it.start() }
    }

    /** Stops the adapter [device], or all of them (null); [newStatus] shows once none is left. */
    @Synchronized
    private fun stopAdapter(device: String?, newStatus: String) {
        for (s in sessions.filter { device == null || it.device == device }) { s.stop(); sessions -= s }
        if (sessions.isEmpty()) {
            idle = newStatus
            com.rfsentinel.app.esp.HeardBy.usb.clear() // unplugged or stopped: nothing is "heard by USB" any more
        }
    }
}
