package com.rfsentinel.app.data

import android.content.Context

/** Your own names for devices (known_devices.alias), kept in memory so the list and map needn't read the database per device. */
object DeviceNames {
    @Volatile private var names: Map<String, String> = emptyMap()

    /** Your name for [mac], or null. */
    fun of(mac: String): String? = names[mac]

    suspend fun load(context: Context) {
        names = AppDatabase.getInstance(context).knownDeviceDao().aliases().associate { it.mac to it.alias }
    }

    /** Names (or, with a blank [alias], un-names) a device, creating its history row if it hasn't been flushed yet. */
    suspend fun set(context: Context, mac: String, alias: String?, name: String?, vendor: String?, type: String?) {
        val clean = alias?.trim()?.take(64)?.ifEmpty { null }
        ensureRow(context, mac, name, vendor, type)
        AppDatabase.getInstance(context).knownDeviceDao().setAlias(mac, clean)
        names = if (clean != null) names + (mac to clean) else names - mac
    }

    /** Saves your note on a device ([note] blank clears it). */
    suspend fun setNote(context: Context, mac: String, note: String?, name: String?, vendor: String?, type: String?) {
        ensureRow(context, mac, name, vendor, type)
        AppDatabase.getInstance(context).knownDeviceDao().setNote(mac, note?.trim()?.take(1000)?.ifEmpty { null })
    }

    private suspend fun ensureRow(context: Context, mac: String, name: String?, vendor: String?, type: String?) {
        val dao = AppDatabase.getInstance(context).knownDeviceDao()
        if (dao.get(mac) == null) {
            val now = System.currentTimeMillis()
            dao.upsertAll(listOf(KnownDeviceEntity(mac, now, now, 1, 1, name, vendor, type)))
        }
    }
}
