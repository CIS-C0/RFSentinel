package com.rfsentinel.app.detect

/**
 * A structural fingerprint of a BLE advertisement that survives address
 * rotation: which services, company IDs and payload sizes it carries, its name
 * and TX power. When a private address goes silent and a new one with the same
 * fingerprint appears seconds later, it is very likely the same device.
 *
 * Deliberately returns null for adverts that carry too little to tell devices
 * apart (Apple Continuity-only or empty adverts: every iPhone looks alike),
 * so ordinary phones never get linked to each other.
 */
object AdvertFingerprint {

    private const val CID_APPLE = 0x004C

    fun of(a: Advert): String? {
        if (!a.isBle) return null
        val nonAppleMfg = a.manufacturerData.filterKeys { it != CID_APPLE }
        val name = a.name?.trim()?.takeIf { it.isNotEmpty() }
        val informative = name != null || nonAppleMfg.isNotEmpty() || a.serviceData.isNotEmpty() || a.serviceUuids.isNotEmpty()
        if (!informative) return null
        return buildString {
            append("n=").append(name ?: "")
            append("|u=").append(a.serviceUuids.map { it.toString() }.sorted().joinToString(","))
            append("|d=").append(a.serviceData.entries.map { "${it.key}:${it.value.size}" }.sorted().joinToString(","))
            append("|m=").append(a.manufacturerData.entries.map { "${it.key}:${it.value.size}" }.sorted().joinToString(","))
            append("|t=").append(a.txPower ?: "")
            append("|c=").append(a.connectable ?: "")
        }
    }

    /** True when the address type means it's expected to rotate (or unknown on Android < 15). */
    fun mayRotate(a: Advert): Boolean = a.isBle && a.addressType != AddressType.PUBLIC &&
        a.addressType != AddressType.RANDOM_STATIC
}
