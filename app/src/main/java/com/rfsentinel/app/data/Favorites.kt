package com.rfsentinel.app.data

import android.content.Context

/** In-memory mirror of known_devices.favorite for fast list filtering. */
object Favorites {
    @Volatile private var macs: Set<String> = emptySet()

    fun contains(mac: String) = mac in macs

    suspend fun load(context: Context) {
        macs = AppDatabase.getInstance(context).knownDeviceDao().favorites().toSet()
    }

    /** Stars / un-stars a device, creating its history row if it hasn't been flushed yet. */
    suspend fun set(context: Context, mac: String, favorite: Boolean, name: String?, vendor: String?, type: String?) {
        val dao = AppDatabase.getInstance(context).knownDeviceDao()
        if (dao.get(mac) == null) {
            val now = System.currentTimeMillis()
            dao.upsertAll(listOf(KnownDeviceEntity(mac, now, now, 1, 1, name, vendor, type, favorite)))
        } else {
            dao.setFavorite(mac, favorite)
        }
        macs = if (favorite) macs + mac else macs - mac
    }
}
