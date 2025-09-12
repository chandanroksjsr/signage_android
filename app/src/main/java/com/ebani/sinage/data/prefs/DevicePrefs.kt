package com.ebani.sinage.data.p

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

@SuppressLint("HardwareIds")
class DevicePrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val appContext = ctx.applicationContext

    val deviceId: String by lazy {
        // Try persisted preference first
        val stored = p.getString("deviceId", null)
        if (stored != null) return@lazy stored

        // Fallback to system Android ID
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        // Persist it for consistency
        p.edit().putString("deviceId", androidId).apply()
        androidId
    }

    var isRegistered: Boolean
        get() = p.getBoolean("registered", false)
        set(v) { p.edit().putBoolean("registered", v).apply() }

    var playlistId: String?
        get() = p.getString("playlistId", null)
        set(v) { p.edit().putString("playlistId", v).apply() }

    // In DevicePrefs
    var screenWidth: Int
        get() = p.getInt("screen_width", 1080)
        set(v) = p.edit().putInt("screen_width", v).apply()

    var screenHeight: Int
        get() = p.getInt("screen_height", 1920)
        set(v) = p.edit().putInt("screen_height", v).apply()

    var layoutJson: String?
        get() = p.getString("layout_json", null)
        set(v) = p.edit().putString("layout_json", v).apply()

    var lastConfigHash: String?
        get() = p.getString("last_config_hash", null)
        set(v) = p.edit().putString("last_config_hash", v).apply()

}
