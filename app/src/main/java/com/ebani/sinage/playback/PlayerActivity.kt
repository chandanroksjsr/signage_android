// com/ebani/sinage/playback/PlayerActivity.kt
package com.ebani.sinage.playback

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.net.Net
import com.ebani.sinage.net.SocketHub
import com.ebani.sinage.net.ws.*
import com.ebani.sinage.pairing.PairingActivity
import com.ebani.sinage.sync.SyncManager
import com.ebani.sinage.sync.SyncStatus
import com.ebani.sinage.util.FileStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.math.min
import kotlin.random.Random

class PlayerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var controller: PlayerController
    private lateinit var prefs: DevicePrefs

    private val json = Json { ignoreUnknownKeys = true }
    private val regionOverlays = mutableMapOf<String, RegionOverlay>()
    private val regionDownloadJobs = mutableMapOf<String, Job>()

    /** Prevent overlapping refreshes on bursts of content updates */
    private var refreshJob: Job? = null
    private val socketListener = object : SocketHub.Listener {
        override fun onConnected() {
            Timber.i("Socket connected Player")

        }
        override fun onMessage(msg: PairingMessage) {
            println("update msg $msg")
            when (msg) {

                is MsgContentUpdate -> {
                    Timber.i("WS content update: ${msg ?: "no-reason"}")
                    requestContentRefresh( "update")
                }
                is MsgHandshakeOk -> {
                    Timber.i("WS handshake ok, socketId=${msg.socketId}")
                }
                is MsgHandshakeError -> {
                    Timber.w("WS handshake error: ${msg}")
                }
                is MsgPing -> {
                    // no-op
                }
                else -> Timber.d("WS msg ignored: $msg")
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WS
        // Prefer appContext to avoid leaking the Activity
//        SocketHub.start(this, Net.WS_URL)
        SocketHub.addListener(socketListener)

        // 🔑 tell server who we are
//        lifecycleScope.launch {
//            val deviceId = DevicePrefs(this@PlayerActivity).deviceId   // or prefs.deviceIdString
//            try {
//                Timber.i("WS handshake sent for deviceId=$deviceId")
//            } catch (t: Throwable) {
//                Timber.e(t, "Failed to send handshake")
//            }
//        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

        prefs = DevicePrefs(this)

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(stage)
        setContentView(root)

        // Initial sync + build
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SyncManager.syncLayoutOnly(this@PlayerActivity) }
            when (result) {
                is SyncStatus.UNPAIRED -> {
                    Timber.w("Device not paired; opening PairingActivity")
                    startActivity(Intent(this@PlayerActivity, PairingActivity::class.java))
                    finish()
                    return@launch
                }
                is SyncStatus.ERROR -> {
                    Timber.e("Initial sync failed; using cached layout")
                }
                SyncStatus.OK -> Unit
            }
            val layout = loadLayoutOrFallback()
            rebuildFromConfig(layout) // build + start downloads
        }
    }

    override fun onDestroy() {
        SocketHub.removeListener(socketListener)
        regionDownloadJobs.values.forEach { it.cancel() }
        super.onDestroy()
    }

    /* -------------------- Socket listener -------------------- */





    /** Coalesce refreshes; run one at a time. */
    private fun requestContentRefresh(source: String) {
        if (refreshJob?.isActive == true) {
            Timber.i("Refresh already running; skipping extra ($source)")
            return
        }
        refreshJob = lifecycleScope.launch {
            Timber.i("Refreshing content ($source)…")
            val status = withContext(Dispatchers.IO) { SyncManager.syncLayoutOnly(this@PlayerActivity) }
            when (status) {
                is SyncStatus.UNPAIRED -> {
                    Timber.w("Now unpaired; redirecting to PairingActivity")
                    startActivity(Intent(this@PlayerActivity, PairingActivity::class.java))
                    finish()
                    return@launch
                }
                is SyncStatus.ERROR -> {
                    Timber.e("Refresh failed; keeping current playback")
                    return@launch
                }
                SyncStatus.OK -> {
                    Timber.i("Refresh OK; reloading layout")
                    val layout = loadLayoutOrFallback()
                    rebuildFromConfig(layout)
                }
            }
        }
    }

    /* -------------------- Build / Rebuild -------------------- */

    private fun rebuildFromConfig(layout: LayoutConfig) {
        // stop old region downloads
        regionDownloadJobs.values.forEach { it.cancel() }
        regionDownloadJobs.clear()
        regionOverlays.clear()

        // Build the stage (creates controller and overlays again)
        buildStage(layout)

        // Start downloads per region; playback starts after each finishes
        layout.regions.forEach { r ->
            val pid = r.playlistId
            if (!pid.isNullOrBlank()) startRegionDownload(r.id, pid)
        }
    }

    private fun loadLayoutOrFallback(): LayoutConfig {
        val cached = prefs.layoutJson
        if (!cached.isNullOrBlank()) {
            runCatching {
                val dto = json.decodeFromString(com.ebani.sinage.net.LayoutConfigDTO.serializer(), cached)
                return dto.toUi()
            }.onFailure { Timber.w(it, "[Player] parse layout failed; fallback") }
        }
        val w = prefs.screenWidth.takeIf { it > 0 } ?: 1080
        val h = prefs.screenHeight.takeIf { it > 0 } ?: 1920
        return LayoutConfig(
            design = LayoutDesign(w, h, "#000000"),
            regions = listOf(LayoutRegion("full", 0, 0, w, h, 0, LayoutFit.cover, null))
        )
    }

    private fun buildStage(layout: LayoutConfig) {
        root.post {
            val availW = root.width.coerceAtLeast(1)
            val availH = root.height.coerceAtLeast(1)
            val dw = layout.design.width.coerceAtLeast(1)
            val dh = layout.design.height.coerceAtLeast(1)
            val s = min(availW.toFloat() / dw, availH.toFloat() / dh)

            val stageW = (dw * s).toInt()
            val stageH = (dh * s).toInt()
            stage.updateLayoutParams<FrameLayout.LayoutParams> {
                width = stageW; height = stageH
            }
            stage.x = (availW - stageW) / 2f
            stage.y = (availH - stageH) / 2f
            stage.setBackgroundColor(parseColorOr(layout.design.bgColor, Color.BLACK))

            // Regions
            stage.removeAllViews()
            layout.regions.sortedBy { it.z ?: 0 }.forEach { r ->
                val region = FrameLayout(this).apply {
                    contentDescription = "region_${r.id}"
                    elevation = (r.z ?: 0).toFloat()
                    setBackgroundColor(randomNiceColor())
                    layoutParams = FrameLayout.LayoutParams((r.w * s).toInt(), (r.h * s).toInt())
                }
                region.x = r.x * s
                region.y = r.y * s
                stage.addView(region)

                regionOverlays[r.id] = attachOverlay(region)
            }

            // Controller
            controller = PlayerController(
                context = this,
                db = AppDatabase.getInstance(applicationContext),
                fileStore = FileStore(applicationContext),
                rootStage = stage,
                layout = layout,
                scale = s
            )
        }
    }

    private fun startRegionDownload(regionId: String, playlistId: String) {
        regionDownloadJobs[regionId]?.cancel()
        val job = lifecycleScope.launch {
            SyncManager.downloadPlaylist(this@PlayerActivity, playlistId).collectLatest { prog ->
                val overlay = regionOverlays[regionId] ?: return@collectLatest
                overlay.progress.isIndeterminate = false
                overlay.caption.text = "Downloading… ${prog.percent}%"
                overlay.progress.progress = prog.percent
                overlay.root.visibility = if (prog.done) View.GONE else View.VISIBLE
                if (prog.done) {
                    controller.startRegion(regionId)
                    cancel()
                }
            }
        }
        regionDownloadJobs[regionId] = job
    }

    /* -------------------- UI helpers -------------------- */

    private data class RegionOverlay(
        val root: FrameLayout,
        val progress: ProgressBar,
        val caption: TextView
    )

    private fun attachOverlay(region: FrameLayout): RegionOverlay {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.VISIBLE
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            text = "Preparing…"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER }
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 100
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 24
                leftMargin = 24; rightMargin = 24
            }
        }
        overlay.addView(tv)
        overlay.addView(bar)
        region.addView(overlay)
        return RegionOverlay(overlay, bar, tv)
    }

    private fun parseColorOr(s: String?, fallback: Int): Int =
        try { if (s.isNullOrBlank()) fallback else Color.parseColor(s) } catch (_: Throwable) { fallback }

    private fun randomNiceColor(): Int {
        val hue = Random.nextInt(0, 360)
        val sat = 0.35f + Random.nextFloat() * 0.25f
        val valv = 0.35f + Random.nextFloat() * 0.25f
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), sat, valv))
    }
}

/* ====== UI models & mappers (unchanged) ====== */
data class LayoutDesign(val width: Int, val height: Int, val bgColor: String?)
enum class LayoutFit { cover, contain, fill, fitWidth, fitHeight }
data class LayoutRegion(
    val id: String, val x: Int, val y: Int, val w: Int, val h: Int,
    val z: Int? = 0, val fit: LayoutFit? = LayoutFit.cover, val playlistId: String? = null
)
data class LayoutConfig(val design: LayoutDesign, val regions: List<LayoutRegion>)

private fun com.ebani.sinage.net.LayoutConfigDTO.toUi(): LayoutConfig =
    LayoutConfig(
        design = LayoutDesign(design.width, design.height, design.bgColor),
        regions = regions.map { it.toUi() }
    )

private fun com.ebani.sinage.net.LayoutRegionDTO.toUi(): LayoutRegion =
    LayoutRegion(
        id = id, x = x, y = y, w = w, h = h, z = z,
        fit = when (fit) {
            "fill" -> LayoutFit.fill
            "contain" -> LayoutFit.contain
            "fitWidth" -> LayoutFit.fitWidth
            "fitHeight" -> LayoutFit.fitHeight
            else -> LayoutFit.cover
        },
        playlistId = playlistId
    )
