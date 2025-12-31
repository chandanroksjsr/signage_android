package com.ebani.sinage.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.*
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Loads THREE TFLite models (age + gender + emotion) from assets and predicts FaceAttrib.
 *
 * Age/Gender models are from shubham0204/Age-Gender_Estimation_TF-Android:
 *  - Age input:    200x200 RGB; output in (0,1] → multiply by 116 to get years.
 *  - Gender input: 128x128 RGB; output softmax for ["male","female"].
 *
 * Emotion model is from vicksam/fer-app:
 *  - Input: 48x48 grayscale, float32 in [0,1]; output 8 logits → softmax.
 */
class AgeGenderEmotionDetector(
    ctx: Context,
    ageModelAsset: String = "model_age_nonq.tflite",
    genderModelAsset: String = "model_gender_nonq.tflite",
    private val unknownMargin: Float = 0.55f,     // gender: if max prob < margin → "unknown"
    emotionModelAsset: String = "fer_model.tflite",
    emotionLabelsAsset: String = "fer_model.names",
    private val emotionUnknownMargin: Float = 0.25f // if top prob < margin → "neutral" (if label exists)
) {
    // ---- Age ----
    private val ageTflite: Interpreter
    private val ageInputShape: IntArray
    private val ageInputType: DataType
    private val ageQuantScale: Float
    private val ageQuantZero: Int

    // ---- Gender ----
    private val genderTflite: Interpreter
    private val genderInputShape: IntArray
    private val genderInputType: DataType
    private val genderQuantScale: Float
    private val genderQuantZero: Int

    // ---- Emotion ----
    private val emotionTflite: Interpreter
    private val emotionInputShape: IntArray
    private val emotionInputType: DataType
    private val emotionInputQuantScale: Float
    private val emotionInputQuantZero: Int
    private val emotionOutputType: DataType
    private val emotionOutputQuantScale: Float
    private val emotionOutputQuantZero: Int
    private val emotionLabels: List<String>
    private val emotionClasses: Int

    init {
        val opts = Interpreter.Options()

        // Age
        ageTflite = Interpreter(mapModel(ctx, ageModelAsset), opts)
        ageInputShape = ageTflite.getInputTensor(0).shape()
        ageInputType = ageTflite.getInputTensor(0).dataType()
        ageTflite.getInputTensor(0).quantizationParams().let {
            ageQuantScale = it.scale
            ageQuantZero = it.zeroPoint
        }

        // Gender
        genderTflite = Interpreter(mapModel(ctx, genderModelAsset), opts)
        genderInputShape = genderTflite.getInputTensor(0).shape()
        genderInputType = genderTflite.getInputTensor(0).dataType()
        genderTflite.getInputTensor(0).quantizationParams().let {
            genderQuantScale = it.scale
            genderQuantZero = it.zeroPoint
        }

        // Emotion
        emotionTflite = Interpreter(mapModel(ctx, emotionModelAsset), opts)
        emotionInputShape = emotionTflite.getInputTensor(0).shape() // [1,48,48,1]
        emotionInputType = emotionTflite.getInputTensor(0).dataType()
        emotionTflite.getInputTensor(0).quantizationParams().let {
            emotionInputQuantScale = it.scale
            emotionInputQuantZero = it.zeroPoint
        }
        val emoOutTensor: Tensor = emotionTflite.getOutputTensor(0)
        emotionOutputType = emoOutTensor.dataType()
        emoOutTensor.quantizationParams().let {
            emotionOutputQuantScale = it.scale
            emotionOutputQuantZero = it.zeroPoint
        }
        emotionClasses = emoOutTensor.numElements()
        emotionLabels = loadLabels(ctx, emotionLabelsAsset)

        require(ageInputShape.size >= 4 && genderInputShape.size >= 4) {
            "Unexpected input ranks: age=${ageInputShape.contentToString()} gender=${genderInputShape.contentToString()}"
        }
    }

    fun close() {
        runCatching { ageTflite.close() }
        runCatching { genderTflite.close() }
        runCatching { emotionTflite.close() }
    }

    // ---------------- Public API ----------------

    /**
     * Basic A/G/E only (non-breaking). If you also want gaze, call [computeGaze] too
     * or use [inferWithGaze].
     */
    // existing public
    fun inferOnCroppedFace(faceBmp: Bitmap): FaceAttrib =
        inferOnCroppedFace(faceBmp, null)

    // new overload using ML Kit pose/eyes
    fun inferOnCroppedFace(faceBmp: Bitmap, hints: GazeHints?): FaceAttrib {
        val age = predictAge(faceBmp)
        val gender = predictGender(faceBmp)
        val emotion = predictEmotion(faceBmp)
        val looking = inferLooking(hints)
        return FaceAttrib(age = age, gender = gender, emotion = emotion, looking = looking)
    }
    data class GazeHints(
        val yawDeg: Float? = null,          // ML Kit: headEulerAngleY (left/right)
        val pitchDeg: Float? = null,        // ML Kit: headEulerAngleX (up/down)
        val leftEyeOpenProb: Float? = null, // 0..1 (may be null)
        val rightEyeOpenProb: Float? = null // 0..1 (may be null)
    )
    private fun inferLooking(hints: GazeHints?): Boolean {
        if (hints == null) return true // default optimistic if we have no pose info

        // tunables
        val YAW_LIMIT = 18f    // deg (left/right)
        val PITCH_LIMIT = 18f  // deg (up/down)
        val EYES_MIN = 0.35f   // average open prob threshold

        val yawOk = hints.yawDeg?.let { kotlin.math.abs(it) <= YAW_LIMIT } ?: true
        val pitchOk = hints.pitchDeg?.let { kotlin.math.abs(it) <= PITCH_LIMIT } ?: true

        // eye-open average if available; if ML Kit didn't provide, don't block
        val eyes =
            if (hints.leftEyeOpenProb != null && hints.rightEyeOpenProb != null)
                (hints.leftEyeOpenProb + hints.rightEyeOpenProb) / 2f
            else null
        val eyesOk = eyes?.let { it >= EYES_MIN } ?: true

        return yawOk && pitchOk && eyesOk
    }
    /**
     * Convenience: returns A/G/E + gaze in one call.
     * Pass ML Kit Euler angles + eye open probs if available.
     *
     * @param eulerYawDeg   left(-)/right(+) head yaw in degrees (ML Kit: headEulerAngleY)
     * @param eulerPitchDeg up(-)/down(+) head pitch in degrees  (ML Kit: headEulerAngleX)
     * @param leftEyeOpenProb/rightEyeOpenProb in [0..1] or -1 if not available
     */
    fun inferWithGaze(
        faceBmp: Bitmap,
        eulerYawDeg: Float? = null,
        eulerPitchDeg: Float? = null,
        leftEyeOpenProb: Float? = null,
        rightEyeOpenProb: Float? = null
    ): Pair<FaceAttrib, GazeResult> {
        val attrib = inferOnCroppedFace(faceBmp)
        val gaze = computeGaze(eulerYawDeg, eulerPitchDeg, leftEyeOpenProb, rightEyeOpenProb)
        return attrib to gaze
    }

    /**
     * Determines if the person is **looking at the screen** based on head pose + eyes.
     * Works best when you pass Euler angles from ML Kit Face Detection.
     *
     * Heuristic (tunable):
     *  - Looking if |yaw| ≤ 15° and |pitch| ≤ 20° (with soft confidence falloff).
     *  - Eye-open probabilities (if provided) boost confidence; both < 0.15 reduces it.
     */
    fun computeGaze(
        eulerYawDeg: Float? = null,
        eulerPitchDeg: Float? = null,
        leftEyeOpenProb: Float? = null,
        rightEyeOpenProb: Float? = null
    ): GazeResult {
        // Thresholds & soft ranges
        val ON_YAW = 15f
        val ON_PITCH = 20f
        val MAX_YAW = 45f     // soft-clip for confidence
        val MAX_PITCH = 35f

        val yaw = eulerYawDeg
        val pitch = eulerPitchDeg

        val eyeL = leftEyeOpenProb?.takeIf { it >= 0f && it <= 1f }
        val eyeR = rightEyeOpenProb?.takeIf { it >= 0f && it <= 1f }
        val eyeAvg = when {
            eyeL != null && eyeR != null -> (eyeL + eyeR) / 2f
            eyeL != null -> eyeL
            eyeR != null -> eyeR
            else -> null
        }

        // Base confidence from pose
        val yawScore = yaw?.let { 1f - (abs(it) / MAX_YAW).coerceIn(0f, 1f) } ?: 0.6f
        val pitchScore = pitch?.let { 1f - (abs(it) / MAX_PITCH).coerceIn(0f, 1f) } ?: 0.6f

        // Eye contribution
        val eyeScore = eyeAvg ?: 0.8f
        val conf = (yawScore * 0.5f + pitchScore * 0.3f + eyeScore * 0.2f)
            .coerceIn(0f, 1f)

        // Binary decision with hysteresis-like guard using pose thresholds
        val lookingPose =
            (yaw == null || abs(yaw) <= ON_YAW) &&
                    (pitch == null || abs(pitch) <= ON_PITCH)

        // Penalize if both eyes almost surely closed
        val eyesOk = eyeAvg == null || eyeAvg >= 0.15f

        val looking = lookingPose && eyesOk && conf >= 0.55f
        return GazeResult(looking = looking, confidence = conf, yawDeg = yaw, pitchDeg = pitch)
    }

    // ---------------- Age ----------------

    private fun predictAge(src: Bitmap): Float {
        val h = ageInputShape[1]; val w = ageInputShape[2]
        val buf = makeRgbInputBuffer(src, w, h, ageInputType, ageQuantScale, ageQuantZero)

        val outF = Array(1) { FloatArray(1) }

        return when (getOutputType(ageTflite)) {
            DataType.FLOAT32 -> {
                ageTflite.run(buf, outF)
                (outF[0][0] * 116f).coerceIn(0f, 116f)
            }
            DataType.UINT8, DataType.INT8 -> {
                val t: Tensor = ageTflite.getOutputTensor(0)
                val q = t.quantizationParams()
                val outU8 = ByteArray(t.numElements())
                ageTflite.run(buf, outU8)
                val y = dequantizeU8Mean(outU8, q.scale, q.zeroPoint)
                (y * 116f).coerceIn(0f, 116f)
            }
            else -> {
                val bb = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                ageTflite.run(buf, bb)
                bb.rewind()
                val y = bb.float
                (y * 116f).coerceIn(0f, 116f)
            }
        }
    }

    // ---------------- Gender ----------------

    private fun predictGender(src: Bitmap): String {
        val h = genderInputShape[1]; val w = genderInputShape[2]
        val buf = makeRgbInputBuffer(src, w, h, genderInputType, genderQuantScale, genderQuantZero)

        val outFloat = Array(1) { FloatArray(2) }
        val outU8 = ByteArray(2)

        return when (getOutputType(genderTflite)) {
            DataType.FLOAT32 -> {
                genderTflite.run(buf, outFloat)
                toGender(outFloat[0])
            }
            DataType.UINT8, DataType.INT8 -> {
                val t: Tensor = genderTflite.getOutputTensor(0)
                val q = t.quantizationParams()
                genderTflite.run(buf, outU8)
                val f0 = (outU8[0].toInt() and 0xFF - q.zeroPoint) * q.scale
                val f1 = (outU8[1].toInt() and 0xFF - q.zeroPoint) * q.scale
                toGender(softmax2(f0, f1))
            }
            else -> {
                genderTflite.run(buf, outFloat)
                toGender(outFloat[0])
            }
        }
    }

    private fun toGender(p: FloatArray): String {
        val male = p.getOrNull(0) ?: 0.5f
        val female = p.getOrNull(1) ?: 0.5f
        val maxP = max(male, female)
        if (maxP < unknownMargin) return "unknown"
        return if (female > male) "female" else "male"
    }

    // ---------------- Emotion ----------------

    private fun predictEmotion(src: Bitmap): String {
        val h = emotionInputShape[1]; val w = emotionInputShape[2]
        val buf = makeGrayInputBuffer(src, w, h, emotionInputType, emotionInputQuantScale, emotionInputQuantZero)

        return when (emotionOutputType) {
            DataType.FLOAT32 -> {
                val out = Array(1) { FloatArray(emotionClasses) }
                emotionTflite.run(buf, out)
                val probs = softmax(out[0])
                val (idx, conf) = argmax(probs)
                val label = emotionLabels.getOrElse(idx) { "neutral" }
                if (conf < emotionUnknownMargin && emotionLabels.contains("neutral")) "neutral" else label
            }
            DataType.UINT8, DataType.INT8 -> {
                val t: Tensor = emotionTflite.getOutputTensor(0)
                val outRaw = ByteArray(t.numElements())
                emotionTflite.run(buf, outRaw)
                val floats = FloatArray(outRaw.size) { i ->
                    val u = outRaw[i].toInt() and 0xFF
                    (u - emotionOutputQuantZero) * emotionOutputQuantScale
                }
                val probs = softmax(floats)
                val (idx, conf) = argmax(probs)
                val label = emotionLabels.getOrElse(idx) { "neutral" }
                if (conf < emotionUnknownMargin && emotionLabels.contains("neutral")) "neutral" else label
            }
            else -> {
                val out = Array(1) { FloatArray(emotionClasses) }
                emotionTflite.run(buf, out)
                val probs = softmax(out[0])
                val (idx, conf) = argmax(probs)
                val label = emotionLabels.getOrElse(idx) { "neutral" }
                if (conf < emotionUnknownMargin && emotionLabels.contains("neutral")) "neutral" else label
            }
        }
    }

    // ---------------- Buffer helpers ----------------

    private fun mapModel(ctx: Context, assetName: String): MappedByteBuffer {
        val afd = ctx.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun loadLabels(ctx: Context, assetName: String): List<String> =
        ctx.assets.open(assetName).use { ins ->
            BufferedReader(InputStreamReader(ins)).readLines().filter { it.isNotBlank() }
        }

    private fun getOutputType(interp: Interpreter): DataType =
        interp.getOutputTensor(0).dataType()

    /** Builds NHWC RGB input buffer ([1,h,w,3]) for float or quantized models. */
    private fun makeRgbInputBuffer(
        src: Bitmap,
        reqW: Int,
        reqH: Int,
        type: DataType,
        scale: Float,
        zero: Int
    ): Any {
        val bmp = Bitmap.createScaledBitmap(src, reqW, reqH, true)

        return when (type) {
            DataType.FLOAT32 -> {
                val buf = ByteBuffer.allocateDirect(4 * reqW * reqH * 3).order(ByteOrder.nativeOrder())
                for (y in 0 until reqH) for (x in 0 until reqW) {
                    val p = bmp.getPixel(x, y)
                    buf.putFloat(Color.red(p) / 255f)
                    buf.putFloat(Color.green(p) / 255f)
                    buf.putFloat(Color.blue(p) / 255f)
                }
                buf.rewind(); buf
            }
            DataType.UINT8, DataType.INT8 -> {
                val buf = ByteBuffer.allocateDirect(reqW * reqH * 3).order(ByteOrder.nativeOrder())
                for (y in 0 until reqH) for (x in 0 until reqW) {
                    val p = bmp.getPixel(x, y)
                    buf.put(quantize01(Color.red(p) / 255f, scale, zero))
                    buf.put(quantize01(Color.green(p) / 255f, scale, zero))
                    buf.put(quantize01(Color.blue(p) / 255f, scale, zero))
                }
                buf.rewind(); buf
            }
            else -> {
                val buf = ByteBuffer.allocateDirect(4 * reqW * reqH * 3).order(ByteOrder.nativeOrder())
                for (y in 0 until reqH) for (x in 0 until reqW) {
                    val p = bmp.getPixel(x, y)
                    buf.putFloat(Color.red(p) / 255f)
                    buf.putFloat(Color.green(p) / 255f)
                    buf.putFloat(Color.blue(p) / 255f)
                }
                buf.rewind(); buf
            }
        }
    }

    /** Builds NHWC grayscale input buffer ([1,h,w,1]) for float or quantized models. */
    private fun makeGrayInputBuffer(
        src: Bitmap,
        reqW: Int,
        reqH: Int,
        type: DataType,
        scale: Float,
        zero: Int
    ): Any {
        val bmp = Bitmap.createScaledBitmap(src, reqW, reqH, true)

        return when (type) {
            DataType.FLOAT32 -> {
                val buf = ByteBuffer.allocateDirect(4 * reqW * reqH).order(ByteOrder.nativeOrder())
                for (y in 0 until reqH) for (x in 0 until reqW) {
                    val p = bmp.getPixel(x, y)
                    val g = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    buf.putFloat(g / 255f)
                }
                buf.rewind(); buf
            }
            DataType.UINT8, DataType.INT8 -> {
                val buf = ByteBuffer.allocateDirect(reqW * reqH).order(ByteOrder.nativeOrder())
                for (y in 0 until reqH) for (x in 0 until reqW) {
                    val p = bmp.getPixel(x, y)
                    val g = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    buf.put(quantize01(g / 255f, scale, zero))
                }
                buf.rewind(); buf
            }
            else -> {
                val buf = ByteBuffer.allocateDirect(4 * reqW * reqH).order(ByteOrder.nativeOrder())
                for (y in 0 until reqH) for (x in 0 until reqW) {
                    val p = bmp.getPixel(x, y)
                    val g = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                    buf.putFloat(g / 255f)
                }
                buf.rewind(); buf
            }
        }
    }

    private fun quantize01(x01: Float, scale: Float, zero: Int): Byte {
        val q = (x01 / max(scale, 1e-6f) + zero).toInt()
        return q.coerceIn(-128, 255).toByte()
    }

    private fun dequantizeU8Mean(arr: ByteArray, scale: Float, zero: Int): Float {
        if (arr.isEmpty()) return 0f
        var s = 0f
        arr.forEach { b ->
            val u = b.toInt() and 0xFF
            s += (u - zero) * scale
        }
        return s / arr.size
    }

    private fun softmax2(a: Float, b: Float): FloatArray {
        val ma = max(a, b)
        val ea = exp((a - ma).toDouble()).toFloat()
        val eb = exp((b - ma).toDouble()).toFloat()
        val sum = ea + eb
        return floatArrayOf(ea / sum, eb / sum)
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val m = logits.maxOrNull() ?: 0f
        var sum = 0f
        val exps = FloatArray(logits.size)
        for (i in logits.indices) {
            val e = exp((logits[i] - m).toDouble()).toFloat()
            exps[i] = e
            sum += e
        }
        if (sum <= 0f) return FloatArray(logits.size) { 0f }
        for (i in exps.indices) exps[i] /= sum
        return exps
    }

    private fun argmax(a: FloatArray): Pair<Int, Float> {
        var idx = 0
        var best = -1f
        for (i in a.indices) if (a[i] > best) { best = a[i]; idx = i }
        return idx to max(best, 0f)
    }
}

/** Simple gaze result for analytics. */
data class GazeResult(
    val looking: Boolean,
    val confidence: Float,
    val yawDeg: Float? = null,
    val pitchDeg: Float? = null
)
