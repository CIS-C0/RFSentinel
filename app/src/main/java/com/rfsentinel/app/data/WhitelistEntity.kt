package com.rfsentinel.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whitelist")
data class WhitelistEntity(
    @PrimaryKey val mac: String,
    val note: String = ""
)
