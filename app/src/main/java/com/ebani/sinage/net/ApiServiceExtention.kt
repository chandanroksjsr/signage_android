// ApiServiceExtensions.kt
package com.ebani.sinage.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

sealed interface PlaylistFetchResult {
    data class Ok(val dto: DevicePlaylistDTO) : PlaylistFetchResult
    object Empty : PlaylistFetchResult                 // registered but no items (204 or empty list)
    object NotRegistered : PlaylistFetchResult         // 404 or explicit null playlist
    data class HttpError(val code: Int) : PlaylistFetchResult
    data class NetworkError(val cause: Throwable) : PlaylistFetchResult
}

suspend fun ApiService.safeGetPlayerConfig(deviceId: String): Result<PlayerConfigDTO> =
    withContext(Dispatchers.IO) {
        println("A4 deb")
        runCatching { getPlayerConfig(deviceId)

        }
    }

suspend fun ApiService.fetchPlaylistForDevice(deviceId: String): PlaylistFetchResult {
    return try {
        val res = devicePlaylistRaw(deviceId)

        if (!res.isSuccessful) {
            return when (res.code()) {
                404 -> PlaylistFetchResult.NotRegistered
                204 -> PlaylistFetchResult.Empty
                else -> PlaylistFetchResult.HttpError(res.code())
            }
        }

        val body = res.body() ?: return PlaylistFetchResult.HttpError(500)

        val hasRoot = body.id != null && !body.items.isNullOrEmpty()
        val playlistObj = body.playlist

        if (hasRoot) {
            val dto = DevicePlaylistDTO(
                id = body.id!!,
                name = body.name.orEmpty(),
                updatedAt = body.updated_at,
                items = body.items!!.map {
                    // prefer embedded asset.id if present; otherwise assetId; otherwise item id
                    val assetId = (it.asset?.id ?: it.assetId ?: it.id).trim().lowercase()
                    DevicePlaylistItemDTO(
                        id = it.id.trim().lowercase(),
                        assetId = assetId,
                        durationSec = it.durationSec,
                        transition = it.transition ?: "none",
                        asset = it.asset?.let { a ->
                            DeviceAssetDTO(
                                id = a.id.trim().lowercase(),
                                title = a.title,
                                mediaType = a.mediaType,
                                url = a.url
                            )
                        }
                    )
                }
            )
            if (dto.items.isEmpty()) return PlaylistFetchResult.Empty
            return PlaylistFetchResult.Ok(dto)
        }

        // Many backends use { playlist:null } as “not registered”
        if (playlistObj == null) return PlaylistFetchResult.NotRegistered

        // Any other odd shape → be conservative: treat as Empty (not NotRegistered)
        PlaylistFetchResult.Empty
    } catch (e: IOException) {
        // timeouts / no network
        PlaylistFetchResult.NetworkError(e)
    } catch (e: HttpException) {
        PlaylistFetchResult.HttpError(e.code())
    } catch (t: Throwable) {
        PlaylistFetchResult.NetworkError(t)
    }
}
