package com.ebani.sinage.data.model
import androidx.room.Entity

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["id"]
)
data class PlaylistItemEntity(
    val id: String,
    val playlistId: String,
    val assetId: String,
    val durationSec: Int,
    val orderIndex: Int,
//    val transition: String?
)

