package com.rfsentinel.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WhitelistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: WhitelistEntity)

    @Delete
    suspend fun remove(entry: WhitelistEntity)

    @Query("SELECT * FROM whitelist ORDER BY mac ASC")
    fun all(): Flow<List<WhitelistEntity>>

    @Query("SELECT * FROM whitelist ORDER BY mac ASC")
    suspend fun list(): List<WhitelistEntity>

    /** A restored backup's whitelist in place of this one. */
    @Transaction
    suspend fun replaceAll(entries: List<WhitelistEntity>) {
        clear()
        entries.forEach { add(it) }
    }

    @Query("DELETE FROM whitelist")
    suspend fun clear()

    @Query("SELECT * FROM whitelist WHERE mac = :mac")
    suspend fun get(mac: String): WhitelistEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM whitelist WHERE mac = :mac)")
    suspend fun isWhitelisted(mac: String): Boolean
}
