package com.ebani.sinage.net

import android.content.Context
import com.ebani.sinage.net.ws.PairingMessage
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide WebSocket hub.
 * Owns a single SocketClient and fan-outs events to listeners in Activities.
 */
object SocketHub {

    interface Listener {
        fun onConnected() {
            println("connected")
        }
        fun onMessage(msg: PairingMessage) {}
    }

    private var client: SocketClient? = null
    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var started = false

    /** Call once (idempotent). Safe to call from any Activity. */
    @Synchronized
    fun start(ctx: Context, url: String) {
        if (started && client != null) return
        val appCtx = ctx.applicationContext
        client = SocketClient(
            appCtx,
            url = url,
            onMessage = { msg -> listeners.forEach { it.onMessage(msg) } },
            onConnected = { listeners.forEach { it.onConnected() } }
        ).also {
            it.connect()           // single owner of connect()
            started = true
        }

    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun sendText(text: String): Boolean = client?.sendText(text) == true

    fun isConnected(): Boolean = client?.isConnected() == true

    /** Optional: fully stop socket (e.g., app background policy). */
    @Synchronized
    fun stop() {
        started = false
        client?.close()
        client = null
        listeners.clear()
    }
}
