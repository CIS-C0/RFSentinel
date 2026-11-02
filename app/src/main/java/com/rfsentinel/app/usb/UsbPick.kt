package com.rfsentinel.app.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.widget.Toast
import com.rfsentinel.app.sdr.SdrRadio
import com.rfsentinel.app.util.Prefs

/**
 * Settings › External hardware: which plugged-in adapter each driver uses when several of a kind are plugged in.
 * Saved by USB ID ("0bda:8771"). Auto takes the first one found - for Bluetooth, a Bluetooth-only dongle before the
 * Bluetooth side of a combo WiFi adapter, which only has the antenna inside its case.
 */
object UsbPick {

    enum class Kind(val key: String, val what: String) {
        WIFI("wifi", "WiFi adapters"), BT("bt", "Bluetooth adapters"), SDR("sdr", "SDRs")
    }

    fun id(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

    /** What [kind]'s driver can use, Auto's choice first. */
    fun candidates(kind: Kind, all: Collection<UsbDevice>): List<UsbDevice> = when (kind) {
        Kind.WIFI -> all.filter { UsbWifi.chipOf(it) != null }
        Kind.BT -> all.filter { UsbBluetooth.btInterface(it) != null }.sortedBy { UsbWifi.chipOf(it) != null }
        Kind.SDR -> all.filter { SdrRadio.isSdr(it) }
    }

    /** The saved device if it's plugged in (else null); with nothing saved (Auto), the first of [list]. */
    internal fun <T> choose(list: List<T>, saved: String?, id: (T) -> String): T? =
        if (saved == null) list.firstOrNull() else list.firstOrNull { id(it) == saved }

    /** The device [kind]'s driver should use, without telling anyone. */
    fun chosen(context: Context, kind: Kind, all: Collection<UsbDevice>): UsbDevice? =
        choose(candidates(kind, all), Prefs.usbPick(context, kind.key), ::id)

    /** [chosen], with a short popup the first time several of [kind] are plugged in together. */
    fun pick(context: Context, kind: Kind, all: Collection<UsbDevice>): UsbDevice? {
        val list = candidates(kind, all)
        val d = choose(list, Prefs.usbPick(context, kind.key), ::id)
        if (d != null && list.size > 1) tellOnce(context, kind, list, d)
        return d
    }

    /** The saved device's name when it's unplugged but others of [kind] are in: the driver waits for it. */
    fun waitingFor(context: Context, kind: Kind, all: Collection<UsbDevice>): String? {
        val saved = Prefs.usbPick(context, kind.key) ?: return null
        val list = candidates(kind, all)
        return if (list.isNotEmpty() && list.none { id(it) == saved }) Prefs.usbPickName(context, kind.key) ?: saved else null
    }

    /** "ALFA AWUS036AXML · MT7921AU"; the maker's name is only readable once USB access is allowed. */
    fun label(d: UsbDevice): String {
        val name = runCatching { listOfNotNull(d.manufacturerName, d.productName).joinToString(" ") }.getOrDefault("")
        val chip = UsbWifi.chipOf(d)?.name ?: if (SdrRadio.isSdr(d)) SdrRadio.labelOf(d) else null
        return listOfNotNull(name.ifBlank { null }, chip).joinToString(" · ").ifBlank { "USB device ${id(d)}" }
    }

    private val told = HashMap<Kind, String>()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    @Synchronized
    private fun tellOnce(context: Context, kind: Kind, list: List<UsbDevice>, d: UsbDevice) {
        val key = list.joinToString { it.deviceName } + ">" + d.deviceName
        if (told[kind] == key) return
        told[kind] = key
        val msg = "${list.size} ${kind.what} plugged in · using ${label(d)}. Pick one in Settings › External hardware"
        val app = context.applicationContext
        main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }
}
