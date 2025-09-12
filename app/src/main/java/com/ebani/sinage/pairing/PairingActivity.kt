package com.ebani.sinage.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ebani.sinage.R
import com.ebani.sinage.data.p.DevicePrefs

import com.ebani.sinage.databinding.ActivityPairingBinding
import com.ebani.sinage.net.Net
import com.ebani.sinage.net.SocketHub
import com.ebani.sinage.net.ws.MsgContentUpdate
import com.ebani.sinage.net.ws.MsgHandshakeError
import com.ebani.sinage.net.ws.MsgHandshakeOk
import com.ebani.sinage.net.ws.MsgPairingCode
import com.ebani.sinage.net.ws.MsgPing
import com.ebani.sinage.net.ws.MsgRegistered
import com.ebani.sinage.net.ws.PairingMessage
import com.ebani.sinage.playback.PlayerActivity
import com.ebani.sinage.sync.SyncManager
import com.ebani.sinage.sync.SyncStatus
import com.ebani.sinage.util.DisplayUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
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

    private val socketListener = object : SocketHub.Listener {
        override fun onConnected() {
            Timber.i("Socket connected (pairing)")
            setWsStatus(true)
            // Re-announce current pairing status on reconnect
            if (currentCode.isNotEmpty() && ttlSec > 0) {
                sendPairingActive(true, currentCode.replace(" ", ""), ttlSec)
            }
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

        // app version
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
        // Seed state until we get callbacks
        setWsStatus(connected = false)
    }

    private fun setWsStatus(connected: Boolean) {
        binding.wsText.text = if (connected) "Online" else "Offline"
        binding.wsDot.setBackgroundResource(
            if (connected) R.drawable.bg_dot_green else R.drawable.bg_dot_red
        )
    }


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)


        prefs = DevicePrefs(this)





        binding.deviceIdText.text = prefs.deviceId
        binding.pairingCodeText.text = "Waiting for pairing…"
        binding.expiry.text = ""
        binding.retryButton.setOnClickListener { trySync() }

        // Start hub once (idempotent) and register listener
//        SocketHub.start(this, Net.WS_URL)
        SocketHub.addListener(socketListener)
        setupWsStatusPill()
        setupUiBasics()
        fillDeviceInfo()

        // Start pairing UI loop once
        startPairingLoop()
    }

    private fun trySync() {
        binding.retryButton.isEnabled = false
        lifecycleScope.launch {
//            when (SyncManager.syncNow(this@PairingActivity)) {
//                SyncStatus.OK -> goToPlayer()
//                SyncStatus.EMPTY -> {
//                    Toast.makeText(this@PairingActivity, "Device registered, no content yet. Showing fallback.", Toast.LENGTH_SHORT).show()
//                    goToPlayer()
//                }
//                SyncStatus.NOT_REGISTERED -> {
//                    binding.pairingCodeText.text = "Device not registered. Finish pairing in admin."
//                    binding.retryButton.isEnabled = true
//                }
//                SyncStatus.ERROR -> {
//                    Toast.makeText(this@PairingActivity, "Sync failed. Check network and try again.", Toast.LENGTH_SHORT).show()
//                    binding.retryButton.isEnabled = true
//                }
//            }
        }
    }

    private fun handleSocketMessage(msg: PairingMessage) {
        when (msg) {
            is MsgPairingCode -> {
                runOnUiThread {
                    binding.pairingCodeText.text = msg.code
                }
            }
            is MsgRegistered->{
                goToPlayer();
            }
            is MsgContentUpdate -> {
                // Device is registered (or admin changed content) -> stop loop, announce inactive, sync
                pairingJob?.cancel()
                sendPairingActive(false, currentCode.replace(" ", ""), 0)
                trySync()
            }
            is MsgHandshakeOk -> {
                // Optional: you can log/store socketId if you need it
                Timber.i("WS handshake ok, socketId=${msg.socketId}")
            }
            is MsgHandshakeError -> {
                Timber.w("WS handshake error: ${msg.reason}")
                runOnUiThread {
                    Toast.makeText(this, "Socket handshake error: ${msg.reason}", Toast.LENGTH_SHORT).show()
                }
            }
            is MsgPing -> {
                // no-op
            }
            else -> {
                // future-proof
                Timber.d("WS msg ignored: $msg")
            }
        }
    }


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
        val payload = JSONObject()
            .put("type", "pairing_status")
            .put("active", active)
            .put("code", code)
            .put("ttlSec", ttlSec)
            .put("deviceId", prefs.deviceId)
            .put("resolution", DisplayUtils.screenInfoJson(this))
            .toString()

        if (!SocketHub.sendText(payload)) {
            Timber.w("Failed to send pairing_status (socket not connected)")
        }
    }

    private fun updateUi(code: String, secondsLeft: Int) {
        val mm = secondsLeft / 60
        val ss = secondsLeft % 60
        binding.pairingCodeText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        binding.pairingCodeText.text = code
        binding.expiry.textAlignment = View.TEXT_ALIGNMENT_CENTER
        binding.expiry.text = "Expires in: %02d:%02d".format(mm, ss)
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingJob?.cancel()
        SocketHub.removeListener(socketListener)
        setWsStatus(false)
    }
}
