package com.ebani.sinage.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.net.ws.DeviceHandshake
import com.ebani.sinage.net.ws.PairingMessage
//import com.ebani.sinage.data.prefs.DevicePrefs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okio.ByteString
import timber.log.Timber
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class SocketClient(
    private val ctx: Context,
    private val url: String,
    private val onMessage: (PairingMessage) -> Unit,
    private val onConnected: () -> Unit = {}
) : WebSocketListener() {

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"   // 👈 important for PairingMessage sealed types
    }

    @Volatile private var connected = false
    @Volatile private var connecting = false
    @Volatile private var intentionalClose = false
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectScheduled = false

    private val minBackoffMs = 1_000L
    private val maxBackoffMs = 30_000L
    private val backoffMultiplier = 2.0

    private val scheduler = ScheduledThreadPoolExecutor(1)

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!connected && !intentionalClose && !connecting) {
                Timber.i("[WS] Network available -> reconnect now")
                reconnectNow()
            }
        }
    }

    init {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, networkCallback) }
            .onFailure { Timber.w(it, "[WS] Failed to register network callback") }
    }

    // -------- Public API --------

    fun connect() {
        intentionalClose = false
        openSocket(resetBackoff = true)

    }

    fun reconnectNow() {
        if (intentionalClose) return
        cancelScheduledTasks()
        reconnectScheduled = false
        if (connected || connecting) {
            Timber.d("[WS] reconnectNow ignored (connected=$connected connecting=$connecting)")
            return
        }
        safeCloseCurrent(code = 1001, reason = "reconnectNow")
        openSocket(resetBackoff = true)
    }

    fun isConnected(): Boolean = connected

    fun close() {
        intentionalClose = true
        cancelScheduledTasks()
        reconnectScheduled = false
        safeCloseCurrent(code = 1000, reason = "client_close")
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
            .onFailure { /* no-op */ }
    }

    fun sendText(text: String): Boolean {
        val ok = ws?.send(text) ?: false
        if (!ok) Timber.w("[WS] sendText failed (socket null or closed)")
        return ok
    }

    inline fun <reified T> sendSerializable(obj: T): Boolean {
        val txt = runCatching { json.encodeToString(obj) }.getOrElse {
            Timber.e(it, "[WS] Failed to encode message")
            return false
        }
        return sendText(txt)
    }

    // -------- Internals --------

    private fun openSocket(resetBackoff: Boolean = false) {
        if (intentionalClose) return
        if (connected || connecting) {
            Timber.d("[WS] openSocket ignored (connected=$connected connecting=$connecting)")
            return
        }
        if (resetBackoff) reconnectAttempts = 0
        val req = Request.Builder().url(url).build()
        connecting = true
        reconnectScheduled = false
        ws = client.newWebSocket(req, this)
        Timber.i("[WS] connecting… $url")
    }

    private fun scheduleReconnect() {
        if (intentionalClose) return
        if (reconnectScheduled) {
            Timber.d("[WS] reconnect already scheduled")
            return
        }
        val base = if (reconnectAttempts == 0) minBackoffMs else
            min((minBackoffMs * Math.pow(backoffMultiplier, reconnectAttempts.toDouble())).toLong(), maxBackoffMs)
        val jitter = Random.nextLong(0, 250)
        val delay = base + jitter
        reconnectAttempts++
        reconnectScheduled = true
        Timber.i("[WS] scheduling reconnect in ${delay}ms (attempt=$reconnectAttempts)")
        scheduler.schedule({
            reconnectScheduled = false
            openSocket()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelScheduledTasks() {
        scheduler.queue.removeIf { true }
    }

    private fun safeCloseCurrent(code: Int, reason: String) {
        try { ws?.close(code, reason) } catch (_: Throwable) {}
        ws = null
        connected = false
        connecting = false
    }

    private fun sendHandshake(webSocket: WebSocket) {
        val prefs = DevicePrefs(ctx)
        val payload = DeviceHandshake( "handshake",prefs.deviceId)
        val encs = json.encodeToString(payload)
        println("handshake send: $encs")
        runCatching { webSocket.send(encs) }
            .onFailure { Timber.w(it, "[WS] handshake send failed") }
    }

    // -------- WebSocketListener --------

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Timber.i("[WS] open")
        connected = true
        connecting = false
        reconnectAttempts = 0
        onConnected()
        sendHandshake(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching {
            println("messagefromserver: $text")
            val msg = json.decodeFromString<PairingMessage>(text)
            onMessage(msg)
        }.onFailure {
            Timber.w(it, "[WS] Failed to decode incoming message")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { /* ignore */ }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Timber.i("[WS] closing code=$code reason=$reason")
        connected = false
        connecting = false
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Timber.i("[WS] closed code=$code reason=$reason")
        connected = false
        connecting = false
        if (!intentionalClose) scheduleReconnect()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Timber.e(t, "[WS] failure ${response?.code ?: ""}")
        connected = false
        connecting = false
        if (!intentionalClose) scheduleReconnect()
    }
}
