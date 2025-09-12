// com/ebani/sinage/sync/SyncManager.kt
package com.ebani.sinage.sync

import android.content.Context
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.data.repo.ContentRepository
import com.ebani.sinage.net.Net
import com.ebani.sinage.util.FileStore
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

sealed class SyncStatus {
    data object OK : SyncStatus()
    data object ERROR : SyncStatus()
    data class UNPAIRED(val deviceId: String) : SyncStatus()
}

object SyncManager {
    private fun repo(ctx: Context): ContentRepository =
        ContentRepository(
            context = ctx,
            api = Net.api,
            db = AppDatabase.getInstance(ctx),
            fileStore = FileStore(ctx),
            prefs = DevicePrefs(ctx)
        )

    suspend fun syncLayoutOnly(ctx: Context): SyncStatus {
        val res = repo(ctx).syncLayoutOnly() // Result<SyncLayoutResult>
        res.onSuccess { return SyncStatus.OK }

        val err = res.exceptionOrNull()
        return when (err) {
            is ContentRepository.PairingRequiredException ->
                SyncStatus.UNPAIRED(err.deviceId)
            else -> {
                Timber.e(err, "syncLayoutOnly failed")
                SyncStatus.ERROR
            }
        }
    }

    fun downloadPlaylist(ctx: Context, playlistId: String): Flow<ContentRepository.DownloadProgress> =
        repo(ctx).downloadAssetsForPlaylist(playlistId)
}
