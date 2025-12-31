package com.ebani.sinage.ai

import android.graphics.RectF

/**
 * Per-face attributes predicted by your TFLite A/G/E model.
 */
data class FaceAttrib(
    val age: Float,              // 0..100 (or your model's range)
    val gender: String,          // "male" | "female" | "unknown"
    val emotion: String,
    val looking: Boolean// e.g., "happy","neutral","sad","angry","surprise","fear","disgust"
)

/**
 * Playback context for the item currently showing inside a region.
 * Emitted by PlayerController via PlaybackListener.
 */
data class PlaybackCtx(
    val regionId: String,
    val playlistId: String?,
    val assetId: String,
    val mediaType: String,
    val startedAtMs: Long,
    val runId: String                   // NEW
)

/**
 * One detected face in the current frame (after you cropped and embedded).
 */
data class DetFace(
    val bbox: RectF,             // in camera/image coords (not screen)
    val embedding: FloatArray,   // L2-normalized vector from FaceEmbedder
    val attrib: FaceAttrib       // A/G/E predicted for this face
)

/**
 * Output of identity tracking for a frame, with a stable personId.
 */
data class Tracked(
    val personId: String,
    val bbox: RectF,
    val attrib: FaceAttrib
)
