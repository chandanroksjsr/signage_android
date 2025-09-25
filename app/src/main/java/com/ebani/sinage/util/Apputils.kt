package com.ebani.sinage.util

import com.ebani.sinage.net.PlayerConfigDTO
import com.ebani.sinage.net.safeGetPlayerConfig
import java.util.Locale
import java.util.concurrent.TimeUnit

data object Apputils {
     fun formatBytes(b: Long): String {
        if (b < 1024L) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
        var v = b.toDouble()
        var steps = 0
        while (v >= 1024.0 && steps < units.size) {
            v /= 1024.0
            steps++
        }
        // steps = number of divisions; label is units[steps - 1]
        return String.format(java.util.Locale.US, "%.1f %s", v, units[(steps - 1).coerceAtLeast(0)])
    }


     fun formatRate(bps: Long): String =
        if (bps <= 0L) "—" else "${formatBytes(bps)}/s"

     fun formatEta(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else       String.format(Locale.US, "%d:%02d", m, s)
    }

    sealed class SyncDecision {
        object NotNeeded : SyncDecision()
        object Needed : SyncDecision()
        data class NeededAssetsOnly(val missingCount: Int) : SyncDecision()
        data class Unpaired(val deviceId: String) : SyncDecision()
        data class Error(val e: Throwable) : SyncDecision()
    }


    private fun sha1(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Build a deterministic string from stable fields only. */
    fun computeConfigFingerprint(cfg: PlayerConfigDTO): String {
        val sb = StringBuilder()

        // top-level bits
        sb.append("paired=").append(cfg.paired).append('|')
        cfg.screen?.let { s ->
            sb.append("screen:")
                .append(s.id ?: "").append('|')
                .append(s.name ?: "").append('|')
//                .append(s.updated_at ?: "").append('|')
                .append(s.resolution?.width ?: 0).append('x')
                .append(s.resolution?.height ?: 0).append('|')
        }

        // layout design
        sb.append("layout:")
            .append(cfg.layout.design.width).append('x')
            .append(cfg.layout.design.height).append('|')
            .append(cfg.layout.design.bgColor ?: "").append('|')

        // layout regions (don’t include URLs)
        cfg.layout.regions
            .sortedBy { it.id }
            .forEach { r ->
                sb.append("r:")
                    .append(r.id).append(':')
                    .append(r.x).append(',').append(r.y).append(',')
                    .append(r.w).append('x').append(r.h).append(':')
                    .append(r.z ?: 0).append(':')
                    .append(r.fit ?: "").append(':')
                    .append(r.playlistId ?: "").append('|')
            }

        // playlists + items (ignore asset.url; include ids/order/duration/type)
        cfg.playlists
            .sortedBy { it.id }
            .forEach { p ->
                sb.append("pl:").append(p.id).append(':')
                    .append(p.name ?: "").append(':')
//                    .append(p.updated_at ?: "").append('|')

                p.items.sortedBy { it.id }.forEach { itx ->
                    sb.append("it:")
                        .append(itx.id).append(':')
//                        .append(itx.assetId).append(':')
                        .append(itx.durationSec ?: 0).append(':')
                        .append((itx.asset?.mediaType ?: "")).append('|')
                }
            }

        return sha1(sb.toString())
    }
}