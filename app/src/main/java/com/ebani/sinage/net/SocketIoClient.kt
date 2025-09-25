// SocketIoClient.kt
package com.ebani.sinage.net

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.EngineIOException
import io.socket.engineio.client.Socket.EVENT_CLOSE
import io.socket.engineio.client.Socket.EVENT_ERROR
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SocketIoClient(
    private val baseUrl: String,                 // e.g., https://app.digitopia.live
    private val path: String = "/api/socket",
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}, // 👈 NEW
    private val onJson: (JSONObject) -> Unit = {},
    onReconnect: () -> Unit
) {
    private var socket: Socket? = null
    private val hbExec = ScheduledThreadPoolExecutor(1).apply { setRemoveOnCancelPolicy(true) }
    private var hbTask: ScheduledFuture<*>? = null
    fun connect() {
        println("Connecting to $baseUrl")
        val opts = IO.Options().apply {
            this.path = "/api/socket"                // 👈 use provided path (was hardcoded)
//             transports = arrayOf(io.socket.engineio.client.transports.WebSocket.NAME) // uncomment if needed behind proxies/CDN
            reconnection = true
            auth = mapOf("role" to "device")
            reconnectionDelay = 1000
            reconnectionDelayMax = 5000
        }

        socket = IO.socket(baseUrl, opts)

        // ===== Core lifecycle =====
        socket?.on(Socket.EVENT_CONNECT) {
            println("EVENT_CONNECT")
            onConnected()
        }?.on(Socket.EVENT_DISCONNECT) {
            println("EVENT_DISCONNECT")
            onDisconnected()
        }?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            println("CONNECT_ERROR: " + args.joinToString { it?.toString() ?: "null" })
            (args.firstOrNull() as? Exception)?.printStackTrace()
            onDisconnected() // treat as disconnected
        }?.on(EngineIOException::class.java.name){
            println("ENGINE IO EXCEPTION: $it")
            onDisconnected()
        }

        // Engine.IO layer signals (close/error)
        socket?.io()?.on(EVENT_CLOSE) {
            println("ENGINE.IO CLOSE")
            onDisconnected()

        }
        socket?.io()?.on(EVENT_ERROR) { args ->
            println("ENGINE.IO ERROR: " + args.joinToString { it?.toString() ?: "null" })
            onDisconnected()
        }

        // ===== App-level events -> JSON pump =====
        socket?.on("ping") { args ->
            (args.firstOrNull() as? JSONObject)?.let { it.put("type","ping"); onJson(it) }
        }?.on("handshake_ok") { args ->
            val obj = (args.firstOrNull() as? JSONObject) ?: JSONObject()
            obj.put("type","handshake_ok"); onJson(obj)
        }?.on("handshake_error") { args ->
            val obj = (args.firstOrNull() as? JSONObject) ?: JSONObject()
            obj.put("type","handshake_error"); onJson(obj)
        }?.on("pairing_status_ok") {
            onJson(JSONObject(mapOf("type" to "pairing_status_ok")))
        }?.on("pairing_status_error") { args ->
            val obj = (args.firstOrNull() as? JSONObject) ?: JSONObject()
            obj.put("type","pairing_status_error"); onJson(obj)
        }?.on("message") { args ->
            val obj = (args.firstOrNull() as? JSONObject) ?: JSONObject()
            if (!obj.has("type")) obj.put("type","message")
            onJson(obj)
        }
//            ?.on("unpair_reset") { args ->
//            val obj = (args.firstOrNull() as? JSONObject) ?: JSONObject()
//
//        }


        println("Socket Connect")
        socket?.connect()
    }

    fun emitHandshake(deviceId: String) {
        socket?.emit("handshake", JSONObject().put("deviceId", deviceId))
    }

    fun emitPairingStatus(deviceId: String, code: String, ttlSec: Int, active: Boolean, resolution: JSONObject) {
        println("Socket Pair Emit")
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("code", code)
            .put("ttlSec", ttlSec)
            .put("active", active)
            .put("resolution", resolution)
        socket?.emit("pairing_status", payload)
    }

    fun broadcast(deviceId: String?, payload: JSONObject) {
        val body = JSONObject().put("payload", payload)
        if (deviceId != null) body.put("deviceId", deviceId)
        socket?.emit("broadcast", body)
    }

    fun registeredShake(deviceId: String?,userId: String?, payload: JSONObject) {
        val body = JSONObject().put("payload", payload)
        if (deviceId != null) {
            body.put("deviceId", deviceId)
            body.put("userId", userId)
        }
        socket?.emit("registeredShake", body)
    }

    fun isConnected(): Boolean = socket?.connected() == true
    /** One-off ping to server presence endpoint. */
    fun emitPing(deviceId: String?, userId: String?) {
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("userId", userId)
        // server side expects "device:ping"
        socket?.emit("device:ping", payload)
    }

    /** Start periodic heartbeat (default: every 12s). Call stopHeartbeat() on pause/disconnect. */
    fun startHeartbeat(deviceId: String?, userId: String?, periodMs: Long = 12_000L) {
        stopHeartbeat() // avoid duplicates
        hbTask = hbExec.scheduleWithFixedDelay({
            try {
                if (isConnected()) {
                    emitPing(deviceId, userId)
                }
            } catch (t: Throwable) {
                // swallow to keep the scheduler alive
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS)
    }

    /** Stop the periodic heartbeat. */
    fun stopHeartbeat() {
        if (hbTask!=null){
            hbTask?.cancel(false)
            hbTask = null
        }
    }
    fun close() {
        try {
            stopHeartbeat();
            socket?.off()           // remove all listeners to avoid leaks
            socket?.disconnect()
            socket?.close()
        } finally {
            socket = null
        }
    }
}
