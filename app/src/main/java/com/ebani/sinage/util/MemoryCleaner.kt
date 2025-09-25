// com/ebani/sinage/util/MemoryCleaner.kt
package com.ebani.sinage.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import coil.Coil

object MemoryCleaner {
    /** Free as much of *your app’s* RAM as possible (no user-data loss). */
    fun purgeAppMemory(
        ctx: Context,
        releaseVideoSurfaces: (() -> Unit)? = null,
        extraCleanup: (() -> Unit)? = null
    ) {
        runCatching { releaseVideoSurfaces?.invoke() }  // stop players & free surfaces

        // Trim & clear image caches (Coil singleton)
        runCatching {
            val loader = Coil.imageLoader(ctx)
            loader.memoryCache?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
//            loader.bitmapPool?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            loader.memoryCache?.clear()
//            loader.diskCache?.clear()
        }

        // If you use any WebView (HTML apps), clear transient storage
        runCatching {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().flush()
        }

        // App-specific refs (maps, lists, overlays)
        runCatching { extraCleanup?.invoke() }

        // Hint GC after big releases (not guaranteed, but fine post-sync)
        runCatching { System.gc() }
    }
}
