
package com.ebani.sinage.net

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("api/device/{deviceId}/playlist")
    suspend fun devicePlaylistRaw(@Path("deviceId") deviceId: String): Response<DevicePlaylistResponse>

    @GET("/api/device/{deviceId}/config")
    suspend fun getPlayerConfig(@Path("deviceId") deviceId: String): PlayerConfigDTO

//    suspend fun getPlayerConfig(
//        @Query("deviceId") deviceId: String
//    ): PlayerConfigDTO

}


/* ===== Wire models that match your sample response ===== */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DevicePlaylistResponse(
    val id: String? = null,
    val name: String? = null,
    val updated_at: String? = null,
    val items: List<DevicePlaylistItemResponse>? = null,
    // In case API sometimes returns { playlist: { ... } } or { playlist: null }
    val playlist: kotlinx.serialization.json.JsonElement? = null
)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DevicePlaylistItemResponse(
    val id: String,
    val assetId: String? = null,
    val durationSec: Int,
    val transition: String? = null,
    val asset: DeviceAssetResponse? = null
)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DeviceAssetResponse(
    val id: String,
    val title: String,
    val mediaType: String, // "image" | "video"
    val url: String        // presigned URL
)

/* ===== DTOs used by the app (you already have similar structs) ===== */
data class DevicePlaylistDTO(
    val id: String,
    val name: String,
    val updatedAt: String?,
    val items: List<DevicePlaylistItemDTO>
)

data class DevicePlaylistItemDTO(
    val id: String,
    val assetId: String,
    val durationSec: Int,
    val transition: String,
    val asset: DeviceAssetDTO?
)

data class DeviceAssetDTO(
    val id: String,
    val title: String,
    val mediaType: String,
    val url: String
)
