// com/ebani/sinage/media/VideoOptimizer.kt
package com.ebani.sinage.data.repo

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

data class OptimizeSpec(
    val maxWidth: Int,
    val maxHeight: Int,
    // kept for future use when your APIs support forcing codec
    val targetMime: String = "video/avc"
)

data class Probe(
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val bitrate: Int?
)

object VideoOptimizer {

    fun probe(path: String): Probe {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            Probe(mime, w, h, br)
        } catch (_: Throwable) {
            Probe(null, null, null, null)
        } finally {
            r.release()
        }
    }

    /** Conservative: transcode if dimensions are unknown or exceed bounds. */
    fun needsTranscode(p: Probe, spec: OptimizeSpec): Boolean {
        val hasDims = p.width != null && p.height != null
        if (!hasDims) return true
        val wOk = (p.width ?: Int.MAX_VALUE) <= spec.maxWidth
        val hOk = (p.height ?: Int.MAX_VALUE) <= spec.maxHeight
        return !(wOk && hOk)
    }

    /**
     * Minimal, 1.8.0-compatible transcode.
     * No explicit scaling/codec (the missing APIs in your setup). Everything runs on a dedicated looper.
     */
    @UnstableApi
    suspend fun transcode(
        context: Context,
        inputPath: String,
        outputPath: String,
        spec: OptimizeSpec
    ): File? {
        val inFile = File(inputPath)
        val outFile = File(outputPath).apply { if (exists()) delete() }

        val mediaItem = MediaItem.fromUri(Uri.fromFile(inFile))
        val edited = EditedMediaItem.Builder(mediaItem).build()

        // Build and use Transformer on the SAME application looper (HandlerThread).
        val thread = android.os.HandlerThread("transformer-thread").apply { start() }
        val looper = thread.looper
        val handler = android.os.Handler(looper)

        return suspendCancellableCoroutine { cont ->
            handler.post {
                // Build ON this looper so internal applicationLooper == thread.looper
                val transformer = Transformer.Builder(context).build()

                fun finish(file: File?) {
                    cont.resume(file)
                    handler.post { thread.quitSafely() }
                }

                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult
                    ) {
                        finish(outFile)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        outFile.delete()
                        finish(null)
                    }
                })

                transformer.start(edited, outFile.absolutePath)

                // Cancellation must run on the same application thread.
                cont.invokeOnCancellation {
                    handler.post {
                        runCatching { transformer.cancel() }
                        outFile.delete()
                        thread.quitSafely()
                    }
                }
            }
        }
    }
}
