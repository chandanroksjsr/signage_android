package com.ebani.sinage.hotspot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class SetupHostService : Service() {

    companion object {
        private const val CH_ID = "setup_host"
        private const val NOTIF_ID = 42

        const val ACTION_START = "com.ebani.sinage.action.START_HOTSPOT"
        const val ACTION_STOP  = "com.ebani.sinage.action.STOP_HOTSPOT"
        const val ACTION_STATE = "com.ebani.sinage.action.HOTSPOT_STATE"
        const val ACTION_CLIENT_JOINED = "com.ebani.sinage.action.CLIENT_JOINED"

        // 🔐 From LocalWebServer (/api/wifi)
        const val ACTION_APPLY_WIFI = "com.ebani.sinage.action.APPLY_WIFI"
        const val EXTRA_WIFI_SSID   = "wifiSsid"
        const val EXTRA_WIFI_PASS   = "wifiPass"

        const val EXTRA_RUNNING = "running"
        const val EXTRA_SSID    = "ssid"
        const val EXTRA_PASS    = "pass"
        const val EXTRA_PORT    = "port"
        const val EXTRA_CLIENT_IP = "clientIp"

        fun guessApAddress(): String? {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (!name.contains("ap") && !name.contains("wlan")) continue
                val addrs = Collections.list(ni.inetAddresses)
                for (ia in addrs) if (ia is Inet4Address && ia.isSiteLocalAddress) return ia.hostAddress
            }
            return null
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, SetupHostService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SetupHostService::class.java).setAction(ACTION_STOP))
        }
    }

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: LocalWebServer? = null
    private var nsd: NsdAdvertiser? = null

    @Volatile private var running = false
    @Volatile private var starting = false

    private var ssid: String = ""
    private var pass: String = ""
    private var boundPort: Int = -1

    private lateinit var wifi: WifiManager
    private lateinit var cm: ConnectivityManager
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val applying = AtomicBoolean(false)

    private var applyWifiRx: BroadcastReceiver? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cm   = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Receive /api/wifi → ACTION_APPLY_WIFI(ssid, pass)
        applyWifiRx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == ACTION_APPLY_WIFI) {
                    val targetSsid = i.getStringExtra(EXTRA_WIFI_SSID) ?: return
                    val targetPass = i.getStringExtra(EXTRA_WIFI_PASS)
                    attemptConnectToWifi(targetSsid, targetPass)
                }
            }
        }

        val filter = IntentFilter(ACTION_APPLY_WIFI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ✅ Required on Android 13+ for app-internal broadcasts
            registerReceiver(applyWifiRx, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(applyWifiRx, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(applyWifiRx) }
        unregisterNetworkCallback()
        stopEverything()
        super.onDestroy()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            ACTION_START, null -> {
                startForeground(NOTIF_ID, notif("Starting offline setup…", includeStopAction = false))
                if (!running && !starting) {
                    val (ok, why) = canStartHotspot()
                    if (!ok) {
                        Toast.makeText(applicationContext, why, Toast.LENGTH_LONG).show()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startLohs()
                } else {
                    startForeground(NOTIF_ID, notif(runningText(), includeStopAction = true))
                    broadcastState()
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun canStartHotspot(): Pair<Boolean, String> {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return false to "Grant Location permission."
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED) return false to "Grant Nearby Wi-Fi Devices permission."
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return false to "Allow notifications."

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationOn = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locationOn) return false to "Turn on Location in system settings."

        return true to "OK"
    }

//    @Suppress("MissingPermission")
    @SuppressLint("MissingPermission")
    private fun startLohs() {
        if (reservation != null || running || starting) return
        starting = true

        val cb = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res

                // Read SSID/pass across API levels
                runCatching {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val conf = res.javaClass.getMethod("getSoftApConfiguration").invoke(res)
                        ssid = conf.javaClass.getMethod("getSsid").invoke(conf) as String
                        pass = (conf.javaClass.getMethod("getPassphrase").invoke(conf) as? String).orEmpty()
                    } else {
                        val wcfg = res.javaClass.getMethod("getWifiConfiguration").invoke(res)
                        ssid = (wcfg.javaClass.getField("SSID").get(wcfg) as? String) ?: "Sinage-Setup"
                        pass = (wcfg.javaClass.getField("preSharedKey").get(wcfg) as? String).orEmpty()
                    }
                }.onFailure { ssid = "Sinage-Setup"; pass = "" }

                // HTTP server on a free port; notify on first client so UI hides QR
                boundPort = startServerOnFreePort(8080..8090)

                nsd = NsdAdvertiser(this@SetupHostService).also { it.register("_http._tcp.", "Sinage-Setup", boundPort) }

                running = true
                starting = false
                startForeground(NOTIF_ID, notif(runningText(), includeStopAction = true))
                broadcastState()
            }
            override fun onStopped() { stopEverything() }
            override fun onFailed(reason: Int) { stopEverything(); stopSelf() }
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try { wm.startLocalOnlyHotspot(cb, Handler(Looper.getMainLooper())) }
        catch (_: Throwable) { wm.startLocalOnlyHotspot(cb, null) }
    }

    private fun startServerOnFreePort(range: IntRange): Int {
        runCatching { server?.stop() }; server = null
        var last: Throwable? = null
        for (p in range) {
            try {
                server = LocalWebServer(applicationContext, p) { clientIp ->
                    // any hit → tell UI to hide QR
                    sendBroadcast(Intent(ACTION_CLIENT_JOINED).putExtra(EXTRA_CLIENT_IP, clientIp))
                }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                return p
            } catch (t: Throwable) {
                last = t
                if (t !is BindException) break
            }
        }
        Toast.makeText(applicationContext, "Server failed: ${last?.message}", Toast.LENGTH_LONG).show()
        return -1
    }

    private fun runningText(): String {
        val host = if (boundPort > 0) "http://192.168.49.1:$boundPort" else "(starting server…)"
        val pw = if (pass.isBlank()) "None" else pass
        return "Connect to $ssid  (PW: $pw)\n$host"
    }

    // ── APPLY WIFI (leave LOHS and connect to internet) ────────────────────────
    private fun attemptConnectToWifi(targetSsid: String, targetPass: String?) {
        if (!applying.compareAndSet(false, true)) return
        Toast.makeText(applicationContext, "Connecting to $targetSsid…", Toast.LENGTH_SHORT).show()

        // 1) Stop LOHS + local server (Wi-Fi client and SoftAP can’t coexist)
        stopEverything(keepNotification = true)

        // 2) Wait for Wi-Fi with INTERNET via NetworkCallback
        registerNetworkCallback()

        if (Build.VERSION.SDK_INT >= 29) {
            // Suggestions (background-friendly). User may have to approve once.
            val b = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(targetSsid)
            val sug = if (targetPass.isNullOrEmpty())
                b.build()
            else
                b.setWpa2Passphrase(targetPass).build()

            // Clear old and add new
            runCatching { wifi.removeNetworkSuggestions(emptyList()) }
            val status = wifi.addNetworkSuggestions(listOf(sug))
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            startForeground(NOTIF_ID, notif("Connecting to $targetSsid… (confirm in Wi-Fi panel if prompted)", includeStopAction = false))
        } else {
            // Legacy immediate join
            @Suppress("DEPRECATION")
            val cfg = WifiConfiguration().apply {
                SSID = "\"$targetSsid\""
                if (targetPass.isNullOrEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$targetPass\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }
            @Suppress("DEPRECATION")
            val netId = wifi.addNetwork(cfg)
            if (netId != -1) {
                @Suppress("DEPRECATION")
                runCatching { wifi.disconnect(); wifi.enableNetwork(netId, true); wifi.reconnect() }
            } else {
                Toast.makeText(this, "Failed to add Wi-Fi config", Toast.LENGTH_LONG).show()
            }
            startForeground(NOTIF_ID, notif("Connecting to $targetSsid…", includeStopAction = false))
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try { cm.bindProcessToNetwork(network) } catch (_: Throwable) {}
                applying.set(false)
                // Tell activity to hide QR/status, and relax foreground
                sendBroadcast(Intent(ACTION_STATE).putExtra(EXTRA_RUNNING, false))
                startForeground(NOTIF_ID, notif("Connected to internet via Wi-Fi", includeStopAction = false))
                stopSelf() // we’re done
            }
            override fun onUnavailable() {
                applying.set(false)
                startForeground(NOTIF_ID, notif("Connection failed. Open Wi-Fi panel and pick the network.", includeStopAction = false))
            }
        }
        cm.registerNetworkCallback(req, netCb!!)
    }
    private fun unregisterNetworkCallback() {
        netCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCb = null
    }

    // ── teardown ────────────────────────────────────────────────────────────────
    private fun stopEverything(keepNotification: Boolean = false) {
        runCatching { server?.stop() }
        runCatching { nsd?.unregister() }
        runCatching { reservation?.close() }
        server = null; nsd = null; reservation = null
        val wasRunning = running
        running = false; starting = false
        if (wasRunning) broadcastState()
        ssid = ""; pass = ""; boundPort = -1
        if (!keepNotification) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun broadcastState() {
        sendBroadcast(
            Intent(ACTION_STATE)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_SSID, ssid)
                .putExtra(EXTRA_PASS, pass)
                .putExtra(EXTRA_PORT, boundPort)
        )
    }

    override fun onBind(intent: Intent?) = null

    private fun notif(text: String, includeStopAction: Boolean): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_ID, "Setup Hosting", NotificationManager.IMPORTANCE_LOW))
        }
        val b = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentTitle("Offline setup")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
        if (includeStopAction) {
            val pi = PendingIntent.getService(
                this, 0, Intent(this, SetupHostService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(android.R.drawable.ic_delete, "Stop", pi)
        }
        return b.build()
    }
}
