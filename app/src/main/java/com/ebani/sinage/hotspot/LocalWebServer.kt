package com.ebani.sinage.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class LocalWebServer(
    private val context: Context,
    private val port: Int = 8080,
    private val onFirstClientSeen: ((String) -> Unit)? = null
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        // Your SetupHostService should listen for this broadcast to actually apply Wi-Fi.
        const val ACTION_APPLY_WIFI = "com.ebani.sinage.action.APPLY_WIFI"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        // Where we stash the last requested creds (optional; useful for debugging).
        private const val SP_NAME = "setup_wifi"
        private const val SP_SSID = "ssid"
        private const val SP_PASS = "pass"
    }

    @Volatile private var announced = false

    override fun serve(session: IHTTPSession): Response {
        // 0) first client seen → signal UI to hide QR, etc.
        val ip = try { session.remoteIpAddress ?: "unknown" } catch (_: Throwable) { "unknown" }
        if (!announced && ip != "127.0.0.1" && ip != "::1") {
            announced = true
            onFirstClientSeen?.invoke(ip)
        }

        // 1) CORS preflight
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                .withCors()
        }

        // 2) Captive portal nudges → redirect to our UI
        val path = session.uri.lowercase(Locale.US)
        if (path == "/generate_204" || path == "/gen_204" ||
            path.endsWith("/hotspot-detect.html") || path.endsWith("/success.html") ||
            path == "/connecttest.txt" || path == "/ncsi.txt") {
            return redirect("${SetupHostService}:$port/").withCors()
        }

        return try {
            when {
                // ---------- API ----------
                path == "/api/ping" && session.method == Method.GET -> {
                    json(200, """{"ok":true}""")
                }

                path == "/api/scan" && session.method == Method.GET -> {
                    apiScan()
                }

                path == "/api/status" && session.method == Method.GET -> {
                    apiStatus()
                }

                path == "/api/wifi" && session.method == Method.POST -> {
                    apiWifi(session)
                }

                // ---------- Static ----------
                path == "/" || path == "/index.html" -> assetResponse("web/index.html")
                else -> {
                    val assetPath = "web/" + session.uri.removePrefix("/").ifEmpty { "index.html" }
                    assetResponse(assetPath)
                }
            }.withCors()
        } catch (t: Throwable) {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not Found"
            ).withCors()
        }
    }

    // ---------- API impl ----------

    @SuppressLint("MissingPermission")
    private fun apiScan(): Response {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Note: startScan() is best-effort & may be rate-limited. We just read current results.
        val results = runCatching { wifi.scanResults }.getOrDefault(emptyList())

        // Make a concise DTO with security flag
        val items = results
            .groupBy { it.SSID ?: "" } // de-dup by SSID; keep strongest
            .mapNotNull { (ssid, list) ->
                if (ssid.isNullOrEmpty()) return@mapNotNull null
                val best = list.maxByOrNull { it.level } ?: return@mapNotNull null
                val caps = best.capabilities?.uppercase(Locale.US) ?: ""
                val secure = caps.contains("WEP") || caps.contains("WPA") || caps.contains("EAP")
                NetworkDTO(ssid, best.level, secure)
            }
            .sortedWith(
                compareByDescending<NetworkDTO> { it.secure } // secured first
                    .thenByDescending { it.level }            // stronger first
                    .thenBy { it.ssid.lowercase(Locale.US) }
            )

        val body = buildString {
            append("""{"networks":[""")
            append(items.joinToString(",") {
                """{"ssid":${js(it.ssid)},"level":${it.level},"secure":${it.secure}}"""
            })
            append("]}")
        }
        return json(200, body)
    }

    private fun apiStatus(): Response {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        var ssid: String? = null
        var ip: String? = null
        if (onWifi) {
            val info: WifiInfo? = runCatching { wifi.connectionInfo }.getOrNull()
            ssid = info?.ssid?.trim('"')
            // IP (client mode): dhcpInfo.ipAddress; in SoftAP mode the LAN IP is often 192.168.49.1
            val ipInt = runCatching { wifi.dhcpInfo?.ipAddress ?: 0 }.getOrNull() ?: 0
            ip = if (ipInt != 0) Formatter.formatIpAddress(ipInt) else "192.168.49.1"
        }

        val body = """{"connected":$onWifi,"ssid":${jsOrNull(ssid)},"ip":${jsOrNull(ip)}}"""
        return json(200, body)
    }

    private fun apiWifi(session: IHTTPSession): Response {
        val body = readBody(session)
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return json(400, """{"ok":false,"error":"invalid_json"}""")

        val ssid = obj.optString("ssid", "").trim()
        val password = obj.optString("password", null)

        if (ssid.isEmpty()) return json(400, """{"ok":false,"error":"ssid_required"}""")

        // Persist (optional; helps your service recover after process death)
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putString(SP_SSID, ssid).putString(SP_PASS, password).apply()

        // Broadcast to SetupHostService (or a receiver) to actually attempt the connection.
        context.sendBroadcast(
            android.content.Intent(ACTION_APPLY_WIFI)
                .putExtra(EXTRA_SSID, ssid)
                .putExtra(EXTRA_PASSWORD, password)
        )

        return json(200, """{"ok":true}""")
    }

    // ---------- helpers ----------

    private data class NetworkDTO(val ssid: String, val level: Int, val secure: Boolean)

    private fun redirect(to: String): Response =
        newFixedLengthResponse(
            Response.Status.REDIRECT, "text/html",
            "<!doctype html><meta http-equiv='refresh' content='0;url=$to'>"
        ).apply { addHeader("Location", to) }

    private fun assetResponse(assetPath: String): Response {
        val am = context.assets
        val stream: InputStream = am.open(assetPath) // throws if missing → caught by caller
        val ext = assetPath.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: guessMime(ext)
        return newChunkedResponse(Response.Status.OK, mime, stream)
    }

    private fun json(code: Int, body: String) =
        newFixedLengthResponse(Response.Status.lookup(code), "application/json", body)

    private fun Response.withCors(): Response = apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "*")
        addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        addHeader("Cache-Control", "no-store, max-age=0")
    }

    private fun guessMime(ext: String) = when (ext) {
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "html" -> "text/html"
        else -> "text/plain"
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun jsOrNull(s: String?) = s?.let { js(it) } ?: "null"
}
