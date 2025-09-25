// SocketHub.kt
package com.ebani.sinage.net

import android.content.Context
import com.ebani.sinage.data.model.DeviceEntity
import com.ebani.sinage.net.ws.PairingMessage
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

object SocketHub {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onReconnect()
        fun onMessage(msg: PairingMessage) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var client: SocketIoClient? = null
    @Volatile private var started = false
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun start(ctx: Context, baseUrl: String) {
        if (started && client != null) return
        client = SocketIoClient(
            baseUrl = baseUrl,
            path = "/api/socket",
            onConnected = { listeners.forEach { it.onConnected() } },
            onDisconnected = { listeners.forEach { it.onDisconnected() } },
            onReconnect = { listeners.forEach { it.onReconnect() } },
            onJson = { obj ->
                // Convert server JSON -> your sealed PairingMessage
                val withType = obj.toString()
                runCatching { json.decodeFromString<PairingMessage>(withType) }
                    .onSuccess { pm -> listeners.forEach { it.onMessage(pm) } }
            }
        ).also { it.connect(); started = true }

    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun emitHandshake(deviceId: String) {
        client?.emitHandshake(deviceId)
    }

    fun emitHandshakeRegistered(device: DeviceEntity?, info: JSONObject) {
        client?.registeredShake(deviceId =device?.deviceId,userId= device?.adminUserId,info )
    }


    fun emitPairingStatus(deviceId: String, code: String, ttlSec: Int, active: Boolean, resolution: JSONObject) {
        client?.emitPairingStatus(deviceId, code, ttlSec, active, resolution)
    }

    fun broadcast(deviceId: String?, payload: JSONObject) {
        client?.broadcast(deviceId, payload)
    }
    fun startHeartbeat(deviceId: String?, userId:String?){
        client?.startHeartbeat(deviceId =deviceId, userId = userId)
    }
    fun stopheartBeat(){
        client?.stopHeartbeat()
    }

    fun isConnected(): Boolean = client?.isConnected() == true

    @Synchronized
    fun stop() {
        started = false
        client?.close()
        client = null
        listeners.clear()
    }
}
