package com.ebani.sinage.data.model
import androidx.room.Entity

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "assetId", "orderIndex"]
)
data class PlaylistItemEntity(
    val playlistId: String,
    val assetId: String,
    val orderIndex: Int,
    val durationSec: Int,
    val id: String
)

