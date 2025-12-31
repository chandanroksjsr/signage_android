package com.ebani.sinage.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt
import timber.log.Timber

/** Public API */
interface FaceEmbedder {
    fun embed(faceBmp: Bitmap): FloatArray
    fun close()
}

/**
 * MobileFaceNet-friendly embedder (handles fixed batch=2, output [2,D]).
 * Default model name matches the Java you shared: "MobileFaceNet.tflite".
 */
class TFLiteFaceEmbedder(
    ctx: Context,
    private val assetModel: String = "MobileFaceNet.tflite",
    private val normalizeToMinus1To1: Boolean = false // toggle if embeddings seem “flat”
) : FaceEmbedder {

    internal val tflite: Interpreter
    private val inShape: IntArray
    private val inType: DataType
    private val inQScale: Float
    private val inQZero: Int
    private val B: Int
    private val H: Int
    private val W: Int
    private val C: Int

    private val outShape: IntArray
    private val outType: DataType
    private val outQScale: Float
    private val outQZero: Int
    private val OUT_B: Int
    private val D: Int

    init {
        tflite = Interpreter(mapModel(ctx, assetModel), Interpreter.Options().apply {
            // tune if you like:
            // setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        })

        // --- Input ---
        val inT: Tensor = tflite.getInputTensor(0)
        inShape = inT.shape()      // e.g. [2,112,112,3]
        inType  = inT.dataType()
        inT.quantizationParams().let { inQScale = it.scale; inQZero = it.zeroPoint }
        require(inShape.size == 4) { "Expected NHWC input; got ${inShape.contentToString()}" }
        B = inShape[0]; H = inShape[1]; W = inShape[2]; C = max(1, inShape[3])

        // --- Output ---
        val outT: Tensor = tflite.getOutputTensor(0)
        outShape = outT.shape()    // e.g. [2,192]
        outType  = outT.dataType()
        outT.quantizationParams().let { outQScale = it.scale; outQZero = it.zeroPoint }
        require(outShape.isNotEmpty()) { "Empty output shape" }
        OUT_B = outShape.first()           // usually 2
        D     = outShape.last()            // embedding length (192)

        Timber.i("[Embedder] %s | in=%s %s  out=%s %s  (B=%d,H=%d,W=%d,C=%d; D=%d)",
            assetModel, inShape.contentToString(), inType,
            outShape.contentToString(), outType, B, H, W, C, D
        )
    }

    override fun embed(faceBmp: Bitmap): FloatArray {
        // Build input for *B* samples; fill all batches with the SAME face.
        val input: Any = when (inType) {
            DataType.FLOAT32 -> makeFloatInput(faceBmp)
            DataType.UINT8, DataType.INT8 -> makeQuantInput(faceBmp)
            else -> makeFloatInput(faceBmp)
        }

        // Prepare an output holder that matches [OUT_B, D]
        return when (outType) {
            DataType.FLOAT32 -> {
                val out = Array(OUT_B) { FloatArray(D) }
                tflite.run(input, out)
                l2norm(out[0]) // take the first embedding
            }
            DataType.UINT8, DataType.INT8 -> {
                val raw = ByteArray(OUT_B * D)
                tflite.run(input, raw)
                val firstVec = FloatArray(D) { i ->
                    val u = raw[i].toInt() and 0xFF
                    (u - outQZero) * outQScale
                }
                l2norm(firstVec)
            }
            else -> {
                val out = Array(OUT_B) { FloatArray(D) }
                tflite.run(input, out)
                l2norm(out[0])
            }
        }
    }

    override fun close() { runCatching { tflite.close() } }

    // ---------------- helpers ----------------

    private fun makeFloatInput(src: Bitmap): ByteBuffer {
        val bmp = Bitmap.createScaledBitmap(src, W, H, true)
        val buf = ByteBuffer.allocateDirect(4 * B * H * W * C).order(ByteOrder.nativeOrder())
        repeat(B) {
            if (C == 1) {
                for (y in 0 until H) for (x in 0 until W) {
                    val p = bmp.getPixel(x, y)
                    val g = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    val v01 = g / 255f
                    buf.putFloat(if (normalizeToMinus1To1) v01 * 2f - 1f else v01)
                }
            } else {
                for (y in 0 until H) for (x in 0 until W) {
                    val p = bmp.getPixel(x, y)
                    val r = Color.red(p) / 255f
                    val g = Color.green(p) / 255f
                    val b = Color.blue(p) / 255f
                    if (normalizeToMinus1To1) {
                        buf.putFloat(r * 2f - 1f); buf.putFloat(g * 2f - 1f); buf.putFloat(b * 2f - 1f)
                    } else {
                        buf.putFloat(r); buf.putFloat(g); buf.putFloat(b)
                    }
                }
            }
        }
        buf.rewind(); return buf
    }

    private fun makeQuantInput(src: Bitmap): ByteBuffer {
        val bmp = Bitmap.createScaledBitmap(src, W, H, true)
        val buf = ByteBuffer.allocateDirect(B * H * W * C).order(ByteOrder.nativeOrder())
        val scale = if (inQScale > 0f) inQScale else 1f
        repeat(B) {
            if (C == 1) {
                for (y in 0 until H) for (x in 0 until W) {
                    val p = bmp.getPixel(x, y)
                    val g = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    val v01 = g / 255f
                    val q = (v01 / scale + inQZero).toInt().coerceIn(-128, 255)
                    buf.put(q.toByte())
                }
            } else {
                for (y in 0 until H) for (x in 0 until W) {
                    val p = bmp.getPixel(x, y)
                    val rn = Color.red(p) / 255f
                    val gn = Color.green(p) / 255f
                    val bn = Color.blue(p) / 255f
                    val rq = (rn / scale + inQZero).toInt().coerceIn(-128, 255)
                    val gq = (gn / scale + inQZero).toInt().coerceIn(-128, 255)
                    val bq = (bn / scale + inQZero).toInt().coerceIn(-128, 255)
                    buf.put(rq.toByte()); buf.put(gq.toByte()); buf.put(bq.toByte())
                }
            }
        }
        buf.rewind(); return buf
    }

    private fun l2norm(v: FloatArray): FloatArray {
        var s = 0f; for (x in v) s += x * x
        val inv = 1f / kotlin.math.sqrt(max(s, 1e-9f))
        for (i in v.indices) v[i] *= inv
        return v
    }

    private fun mapModel(ctx: Context, name: String): MappedByteBuffer {
        val afd = ctx.assets.openFd(name)
        FileInputStream(afd.fileDescriptor).channel.use {
            return it.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
}
