// com/ebani/sinage/data/repo/ContentRepository.kt
package com.ebani.sinage.data.repo

import android.content.Context
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.model.AssetEntity
import com.ebani.sinage.data.model.PlaylistEntity
import com.ebani.sinage.data.model.PlaylistItemEntity
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.net.ApiService
import com.ebani.sinage.net.PlayerConfigDTO
import com.ebani.sinage.net.safeGetPlayerConfig
import com.ebani.sinage.util.FileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.MessageDigest
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.File

class ContentRepository(
    private val context: Context,
    private val api: ApiService,
    private val db: AppDatabase,
    private val fileStore: FileStore,
    private val prefs: DevicePrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class SyncLayoutResult(val playlists: Int, val items: Int)

    data class DownloadProgress(
        val playlistId: String,
        val finishedCount: Int,
        val totalCount: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) {
        val percent: Int = if (totalBytes > 0L) ((bytesDownloaded * 100) / totalBytes).toInt()
        else if (totalCount > 0) ((finishedCount * 100) / totalCount)
        else 100
        val done: Boolean = (finishedCount >= totalCount) || (totalCount == 0)
    }

    // a small typed error you can catch in UI
    class PairingRequiredException(val deviceId: String) :
        IllegalStateException("Device $deviceId not paired")

    suspend fun syncLayoutOnly(): Result<SyncLayoutResult> = withContext(io) {
        val deviceId = prefs.deviceId ?: return@withContext Result.failure(
            IllegalStateException("deviceId missing")
        )

        Timber.d("syncLayoutOnly(): fetching config for deviceId=%s", deviceId)

        val net: Result<PlayerConfigDTO> = api.safeGetPlayerConfig(deviceId)

        net.onFailure { e ->
            Timber.e(e, "safeGetPlayerConfig() failed for deviceId=%s", deviceId)
            return@withContext Result.failure(e)
        }

        val cfg = net.getOrThrow()
        Timber.d(
            "safeGetPlayerConfig() OK. paired=%s screen=%s layoutWxH=%sx%s",
            cfg.paired, cfg.screen, cfg.layout.design.width, cfg.layout.design.height
        )
        runCatching {
            Timber.d(
                "config json:\n%s",
                Json { prettyPrint = true; ignoreUnknownKeys = true }.encodeToString(cfg)
            )
        }

        if (!cfg.paired || cfg.screen == null) {
            Timber.w("Device not paired; throwing PairingRequiredException")
            return@withContext Result.failure(PairingRequiredException(deviceId))
        }

        // Persist screen + layout
        prefs.screenWidth = cfg.layout.design.width
        prefs.screenHeight = cfg.layout.design.height
        prefs.layoutJson = json.encodeToString(cfg.layout)
        prefs.lastConfigHash = sha1(json.encodeToString(cfg))

        // Upsert DB rows (keep any already-downloaded asset paths)
        var itemCount = 0
        db.runInTransaction {
            db.playlists().deleteAll()
            db.playlistItems().deleteAll()

            cfg.playlists.forEach { p ->
                db.playlists().insert(PlaylistEntity(id = p.id, name = p.name))
                p.items.forEachIndexed { idx, it ->
                    itemCount++
                    val a = it.asset
                    val existing = db.assets().findById(a.id)

                    val ae = AssetEntity(
                        id = a.id,
                        remoteUrl = a.url,
                        title = a.title,
                        mediaType = guessExt(a.mediaType, a.url),
                        sizeBytes = a.bytes ?: 0L,
                        hash = a.hash,
                        localPath = existing?.localPath,
                        downloadedAt = existing?.downloadedAt
                    )
                    db.assets().upsert(ae)

                    db.playlistItems().insert(
                        PlaylistItemEntity(
                            playlistId = p.id,
                            assetId = a.id,
                            id = a.id,
                            orderIndex = idx,
                            durationSec = it.durationSec ?: 10
                        )
                    )
                }
            }
        }

        // 🔥 Cleanup assets/files that are no longer referenced
        cleanupAfterSync(cfg)

        Timber.d("syncLayoutOnly(): done. playlists=%d items=%d", cfg.playlists.size, itemCount)
        Result.success(SyncLayoutResult(playlists = cfg.playlists.size, items = itemCount))
    }

    /**
     * Remove assets not referenced by freshly-synced playlists, clear broken local paths,
     * and delete orphan files in the assets directory.
     *
     * Requires small helpers on AssetDao:
     *  - allLite(): List<AssetLite(id, localPath)>
     *  - allLocalPaths(): List<String>
     *  - clearLocal(id: String)
     *  - deleteByIds(ids: List<String>)
     */
    private suspend fun cleanupAfterSync(cfg: PlayerConfigDTO) = withContext(io) {
        // Which assets are in use per the latest config
        val keepAssetIds: Set<String> = cfg.playlists
            .flatMap { it.items }
            .map { it.asset.id }
            .toSet()

        // 1) Normalize broken local paths in DB and collect existing file paths we might keep
        val allAssets = db.assets().allLite() // id + localPath
        val keepPaths = mutableSetOf<String>()

        db.runInTransaction {
            allAssets.forEach { a ->
                val lp = a.localPath
                if (!lp.isNullOrBlank()) {
                    val f = File(lp)
                    if (!f.isFile) {
                        // stale reference; clear it so future downloads re-fetch
                        db.assets().clearLocal(a.id)
                    } else {
                        keepPaths += f.absolutePath
                    }
                }
            }
        }

        // 2) Delete DB rows (and files) for assets no longer referenced
        val toDelete = allAssets.asSequence()
            .filter { it.id !in keepAssetIds }
            .toList()

        if (toDelete.isNotEmpty()) {
            // Delete files first
            toDelete.forEach { a ->
                a.localPath?.let { p ->
                    runCatching {
                        val f = File(p)
                        if (f.isFile) f.delete()
                    }.onFailure { e -> Timber.w(e, "Failed deleting asset file: %s", p) }
                }
            }
            // Then remove DB rows
            db.assets().deleteByIds(toDelete.map { it.id })
        }

        // 3) Sweep orphan files in the assets directory (not referenced by DB at all)
        val probe = fileStore.assetFile(".__probe__", "bin") // doesn't create file
        val assetsDir = probe.parentFile
        if (assetsDir != null && assetsDir.isDirectory) {
            val currentDbPaths = db.assets().allLocalPaths().toSet()
            assetsDir.listFiles()?.forEach { f ->
                if (f.isFile && f.absolutePath !in currentDbPaths) {
                    runCatching { f.delete() }
                        .onFailure { e -> Timber.w(e, "Failed deleting orphan file: %s", f.absolutePath) }
                }
            }
        }

        Timber.i(
            "Cleanup complete. Kept %d assets; removed %d assets; orphan sweep done.",
            keepAssetIds.size,
            toDelete.size
        )
    }

    /** Download all missing assets for a playlist; emits progress updates. */
    fun downloadAssetsForPlaylist(playlistId: String): Flow<DownloadProgress> = channelFlow {
        launch(io) {
            val items = db.playlistItems()
                .itemsForPlaylist(playlistId)
                .sortedBy { it.orderIndex }

            val assets = items.mapNotNull { db.assets().findById(it.assetId) }

            fun needsDownload(a: AssetEntity): Boolean {
                val path = a.localPath ?: return true
                val f = File(path)
                if (!f.isFile) return true
                a.sizeBytes?.takeIf { it > 0L }?.let { expected ->
                    if (f.length() != expected) return true
                }
                return false
            }

            val toDownload = assets.filter { needsDownload(it) }

            val totalCount = toDownload.size
            val totalBytes = toDownload.sumOf { it.sizeBytes ?: 0L }
            var finishedCount = 0
            var doneBytes = 0L

            trySend(DownloadProgress(playlistId, finishedCount, totalCount, doneBytes, totalBytes))

            for (asset in toDownload) {
                val ext = guessExt(asset.mediaType, asset.remoteUrl)
                val dest = fileStore.assetFile(asset.id, ext)

                var lastEmitted = 0L
                val ok = fileStore.downloadTo(
                    url = asset.remoteUrl,
                    dest = dest,
                    onProgress = { read, _ ->
                        val delta = (read - lastEmitted).coerceAtLeast(0L)
                        lastEmitted = read
                        doneBytes += delta
                        trySend(
                            DownloadProgress(
                                playlistId,
                                finishedCount,
                                totalCount,
                                doneBytes,
                                totalBytes
                            )
                        )
                    }
                )

                if (ok) {
                    finishedCount++
                    db.assets().setDownloaded(
                        id = asset.id,
                        localPath = dest.absolutePath,
                        downloadedAt = System.currentTimeMillis()
                    )
                }

                trySend(DownloadProgress(playlistId, finishedCount, totalCount, doneBytes, totalBytes))
            }

            close()
        }
    }

    private fun guessExt(mime: String?, url: String): String {
        val u = url.substringBefore('?').lowercase()
        return when {
            (mime ?: "").contains("mp4", true) || u.endsWith(".mp4") -> "mp4"
            (mime ?: "").contains("webm", true) || u.endsWith(".webm") -> "webm"
            (mime ?: "").contains("png", true) || u.endsWith(".png") -> "png"
            (mime ?: "").contains("jpeg", true) || u.endsWith(".jpeg") -> "jpg"
            (mime ?: "").contains("jpg", true)  || u.endsWith(".jpg")  -> "jpg"
            (mime ?: "").contains("gif", true)  || u.endsWith(".gif")  -> "gif"
            else -> u.substringAfterLast('.', "bin")
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
