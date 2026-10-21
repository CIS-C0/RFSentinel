package com.rfsentinel.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectionDao {

    @Insert
    suspend fun insert(detection: DetectionEntity): Long

    @Query("SELECT * FROM detections ORDER BY timestamp DESC LIMIT 1000")
    fun recent(): Flow<List<DetectionEntity>>

    /** Match history search: text in the address, label, maker, name or evidence; one category; since a time. */
    @Query("""SELECT * FROM detections
        WHERE timestamp >= :since AND (:category IS NULL OR category = :category)
          AND (:query = '' OR mac LIKE :like ESCAPE '\' OR REPLACE(mac, ':', '') LIKE :likeHex ESCAPE '\'
               OR label LIKE :like ESCAPE '\' OR vendor LIKE :like ESCAPE '\'
               OR deviceName LIKE :like ESCAPE '\' OR evidence LIKE :like ESCAPE '\')
        ORDER BY timestamp DESC LIMIT 1000""")
    fun search(query: String, like: String, likeHex: String, category: String?, since: Long): Flow<List<DetectionEntity>>

    /** The categories in the log, for the filter chips. */
    @Query("SELECT DISTINCT category FROM detections")
    suspend fun categories(): List<String>

    @Query("SELECT * FROM detections WHERE mac = :mac ORDER BY timestamp DESC LIMIT 200")
    suspend fun forMac(mac: String): List<DetectionEntity>

    /** One device's logged matches since [since], for its sightings map. */
    @Query("SELECT * FROM detections WHERE mac = :mac AND timestamp >= :since ORDER BY timestamp DESC LIMIT 5000")
    suspend fun forMacSince(mac: String, since: Long): List<DetectionEntity>

    @Query("SELECT * FROM detections ORDER BY timestamp DESC")
    suspend fun allForExport(): List<DetectionEntity>

    /** Exports, in batches: the [limit] newest rows logged before row [beforeId] (stable while new ones arrive). */
    @Query("SELECT * FROM detections WHERE id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun pageBefore(beforeId: Long, limit: Int): List<DetectionEntity>

    @Query("SELECT * FROM detections WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<DetectionEntity>

    @Query("SELECT COUNT(*) FROM detections")
    suspend fun count(): Int

    @Query("DELETE FROM detections WHERE timestamp < :before")
    suspend fun prune(before: Long): Int

    @Query("DELETE FROM detections")
    suspend fun clearAll()
}
