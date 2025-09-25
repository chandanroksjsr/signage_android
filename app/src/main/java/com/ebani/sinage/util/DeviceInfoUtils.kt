package com.ebani.sinage.util



import androidx.annotation.RequiresApi
import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DeviceInfoUtils {

    /** Collects comprehensive device info as JSON (no blocking I/O). */
    @RequiresApi(Build.VERSION_CODES.R)
    fun collect(context: Context): JSONObject = JSONObject().apply {
        val appCtx = context.applicationContext

        // ---- IDs / Locale / Time ----
        put("locale", Locale.getDefault().toLanguageTag())
        put("timezone", TimeZone.getDefault().id)
        put("uptimeMs", SystemClock.uptimeMillis())
        put("elapsedRealtimeMs", SystemClock.elapsedRealtime())

        // ---- App ----
        runCatching {
            val p = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            put("app", JSONObject().apply {
                put("package", appCtx.packageName)
                put("versionName", p.versionName)
                put("versionCode", p.longVersionCode)
            })
        }

        // ---- Build / Hardware ----
        put("build", JSONObject().apply {
            put("brand", Build.BRAND)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("product", Build.PRODUCT)
            put("device", Build.DEVICE)
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
            put("fingerprint", Build.FINGERPRINT)
            put("type", Build.TYPE)
            put("tags", Build.TAGS)
            put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS?.toList() ?: emptyList<String>()))
            put("bootloader", Build.BOOTLOADER)
            put("radio", Build.getRadioVersion() ?: "")
            put("serialHint", if (Build.VERSION.SDK_INT >= 26) "" else Build.SERIAL) // empty on 26+
        })

        // ---- Android / SDK ----
        put("android", JSONObject().apply {
            put("release", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("securityPatch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "")
            put("previewSdkInt", Build.VERSION.PREVIEW_SDK_INT)
        })

        // ---- Display ----
        put("display", screenInfo(context))

        // ---- Memory (RAM) ----
        put("memory", JSONObject().apply {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            put("totalMem", mi.totalMem)
            put("availMem", mi.availMem)
            put("lowMemory", mi.lowMemory)
            put("threshold", mi.threshold)
            put("memoryClassMB", am.memoryClass)
            put("largeMemoryClassMB", am.largeMemoryClass)
        })

        // ---- Storage ----
        put("storage", JSONObject().apply {
            fun stat(path: String): JSONObject {
                val s = StatFs(path)
                return JSONObject().apply {
                    put("path", path)
                    put("totalBytes", s.totalBytes)
                    put("freeBytes", s.freeBytes)
                    put("availableBytes", s.availableBytes)
                }
            }
            put("internalData", stat(Environment.getDataDirectory().absolutePath))
            appCtx.getExternalFilesDir(null)?.let { put("appExternal", stat(it.absolutePath)) }
        })

        // ---- Battery ----
        put("battery", JSONObject().apply {
            val bm = appCtx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            put("capacityPercent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("chargeCounteruAh", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            put("currentNowuA", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            put("energyuWh", if (Build.VERSION.SDK_INT >= 34)
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) else 0)
            // quick static hints
            put("isCharging", bm.isCharging(appCtx))
        })

        // ---- Network ----
        put("network", JSONObject().apply {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val an = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(an)
            put("transportWifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            put("transportCellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
            put("transportEthernet", caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
            put("metered", cm.isActiveNetworkMetered)

            // Wi-Fi (SSID requires location permission on modern Android; may be "<unknown ssid>")
            runCatching {
                val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                put("wifi", JSONObject().apply {
                    put("ssid", info.ssid ?: "")
                    put("bssid", info.bssid ?: "")
                    put("ip", info.ipAddress)
                    put("linkSpeedMbps", info.linkSpeed)
                    put("rssi", info.rssi)
                })
            }
        })

        // ---- Cameras ----
        put("cameras", JSONObject().apply {
            runCatching {
                val cmgr = appCtx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val list = cmgr.cameraIdList.map { id ->
                    val ch = cmgr.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    val hwLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    JSONObject().apply {
                        put("id", id)
                        put("facing",
                            when (facing) {
                                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                                CameraCharacteristics.LENS_FACING_BACK -> "back"
                                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                                else -> "unknown"
                            })
                        put("hardwareLevel", when (hwLevel) {
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                            else -> "UNKNOWN"
                        })
                    }
                }
                put("count", list.size)
                put("list", JSONArray(list))
            }
        })

        // ---- Sensors (names only, keeps payload small) ----
        put("sensors", JSONObject().apply {
            runCatching {
                val sm = appCtx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val names = sm.getSensorList(Sensor.TYPE_ALL).map { it.name }.distinct()
                put("count", names.size)
                put("names", JSONArray(names))
            }
        })

        // ---- “Root” heuristics (very light) ----
        put("securityHints", JSONObject().apply {
            val tags = Build.TAGS ?: ""
            val testKeys = tags.contains("test-keys", ignoreCase = true)
            val suPaths = listOf(
                "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
                "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su"
            )
            val suExists = suPaths.any { kotlin.runCatching { java.io.File(it).exists() }.getOrDefault(false) }
            put("buildTagsTestKeys", testKeys)
            put("suBinaryFound", suExists)
        })
    }

    private fun BatteryManager.isCharging(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isCharging
        } else {
            // Fallback via ACTION_BATTERY_CHANGED broadcast (older APIs)
            val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun screenInfo(ctx: Context): JSONObject {
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        val pxW = dm.widthPixels
        val pxH = dm.heightPixels
        val dpi = dm.densityDpi
        val density = dm.density

        val dpW = (pxW / density).roundToInt()
        val dpH = (pxH / density).roundToInt()
        val inchesW = if (dpi > 0) pxW / dpi.toDouble() else 0.0
        val inchesH = if (dpi > 0) pxH / dpi.toDouble() else 0.0
        val diagInches = sqrt(inchesW.pow(2) + inchesH.pow(2))

        val refresh = runCatching {
            // Prefer activity/display when available; fallback ~60Hz
            ctx.display?.refreshRate ?: 60f
        }.getOrElse { 60f }

        return JSONObject().apply {
            put("px", JSONObject().apply { put("w", pxW); put("h", pxH) })
            put("dp", JSONObject().apply { put("w", dpW); put("h", dpH) })
            put("density", density)
            put("densityDpi", dpi)
            put("approxDiagonalInches", String.format(Locale.US, "%.2f", diagInches))
            put("refreshHz", refresh.roundToInt())
            put("orientation", if (pxW >= pxH) "landscape" else "portrait")
        }
    }

    private fun Int.ipAddressIntToString(): String {
        // WifiInfo.ipAddress is little-endian int
        return listOf(this and 0xff, (this shr 8) and 0xff, (this shr 16) and 0xff, (this shr 24) and 0xff)
            .joinToString(".")
    }
}
