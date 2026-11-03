package com.rfsentinel.app.data

import android.content.Context
import com.rfsentinel.app.detect.AdvertFingerprint
import androidx.room.withTransaction

/** Your own names for devices (known_devices.alias), kept in memory so the list and map needn't read the database per device. */
object DeviceNames {
    private const val PREFS = "device_names"
    private const val KEY_LOOKS = "looks"

    @Volatile private var names: Map<String, String> = emptyMap()
    /** Advert fingerprint hash ([AdvertFingerprint.hash]) of each named device -> its address, to spot it under a new address. */
    @Volatile private var looks: Map<String, String> = emptyMap()

    /** Your name for [mac], or null. */
    fun of(mac: String): String? = names[mac]

    /**
     * Your name for another device whose adverts look exactly like this one's ([fingerprint], see
     * [AdvertFingerprint.of]): most likely the same device under a new address, or one of the same model.
     * Null when [mac] has a name of its own or nothing matches.
     */
    fun lookalike(mac: String, fingerprint: String?): String? {
        if (fingerprint == null || mac in names) return null
        val named = looks[AdvertFingerprint.hash(fingerprint)]?.takeIf { it != mac } ?: return null
        return names[named]
    }

    suspend fun load(context: Context) {
        names = AppDatabase.getInstance(context).knownDeviceDao().aliases().associate { it.mac to it.alias }
        looks = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_LOOKS, null).orEmpty()
            .mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    /**
     * Names (or, with a blank [alias], un-names) a device, creating its history row if it hasn't been flushed yet.
     * Its advert [fingerprint], when it has one, is kept with the name so [lookalike] finds it again.
     */
    suspend fun set(context: Context, mac: String, alias: String?, name: String?, vendor: String?, type: String?, fingerprint: String? = null) {
        val clean = alias?.trim()?.take(64)?.ifEmpty { null }
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val dao = db.knownDeviceDao()
            dao.ensureRow(mac, name, vendor, type)
            dao.setAlias(mac, clean)
        }
        names = if (clean != null) names + (mac to clean) else names - mac
        val kept = looks.filterValues { it != mac }
        looks = if (clean != null && fingerprint != null) kept + (AdvertFingerprint.hash(fingerprint) to mac) else kept
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_LOOKS, looks.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    /** Saves your note on a device ([note] blank clears it). */
    suspend fun setNote(context: Context, mac: String, note: String?, name: String?, vendor: String?, type: String?) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            val dao = db.knownDeviceDao()
            dao.ensureRow(mac, name, vendor, type)
            dao.setNote(mac, note?.trim()?.take(1000)?.ifEmpty { null })
        }
    }
}
