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

    @Query("SELECT * FROM detections WHERE mac = :mac ORDER BY timestamp DESC LIMIT 200")
    suspend fun forMac(mac: String): List<DetectionEntity>

    @Query("SELECT * FROM detections ORDER BY timestamp DESC")
    suspend fun allForExport(): List<DetectionEntity>

    @Query("SELECT * FROM detections WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<DetectionEntity>

    @Query("SELECT COUNT(*) FROM detections")
    suspend fun count(): Int

    @Query("DELETE FROM detections WHERE timestamp < :before")
    suspend fun prune(before: Long): Int

    @Query("DELETE FROM detections")
    suspend fun clearAll()
}
