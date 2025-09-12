package com.ebani.sinage.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val mediaType: String,         // "image" | "video"
    val remoteUrl: String,
    val localPath: String?,        // filled after download
    val sizeBytes: Long? = null,
    val etag: String? = null,
//    val createdAt:Long?,
    val downloadedAt:String?=null, // for conditional downloads
//    val updatedAt:Long?,
    val hash: String?
)
