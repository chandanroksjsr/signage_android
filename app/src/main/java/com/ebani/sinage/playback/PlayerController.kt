package com.ebani.sinage.playback

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.load
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.model.AssetEntity
import com.ebani.sinage.data.model.PlaylistItemEntity
import com.ebani.sinage.util.FileStore
import kotlinx.coroutines.*
import timber.log.Timber

class PlayerController(
    private val context: Context,
    private val db: AppDatabase,
    private val fileStore: FileStore,
    private val rootStage: FrameLayout,
    private val layout: LayoutConfig,
    private val scale: Float
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val imageLoader by lazy {
        ImageLoader.Builder(context)
            .components {
                // GIF support across APIs
                if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }.build()
    }

    private data class PlayUnit(
        val type: Kind,
        val uri: Uri,
        val durationMs: Long // for images/gif; videos ignore and use natural duration
    ) {
        enum class Kind { VIDEO, IMAGE }
    }

    fun start() {
        // one loop per region
        layout.regions.sortedBy { it.z ?: 0 }.forEach { region ->
            val regionView = findRegionView(region.id) ?: run {
                Timber.w("[Player] Region container not found: ${region.id}")
                return@forEach
            }
            println(region.toString())
            val playlistId = region.playlistId
            if (playlistId.isNullOrBlank()) {
                Timber.i("[Player] Region ${region.id}: no playlistId -> idle")
                return@forEach
            }
            scope.launch {
                val items = loadUnitsForPlaylist(playlistId)
                if (items.isEmpty()) {
                    Timber.i("[Player] Region ${region.id}: playlist empty or assets missing")
                    return@launch
                }
                Timber.i("[Player] Region ${region.id}: ${items.size} units ready")
                playLoop(regionView, region, items)
            }
        }
    }

    fun pause() {
        // nothing special; Exo will pause when invisible if needed
    }

    fun resume() {
        // nothing special
    }

    fun release() {
        scope.cancel()
        // release any players still attached
        for (i in 0 until rootStage.childCount) {
            (rootStage.getChildAt(i) as? ViewGroup)?.let { vg ->
                for (j in 0 until vg.childCount) {
                    (vg.getChildAt(j) as? PlayerView)?.player?.release()
                }
            }
        }
    }

    // ---------- Internals ----------

    private fun findRegionView(id: String): FrameLayout? {
        // PlayerActivity sets contentDescription = "region_$id"
        for (i in 0 until rootStage.childCount) {
            val v = rootStage.getChildAt(i)
            if (v is FrameLayout && v.contentDescription == "region_$id") return v
        }
        return null
    }

    private suspend fun loadUnitsForPlaylist(playlistId: String): List<PlayUnit> = withContext(Dispatchers.IO) {
        // Load items then resolve each asset; this avoids needing a DAO relation shape to match.
        val items: List<PlaylistItemEntity> =
            db.playlistItems().itemsForPlaylist(playlistId) // ensure your DAO has this query

        val units = ArrayList<PlayUnit>(items.size)
        for (item in items.sortedBy { it.orderIndex }) {
            val asset: AssetEntity = db.assets().findById(item.assetId) ?: continue
            val uri = resolveAssetUri(asset) ?: continue
            val kind = if (isVideo(asset)) PlayUnit.Kind.VIDEO else PlayUnit.Kind.IMAGE
            val durMs = (item.durationSec ?: 10).coerceAtLeast(1) * 1000L
            units.add(PlayUnit(kind, uri, durMs))
        }
        units
    }

    private fun resolveAssetUri(a: AssetEntity): Uri? {
        // prefer downloaded file; else stream from network
        a.localPath?.takeIf { it.isNotBlank() }?.let { return Uri.fromFile(java.io.File(it)) }
        return runCatching { Uri.parse(a.localPath) }.getOrNull()
    }

    private fun isVideo(a: AssetEntity): Boolean {
        val m = (a.mediaType ?: "").lowercase()
        val u = (a.mediaType).lowercase()
        return m.contains("mp4") || m.contains("webm") ||
                u.endsWith(".mp4") || u.endsWith(".webm")
    }

    private suspend fun playLoop(container: FrameLayout, region: LayoutRegion, units: List<PlayUnit>) {
        var idx = 0
        while (scope.isActive) {
            val u = units[idx % units.size]
            when (u.type) {
                PlayUnit.Kind.VIDEO -> playVideoOnce(container, region, u)
                PlayUnit.Kind.IMAGE -> showImageFor(container, region, u)
            }
            idx++
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun playVideoOnce(container: FrameLayout, region: LayoutRegion, unit: PlayUnit) = suspendCancellableCoroutine<Unit> { cont ->
        container.removeAllViews()

        val pv = PlayerView(context).apply {
            useController = false
            resizeMode = region.resizeModeForVideo()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val player = ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(unit.uri))
            prepare()
            playWhenReady = true
        }
        pv.player = player
        container.addView(pv)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.removeListener(this)
                    player.release()
                    container.removeView(pv)
                    if (!cont.isCompleted) cont.resume(Unit) {}
                }
            }
        })

        cont.invokeOnCancellation {
            try { player.release() } catch (_: Throwable) {}
            container.removeView(pv)
        }
    }

    private suspend fun showImageFor(container: FrameLayout, region: LayoutRegion, unit: PlayUnit) {
        container.removeAllViews()

        val iv = ImageView(context).apply {
            scaleType = region.scaleTypeForImage()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(iv)
        iv.load(unit.uri, imageLoader)

        // Wait for duration
        delay(unit.durationMs)

        // Tear down
        container.removeView(iv)
    }

    private fun LayoutRegion.scaleTypeForImage(): ImageView.ScaleType = when (fit) {
        LayoutFit.fill      -> ImageView.ScaleType.FIT_XY
        LayoutFit.contain   -> ImageView.ScaleType.FIT_CENTER
        LayoutFit.fitWidth  -> ImageView.ScaleType.FIT_CENTER
        LayoutFit.fitHeight -> ImageView.ScaleType.FIT_CENTER
        else                -> ImageView.ScaleType.CENTER_CROP // cover
    }

    @OptIn(UnstableApi::class)
    private fun LayoutRegion.resizeModeForVideo(): Int = when (fit) {
        LayoutFit.fill      -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        LayoutFit.contain   -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        LayoutFit.fitWidth  -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        LayoutFit.fitHeight -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        else                -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM // cover
    }


    fun startRegion(regionId: String) {
        val region = layout.regions.find { it.id == regionId } ?: return
        val regionView = run {
            for (i in 0 until rootStage.childCount) {
                val v = rootStage.getChildAt(i)
                if (v is FrameLayout && v.contentDescription == "region_$regionId") return@run v
            }
            null
        } ?: return

        scope.launch {
            val items = loadUnitsForPlaylist(region.playlistId ?: return@launch)
            if (items.isEmpty()) return@launch
            playLoop(regionView, region, items)
        }
    }
}
