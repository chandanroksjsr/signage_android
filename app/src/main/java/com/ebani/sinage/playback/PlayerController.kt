// com/ebani/sinage/playback/PlayerController.kt
package com.ebani.sinage.playback

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.load
import coil.size.Dimension
import coil.size.Size
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.model.AssetEntity
import com.ebani.sinage.data.model.PlaylistItemEntity
import com.ebani.sinage.util.FileStore
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

@UnstableApi
class PlayerController(
    private val context: Context,
    private val db: AppDatabase,
    @Suppress("unused") private val fileStore: FileStore,
    private val rootStage: FrameLayout,
    private val layout: LayoutConfig,
    private val scale: Float,
    private val maxConcurrentVideoRegions: Int = 1
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val imageLoader by lazy {
        ImageLoader.Builder(context)
            .components { add(ImageDecoderDecoder.Factory()) } // API 28+ (gif/webp)
            .build()
    }

    private data class RegionState(
        val container: FrameLayout,
        val imageView: ImageView,
        val contentFrame: AspectRatioFrameLayout,
        val textureView: SurfaceView,
        var player: ExoPlayer? = null,
        var job: Job? = null,
        var isVideoRegion: Boolean = false,
        var listener: Player.Listener? = null,

        // NEW: for analytics callbacks
        var currentAssetId: String? = null,
        var currentMediaType: String? = null,
        var currentPlaylistId: String? = null
    )

    private fun notifyRegionActive(regionId: String) {
        playbackListener?.onRegionActive(regionId)
    }

    private fun notifyAssetStart(st: RegionState, region: LayoutRegion, assetId: String, mediaType: String) {
        st.currentAssetId = assetId
        st.currentMediaType = mediaType
        st.currentPlaylistId = region.playlistId
        notifyRegionActive(region.id)
        playbackListener?.onAssetStart(region.id, region.playlistId, assetId, mediaType)
    }

    private fun notifyAssetEnd(st: RegionState, region: LayoutRegion) {
        val assetId = st.currentAssetId ?: return
        val mediaType = st.currentMediaType ?: "unknown"
        playbackListener?.onAssetEnd(region.id, st.currentPlaylistId, assetId, mediaType)
        st.currentAssetId = null
        st.currentMediaType = null
    }

    private val regions = ConcurrentHashMap<String, RegionState>()

    /** Post any ExoPlayer call onto its application looper. */
    private inline fun withPlayerLooper(player: ExoPlayer, crossinline block: (ExoPlayer) -> Unit) {
        val appLooper = player.applicationLooper
        if (Looper.myLooper() === appLooper) block(player)
        else android.os.Handler(appLooper).post { block(player) }
    }

    /** Keep decoder & buffers modest (local files) and CPU awake while playing. */
    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 2_000,
                /* maxBufferMs */ 5_000,
                /* bufferForPlaybackMs */ 500,
                /* bufferForPlaybackAfterRebufferMs */ 1_000
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }

    private val videoSlots = Semaphore(maxConcurrentVideoRegions, /*fair*/ true)

    /** Public: pause and clear surfaces to free decoder memory (e.g., onStop). */
    fun pauseAndClearSurfaces() {
        regions.values.forEach { st ->
            st.player?.let { p ->
                withPlayerLooper(p) {
                    try {
                        it.playWhenReady = false
                        it.clearVideoSurfaceView(st.textureView)
                        it.clearVideoSurface()
                    } catch (_: Throwable) {}
                }
            }
        }
    }
    // Add this interface and setter near the class header
    interface PlaybackListener {
        fun onAssetStart(regionId: String, playlistId: String?, assetId: String, mediaType: String)
        fun onAssetEnd(regionId: String, playlistId: String?, assetId: String, mediaType: String)
        fun onRegionActive(regionId: String) { /* optional */ }
    }
    private var playbackListener: PlaybackListener? = null
    fun setPlaybackListener(l: PlaybackListener) { playbackListener = l }


    /** Start playback for a specific region (mixed mode when allowed). */
    fun startRegion(regionId: String) {
        val region = layout.regions.find { it.id == regionId } ?: return
        val st = ensureRegionState(region) ?: return

        // NEW: finalize previous asset if any
        notifyAssetEnd(st, region)

        // Stop previous work …
        st.job?.cancel()
        st.player?.let { p ->
            st.listener?.let { l -> withPlayerLooper(p) { it.removeListener(l) } }
            withPlayerLooper(p) {
                try {
                    it.stop(); it.clearMediaItems(); it.release()
                } catch (_: Throwable) {}
            }
        }
        st.listener = null
        st.player = null

        // NEW: signal the region is now active
        notifyRegionActive(region.id)

        st.job = scope.launch {
            val items = withContext(Dispatchers.IO) {
                loadUnitsForPlaylist(region.playlistId ?: return@withContext emptyList())
            }
            if (items.isEmpty()) {
                st.imageView.visibility = View.INVISIBLE
                st.contentFrame.visibility = View.INVISIBLE
                return@launch
            }

            val hasVideo = items.any { it.kind == Kind.VIDEO }
            if (!hasVideo) {
                playImagesLoop(st, region, items.filter { it.kind == Kind.IMAGE })
                return@launch
            }

            val acquired = videoSlots.tryAcquire()
            if (!acquired) {
                playImagesLoop(st, region, items.filter { it.kind == Kind.IMAGE })
                return@launch
            }

            st.isVideoRegion = true
            try {
                if (st.player == null) st.player = buildPlayer()
                val player = st.player!!
                withPlayerLooper(player) { it.setVideoSurfaceView(st.textureView) }

                var i = 0
                while (isActive && items.isNotEmpty()) {
                    val u = items[i % items.size]
                    when (u.kind) {
                        Kind.IMAGE -> {
                            st.contentFrame.visibility = View.INVISIBLE
                            st.imageView.visibility = View.VISIBLE

                            val targetW = (region.w * scale).toInt().coerceAtLeast(1)
                            val targetH = (region.h * scale).toInt().coerceAtLeast(1)
                            st.imageView.load(u.uri, imageLoader) {
                                size(Size(Dimension.Pixels(targetW), Dimension.Pixels(targetH)))
                                allowRgb565(true); crossfade(false)
                            }

                            // NEW: signal start/end around the image dwell
                            notifyAssetStart(st, region, u.assetId, "image")
                            delay(u.durMs)
                            notifyAssetEnd(st, region)
                        }
                        Kind.VIDEO -> {
                            st.imageView.visibility = View.INVISIBLE
                            st.contentFrame.visibility = View.VISIBLE

                            val playUri = resolvePlayableUri(u.assetId)
                            if (playUri == null) {
                                Timber.w("[Player] missing asset %s, skipping", u.assetId)
                                st.contentFrame.visibility = View.INVISIBLE
                                st.imageView.visibility = View.VISIBLE
                                delay(250)
                                i++
                                continue
                            }

                            // NEW: start/end around video playback
                            notifyAssetStart(st, region, u.assetId, "video")
                            try {
                                playOneVideo(player, playUri) // suspends until ended/error
                            } catch (t: Throwable) {
                                Timber.e(t, "[Player] video error; skipping")
                            } finally {
                                notifyAssetEnd(st, region)
                            }
                        }
                    }
                    i++
                }
            } finally {
                st.isVideoRegion = false
                videoSlots.release()
            }
        }
    }


    /** Mixed loop (not used by startRegion anymore, kept for completeness/tests). */
    private fun playMixedLoop(st: RegionState, region: LayoutRegion, items: List<UnitItem>) {
        st.isVideoRegion = items.any { it.kind == Kind.VIDEO }
        if (st.player == null) st.player = buildPlayer()
        val player = st.player!!
        withPlayerLooper(player) { it.setVideoSurfaceView(st.textureView) }

        st.job?.cancel()
        st.job = scope.launch {
            var i = 0
            while (isActive && items.isNotEmpty()) {
                val u = items[i % items.size]
                when (u.kind) {
                    Kind.IMAGE -> {
                        st.contentFrame.visibility = View.INVISIBLE
                        st.imageView.visibility = View.VISIBLE
                        val targetW = (region.w * scale).toInt().coerceAtLeast(1)
                        val targetH = (region.h * scale).toInt().coerceAtLeast(1)
                        st.imageView.load(u.uri, imageLoader) {
                            size(Size(Dimension.Pixels(targetW), Dimension.Pixels(targetH)))
                            allowRgb565(true)
                            crossfade(false)
                        }
                        delay(u.durMs)
                    }
                    Kind.VIDEO -> {
                        st.imageView.visibility = View.INVISIBLE
                        st.contentFrame.visibility = View.VISIBLE
                        val playUri = resolvePlayableUri(u.assetId)
                        if (playUri == null) {
                            Timber.w("[Player] missing asset %s, skipping", u.assetId)
                            st.contentFrame.visibility = View.INVISIBLE
                            st.imageView.visibility = View.VISIBLE
                            delay(250)
                            i++
                            continue
                        }
                        try {
                            playOneVideo(player, playUri)
                        } catch (t: Throwable) {
                            Timber.e(t, "[Player] video error; skipping")
                            st.contentFrame.visibility = View.INVISIBLE
                            st.imageView.visibility = View.VISIBLE
                            delay(250)
                        }
                    }
                }
                i++
            }
        }
    }

    /** Play a single video and suspend until it ends (or error). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun playOneVideo(player: ExoPlayer, uri: Uri) =
        suspendCancellableCoroutine<Unit> { cont ->
            // 1) Configure player on its looper
            withPlayerLooper(player) { p ->
                try {
                    p.clearMediaItems()
                    p.setMediaItem(MediaItem.fromUri(uri))
                    p.repeatMode = Player.REPEAT_MODE_OFF
                    p.prepare()
                    p.playWhenReady = true
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(Unit) {}
                    return@withPlayerLooper
                }
            }

            // 2) Listener (removes itself on end/error to avoid leaks)
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && cont.isActive) {
                        withPlayerLooper(player) { it.removeListener(this) }
                        cont.resume(Unit) {}
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Swallow ENOENT/FileNotFound gracefully (file disappeared mid-flight)
                    val msg = error.cause?.message.orEmpty()
                    val isNoFile = msg.contains("ENOENT", true) || msg.contains("FileNotFound", true)
                    if (isNoFile) Timber.w("[Player] file missing during playback; skipping")
                    withPlayerLooper(player) { it.removeListener(this) }
                    if (cont.isActive) cont.resume(Unit) {}
                }
            }

            withPlayerLooper(player) { it.addListener(listener) }

            // 3) Cancellation: always hop to player looper and remove listener
            cont.invokeOnCancellation {
                withPlayerLooper(player) { p ->
                    try {
                        p.removeListener(listener)
                        p.stop()
                        p.clearMediaItems()
                    } catch (_: Throwable) {}
                }
            }
        }

    /** Images-only loop (used when cap is hit or playlist has only images). */
    private fun playImagesLoop(st: RegionState, region: LayoutRegion, items: List<UnitItem>) {
        val imgs = items.ifEmpty { return }
        st.isVideoRegion = false
        st.contentFrame.visibility = View.INVISIBLE
        st.imageView.visibility = View.VISIBLE

        val targetW = (region.w * scale).toInt().coerceAtLeast(1)
        val targetH = (region.h * scale).toInt().coerceAtLeast(1)

        st.job?.cancel()
        st.job = scope.launch {
            var i = 0
            while (isActive) {
                val u = imgs[i % imgs.size]
                st.imageView.load(u.uri, imageLoader) {
                    size(Size(Dimension.Pixels(targetW), Dimension.Pixels(targetH)))
                    allowRgb565(true)
                    crossfade(false)
                }
                notifyAssetStart(st, region, u.assetId, "image")
                delay(u.durMs)
                notifyAssetEnd(st, region)
                i++
            }
        }
    }

    /** Release everything; safe to call from any thread. */
    fun release() {


        runCatching { scope.cancel() }

        regions.values.forEach { st ->
            // detach and release the player
            st.player?.let { p ->
                withPlayerLooper(p) {
                    try {
                        it.clearVideoSurfaceView(st.textureView) // ✅ SurfaceView, not TextureView
                        it.stop()
                        it.clearMediaItems()
                        it.release()
                    } catch (_: Throwable) { }
                }
            }
            st.player = null
            st.listener = null

            // free image memory
            st.imageView.setImageDrawable(null)

            // (optional) remove our two overlay views from the region container
            runCatching {
                st.container.removeView(st.contentFrame)
                st.container.removeView(st.imageView)
            }
        }
        regions.clear()

    }
    // optional convenience inside PlayerController
    fun stopAllRegions() {
        regions.values.forEach { st ->
            st.job?.cancel()
            st.job = null
            st.player?.let { p ->
                withPlayerLooper(p) {
                    try {
                        it.playWhenReady = false
                        it.clearVideoSurfaceView(st.textureView)
                        it.clearVideoSurface()
                        it.stop()
                        it.clearMediaItems()
                    } catch (_: Throwable) {}
                }
            }
            st.imageView.setImageDrawable(null)
            st.contentFrame.visibility = View.INVISIBLE
            st.imageView.visibility = View.INVISIBLE
        }
    }

    private fun doRelease() {
        // Cancel region coroutines first (their cancel handlers post to player looper)
        runCatching { scope.cancel() }

        // Remove listeners & release players on the correct looper
        regions.values.forEach { st ->
            st.player?.let { p ->
                withPlayerLooper(p) {
                    try {
                        st.listener?.let { l -> it.removeListener(l) }
                        it.clearVideoSurfaceView(st.textureView)
                        it.stop()
                        it.clearMediaItems()
                        it.release()
                    } catch (_: Throwable) { /* ignore */ }
                }
            }
            st.listener = null
            st.player = null
        }
    }

    // ---------- internals ----------

    private fun findRegionView(id: String): FrameLayout? {
        for (i in 0 until rootStage.childCount) {
            val v = rootStage.getChildAt(i)
            if (v is FrameLayout && v.contentDescription == "region_$id") return v
        }
        return null
    }

    @UnstableApi
    private fun ensureRegionState(region: LayoutRegion): RegionState? {
        regions[region.id]?.let { return it }
        val container = findRegionView(region.id) ?: return null
        container.keepScreenOn = true

        // --- Video layer: SurfaceView inside AspectRatioFrameLayout
        val contentFrame = AspectRatioFrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            resizeMode = when (region.fit) {
                LayoutFit.fill      -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                LayoutFit.contain   -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                LayoutFit.fitWidth  -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                LayoutFit.fitHeight -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                else                -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE
        }
        val textureView = SurfaceView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        contentFrame.addView(textureView)

        // --- Image layer (on top)
        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = when (region.fit) {
                LayoutFit.fill      -> ImageView.ScaleType.FIT_XY
                LayoutFit.contain   -> ImageView.ScaleType.FIT_CENTER
                LayoutFit.fitWidth  -> ImageView.ScaleType.FIT_CENTER
                LayoutFit.fitHeight -> ImageView.ScaleType.FIT_CENTER
                else                -> ImageView.ScaleType.CENTER_CROP
            }
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE
        }

        // keep video below images
        container.addView(contentFrame) // video
        container.addView(imageView)    // image

        return RegionState(
            container = container,
            imageView = imageView,
            contentFrame = contentFrame,
            textureView = textureView
        ).also { regions[region.id] = it }
    }

    private enum class Kind { VIDEO, IMAGE }
    private data class UnitItem(val kind: Kind, val assetId: String, val uri: Uri, val durMs: Long)

    private fun isVideo(a: AssetEntity): Boolean {
        val mt = (a.mediaType ?: "").lowercase()
        val lp = (a.localPath ?: "").lowercase()
        return mt.contains("video") || lp.endsWith(".mp4") || lp.endsWith(".webm") || lp.endsWith(".m4v")
    }

    /** Prefer local file; (optional) fall back to remote URL if you want streaming. */
    private suspend fun resolvePlayableUri(assetId: String): Uri? {
        val a = withContext(Dispatchers.IO) { db.assets().findById(assetId) } ?: return null
        a.localPath?.takeIf { File(it).isFile }?.let { return Uri.fromFile(File(it)) }
        // If you do NOT want to stream, return null here.
        return a.remoteUrl?.let { Uri.parse(it) }
    }

    /** Build play units from DB; includes only items with a local file to avoid ENOENT. */
    private fun loadUnitsForPlaylist(playlistId: String): List<UnitItem> {
        val items: List<PlaylistItemEntity> = db.playlistItems().itemsForPlaylist(playlistId)
        val out = ArrayList<UnitItem>(items.size)
        for (item in items.sortedBy { it.orderIndex }) {
            val asset = db.assets().findById(item.assetId) ?: continue
            val local = asset.localPath
            val file = local?.let { File(it) }
            if (file == null || !file.isFile) {
                // Skip now; startRegion will re-check before play & may stream if allowed
                continue
            }
            val kind = if (isVideo(asset)) Kind.VIDEO else Kind.IMAGE
            val durMs = (item.durationSec.coerceAtLeast(1)) * 1000L
            out.add(UnitItem(kind, asset.id, Uri.fromFile(file), durMs))
        }
        return out
    }

    /** (Legacy helper; no longer used for gating—semaphore handles this atomically.) */
    @Suppress("unused")
    private fun canActivateAnotherVideo(requestingRegionId: String): Boolean {
        val active = regions.filterValues { it.isVideoRegion }.keys
        if (requestingRegionId in active) return true
        return active.size < maxConcurrentVideoRegions
    }
}
