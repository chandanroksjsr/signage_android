package com.ebani.sinage.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val deviceName: String?,
    val registeredOn: String,         // "image" | "video"
    val deviceId: String,
    val screenId: String?,        // filled after download
    val adminUser: String? = null,
    val adminUserId: String? = null
)