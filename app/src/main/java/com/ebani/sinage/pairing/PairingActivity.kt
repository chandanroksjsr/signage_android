package com.ebani.sinage.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.ebani.sinage.R
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.databinding.ActivityPairingBinding
import com.ebani.sinage.net.SocketHub
import com.ebani.sinage.net.ws.MsgContentUpdate
import com.ebani.sinage.net.ws.MsgHandshakeError
import com.ebani.sinage.net.ws.MsgHandshakeOk
import com.ebani.sinage.net.ws.MsgPairingCode
import com.ebani.sinage.net.ws.MsgPing
import com.ebani.sinage.net.ws.MsgRegistered
import com.ebani.sinage.net.ws.PairingMessage
import com.ebani.sinage.playback.PlayerActivity
import com.ebani.sinage.util.DisplayUtils
import io.socket.client.SocketIOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.random.Random

class PairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingBinding
    private lateinit var prefs: DevicePrefs

    private var pairingJob: Job? = null
    private var currentCode: String = ""
    private var ttlSec: Int = 0

    private val CODE_TTL_SEC = 180 // 3 minutes

    /** Small helper: ensure any UI update runs on the main thread */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block()
        else runOnUiThread { block() }
    }

    private val socketListener = object : SocketHub.Listener {
        override fun onConnected() {
            Timber.i("Socket connected (pairing)")
            setWsStatus(true) // safe: setWsStatus now marshals to main
            // Re-announce current pairing status on reconnect
            if (currentCode.isNotEmpty() && ttlSec > 0) {
                sendPairingActive(true, currentCode.replace(" ", ""), ttlSec)
            }
        }

        override fun onDisconnected() {
            setWsStatus(false) // will post to main
        }

        override fun onReconnect() {
            setWsStatus(true)
            sendPairingActive(true, currentCode.replace(" ", ""), ttlSec)
        }

        override fun onMessage(msg: PairingMessage) = handleSocketMessage(msg)
    }

    private fun setupUiBasics() {
        binding.deviceIdText.text = prefs.deviceId
        binding.copyIdBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Device ID", prefs.deviceId))
            Toast.makeText(this, "Device ID copied", Toast.LENGTH_SHORT).show()
        }
        binding.retryButton.setOnClickListener { trySync() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun fillDeviceInfo() {
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().replaceFirstChar { it.uppercase() }
        val androidVer = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        val dm: DisplayMetrics = resources.displayMetrics
        val pxW = dm.widthPixels
        val pxH = dm.heightPixels
        val dpi = dm.densityDpi
        val density = dm.density
        val dpW = (pxW / density).roundToInt()
        val dpH = (pxH / density).roundToInt()
        val aspect = aspectRatio(pxW, pxH)
        val refresh = (display?.refreshRate ?: 60f).let { "${it.roundToInt()} Hz" }

        binding.deviceNameText.text = "$model • $androidVer"
        binding.resolutionText.text =
            "${pxW}×${pxH}px • ${dpi}dpi • ${dpW}×${dpH}dp • $aspect • $refresh"

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionStr = "v${pInfo.versionName} (${pInfo.longVersionCode})"
        binding.appVersionText.text = "App $versionStr"
    }

    private fun aspectRatio(w: Int, h: Int): String {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        if (w <= 0 || h <= 0) return "—"
        val g = gcd(w, h)
        return "${w / g}:${h / g}"
    }

    private fun setupWsStatusPill() {
        setWsStatus(SocketHub.isConnected())
    }

    /** 🧵 Main-safe now */
    private fun setWsStatus(connected: Boolean) = onMain {
        binding.wsText.text = if (connected) "Online" else "Offline"
        binding.wsDot.setBackgroundResource(
            if (connected) R.drawable.bg_dot_green else R.drawable.bg_dot_red
        )
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
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = DevicePrefs(this)
        binding.deviceIdText.text = prefs.deviceId
        binding.pairingCodeText.text = "Waiting for pairing…"
        binding.expiry.text = ""
        binding.ttlRing.progressMax = CODE_TTL_SEC.toFloat()
        binding.ttlRing.progress = 0f

        SocketHub.addListener(socketListener)

        setupUiBasics()
        fillDeviceInfo()
        startPairingLoop()
        setupWsStatusPill()
    }

    private fun trySync() {
        // (Optional) If you want a manual retry to poke the server,
        // emit current pairing status again or trigger a lightweight sync.
        if (currentCode.isNotEmpty() && ttlSec > 0) {
            sendPairingActive(true, currentCode.replace(" ", ""), ttlSec)
        }
    }

    private fun onNewCodeStarted() {
        onMain {
            binding.ttlRing.progress = CODE_TTL_SEC.toFloat()
            binding.ttlRing.progressBarColor = getColor(R.color.colorPrimary)
        }
    }

    private fun handleSocketMessage(msg: PairingMessage) {
        when (msg) {
            is MsgPairingCode -> {
                onMain { binding.pairingCodeText.text = msg.code }
            }
            is MsgRegistered -> {
                onMain { goToPlayer() } // ensure navigation happens on main
            }
            is MsgContentUpdate -> {
                // Device is registered (or admin changed content) -> stop loop, announce inactive
                pairingJob?.cancel()
                sendPairingActive(false, currentCode.replace(" ", ""), 0)
            }
            is MsgHandshakeOk -> {
                Timber.i("WS handshake ok, socketId=${msg.socketId}")
            }
            is MsgHandshakeError -> {
                Timber.w("WS handshake error: ${msg.reason}")
                onMain {
                    Toast.makeText(this, "Socket handshake error: ${msg.reason}", Toast.LENGTH_SHORT).show()
                }
            }
            is MsgPing -> Unit
            else -> Timber.d("WS msg ignored: $msg")
        }
    }

    @OptIn(UnstableApi::class)
    private fun goToPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }

    private fun startPairingLoop() {
        pairingJob?.cancel()
        pairingJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    currentCode = generateSixDigitCode()
                    ttlSec = CODE_TTL_SEC
                    onNewCodeStarted()
                    sendPairingActive(true, currentCode.replace(" ", ""), ttlSec)

                    var secondsLeft = ttlSec
                    while (secondsLeft > 0 && isActive) {
                        updateUi(currentCode, secondsLeft)
                        delay(1_000)
                        secondsLeft--
                    }
                }
            }
        }
    }

    private fun generateSixDigitCode(): String {
        val n = Random.nextInt(0, 1_000_000)
        val raw = "%06d".format(n)
        return raw.substring(0, 3) + " " + raw.substring(3)
    }

    private fun sendPairingActive(active: Boolean, code: String, ttlSec: Int) {
        try {
            SocketHub.emitPairingStatus(
                prefs.deviceId,
                code,
                ttlSec,
                active,
                DisplayUtils.screenInfoJson(this)
            )
        } catch (ex: SocketIOException) {
            Timber.w("Failed to send pairing_status (socket not connected)")
        }
    }

    /** Runs on lifecycleScope (main); safe to update views directly */
    private fun updateUi(code: String, secondsLeft: Int) {
        val mm = secondsLeft / 60
        val ss = secondsLeft % 60
        binding.pairingCodeText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        binding.pairingCodeText.text = code
        binding.expiry.textAlignment = View.TEXT_ALIGNMENT_CENTER
        binding.expiry.text = "Expires in: %02d:%02d".format(mm, ss)

        binding.ttlRing.progress = secondsLeft.toFloat()

        if (secondsLeft <= 10) {
            binding.ttlRing.progressBarColor = 0xFFFF5252.toInt() // red
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingJob?.cancel()
        SocketHub.removeListener(socketListener)
    }
}
