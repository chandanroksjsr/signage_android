
package com.ebani.sinage.playback

//noinspection SuspiciousImport
import android.R
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.net.LayoutConfigDTO
import com.ebani.sinage.net.LayoutRegionDTO
import com.ebani.sinage.net.SocketHub
import com.ebani.sinage.net.SocketHub.emitHandshakeRegistered
import com.ebani.sinage.net.SocketHub.startHeartbeat
import com.ebani.sinage.net.SocketHub.stopheartBeat
import com.ebani.sinage.net.ws.*
import com.ebani.sinage.pairing.PairingActivity
import com.ebani.sinage.sync.SyncManager
import com.ebani.sinage.sync.SyncStatus
import com.ebani.sinage.util.Apputils
import com.ebani.sinage.util.DeviceInfoUtils
import com.ebani.sinage.util.DeviceState
import com.ebani.sinage.util.FileStore
import com.ebani.sinage.util.MemoryCleaner
import com.ebani.sinage.util.PlayerBus
import com.ebani.sinage.util.evaluateDeviceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.min

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var controller: PlayerController
    private lateinit var prefs: DevicePrefs

    // ── socket status dot ────────────────────────────────────────────────────────
    private lateinit var socketIndicator: View

    private val json = Json { ignoreUnknownKeys = true }
    private val regionOverlays = mutableMapOf<String, RegionOverlay>()
    private val regionDownloadJobs = mutableMapOf<String, Job>()

    /** Prevent overlapping refreshes on bursts of content updates */
    private var refreshJob: Job? = null

    private val socketListener = object : SocketHub.Listener {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onConnected() {
            Timber.i("Socket connected (PlayerActivity)")
            setSocketIndicator(true)
            checkWithServerForPair()
        }

        override fun onDisconnected() {
            Timber.w("Socket disconnected (PlayerActivity)")
            setSocketIndicator(false)
        }

        @RequiresApi(Build.VERSION_CODES.R)
        override fun onReconnect() {
            Timber.w("Socket reconnected (PlayerActivity)")
            setSocketIndicator(true)
            checkWithServerForPair()
        }

        override fun onMessage(msg: PairingMessage) {
            when (msg) {
                is MsgContentUpdate -> {
                    Timber.i("WS content update -> refresh")
                    runOnUiThread { requestContentRefresh("socket:update") }
                }
                is MsgHandshakeOk -> {
                    Timber.i("WS handshake ok, socketId=${msg.socketId}")
                    setSocketIndicator(true)
                }
                is MsgHandshakeError -> {
                    Timber.w("WS handshake error: $msg")
                    setSocketIndicator(false)
                }
                is MsgPing -> Unit
                else -> Timber.d("WS msg ignored: $msg")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Optional hard restart (avoid using on Android 14+ due to BAL restrictions)
    // ────────────────────────────────────────────────────────────────────────────
    private fun restartAppHard() {
        try { regionDownloadJobs.values.forEach { it.cancel() } } catch (_: Throwable) {}
        regionDownloadJobs.clear()
        runCatching { if (::controller.isInitialized) controller.release() }

        val ctx = applicationContext
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return

        val pi = PendingIntent.getActivity(
            ctx, 0, launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setExact(AlarmManager.RTC, System.currentTimeMillis() + 300L, pi)

        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    // ────────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    fun checkWithServerForPair() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { SyncManager.syncLayoutOnly(this@PlayerActivity) }
            when (status) {
                is SyncStatus.UNPAIRED -> {
                    stopheartBeat()
                    Timber.w("Device not paired; opening PairingActivity")
                    startActivity(Intent(this@PlayerActivity, PairingActivity::class.java))
                    finish()
                    return@launch
                }
                is SyncStatus.ERROR -> {
                    Timber.e("Initial sync failed; will use cached state")
                }
                is SyncStatus.NOSYNC -> {
                    Timber.i("Initial: no sync needed")
                }
                SyncStatus.OK -> Timber.i("Initial sync OK")
            }

            // Try sending handshake (if device row exists it will include IDs)
            emitRegistredHandshake(applicationContext)

            // Zero-touch: choose the correct UI based on current state
          driveUiNoTouch()
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Memory purge (kept from your code)
    // ────────────────────────────────────────────────────────────────────────────
    private fun purgeRamNow() {
        val release = {
            runCatching { regionDownloadJobs.values.forEach { it.cancel() } }
            regionDownloadJobs.clear()
            if (::controller.isInitialized) {
                controller.pauseAndClearSurfaces()
                controller.release()
            }
        }
        val extra = {
            regionOverlays.clear()
        }
        MemoryCleaner.purgeAppMemory(this, release, extra)
    }

    // ────────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        lifecycleScope.launchWhenStarted {
            PlayerBus.commands.collect { cmd ->
                when (cmd) {
                    is PlayerBus.Command.CheckWithServerForPair -> checkWithServerForPair()
                    is PlayerBus.Command.EmitRegisteredShake   -> emitRegistredHandshake(applicationContext)
                }
            }
        }

        SocketHub.addListener(socketListener)
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

        // Socket indicator (bottom-right)
        socketIndicator = View(this).apply {
            background = makeCircleDrawable(Color.RED)
            val size = dp(14)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                val m = dp(12)
                setMargins(m, m, m, m)
            }
            elevation = dp(2).toFloat()
            contentDescription = "socket_indicator"
            alpha = 1f
            visibility = View.VISIBLE
        }
        root.addView(socketIndicator)

        setContentView(root)

        // Initialize indicator from current hub state if available
        runCatching {
            val connected = SocketHub.isConnected()
            setSocketIndicator(connected)
        }.onFailure { setSocketIndicator(false) }

        // Initial sync + UI decide
        checkWithServerForPair()
    }

    @UnstableApi
    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        SocketHub.removeListener(socketListener)
        regionDownloadJobs.values.forEach { it.cancel() }
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun emitRegistredHandshake(ctx: Context) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val appCtx = ctx
                val device = AppDatabase.getInstance(appCtx).device().getDeviceConfig()
                if (device != null) {
                    val deviceInfo = DeviceInfoUtils.collect(appCtx)
                    val payload: JSONObject = JSONObject()
                        .put("deviceInfo", deviceInfo)
                        .put("deviceData", device)
                    emitHandshakeRegistered(device, info = payload)
                    startHeartbeat(device.deviceId, device.adminUserId)
                }
            }
        }
    }

    // ── Overlays ────────────────────────────────────────────────────────────────

    private var refreshingBanner: TextView? = null
    private fun showRefreshingOverlay(show: Boolean) {
        if (show) {
            if (refreshingBanner == null) {
                refreshingBanner = TextView(this).apply {
                    text = "Refreshing content…"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setBackgroundColor(0x88000000.toInt())
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                root.addView(refreshingBanner)
            }
            refreshingBanner?.visibility = View.VISIBLE
        } else {
            refreshingBanner?.visibility = View.GONE
        }
    }

    private var statusBanner: TextView? = null
    private suspend fun showStatusOverlay(message: String,autohide: Boolean) {
        if (statusBanner == null) {
            statusBanner = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                setBackgroundColor(0x88000000.toInt())
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            root.addView(statusBanner)
        }
        statusBanner?.text = message
        statusBanner?.visibility = View.VISIBLE
        if(autohide){
            delay(3000)
            hideStatusOverlay()
        }

    }
    private fun hideStatusOverlay() { statusBanner?.visibility = View.GONE }

    /** Coalesce refreshes; run one at a time. */
    fun requestContentRefresh(source: String) {
        if (refreshJob?.isActive == true) {
            Timber.i("Refresh already running; skip ($source)")
            return
        }

        // Stop any ongoing downloads BEFORE sync
        try {
            regionDownloadJobs.values.forEach { it.cancel() }
            regionDownloadJobs.clear()
        } catch (_: Throwable) { /* no-op */ }

        // Optional overlay
        showRefreshingOverlay(true)

        refreshJob = lifecycleScope.launch {
            Timber.i("Refreshing content ($source)…")
            val status = withContext(Dispatchers.IO) { SyncManager.syncLayoutOnly(this@PlayerActivity) }
            when (status) {
                is SyncStatus.UNPAIRED -> {
                    Timber.w("Now unpaired; redirecting to PairingActivity")
                    showRefreshingOverlay(false)
                    startActivity(Intent(this@PlayerActivity, PairingActivity::class.java))
                    finish()
                }
                is SyncStatus.ERROR -> {
                    Timber.e("Refresh failed; keeping current UI")
                    showRefreshingOverlay(false)
                    driveUiNoTouch()
                }
                is SyncStatus.NOSYNC -> {
                    Timber.i("No sync needed")
                    showRefreshingOverlay(false)
                    driveUiNoTouch()
                }
                SyncStatus.OK -> {
                    Timber.i("Refresh OK")
                    showRefreshingOverlay(false)
                    driveUiNoTouch()
                }
            }
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    private fun restartActivitySoft() {
        runCatching { regionDownloadJobs.values.forEach { it.cancel() } }
        regionDownloadJobs.clear()
        runCatching { if (::controller.isInitialized) controller.release() }
        runCatching { stopheartBeat() }

        val i = intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        finish()
        overridePendingTransition(0, 0)
        startActivity(i)
        overridePendingTransition(0, 0)
    }

    // ── Decision driver: zero-touch UI ──────────────────────────────────────────
    private suspend fun driveUiNoTouch() {
        when (val state = evaluateDeviceState(this)) {
            DeviceState.UnpairedOffline -> {
                showStatusOverlay("Not paired.\nConnect to internet to pair automatically.",false)
            }
            DeviceState.UnpairedOnline -> {
                startActivity(Intent(this, PairingActivity::class.java))
                finish()
            }
            DeviceState.PairedOnlineNoContent -> {
                showStatusOverlay("Awaiting content from server…",false)
                val layout = loadLayoutOrFallback()
                rebuildFromConfig(layout)
            }
            DeviceState.PairedOnlinePreparing -> {
                hideStatusOverlay()
                val layout = loadLayoutOrFallback()
                rebuildFromConfig(layout)
            }
            DeviceState.PairedDegradedPartial -> {
                showStatusOverlay("Preparing content… Missing items will appear when ready.",true)
                val layout = loadLayoutOrFallback()
                rebuildFromConfig(layout)
            }
            DeviceState.PairedOnlineReady -> {
                hideStatusOverlay()
                val layout = loadLayoutOrFallback()
                rebuildFromConfig(layout)
            }
            DeviceState.PairedOfflineReady -> {
                showStatusOverlay("Offline • Playing cached media",true)
                val layout = loadLayoutOrFallback()
                rebuildFromConfig(layout)
            }
            DeviceState.PairedOfflineNoCache -> {
                showStatusOverlay("Offline • No cached content.\nConnect to internet to download.",false)
            }
        }
    }

    // ── Layout/build helpers ────────────────────────────────────────────────────

    private fun loadLayoutOrFallback(): LayoutConfig {
        val cached = prefs.layoutJson
        if (!cached.isNullOrBlank()) {
            runCatching {
                val dto = json.decodeFromString(LayoutConfigDTO.serializer(), cached)
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

    // Rebuild flow: (downloads are restarted; controller is (re)created)
    @OptIn(UnstableApi::class)
    private fun rebuildFromConfig(layout: LayoutConfig) {
        // stop old region downloads (UI overlays will be rebuilt)
        regionDownloadJobs.values.forEach { it.cancel() }
        regionDownloadJobs.clear()

        // (Optional) release controller BEFORE view touch to avoid texture flicker
        // if (::controller.isInitialized) controller.release()

        buildStage(layout) {
            controller = PlayerController(
                context = this,
                db = AppDatabase.getInstance(applicationContext),
                fileStore = FileStore(applicationContext),
                rootStage = stage,
                layout = layout,
                scale = computeScale(layout),
                maxConcurrentVideoRegions = 5
            )

            // Start downloads per region; playback starts after each finishes
            layout.regions.forEach { r ->
                val pid = r.playlistId
                if (!pid.isNullOrBlank()) startRegionDownload(r.id, pid)
            }
        }
    }

    private fun computeScale(layout: LayoutConfig): Float {
        val availW = root.width.coerceAtLeast(1)
        val availH = root.height.coerceAtLeast(1)
        val dw = layout.design.width.coerceAtLeast(1)
        val dh = layout.design.height.coerceAtLeast(1)
        return min(availW.toFloat() / dw, availH.toFloat() / dh)
    }

    private fun buildStage(layout: LayoutConfig, onBuilt: () -> Unit) {
        root.post {
            val s = computeScale(layout)
            val stageW = (layout.design.width * s).toInt()
            val stageH = (layout.design.height * s).toInt()
            stage.updateLayoutParams<FrameLayout.LayoutParams> {
                width = stageW; height = stageH
            }
            stage.x = (root.width - stageW) / 2f
            stage.y = (root.height - stageH) / 2f
            stage.setBackgroundColor(parseColorOr(layout.design.bgColor, Color.BLACK))

            // Regions
            stage.removeAllViews()
            regionOverlays.clear()
            layout.regions.sortedBy { it.z ?: 0 }.forEach { r ->
                val region = FrameLayout(this).apply {
                    contentDescription = "region_${r.id}"
                    setBackgroundColor(Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams((r.w * s).toInt(), (r.h * s).toInt())
                }
                region.x = r.x * s
                region.y = r.y * s
                stage.addView(region)
                regionOverlays[r.id] = attachOverlay(region)
            }

            onBuilt()
        }
    }

    private fun startRegionDownload(regionId: String, playlistId: String) {
        regionDownloadJobs[regionId]?.cancel()
        val job = lifecycleScope.launch {
            SyncManager.downloadPlaylist(this@PlayerActivity, playlistId).collectLatest { prog ->
                val overlay = regionOverlays[regionId] ?: return@collectLatest

                overlay.progress.isIndeterminate = false
                overlay.progress.max = 100
                overlay.progress.progress = prog.percent

                val speedStr = Apputils.formatRate(prog.rateBytesPerSec)
                val etaStr   = Apputils.formatEta(prog.etaMillis)
                val bytesStr = when {
                    prog.totalBytes > 0L ->
                        "${Apputils.formatBytes(prog.bytesDownloaded)} / ${Apputils.formatBytes(prog.totalBytes)}"
                    prog.currentTotal > 0L ->
                        "${Apputils.formatBytes(prog.currentRead)} / ${Apputils.formatBytes(prog.currentTotal)}"
                    else ->
                        Apputils.formatBytes(prog.bytesDownloaded)
                }
                val countStr = if (prog.totalCount > 0) " (${prog.finishedCount}/${prog.totalCount})" else ""

                overlay.caption.text =
                    "Downloading… ${prog.percent}% \n• $bytesStr \n• $speedStr \n• ETA $etaStr$countStr"

                if (prog.done) {
                    overlay.root.visibility = View.GONE
                    controller.startRegion(regionId)
                    cancel()
                } else {
                    overlay.root.visibility = View.VISIBLE
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
            setBackgroundColor(0x66000000) // only visible during download, then GONE
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
        val bar = ProgressBar(this, null, R.attr.progressBarStyleHorizontal).apply {
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

    // ── Socket indicator ────────────────────────────────────────────────────────
    fun setSocketIndicator(connected: Boolean) {
        val color = if (connected) Color.parseColor("#26C281") else Color.RED
        if (::socketIndicator.isInitialized) {
            socketIndicator.post {
                socketIndicator.background = makeCircleDrawable(color)
                socketIndicator.visibility = View.VISIBLE
                socketIndicator.alpha = 1f
            }
        }
    }

    private fun makeCircleDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/* ====== UI models & mappers (unchanged) ====== */
data class LayoutDesign(val width: Int, val height: Int, val bgColor: String?)
enum class LayoutFit { cover, contain, fill, fitWidth, fitHeight }
data class LayoutRegion(
    val id: String, val x: Int, val y: Int, val w: Int, val h: Int,
    val z: Int? = 0, val fit: LayoutFit? = LayoutFit.cover, val playlistId: String? = null
)
data class LayoutConfig(val design: LayoutDesign, val regions: List<LayoutRegion>)

private fun LayoutConfigDTO.toUi(): LayoutConfig =
    LayoutConfig(
        design = LayoutDesign(design.width, design.height, design.bgColor),
        regions = regions.map { it.toUi() }
    )

private fun LayoutRegionDTO.toUi(): LayoutRegion =
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
