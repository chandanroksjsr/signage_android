package com.ebani.sinage.ai

import android.graphics.RectF
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Short-term identity tracker that assigns a stable personId to each face
 * using cosine(embedding) + IOU(bbox) heuristic. Tracks live for ttlMs.
 */
class IdentityTracker(
    private val simThreshold: Float = 0.75f,    // tune 0.6–0.85
    private val iouBoost: Float = 0.2f,         // gives spatial consistency a nudge
    private val ttlMs: Long = 120_000L          // 2 minutes
) {
    private data class Track(
        val id: String,
        var emb: FloatArray,
        var bbox: RectF,
        var lastSeen: Long
    )

    private val tracks = mutableListOf<Track>()

    fun update(now: Long, faces: List<DetFace>): List<Tracked> {
        // expire old tracks
        tracks.removeAll { now - it.lastSeen > ttlMs }

        val out = ArrayList<Tracked>(faces.size)
        for (f in faces) {
            var bestIdx = -1
            var bestScore = -1f
            for ((i, t) in tracks.withIndex()) {
                val s = cosine(f.embedding, t.emb) + iouBoost * iou(f.bbox, t.bbox)
                if (s > bestScore) { bestScore = s; bestIdx = i }
            }
            if (bestScore >= simThreshold && bestIdx >= 0) {
                val t = tracks[bestIdx]
                // Light EMA keeps track robust to small changes
                t.emb = blend(t.emb, f.embedding, 0.2f)
                t.bbox = f.bbox
                t.lastSeen = now
                out += Tracked(t.id, t.bbox, f.attrib)
            } else {
                val id = UUID.randomUUID().toString()
                tracks += Track(id, f.embedding, f.bbox, now)
                out += Tracked(id, f.bbox, f.attrib)
            }
        }
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix0 = max(a.left, b.left)
        val iy0 = max(a.top, b.top)
        val ix1 = min(a.right, b.right)
        val iy1 = min(a.bottom, b.bottom)
        val iw = (ix1 - ix0).coerceAtLeast(0f)
        val ih = (iy1 - iy0).coerceAtLeast(0f)
        val inter = iw * ih
        val uni = a.width() * a.height() + b.width() * b.height() - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    private fun blend(a: FloatArray, b: FloatArray, alpha: Float): FloatArray {
        val o = FloatArray(a.size)
        val beta = 1f - alpha
        for (i in a.indices) o[i] = a[i] * beta + b[i] * alpha
        return o
    }
}
