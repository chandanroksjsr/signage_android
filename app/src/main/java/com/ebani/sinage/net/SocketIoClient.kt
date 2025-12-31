// SocketIoClient.kt
package com.ebani.sinage.net

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.ebani.sinage.util.DisplayUtils
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.EngineIOException
import io.socket.engineio.client.Socket.EVENT_CLOSE
import io.socket.engineio.client.Socket.EVENT_ERROR
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


class SocketIoClient(
    private val baseUrl: String,                 // e.g., https://app.digitopia.live
    private val path: String = "/api/socket",
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onJson: (JSONObject) -> Unit = {},
    onReconnect: () -> Unit
) {
    private var socket: Socket? = null

    // Heartbeat scheduler
    private val hbExec = ScheduledThreadPoolExecutor(1).apply { setRemoveOnCancelPolicy(true) }
    private var hbTask: ScheduledFuture<*>? = null

    // CPU usage sampling (from /proc/stat)
//    @Volatile private var prevCpuTotal: Long = 0L
//    @Volatile private var prevCpuIdle: Long = 0L
//    @Volatile private var cpuPrimed: Boolean = false
    private val cpuMeter = com.ebani.sinage.util.CpuUsageMeter()
    fun connect() {
        val opts = IO.Options().apply {
            this.path = "/api/socket"
            reconnection = true
            auth = mapOf("role" to "device")
            reconnectionDelay = 1000
            reconnectionDelayMax = 5000
            // transports = arrayOf(io.socket.engineio.client.transports.WebSocket.NAME)
        }
        socket = IO.socket(baseUrl, opts)

        // ===== Core lifecycle =====
        socket?.on(Socket.EVENT_CONNECT) {
            onConnected()
        }?.on(Socket.EVENT_DISCONNECT) {
            onDisconnected()
        }?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            (args.firstOrNull() as? Exception)?.printStackTrace()
            onDisconnected()
        }?.on(EngineIOException::class.java.name) {
            onDisconnected()
        }

        // Engine.IO layer signals (close/error)
        socket?.io()?.on(EVENT_CLOSE) {
            onDisconnected()
        }
        socket?.io()?.on(EVENT_ERROR) { _ ->
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

        socket?.connect()
    }

    fun emitHandshake(deviceId: String) {
        socket?.emit("handshake", JSONObject().put("deviceId", deviceId))
    }

    fun emitPairingStatus(deviceId: String, code: String, ttlSec: Int, active: Boolean, resolution: JSONObject) {
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

    fun registeredShake(deviceId: String?, userId: String?, payload: JSONObject) {
        val body = JSONObject().put("payload", payload)
        if (deviceId != null) {
            body.put("deviceId", deviceId)
            body.put("userId", userId)
        }
        socket?.emit("registeredShake", body)
    }

    fun isConnected(): Boolean = socket?.connected() == true

    /** (Legacy) one-off ping without metrics. Prefer emitHeartbeat(...) */
    fun emitPing(deviceId: String?, userId: String?) {
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("userId", userId)
        socket?.emit("device:ping", payload)
    }

    /** Build a rich heartbeat payload including resolution, cpu, ram, temperature. */
    private fun buildHeartbeatPayload(ctx: Context, deviceId: String?, userId: String?): JSONObject {
        val res = DisplayUtils.screenInfoJson(ctx) // { width, height, densityDpi, density, orientation }
        println("cpustatX")
        var (cpuPctRaw, cores) = cpuMeter.percent()
        cpuPctRaw = sampleCPU()
        println("cpustat : ${sampleCPU()} ")
        val cpuPct = if (cpuPctRaw.isNaN()) null else com.ebani.sinage.util.CpuUsageMeter.round1(cpuPctRaw)


        println("cpustat : $cpuPct $cores")
        val ram = ramStats(ctx)
        val temp = deviceTemperatureC(ctx)

        val metrics = JSONObject()
            .put("resolution", res)
            .put("cpu", JSONObject()
                .put("percent", cpuPct)
                .put("cores", cores)
            )
            .put("ram", JSONObject()
                .put("totalBytes", ram.totalBytes)
                .put("usedBytes", ram.usedBytes)
                .put("freeBytes", ram.freeBytes)
                .put("usedPercent", if (ram.usedPercent >= 0) round1(ram.usedPercent) else JSONObject.NULL)
            )
            .put("temperatureC", if (temp != null) round1(temp) else JSONObject.NULL)
            .put("sdkInt", Build.VERSION.SDK_INT)

        return JSONObject()
            .put("deviceId", deviceId)
            .put("userId", userId)
            .put("metrics", metrics)
    }

    /** Start periodic heartbeat (default: every 12s) with metrics. */
    fun startHeartbeat(ctx: Context, deviceId: String?, userId: String?, periodMs: Long = 12_000L) {
        stopHeartbeat() // avoid duplicates

        // Prime CPU reader so first tick has a delta
//        readCpuTotals() // seeds prev values
        hbTask = hbExec.scheduleWithFixedDelay({
            try {
                if (isConnected()) {
                    val payload = buildHeartbeatPayload(ctx, deviceId, userId)
                    socket?.emit("device:ping", payload)
                }
            } catch (_: Throwable) {
                // swallow to keep scheduler alive
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS)
        println("StartingSampling")
        cpuMeter.start()           // starts background sampling
        cpuMeter.sampleNow()       // force an immediate delta
    }
    private fun sampleCPU(): Double {
        val rate = 0

        try {
            var Result: String?
            val p = Runtime.getRuntime().exec("top -n 1")
            val br = BufferedReader(InputStreamReader(p.getInputStream()))
            while ((br.readLine().also { Result = it }) != null) {
                // replace "com.example.fs" by your application
                if (Result!!.contains("com.ebani.sinage")) {
                    val info: Array<String?> =
                        Result.trim { it <= ' ' }.replace(" +".toRegex(), " ")
                            .split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    return info[9]!!.toDouble()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return rate.toDouble()
    }

    /** Stop the periodic heartbeat. */
    fun stopHeartbeat() {
        hbTask?.cancel(false)
        cpuMeter.stop()
        hbTask = null
    }

    fun close() {
        try {
            stopHeartbeat()
            cpuMeter.stop()
            socket?.off()
            socket?.disconnect()
            socket?.close()
        } finally {
            socket = null
        }
    }

    // ===== Metrics helpers =====================================================

    private data class RamInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val usedBytes: Long,
        val usedPercent: Double
    )

    private fun ramStats(ctx: Context): RamInfo {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val total = mi.totalMem
        val free = mi.availMem
        val used = (total - free).coerceAtLeast(0L)
        val pct = if (total > 0L) (used.toDouble() * 100.0 / total.toDouble()) else -1.0
        return RamInfo(total, free, used, pct)
    }

    /** Battery (device) temperature in °C if available; else null. */
    private fun deviceTemperatureC(ctx: Context): Double? {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val i: Intent? = ctx.registerReceiver(null, ifilter)
            val tenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tenths > 0) tenths / 10.0 else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Returns (percent[0..100], cores). -1 if not yet measurable. */


//    private fun readCpuTotals(): Pair<Long, Long> {
//        // Reads the first line of /proc/stat: "cpu  user nice system idle iowait irq softirq steal guest guest_nice"
//        // We sum all columns for total; idle = idle + iowait
//        try {
//            val line = java.io.RandomAccessFile("/proc/stat", "r").use { raf ->
//                raf.readLine() // first line
//            }
//            val toks = line.trim().split(Regex("\\s+"))
//            // toks[0] == "cpu"
//            var idle = 0L
//            var total = 0L
//            for (i in 1 until toks.size) {
//                val v = toks[i].toLongOrNull() ?: 0L
//                total += v
//                if (i == 4 /* idle */ || i == 5 /* iowait */) idle += v
//            }
//            return total to idle
//        } catch (_: Throwable) {
//            return 0L to 0L
//        }
//    }

//    fun cpuUsagePercent(): Pair<Double, Int> {
//        val (total, idle) = readCpuTotals()
//
//        if (!cpuPrimed) {
//            prevCpuTotal = total
//            prevCpuIdle  = idle
//            cpuPrimed = true
//            // First call has no delta; caller can ignore NaN or show "…"
//            return Pair(Double.NaN, Runtime.getRuntime().availableProcessors())
//        }
//
//        val dTotal = (total - prevCpuTotal).coerceAtLeast(1L).toDouble()
//        val dIdle  = (idle  - prevCpuIdle ).coerceAtLeast(0L).toDouble()
//
//        prevCpuTotal = total
//        prevCpuIdle  = idle
//
//        val busyPct = ((1.0 - dIdle / dTotal) * 100.0).coerceIn(0.0, 100.0)
//        return Pair(busyPct, Runtime.getRuntime().availableProcessors())
//    }




    private fun round1(v: Double): Double = ((v * 10.0).roundToInt()) / 10.0
}
