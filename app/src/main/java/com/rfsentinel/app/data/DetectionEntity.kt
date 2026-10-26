package com.rfsentinel.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One logged watchlist / signature match. Default values match MIGRATION_1_2. */
@Entity(tableName = "detections", indices = [Index("timestamp"), Index("mac")])
data class DetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val label: String,
    val source: String, // "BLE" or "WIFI"
    val rssi: Int,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** A detect.Category name. */
    @ColumnInfo(defaultValue = "CUSTOM") val category: String = "CUSTOM",
    @ColumnInfo(defaultValue = "0") val confidence: Int = 0,
    @ColumnInfo(defaultValue = "") val evidence: String = "",
    val vendor: String? = null,
    val deviceName: String? = null,
    /** The radio side in words ("5 GHz · channel 36 · WPA2"), from v2.30 ([com.rfsentinel.app.detect.DeviceIntel.radioSummary]). */
    val radio: String? = null
)
