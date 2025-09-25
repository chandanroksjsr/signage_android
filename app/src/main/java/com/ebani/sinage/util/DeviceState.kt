package com.ebani.sinage.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.p.DevicePrefs
import com.ebani.sinage.net.LayoutConfigDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

sealed class DeviceState {
    data object UnpairedOffline : DeviceState()
    data object UnpairedOnline : DeviceState()
    data object PairedOnlineNoContent : DeviceState()
    data object PairedOnlinePreparing : DeviceState()   // assigned, downloading
    data object PairedOnlineReady : DeviceState()       // assigned, playable cache present
    data object PairedOfflineReady : DeviceState()      // offline but can play from cache
    data object PairedOfflineNoCache : DeviceState()    // offline + nothing cached
    data object PairedDegradedPartial : DeviceState()   // online; some items missing, some present
}

private fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Snapshot gathered off the main thread to avoid Room’s main-thread guard. */
private data class Snapshot(
    val paired: Boolean,
    val playlistIds: Set<String>,
    val anyLocal: Boolean,
    val missingCount: Int
)

/** Hands-free state evaluator. All DB/file work happens on Dispatchers.IO. */
suspend fun evaluateDeviceState(ctx: Context): DeviceState {
    val online = isOnline(ctx)

    val snap = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(ctx)
        val prefs = DevicePrefs(ctx)
        val json = Json { ignoreUnknownKeys = true }

        // Paired? (device row present)
        val paired = try {
            db.device().getDeviceConfig() != null
        } catch (_: Throwable) { false }

        // What is assigned per cached layout (cheap; no network)?
        val playlistIds: Set<String> = try {
            val layoutJson = prefs.layoutJson
            if (layoutJson.isNullOrBlank()) emptySet()
            else json.decodeFromString(LayoutConfigDTO.serializer(), layoutJson)
                .regions.mapNotNull { it.playlistId }.toSet()
        } catch (_: Throwable) { emptySet() }

        // Local cache coverage
        var totalRequired = 0
        var haveFiles = 0
        for (pid in playlistIds) {
            val items = try { db.playlistItems().itemsForPlaylist(pid) } catch (_: Throwable) { emptyList() }
            totalRequired += items.size
            for (itx in items) {
                val a = try { db.assets().findById(itx.assetId) } catch (_: Throwable) { null }
                val lp = a?.localPath
                if (!lp.isNullOrBlank() && File(lp).isFile) haveFiles++
            }
        }
        val anyLocal = haveFiles > 0
        val missing = (totalRequired - haveFiles).coerceAtLeast(0)

        Snapshot(paired = paired, playlistIds = playlistIds, anyLocal = anyLocal, missingCount = missing)
    }

    val hasAssignment = snap.playlistIds.isNotEmpty()

    return when {
        !snap.paired && !online -> DeviceState.UnpairedOffline
        !snap.paired && online  -> DeviceState.UnpairedOnline

        snap.paired && !hasAssignment && online -> DeviceState.PairedOnlineNoContent
        snap.paired && !hasAssignment && !online -> DeviceState.PairedOfflineNoCache

        snap.paired && hasAssignment && online && !snap.anyLocal -> DeviceState.PairedOnlinePreparing
        snap.paired && hasAssignment && online && snap.anyLocal && snap.missingCount > 0 -> DeviceState.PairedDegradedPartial
        snap.paired && hasAssignment && online && snap.anyLocal -> DeviceState.PairedOnlineReady

        snap.paired && hasAssignment && !online && snap.anyLocal -> DeviceState.PairedOfflineReady
        snap.paired && hasAssignment && !online && !snap.anyLocal -> DeviceState.PairedOfflineNoCache

        else -> DeviceState.PairedOnlineNoContent
    }
}
