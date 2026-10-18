package com.rfsentinel.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Long-term memory of every device address heard, so the app can tell NEW
 * devices from ones seen on earlier days, and count how often each shows up.
 * Rotating (private) addresses naturally appear as new each time.
 */
@Entity(tableName = "known_devices", indices = [Index("lastSeen")])
data class KnownDeviceEntity(
    @PrimaryKey val mac: String,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Number of separate scanning sessions the device was heard in. */
    val sessions: Int,
    /** Throttled sighting count (at most one per minute per device). */
    val detectCount: Int,
    val name: String? = null,
    val vendor: String? = null,
    val deviceType: String? = null,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /** Your own note about it, from its device page. */
    val note: String? = null,
    /** Your own name for it, from its device page: shown in place of the name it broadcasts. */
    val alias: String? = null
)

/** A device's own name ([KnownDeviceDao.aliases]). */
data class MacAlias(val mac: String, val alias: String)

@Dao
interface KnownDeviceDao {
    @Query("SELECT * FROM known_devices WHERE mac = :mac")
    suspend fun get(mac: String): KnownDeviceEntity?

    @Query("SELECT * FROM known_devices ORDER BY lastSeen DESC")
    suspend fun all(): List<KnownDeviceEntity>

    /** Exports, in batches: the order first, then the rows a batch at a time ([getAll]). */
    @Query("SELECT mac FROM known_devices ORDER BY lastSeen DESC")
    suspend fun macsByLastSeen(): List<String>

    @Query("SELECT * FROM known_devices WHERE mac IN (:macs)")
    suspend fun getAll(macs: List<String>): List<KnownDeviceEntity>

    @Upsert
    suspend fun upsertAll(rows: List<KnownDeviceEntity>)

    @Query("UPDATE known_devices SET favorite = :favorite WHERE mac = :mac")
    suspend fun setFavorite(mac: String, favorite: Boolean)

    @Query("SELECT mac FROM known_devices WHERE favorite = 1")
    suspend fun favorites(): List<String>

    @Query("UPDATE known_devices SET alias = :alias WHERE mac = :mac")
    suspend fun setAlias(mac: String, alias: String?)

    @Query("UPDATE known_devices SET note = :note WHERE mac = :mac")
    suspend fun setNote(mac: String, note: String?)

    @Query("SELECT mac, alias FROM known_devices WHERE alias IS NOT NULL AND alias != ''")
    suspend fun aliases(): List<MacAlias>

    @Query("SELECT COUNT(*) FROM known_devices")
    suspend fun count(): Int

    /**
     * Device history search ([com.rfsentinel.app.ui.DeviceHistoryActivity]), newest first: [query] in the
     * MAC (with or without colons), name, vendor, type, note or your own name; only favorites, only devices that were
     * flagged (in the match log), only those seen since [since].
     */
    @Query("""SELECT * FROM known_devices
        WHERE (:query = '' OR mac LIKE :like ESCAPE '\' OR REPLACE(mac, ':', '') LIKE :likeHex ESCAPE '\'
               OR name LIKE :like ESCAPE '\' OR vendor LIKE :like ESCAPE '\'
               OR deviceType LIKE :like ESCAPE '\' OR note LIKE :like ESCAPE '\' OR alias LIKE :like ESCAPE '\')
          AND (:favorites = 0 OR favorite = 1)
          AND (:flagged = 0 OR mac IN (SELECT mac FROM detections))
          AND lastSeen >= :since
        ORDER BY lastSeen DESC LIMIT :limit""")
    suspend fun search(query: String, like: String, likeHex: String, favorites: Boolean, flagged: Boolean, since: Long, limit: Int): List<KnownDeviceEntity>

    companion object {
        /** "%text%" for LIKE, with LIKE's own characters taken literally. */
        fun like(text: String) = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        /** A MAC typed without colons (or with dashes) still finds it. */
        fun likeHex(text: String) = like(text.replace(":", "").replace("-", ""))
    }

    /** Forget non-favorite devices not heard since [before]; ones you named or wrote a note on are kept. */
    @Query("""DELETE FROM known_devices WHERE lastSeen < :before AND favorite = 0
        AND (alias IS NULL OR alias = '') AND (note IS NULL OR note = '')""")
    suspend fun prune(before: Long): Int

    @Query("DELETE FROM known_devices WHERE favorite = 0")
    suspend fun clearNonFavorites()
}
