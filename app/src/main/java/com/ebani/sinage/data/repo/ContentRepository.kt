// com/ebani/sinage/data/repo/ContentRepository.kt
package com.ebani.sinage.data.repo

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.model.AssetEntity
import com.ebani.sinage.data.model.DeviceEntity
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

    // inside ContentRepository.kt (same place as before)
    data class DownloadProgress(
        val playlistId: String,
        val finishedCount: Int,
        val totalCount: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,          // dynamic: grows as we learn sizes
        val rateBytesPerSec: Long,     // smoothed EMA
        val etaMillis: Long,           // -1 when unknown
        val currentAssetId: String?,   // null when idle/done
        val currentRead: Long,         // bytes read in current asset
        val currentTotal: Long         // -1 when unknown
    ) {
        val percent: Int =
            if (totalBytes > 0L) ((bytesDownloaded * 100) / totalBytes).toInt()
            else if (totalCount > 0) ((finishedCount * 100) / totalCount) else 100

        val currentPercent: Int =
            if (currentTotal > 0L) ((currentRead * 100) / currentTotal).toInt() else -1

        val done: Boolean = (finishedCount >= totalCount) || (totalCount == 0)
    }


    // a small typed error you can catch in UI
    class PairingRequiredException(val deviceId: String) :
        IllegalStateException("Device $deviceId not paired")

    class NoSyncNeededException(val deviceId: String) :
        IllegalStateException("Device $deviceId no Sync needed")
    suspend fun syncLayoutOnly(): Result<SyncLayoutResult> = withContext(io) {
        val deviceId = prefs.deviceId

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

        if (!cfg.paired || cfg.screen == null) {
            // If unpaired, clear everything (unchanged behavior)
            db.device().deleteAll()
            db.assets().deleteAll()
            db.playlistItems().deleteAll()
            db.playlists().deleteAll()
            Timber.w("Device not paired; throwing PairingRequiredException")
            return@withContext Result.failure(PairingRequiredException(deviceId))
        }



        val currentConfig = sha1(json.encodeToString(cfg.version))
        Timber.d(
            "ConfigVersions. lastversion=%s currentversion=%s",
            prefs.lastConfigHash,currentConfig,
        )

//        prefs.lastConfigHash = cfg.version
        if(currentConfig==prefs.lastConfigHash){
            return@withContext Result.failure(exception =NoSyncNeededException(deviceId) )
        }
        // Persist screen + layout
        prefs.screenWidth = cfg.layout.design.width
        prefs.screenHeight = cfg.layout.design.height
        prefs.layoutJson = json.encodeToString(cfg.layout)
        prefs.lastConfigHash = sha1(json.encodeToString(cfg.version))

        val deviceDetails = DeviceEntity(
            id = "1",
            deviceName = cfg.screen.name,
            registeredOn = cfg.created_at.toString(),
            deviceId = cfg.deviceId,
            screenId = cfg.screen.id,
            adminUser = "TODO()",
            adminUserId = cfg.userId
        )
        db.device().insert(deviceDetails)

        // ────────────────────────────────────────────────────────────────────
        // POINT 7: Replace per-playlist atomically; prune removed playlists.
        // No global wipe of playlists/items anymore.
        // ────────────────────────────────────────────────────────────────────
        var itemCount = 0
        val incomingIds: Set<String> = cfg.playlists.map { it.id }.toSet()

        db.withTransaction {
            // 1) Upsert assets & prepare playlist items for each incoming playlist
            cfg.playlists.forEach { p ->
                val newItems = mutableListOf<PlaylistItemEntity>()

                p.items.forEachIndexed { idx, it ->
                    itemCount++
                    val a = it.asset
                    val existing = db.assets().findById(a.id)

                    val ae = AssetEntity(
                        id = a.id,
                        remoteUrl = a.url,
                        title = a.title,
                        mediaType = guessExt(a.mediaType, a.url),  // keep as-is per current schema
                        sizeBytes = a.bytes ?: 0L,
                        hash = a.hash,
                        localPath = existing?.localPath,
                        downloadedAt = existing?.downloadedAt
                    )
                    db.assets().upsert(ae)

                    // Generate a stable primary key for PlaylistItemEntity
                    val itemId = "${p.id}:${a.id}:$idx"
                    newItems += PlaylistItemEntity(
                        id = itemId,
                        playlistId = p.id,
                        assetId = a.id,
                        orderIndex = idx,
                        durationSec = it.durationSec ?: 10
                    )
                }

                // 2) Atomically replace this playlist header + items
                db.playlists().replacePlaylist(
                    playlistId = p.id,
                    name = p.name,
                    newItems = newItems
                )
            }

            // 3) Delete playlists (and their items) that are no longer in config
            val existing = db.playlists().allPlaylists()
            val toRemove = existing.filter { it.id !in incomingIds }
            toRemove.forEach { pl ->
                db.playlists().deleteItemsForPlaylist(pl.id)
                db.playlists().deletePlaylist(pl.id)
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

            // Only sum KNOWN sizes; unknowns will be added exactly once when learned
            var totalBytesKnown: Long = toDownload.sumOf { it.sizeBytes?.takeIf { s -> s > 0 } ?: 0L }
            var bytesDownloaded: Long = 0L
            var finishedCount = 0

            // Smoothing + throttling
            var emaRateBps = 0.0
            val EMA_ALPHA = 0.2
            val MIN_EMIT_MS = 200L
            var lastEmitMs = 0L
            var lastTickMs = android.os.SystemClock.elapsedRealtime()
            fun nowMs() = android.os.SystemClock.elapsedRealtime()

            fun emitProgress(
                currentId: String?,
                currentRead: Long,
                currentTotal: Long,
                force: Boolean = false
            ) {
                val t = nowMs()
                if (!force && (t - lastEmitMs) < MIN_EMIT_MS) return

                // keep totals sane
                if (totalBytesKnown < bytesDownloaded) totalBytesKnown = bytesDownloaded

                val remaining = (totalBytesKnown - bytesDownloaded).coerceAtLeast(0L)
                val etaMs = if (emaRateBps > 1.0) ((remaining / emaRateBps) * 1000).toLong() else -1L

                trySend(
                    DownloadProgress(
                        playlistId = playlistId,
                        finishedCount = finishedCount,
                        totalCount = totalCount,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytesKnown,
                        rateBytesPerSec = emaRateBps.toLong(),
                        etaMillis = etaMs,
                        currentAssetId = currentId,
                        currentRead = currentRead,
                        currentTotal = currentTotal
                    )
                )
                lastEmitMs = t
            }

            // Initial ping
            emitProgress(currentId = null, currentRead = 0L, currentTotal = -1L, force = true)

            for (asset in toDownload) {
                val ext = guessExt(asset.mediaType, asset.remoteUrl)
                val dest = fileStore.assetFile(asset.id, ext)

                // ⬇️ Re-check right before downloading: maybe another region finished it already
                if (!needsDownload(asset)) {
                    // add its bytes to progress if we know them (or use actual file length)
                    val preLen = runCatching { File(asset.localPath ?: "").length() }.getOrElse { 0L }
                    val contributed = when {
                        (asset.sizeBytes ?: 0L) > 0L -> asset.sizeBytes!!
                        preLen > 0L -> preLen
                        else -> 0L
                    }
                    bytesDownloaded += contributed
                    if (totalBytesKnown < bytesDownloaded) totalBytesKnown = bytesDownloaded
                    finishedCount++
                    emitProgress(currentId = null, currentRead = 0L, currentTotal = -1L)
                    continue
                }

                var currentRead = 0L
                var currentTotal = asset.sizeBytes ?: -1L
                var isFirstProgress = true
                var announcedTotalForThisAsset = (currentTotal > 0L)

                val ok = fileStore.downloadTo(
                    url = asset.remoteUrl,
                    dest = dest,
                    onProgress = { read, reportedTotal ->
                        val t = nowMs()
                        val dtMs = (t - lastTickMs).coerceAtLeast(1L)
                        val deltaBytes = (read - currentRead).coerceAtLeast(0L)

                        // Accept a plausible content-length ONCE (prevents GB spikes)
                        if (isFirstProgress) {
                            if (!announcedTotalForThisAsset && reportedTotal > 0L && reportedTotal >= read) {
                                currentTotal = reportedTotal
                                totalBytesKnown += currentTotal     // add only once per file
                                announcedTotalForThisAsset = true
                            }
                            isFirstProgress = false
                        }

                        // Advance counters
                        bytesDownloaded += deltaBytes
                        currentRead = read
                        if (totalBytesKnown < bytesDownloaded) totalBytesKnown = bytesDownloaded

                        // Smooth rate
                        val instRateBps = (deltaBytes * 1000.0) / dtMs.toDouble()
                        emaRateBps = if (emaRateBps <= 0.0) instRateBps
                        else EMA_ALPHA * instRateBps + (1.0 - EMA_ALPHA) * emaRateBps

                        lastTickMs = t
                        emitProgress(currentId = asset.id, currentRead = currentRead, currentTotal = currentTotal)
                    }
                )

                // Final tick for this asset
                emitProgress(currentId = asset.id, currentRead = currentRead, currentTotal = currentTotal, force = true)

                // ⬇️ Treat rename/contention as success if dest exists and looks valid
                var success = ok
                if (!success) {
                    val looksValid = dest.isFile && when {
                        currentTotal > 0L -> dest.length() == currentTotal
                        (asset.sizeBytes ?: 0L) > 0L -> dest.length() == asset.sizeBytes
                        else -> dest.length() > 0L
                    }
                    if (looksValid) success = true
                }

                if (success) {
                    finishedCount++
                    db.assets().setDownloaded(
                        id = asset.id,
                        localPath = dest.absolutePath,
                        downloadedAt = System.currentTimeMillis()
                    )
                }
            }

            // Final done state
            emitProgress(currentId = null, currentRead = 0L, currentTotal = -1L, force = true)
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
