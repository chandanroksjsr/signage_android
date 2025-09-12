package com.ebani.sinage.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ===== Region-based Player Config DTOs =====
 * Match backend: GET /api/player/config?deviceId=XYZ
 */

@Serializable
data class PlayerConfigDTO(
    @SerialName("screen") val screen: ScreenDTO?=null,
    @SerialName("playlists") val playlists: List<PlaylistDTO> = emptyList(),
    @SerialName("layout") val layout: LayoutConfigDTO,
    @SerialName("paired") val paired: Boolean
)

@Serializable
data class ScreenDTO(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("resolution") val resolution: ResolutionDTO,
    // <-- make layout optional; backend may omit it
    @SerialName("layout") val layout: LayoutConfigDTO? = null
)

@Serializable
data class ResolutionDTO(
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int
)

@Serializable
data class LayoutConfigDTO(
    @SerialName("design") val design: LayoutDesignDTO,
    @SerialName("regions") val regions: List<LayoutRegionDTO> = emptyList()
)

@Serializable
data class LayoutDesignDTO(
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("bgColor") val bgColor: String? = "#000000"
)

@Serializable
data class LayoutRegionDTO(
    @SerialName("id") val id: String,
    @SerialName("x") val x: Int,
    @SerialName("y") val y: Int,
    @SerialName("w") val w: Int,
    @SerialName("h") val h: Int,
    @SerialName("z") val z: Int? = null,
    @SerialName("fit") val fit: String? = "cover",
    @SerialName("playlistId") val playlistId: String? = null
)

@Serializable
data class PlaylistDTO(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("items") val items: List<PlaylistItemDTO> = emptyList()
)

@Serializable
data class PlaylistItemDTO(
    @SerialName("id") val id: String? = null,
    @SerialName("asset") val asset: AssetDTO,
    @SerialName("durationSec") val durationSec: Int? = null,
    @SerialName("startAt") val startAt: Long? = null,
    @SerialName("endAt") val endAt: Long? = null,
    @SerialName("loop") val loop: Boolean? = null
)

@Serializable
data class AssetDTO(
    @SerialName("id") val id: String,
    @SerialName("url") val url: String,
    @SerialName("title") val title: String,
    @SerialName("mediaType") val mediaType: String? = null,
    @SerialName("bytes") val bytes: Long? = null,
    @SerialName("hash") val hash: String? = null
)

