
package com.ebani.sinage.playback

//noinspection SuspiciousImport
import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.hotspot.NsdAdvertiser
import com.ebani.sinage.hotspot.LocalWebServer
import com.ebani.sinage.hotspot.SetupHostService
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.min
import androidx.core.graphics.toColorInt
import com.ebani.sinage.ai.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import androidx.camera.view.PreviewView
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.exitProcess

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
    @Volatile private var buildNonce = 0L


    //local server vars
    private var tapCount = 0
    private var lastTapAt = 0L

    // Admin portal state
    private var adminPortalEnabled = false
    private var webServer: LocalWebServer? = null
    private var nsdAdvertiser: NsdAdvertiser? = null
    private lateinit var serverIndicator: View

    private lateinit var statusStrip: LinearLayout
    private lateinit var socketIcon: ImageView
    private lateinit var hotspotIcon: ImageView

    private var cameraExecutor: ExecutorService? = null
    private var cameraBound = false
    private lateinit var faceDetector: FaceDetector
    private lateinit var ageGenderEmotion: AgeGenderEmotionDetector
    private lateinit var faceEmbedder: FaceEmbedder
    private val identityTracker = IdentityTracker()
    private lateinit var analytics: AnalyticsManager

    // last active region (for attribution if you don’t do geometry mapping)
    @Volatile private var lastActiveRegionId: String? = null

    // camera preview
    private var camPreviewBox: FrameLayout? = null
    private lateinit var previewView: PreviewView
    private var preview: Preview? = null

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
        Process.killProcess(Process.myPid())
        exitProcess(0)
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

    // local server
    private fun toggleAdminPortal() {
        if (adminPortalEnabled) stopAdminPortal() else startAdminPortal()
        setServerIndicator(adminPortalEnabled)

    }
    // Hotspot/QR
    private var hotspotRunning = false
    private var hotspotClientSeen = false
    private var hotspotSsid: String? = null
    private var hotspotPass: String? = null
    private var qrOverlay: FrameLayout? = null
    private var qrImage: ImageView? = null
    private var qrText: TextView? = null
    private fun showOrUpdateQr(ssid: String?, pass: String?, port1: Int) {
        val safeSsid = ssid?.takeIf { it.isNotBlank() } ?: "Sinage-Setup"
        val safePass = pass?.takeIf { it.isNotBlank() } ?: "(none)"
        val url = "${SetupHostService.guessApAddress()}:$port1/"

        if (qrOverlay == null) {
            // full-screen host (transparent) so we can anchor a small card bottom-left
            qrOverlay = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // transparent so it doesn't dim the player
                setBackgroundColor(Color.TRANSPARENT)
                // tap anywhere on the small card to hide (optional)
                isClickable = false
            }

            // small card anchored bottom-left
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xCC111111.toInt()) // semi-opaque dark
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    val m = dp(12)
                    setMargins(m, m, m, m)
                }
                isClickable = true
                setOnClickListener { hideQr() } // remove if you don't want dismiss-on-tap
            }

            qrImage = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(160), dp(160))
                // keep crisp edges
                scaleType = ImageView.ScaleType.FIT_XY
            }
            qrText = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setLineSpacing(0f, 1.15f)
                // a little spacing from the QR
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(8)
                layoutParams = lp
            }

            card.addView(qrImage)
            card.addView(qrText)
            qrOverlay!!.addView(card)
            root.addView(qrOverlay)
        }

        // Update text (below QR)
        qrText?.text = "SSID: $safeSsid\nPW: $safePass\nURL: $url"

        // Generate/refresh QR
        // Encodes Wi-Fi credentials and the URL. Works for both iOS/Android scanners.
        val wifiPayload = "WIFI:S:$safeSsid;T:WPA;P:${if (safePass == "(none)") "" else safePass};;"
        val img = makeQr("$wifiPayload\n$url", dp(160), dp(160))
        qrImage?.setImageBitmap(img)

        qrOverlay?.visibility = View.VISIBLE
    }

    private fun hideQr() {
        qrOverlay?.visibility = View.GONE
    }

    private fun makeQr(text: String, size: Int, dp: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun attachCameraPreviewBox() {
        if (camPreviewBox != null) return

        camPreviewBox = FrameLayout(this).apply {
            val w = dp(160); val h = dp(120) // tweak size if you want
            layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = Gravity.TOP or Gravity.END
                val m = dp(12)
                setMargins(m, dp(40), m, m) // small top offset
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xCC000000.toInt()) // translucent black
                setStroke(dp(1), 0x33FFFFFF) // subtle border
            }
            clipToOutline = true
            elevation = dp(6).toFloat()
            isClickable = true
            contentDescription = "camera_preview"
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        camPreviewBox!!.addView(previewView)
        root.addView(camPreviewBox)
    }

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                SetupHostService.ACTION_STATE -> {


                    val running = intent.getBooleanExtra(SetupHostService.EXTRA_RUNNING, false)
                    println("States: $running")
                    if (running) {
                        val ssid = intent.getStringExtra(SetupHostService.EXTRA_SSID).orEmpty()
                        val pass = intent.getStringExtra(SetupHostService.EXTRA_PASS).orEmpty()
                        val port = intent.getIntExtra(SetupHostService.EXTRA_PORT, 8080).coerceAtLeast(1)
                        showOrUpdateQr(ssid, pass, port)
                    } else {
                        hideQr()
                    }
                }
                SetupHostService.ACTION_CLIENT_JOINED -> {
                    // any client touched our server → hide QR
                    hideQr()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()

        val filter = IntentFilter().apply {
            addAction(SetupHostService.ACTION_STATE)
            addAction(SetupHostService.ACTION_CLIENT_JOINED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ requires an explicit exported/not-exported flag for non-system broadcasts
            registerReceiver(hotspotReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(hotspotReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(hotspotReceiver) }
        super.onStop()
    }
    private fun startAdminPortal() {
        if (adminPortalEnabled) return
        try {
            ensurePermsAndStart()

            adminPortalEnabled = true
            Toast.makeText(this, "Admin portal ON (Hotspot + Web)", Toast.LENGTH_SHORT).show()
            // Optional UI hint
            lifecycleScope.launch { showStatusOverlay("Admin portal enabled.\nConnect and open http://192.168.49.1:8080", true) }
        } catch (t: Throwable) {
            Timber.e(t, "Failed to start admin portal")
            Toast.makeText(this, "Failed to start portal: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAdminPortal() {
        try {
            nsdAdvertiser?.unregister()
        } catch (_: Throwable) { }
        nsdAdvertiser = null

        try {
            webServer?.stop()
        } catch (_: Throwable) { }
        webServer = null

        try {
            SetupHostService.stop(this)
        } catch (_: Throwable) { }

        adminPortalEnabled = false
        Toast.makeText(this, "Admin portal OFF", Toast.LENGTH_SHORT).show()
    }

    fun setServerIndicator(running: Boolean) {
        hotspotIcon.post {
            hotspotIcon.visibility = if (running) View.VISIBLE else View.GONE
            hotspotIcon.alpha = if (running) 1f else 0f
        }
    }
    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // After user response, try starting
        SetupHostService.start(this)
    }
    //-------------------------------------------------------------------------------------
    private fun ensurePermsAndStart() {
        val wants = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
//        if (Build.VERSION.SDK_INT >= 33) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wants += Manifest.permission.NEARBY_WIFI_DEVICES
            wants += Manifest.permission.POST_NOTIFICATIONS
        }

            wants += Manifest.permission.ACCESS_FINE_LOCATION
        wants += Manifest.permission.ACCESS_WIFI_STATE
        wants += Manifest.permission.CAMERA
//        }
        val missing = wants.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
        else SetupHostService.start(this)
    }

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



        root.isClickable = true
        root.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            tapCount = if (now - lastTapAt < 600) tapCount + 1 else 1
            lastTapAt = now

            if (tapCount >= 5) {
                tapCount = 0
                toggleAdminPortal()
            }
        }
        serverIndicator = View(this).apply {
            background = makeCircleDrawable(Color.parseColor("#60A5FA")) // blue-ish
            val size = dp(14)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                val m = dp(12)
                setMargins(m, m, m, m)
            }
            elevation = dp(2).toFloat()
            contentDescription = "server_indicator"
            alpha = 1f
            visibility = View.GONE   // hidden until server mode is enabled
        }
        root.addView(serverIndicator)









        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(stage)

        // Socket indicator (bottom-right)
        // Bottom-right tiny status strip (hotspot + cloud)
        statusStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                val m = dp(12); setMargins(m, m, m, m)
            }
        }

        hotspotIcon = ImageView(this).apply {
            // hidden until hotspot/server is ON
            setImageResource(com.ebani.sinage.R.drawable.ic_hotspot)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B")) // amber
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                rightMargin = dp(8)
            }
            visibility = View.GONE
            alpha = 0f
        }

        socketIcon = ImageView(this).apply {
            // default: offline
            setImageResource(com.ebani.sinage.R.drawable.ic_cloud_off)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444")) // red
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }

        statusStrip.addView(hotspotIcon)
        statusStrip.addView(socketIcon)
        root.addView(statusStrip)

        setContentView(root)
        attachCameraPreviewBox()

        analytics = AnalyticsManager(AppDatabase.getInstance(applicationContext))
        analytics.start()

        ageGenderEmotion = AgeGenderEmotionDetector(this)
        faceEmbedder = TFLiteFaceEmbedder(this)

        // ML Kit face detector (fast, with head pose + eyes)
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eye open probs
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .enableTracking() // ML Kit ID (not used for identityTracker, but ok)
                .build()
        )

        // Start vision (camera) once UI is up
        ensureCameraPermissionThen { startVision() }


        // Initialize indicator from current hub state if available
        runCatching {
            val connected = SocketHub.isConnected()
            setSocketIndicator(connected)
        }.onFailure { setSocketIndicator(false) }

        // Initial sync + UI decide
        checkWithServerForPair()
    }
    private fun ensureCameraPermissionThen(onOk: () -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) { onOk(); return }
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) onOk() else Toast.makeText(this, "Camera permission required for analytics", Toast.LENGTH_LONG).show()
        }.launch(Manifest.permission.CAMERA)
    }

    private fun startVision() {
        if (cameraBound) return
        cameraExecutor = Executors.newSingleThreadExecutor()

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            // ---- Analysis ----
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)
                .build()
            analysis.setAnalyzer(cameraExecutor!!) { proxy -> analyzeFrame(proxy) }

            // ---- Preview ----
            preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                provider.unbindAll()
                // bind BOTH preview and analysis
                provider.bindToLifecycle(this, selector, preview, analysis)
                cameraBound = true
                camPreviewBox?.visibility = View.VISIBLE
            } catch (t: Throwable) {
                Timber.e(t, "CameraX bind failed")
                camPreviewBox?.visibility = View.GONE
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            // RGBA buffer → Bitmap
            val bmp = rgbaProxyToBitmap(imageProxy) ?: run { imageProxy.close(); return }

            // Run ML Kit face detection on the SAME bitmap (rotation = 0)
            val input = InputImage.fromBitmap(bmp, 0)
            faceDetector.process(input)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        imageProxy.close()
                        return@addOnSuccessListener
                    }
                    val now = System.currentTimeMillis()

                    // Build DetFace list
                    val dets = ArrayList<DetFace>(faces.size)
                    val attribsForWindow = ArrayList<FaceAttrib>(faces.size)

                    for (f in faces) {
                        val crop = cropFace(bmp, f.boundingBox) ?: continue

                        // A/G/E (+ gaze optional via ML Kit angles)
                        val attrib = ageGenderEmotion.inferOnCroppedFace(crop)
                        attribsForWindow += attrib

                        // Embedding for identity
                        val emb = faceEmbedder.embed(crop)

                        // bbox to RectF in the camera frame coords
                        val rectF = RectF(
                            f.boundingBox.left.toFloat(),
                            f.boundingBox.top.toFloat(),
                            f.boundingBox.right.toFloat(),
                            f.boundingBox.bottom.toFloat()
                        )
                        dets += DetFace(rectF, emb, attrib)
                    }

                    // Update analytics window counts (A/G/E)
                    val ridForCounts = lastActiveRegionId ?: "full"
                    analytics.onDetections(ridForCounts, attribsForWindow)

                    // Identity tracking → uniques + dwell
                    val tracked = identityTracker.update(now, dets)

                    // Attribute each face to a region (simple: last active region)
                    analytics.onTracked(
                        now,
                        { _ -> lastActiveRegionId ?: "full" },
                        tracked
                    )
                }
                .addOnFailureListener { Timber.w(it, "Face detection failed") }
                .addOnCompleteListener { imageProxy.close() }
        } catch (t: Throwable) {
            Timber.e(t, "analyzeFrame error")
            imageProxy.close()
        }
    }
    private fun rgbaProxyToBitmap(proxy: ImageProxy): Bitmap? {
        if (proxy.format != ImageFormat.UNKNOWN &&
            proxy.format != ImageFormat.YUV_420_888 &&
            proxy.format != ImageFormat.PRIVATE) {
            // We asked CameraX for RGBA_8888; planes[0] contains contiguous RGBA
        }
        val plane = proxy.planes.firstOrNull() ?: return null
        val w = proxy.width; val h = proxy.height
        val buf = plane.buffer
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    private fun cropFace(src: Bitmap, r: Rect): Bitmap? {
        val left = r.left.coerceAtLeast(0)
        val top = r.top.coerceAtLeast(0)
        val right = r.right.coerceAtMost(src.width)
        val bottom = r.bottom.coerceAtMost(src.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }


    @UnstableApi
    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        SocketHub.removeListener(socketListener)
        regionDownloadJobs.values.forEach { it.cancel() }
        if (::controller.isInitialized) controller.release()


        runCatching { faceDetector.close() }
        runCatching { ageGenderEmotion.close() }
        runCatching { faceEmbedder.close() }
        analytics.stop()
        cameraBound = false
        cameraExecutor?.shutdown()
        cameraExecutor = null


        if (adminPortalEnabled) stopAdminPortal()
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
                    startHeartbeat(ctx,device.deviceId, device.adminUserId)
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
                    try { if (::controller.isInitialized) controller.pauseAndClearSurfaces() } catch (_: Throwable) {}
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
// in rebuildFromConfig(layout: LayoutConfig)
    @OptIn(UnstableApi::class)
    private fun rebuildFromConfig(layout: LayoutConfig) {
        // 1) release any previous playback stack (players, surfaces, coroutines)
        runCatching { if (::controller.isInitialized) controller.release() }

        // 2) bump generation and capture a nonce for this build
        val nonce = ++buildNonce

        // 3) build views for this layout; only continue if this is the latest build
        buildStage(layout, nonce) {
            controller = PlayerController(
                context = this,
                db = AppDatabase.getInstance(applicationContext),
                fileStore = FileStore(applicationContext),
                rootStage = stage,
                layout = layout,
                scale = computeScale(layout),
                // you can keep this high — user may define many regions;
                // PlayerController internally limits concurrent decoders per region playback
                maxConcurrentVideoRegions = 5
            )


            controller.setPlaybackListener(object : PlayerController.PlaybackListener {
                override fun onAssetStart(regionId: String, playlistId: String?, assetId: String, mediaType: String) {
                    val now = System.currentTimeMillis()
                    val runId = UUID.randomUUID().toString()
                    lastActiveRegionId = regionId
                    analytics.onAssetStart(
                        PlaybackCtx(
                            regionId = regionId,
                            playlistId = playlistId,
                            assetId = assetId,
                            mediaType = mediaType,
                            startedAtMs = now,
                            runId = runId
                        )
                    )
                }

                override fun onAssetEnd(regionId: String, playlistId: String?, assetId: String, mediaType: String) {
                    analytics.onAssetEnd(regionId, System.currentTimeMillis())
                }

                override fun onRegionActive(regionId: String) {
                    lastActiveRegionId = regionId
                }
            })


            // Start per-region downloads; when a region finishes it will call startRegion()
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

    private fun buildStage(layout: LayoutConfig, nonce: Long, onBuilt: () -> Unit) {
        root.post {
            // Drop stale work if a newer rebuild started while this Runnable was queued
            if (nonce != buildNonce) return@post

            val s = computeScale(layout)
            val stageW = (layout.design.width * s).toInt()
            val stageH = (layout.design.height * s).toInt()

            stage.updateLayoutParams<FrameLayout.LayoutParams> {
                width = stageW; height = stageH
            }
            stage.x = (root.width - stageW) / 2f
            stage.y = (root.height - stageH) / 2f
            stage.setBackgroundColor(parseColorOr(layout.design.bgColor, Color.BLACK))

            // Recreate region containers cleanly for this generation
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
        try { if (s.isNullOrBlank()) fallback else s.toColorInt() } catch (_: Throwable) { fallback }

    // ── Socket indicator ────────────────────────────────────────────────────────
    fun setSocketIndicator(connected: Boolean) {
        socketIcon.post {
            val (icon, tint) = if (connected) {
                com.ebani.sinage.R.drawable.ic_cloud_on to "#22C55E".toColorInt() // green
            } else {
                com.ebani.sinage.R.drawable.ic_cloud_off to "#EF4444".toColorInt()   // red
            }
            socketIcon.setImageResource(icon)
            socketIcon.imageTintList = ColorStateList.valueOf(tint)
            socketIcon.visibility = View.VISIBLE
            socketIcon.alpha = 1f
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
