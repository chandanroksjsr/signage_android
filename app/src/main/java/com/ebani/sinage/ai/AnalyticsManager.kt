package com.ebani.sinage.ai

import com.ebani.sinage.data.db.AppDatabase
import com.ebani.sinage.data.model.AnalyticsEntity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Aggregates per-frame detections into time windows and persists analytics rows.
 * Also tracks unique persons (2-minute memory) to compute dwell per asset.
 */
class AnalyticsManager(
    private val db: AppDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val windowMs: Long = 1_000L
) {
    // Current playback context per region
    private val current = ConcurrentHashMap<String, PlaybackCtx>()

    /** In-memory dedupe: key = "$runId:$tick". */
    private val written = ConcurrentHashMap.newKeySet<String>()

    fun start() { /* no-op; kept for lifecycle symmetry */ }

    fun stop() {
        runCatching { (scope.coroutineContext[Job] as? Job)?.cancel() }
    }

    // ---------------- Playback lifecycle ----------------

    fun onAssetStart(ctx: PlaybackCtx) {
        current[ctx.regionId] = ctx
        // best-effort cleanup for any earlier keys of the same run (usually none; runId is a UUID)
        written.removeIf { it.startsWith("${ctx.runId}:") }
    }

    fun onAssetEnd(regionId: String, endedAtMs: Long) {
        current.remove(regionId)?.let { endedCtx ->
            // optional: clear dedupe keys for the finished run
            written.removeIf { it.startsWith("${endedCtx.runId}:") }
        }
    }

    // ---------------- Snapshot writer (called once per 5s from PlayerActivity) ----------------

    /**
     * Write one analytics row for the current (region, run) snapshot.
     * PlayerActivity already ensures this is invoked at most once per 5s window per run.
     * We still compute a 'tick' and dedupe defensively here.
     */
    fun onDetections(regionId: String, faces: List<FaceAttrib>) {
        val ctx = current[regionId] ?: return
        val now = System.currentTimeMillis()

        // tick number since this asset started; 0,1,2... every 5 seconds
        val tick = (now - ctx.startedAtMs) / 10_000L
        val key = "${ctx.runId}:$tick"
        if (!written.add(key)) return  // already recorded this run+tick

        var male = 0
        var female = 0
        var unknown = 0

        // Build detections JSON array: [{gender, age, emotion}, ...]
        val detections = JSONArray()
        faces.forEach { f ->
            when (f.gender.lowercase()) {
                "male"   -> male++
                "female" -> female++
                else     -> unknown++
            }
            detections.put(
                JSONObject().apply {
                    put("gender",  f.gender)
                    put("age",     (f.age * 10).roundToInt() / 10.0) // 1 decimal; adjust as needed
                    put("emotion", f.emotion)
                    put("looking", f.looking)
                }
            )
        }

        val row = AnalyticsEntity(
            ts = now,
            runId = ctx.runId,
            assetId = ctx.assetId,
            regionId = ctx.regionId,
            playlistId = ctx.playlistId,
            detectionsJson = detections.toString(),
            totalMale = male,
            totalFemale = female,
            unknowns = unknown
        )

        scope.launch {
            runCatching { db.analytics().insert(row) }
        }
    }

    // ----- Identity-aware tracking (uniques + dwell) -----

    /**
     * Update unique-person dwell. Provide a mapper that assigns each tracked face to a regionId.
     * Example mapper: `{ _ -> lastActiveRegionId ?: "r0" }` or geometric mapping using bbox.
     */
    fun onTracked(now: Long, regionIdForFace: (Tracked) -> String, people: List<Tracked>) {
//        people.forEach { tr ->
//            val rid = regionIdForFace(tr)
//            val ctxPlayback = current[rid] ?: return@forEach
//            val pc = persons[tr.personId]
//            if (pc == null || pc.assetId != ctxPlayback.assetId || pc.regionId != ctxPlayback.regionId) {
//                persons[tr.personId] = PersonCtx(
//                    personId = tr.personId,
//                    regionId = ctxPlayback.regionId,
//                    playlistId = ctxPlayback.playlistId,
//                    assetId = ctxPlayback.assetId,
//                    mediaType = ctxPlayback.mediaType,
//                    firstSeen = now,
//                    lastSeen = now
//                )
//            } else {
//                pc.lastSeen = now
//                pc.regionId = ctxPlayback.regionId
//                pc.playlistId = ctxPlayback.playlistId
//                pc.mediaType = ctxPlayback.mediaType
//            }
//        }
    }

    /**
     * When an asset ends in a region, persist one-row dwell per unique person attached to that asset.
     * This complements the windowed rows written every windowMs.
     */

}
