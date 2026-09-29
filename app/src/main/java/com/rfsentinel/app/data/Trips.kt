package com.rfsentinel.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A recorded scan trace: your GPS path plus every device heard along it. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long,
    val endTime: Long? = null,
    val distanceM: Double = 0.0,
    val pointCount: Int = 0,
    val deviceCount: Int = 0,
    val flaggedCount: Int = 0
) {
    val recording: Boolean get() = endTime == null
}

@Entity(
    tableName = "trip_points",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")]
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val speedMs: Float? = null
)

/**
 * A device heard during a trip. Its position is where YOUR phone was when the
 * device's signal was strongest - an approximation of where it was, not a fix.
 */
@Entity(
    tableName = "trip_devices",
    primaryKeys = ["tripId", "mac"],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")]
)
data class TripDeviceEntity(
    val tripId: Long,
    val mac: String,
    val label: String,
    val name: String?,
    val vendor: String?,
    val deviceType: String?,
    val source: String,
    /** detect.Category name when flagged, else null. */
    val category: String?,
    val confidence: Int,
    val evidence: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val bestRssi: Int,
    val lat: Double?,
    val lon: Double?
) {
    val flagged: Boolean get() = category != null
}

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Query("UPDATE trips SET endTime = :end, distanceM = :distance, pointCount = :points, deviceCount = :devices, flaggedCount = :flagged WHERE id = :id")
    suspend fun updateStats(id: Long, end: Long?, distance: Double, points: Int, devices: Int, flagged: Int)

    @Query("UPDATE trips SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun allFlow(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): TripEntity?

    /** A trip left "recording" by a crash / force-stop. */
    @Query("SELECT * FROM trips WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun openTrip(): TripEntity?

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertPoints(points: List<TripPointEntity>)

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY time")
    suspend fun points(tripId: Long): List<TripPointEntity>

    @Upsert
    suspend fun upsertDevices(devices: List<TripDeviceEntity>)

    @Query("SELECT * FROM trip_devices WHERE tripId = :tripId ORDER BY (category IS NULL), confidence DESC, bestRssi DESC")
    suspend fun devices(tripId: Long): List<TripDeviceEntity>
}
