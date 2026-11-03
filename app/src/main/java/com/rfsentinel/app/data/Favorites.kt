package com.rfsentinel.app.data

import android.content.Context
import androidx.room.withTransaction

/** In-memory mirror of known_devices.favorite for fast list filtering. */
object Favorites {
    @Volatile private var macs: Set<String> = emptySet()

    fun contains(mac: String) = mac in macs

    suspend fun load(context: Context) {
        macs = AppDatabase.getInstance(context).knownDeviceDao().favorites().toSet()
    }

    /** Stars / un-stars a device, creating its history row if it hasn't been flushed yet. */
    suspend fun set(context: Context, mac: String, favorite: Boolean, name: String?, vendor: String?, type: String?) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val dao = db.knownDeviceDao()
            dao.ensureRow(mac, name, vendor, type)
            dao.setFavorite(mac, favorite)
        }
        macs = if (favorite) macs + mac else macs - mac
    }
}
