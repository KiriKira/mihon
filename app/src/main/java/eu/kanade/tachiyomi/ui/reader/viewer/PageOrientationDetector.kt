package eu.kanade.tachiyomi.ui.reader.viewer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.min
import kotlin.math.roundToInt

/** Detects the correction needed to make a page upright. */
internal object PageOrientationDetector {

    private const val MODEL_ASSET = "models/page_orientation.onnx"
    private const val RESIZE_SHORT_SIDE = 256
    private const val INPUT_SIZE = 224
    private const val CHANNEL_SIZE = INPUT_SIZE * INPUT_SIZE
    private const val MIN_ROTATION_CONFIDENCE = 0.70f

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val session by lazy {
        val model = Injekt.get<Application>().assets.open(MODEL_ASSET).use { it.readBytes() }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(model, options)
        }
    }
    private val recognitionMutex = Mutex()

    /** Returns one of 0, 90, 180, or 270 clockwise correction degrees. */
    suspend fun detectCorrection(imageSource: BufferedSource): Float = recognitionMutex.withLock {
        val bitmap = decodeModelInput(imageSource) ?: return@withLock 0f
        try {
            selectCorrection(runModel(bitmap)).toFloat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to classify page orientation" }
            0f
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeModelInput(imageSource: BufferedSource): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val shortSide = min(bounds.outWidth, bounds.outHeight)
        while (shortSide / (sampleSize * 2) >= RESIZE_SHORT_SIDE) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeStream(
            imageSource.peek().inputStream(),
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val scale = RESIZE_SHORT_SIDE.toFloat() / min(decoded.width, decoded.height)
        val scaledWidth = (decoded.width * scale).roundToInt()
        val scaledHeight = (decoded.height * scale).roundToInt()
        val scaled = Bitmap.createScaledBitmap(decoded, scaledWidth, scaledHeight, true)
        if (scaled !== decoded) decoded.recycle()

        val left = (scaled.width - INPUT_SIZE) / 2
        val top = (scaled.height - INPUT_SIZE) / 2
        val cropped = Bitmap.createBitmap(scaled, left, top, INPUT_SIZE, INPUT_SIZE)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    private fun runModel(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(CHANNEL_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val input = FloatArray(CHANNEL_SIZE * 3)
        pixels.forEachIndexed { index, pixel ->
            input[index] = (((pixel shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            input[CHANNEL_SIZE + index] = (((pixel shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
            input[CHANNEL_SIZE * 2 + index] = ((pixel and 0xFF) / 255f - 0.406f) / 0.225f
        }

        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        ).use { tensor ->
            session.run(Collections.singletonMap(session.inputNames.first(), tensor)).use { result ->
                val scores = FloatArray(4)
                (result[0] as OnnxTensor).floatBuffer.get(scores)
                return scores
            }
        }
    }

    /** Model labels describe current clockwise orientation; the viewer needs its inverse. */
    internal fun selectCorrection(scores: FloatArray): Int {
        require(scores.size == 4)
        val orientationClass = scores.indices.maxBy { scores[it] }
        if (orientationClass == 0 || scores[orientationClass] < MIN_ROTATION_CONFIDENCE) return 0
        return (360 - orientationClass * 90) % 360
    }
}
