package com.rfsentinel.app.detect

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

/**
 * A shareable "please add this device" report for devices RF Sentinel doesn't
 * recognise yet. It keeps only what a signature is built from (vendor prefix,
 * name pattern, service UUIDs, manufacturer IDs, WiFi security / vendor IEs)
 * and leaves out everything that could identify the device or you: no full
 * address, no serial numbers (digit runs in names become '#'), no payload
 * bytes beyond a short type header, no location and no times.
 */
object SignatureReport {

    const val ISSUE_URL = "https://github.com/CIS-C0/RFSentinel/issues/new"

    /** Payload bytes kept per manufacturer / service-data entry (enough for a type byte, not a serial). */
    private const val HEADER_BYTES = 2

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Serial-looking digit runs (4+ digits) become '#' per digit; model numbers (e.g. "Forerunner 265") stay. */
    fun namePattern(name: String): String =
        Regex("\\d{4,}").replace(name.trim()) { "#".repeat(it.value.length) }

    fun build(
        advert: Advert,
        vendor: String?,
        deviceType: String?,
        randomized: Boolean,
        userNote: String?,
        appVersion: String
    ): String {
        val o = JsonObject()
        o.addProperty("format", "rfsentinel-signature-report/1")
        o.addProperty("app", appVersion)
        o.addProperty("radio", advert.source.name)
        // Only a real vendor prefix; a randomized address's prefix means nothing.
        if (!randomized) o.addProperty("oui", advert.mac.take(8).uppercase(Locale.US))
        vendor?.takeIf { it.isNotBlank() }?.let { o.addProperty("vendor", it) }
        deviceType?.takeIf { it.isNotBlank() }?.let { o.addProperty("guessedType", it) }
        o.addProperty("addressType", advert.addressType.name)
        advert.name?.takeIf { it.isNotBlank() }?.let { o.addProperty("namePattern", namePattern(it)) }
        advert.connectable?.let { o.addProperty("connectable", it) }
        advert.txPower?.let { o.addProperty("txPower", it) }
        advert.phy?.let { o.addProperty("phy", it) }

        if (advert.serviceUuids.isNotEmpty()) {
            o.add("serviceUuids", JsonArray().apply { advert.serviceUuids.map { uuidText(it) }.distinct().sorted().forEach { add(it) } })
        }
        if (advert.manufacturerData.isNotEmpty()) {
            o.add("manufacturerData", JsonArray().apply {
                advert.manufacturerData.toSortedMap().forEach { (id, bytes) ->
                    add(JsonObject().apply {
                        addProperty("companyId", String.format(Locale.US, "0x%04X", id))
                        addProperty("length", bytes.size)
                        addProperty("header", hex(bytes.take(HEADER_BYTES)))
                    })
                }
            })
        }
        if (advert.serviceData.isNotEmpty()) {
            o.add("serviceData", JsonArray().apply {
                advert.serviceData.entries.sortedBy { it.key.toString() }.forEach { (uuid, bytes) ->
                    add(JsonObject().apply {
                        addProperty("uuid", uuidText(uuid))
                        addProperty("length", bytes.size)
                        addProperty("header", hex(bytes.take(HEADER_BYTES)))
                    })
                }
            })
        }
        advert.wifi?.let { w ->
            o.add("wifi", JsonObject().apply {
                addProperty("band", when {
                    w.frequencyMhz >= 5925 -> "6 GHz"
                    w.frequencyMhz >= 4900 -> "5 GHz"
                    else -> "2.4 GHz"
                })
                addProperty("security", w.capabilities)
                w.standard?.let { addProperty("standard", it) }
                if (advert.name.isNullOrBlank()) addProperty("hidden", true)
                // Vendor-specific IEs (221): the OUI says which chipset / maker built the AP.
                val vendorIes = w.infoElements.filter { it.first == 221 && it.second.size >= 3 }
                    .map { hex(it.second.take(3), ":") }.distinct().sorted()
                if (vendorIes.isNotEmpty()) add("vendorIeOuis", JsonArray().apply { vendorIes.forEach { add(it) } })
            })
        }
        userNote?.trim()?.takeIf { it.isNotEmpty() }?.let { o.addProperty("whatIThinkItIs", it.take(300)) }
        return gson.toJson(o)
    }

    /** Short form for SIG 16-bit UUIDs, full form for vendor 128-bit ones. */
    private fun uuidText(u: java.util.UUID): String =
        Advert.shortOf(u)?.let { String.format(Locale.US, "0x%04X", it) } ?: u.toString()

    private fun hex(bytes: List<Byte>, sep: String = ""): String =
        bytes.joinToString(sep) { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
}
