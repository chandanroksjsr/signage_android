
package com.ebani.sinage.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import org.json.JSONObject

object DisplayUtils {

    /** Returns a JSON object with physical width/height (px), density, and orientation. */
    fun screenInfoJson(ctx: Context): JSONObject {
        val dm = ctx.resources.displayMetrics
        var widthPx = dm.widthPixels
        var heightPx = dm.heightPixels

        // Prefer physical mode size when available (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = ctx.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            if (mode != null) {
                widthPx = mode.physicalWidth
                heightPx = mode.physicalHeight
            }
        } else {
            // Legacy: get real size including system bars
            @Suppress("DEPRECATION")
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            widthPx = p.x
            heightPx = p.y
        }

        val orientation = when (ctx.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }

        return JSONObject()
            .put("width", widthPx)
            .put("height", heightPx)
            .put("densityDpi", dm.densityDpi)
            .put("density", dm.density.toDouble())
            .put("orientation", orientation)
    }
}
