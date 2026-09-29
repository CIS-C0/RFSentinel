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
    val note: String? = null
)

@Dao
interface KnownDeviceDao {
    @Query("SELECT * FROM known_devices WHERE mac = :mac")
    suspend fun get(mac: String): KnownDeviceEntity?

    @Query("SELECT * FROM known_devices ORDER BY lastSeen DESC")
    suspend fun all(): List<KnownDeviceEntity>

    @Query("SELECT * FROM known_devices WHERE mac IN (:macs)")
    suspend fun getAll(macs: List<String>): List<KnownDeviceEntity>

    @Upsert
    suspend fun upsertAll(rows: List<KnownDeviceEntity>)

    @Query("UPDATE known_devices SET favorite = :favorite WHERE mac = :mac")
    suspend fun setFavorite(mac: String, favorite: Boolean)

    @Query("SELECT mac FROM known_devices WHERE favorite = 1")
    suspend fun favorites(): List<String>

    @Query("SELECT COUNT(*) FROM known_devices")
    suspend fun count(): Int

    /** Forget non-favorite devices not heard since [before]. */
    @Query("DELETE FROM known_devices WHERE lastSeen < :before AND favorite = 0")
    suspend fun prune(before: Long): Int

    @Query("DELETE FROM known_devices WHERE favorite = 0")
    suspend fun clearNonFavorites()
}
