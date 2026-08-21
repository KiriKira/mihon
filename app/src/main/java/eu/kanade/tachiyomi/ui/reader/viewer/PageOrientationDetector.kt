package eu.kanade.tachiyomi.ui.reader.viewer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.FloatBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Conservatively detects the correction needed to make a manga page upright. */
internal object PageOrientationDetector {

    private const val MODEL_ASSET = "models/page_orientation.onnx"
    private const val PREVIEW_MAX_DIMENSION = 1600
    private const val RESIZE_SHORT_SIDE = 256
    private const val INPUT_SIZE = 224
    private const val CHANNEL_SIZE = INPUT_SIZE * INPUT_SIZE
    private const val CROP_COUNT = 5

    private const val MIN_MEAN_CONFIDENCE = 0.75f
    private const val MIN_VALID_CHARACTERS = 6
    private const val MIN_TEXT_ELEMENTS = 2
    private const val BOX_ASPECT_RATIO = 1.4f
    private const val MIN_VERTICAL_MASS = 3.0f
    private const val PREFETCH_RETRY_DELAY_MS = 50L
    private const val PREFETCH_PAGE_GAP_MS = 100L

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
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val recognitionMutex = Mutex()
    private val foregroundRequests = AtomicInteger()
    private val correctionCache = Collections.synchronizedMap(WeakHashMap<ReaderPage, Float>())

    /** Returns one of 0, 90, 180, or 270 clockwise correction degrees. */
    suspend fun detectCorrection(page: ReaderPage?, imageSource: BufferedSource): Float {
        page?.let(correctionCache::get)?.let { return it }

        foregroundRequests.incrementAndGet()
        try {
            return recognitionMutex.withLock {
                page?.let(correctionCache::get) ?: detectAndCache(page, imageSource)
            }
        } finally {
            foregroundRequests.decrementAndGet()
        }
    }

    /** Warms the cache for pages which the loader has already made available. */
    suspend fun prefetch(pages: List<ReaderPage>) {
        pages.forEach { page ->
            try {
                if (correctionCache.containsKey(page)) return@forEach

                val state = page.statusFlow.first { it is Page.State.Ready || it is Page.State.Error }
                if (state is Page.State.Error || page.stream == null) return@forEach
                val source = page.stream?.invoke()?.use { Buffer().readFrom(it) } ?: return@forEach

                while (foregroundRequests.get() > 0 || !recognitionMutex.tryLock()) {
                    delay(PREFETCH_RETRY_DELAY_MS)
                    currentCoroutineContext().ensureActive()
                }
                try {
                    if (!correctionCache.containsKey(page)) {
                        detectAndCache(page, source)
                    }
                } finally {
                    recognitionMutex.unlock()
                }
                delay(PREFETCH_PAGE_GAP_MS)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) {
                    "Page orientation prefetch failed (page=${page.number}); skipping"
                }
            }
        }
    }

    private suspend fun detectAndCache(page: ReaderPage?, imageSource: BufferedSource): Float {
        val pageLabel = page?.let { "page=${it.number}" } ?: "transformed-page"
        val startedAt = SystemClock.elapsedRealtime()
        val bitmap = try {
            decodePreview(imageSource)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Page orientation decode failed ($pageLabel); keeping original" }
            return cache(page, 0f)
        }
        if (bitmap == null) {
            logcat(LogPriority.WARN) { "Page orientation decode failed ($pageLabel); keeping original" }
            return cache(page, 0f)
        }

        return try {
            val diagnostic = diagnose(bitmap)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            logcat(LogPriority.DEBUG) {
                "Page orientation ($pageLabel, ${elapsedMs}ms): $diagnostic"
            }
            cache(page, diagnostic.correctionDegrees.toFloat())
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            logcat(LogPriority.ERROR, e) {
                "Page orientation failed ($pageLabel, ${elapsedMs}ms); keeping original"
            }
            cache(page, 0f)
        } finally {
            bitmap.recycle()
        }
    }

    private fun cache(page: ReaderPage?, correction: Float): Float {
        page?.let { correctionCache[it] = correction }
        return correction
    }

    internal suspend fun diagnose(bitmap: Bitmap): Diagnostic {
        val cropScores = runModelAtAllRotations(bitmap)
        val proposal = selectConsistentProposal(cropScores)
            ?: return Diagnostic(0, cropScores, null, "classifier_rejected")

        val chinese = recognize(chineseRecognizer, bitmap)
        if (hasSufficientText(chinese)) {
            return acceptOrGuard(proposal, cropScores, chinese, null)
        }

        currentCoroutineContext().ensureActive()
        val japanese = recognize(japaneseRecognizer, bitmap)
        return if (hasSufficientText(japanese)) {
            acceptOrGuard(proposal, cropScores, chinese, japanese)
        } else {
            Diagnostic(0, cropScores, ScriptEvidence(chinese, japanese), "ocr_rejected")
        }
    }

    /**
     * OCR proves text exists, not that the page is sideways. On upright pages
     * with native vertical writing, recognized lines stay tall-and-narrow at
     * 0°; a genuinely sideways page cannot produce confident vertical lines
     * before correction. When that vertical mass dominates, trust geometry
     * over the classifier and keep 0°. This trades recall on sideways pages
     * whose print text is horizontal (their rotated lines look vertical) for
     * never rotating an already-upright page - the failure we must avoid.
     */
    private fun acceptOrGuard(
        proposal: Int,
        cropScores: Map<Int, List<FloatArray>>,
        chinese: OcrEvidence,
        japanese: OcrEvidence?,
    ): Diagnostic {
        val evidence = ScriptEvidence(chinese, japanese)
        val verticalMass = chinese.textMass + (japanese?.textMass ?: 0f)
        return if (verticalMass < MIN_VERTICAL_MASS) {
            Diagnostic(proposal, cropScores, evidence, "accepted")
        } else {
            Diagnostic(0, cropScores, evidence, "geometry_guard_rejected")
        }
    }

    private fun decodePreview(imageSource: BufferedSource): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        return BitmapFactory.decodeStream(
            imageSource.peek().inputStream(),
            null,
            BitmapFactory.Options().apply {
                inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / PREVIEW_MAX_DIMENSION)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun runModelAtAllRotations(bitmap: Bitmap): Map<Int, List<FloatArray>> {
        val scale = RESIZE_SHORT_SIDE.toFloat() / min(bitmap.width, bitmap.height)
        val scaled = downscaleWithPrefilter(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
        )
        val buffers = ModelBuffers(
            pixels = IntArray(CHANNEL_SIZE),
            input = FloatArray(CHANNEL_SIZE * 3),
        )
        try {
            return QUARTER_TURNS.associateWith { rotation ->
                val rotated = if (rotation == 0) scaled else rotate(scaled, rotation)
                try {
                    runModelCrops(rotated, buffers)
                } finally {
                    if (rotated !== scaled) rotated.recycle()
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun runModelCrops(scaled: Bitmap, buffers: ModelBuffers): List<FloatArray> {
        val horizontal = scaled.width > scaled.height
        val travel = (if (horizontal) scaled.width else scaled.height) - INPUT_SIZE
        return List(CROP_COUNT) { index ->
            val offset = (travel * index.toFloat() / (CROP_COUNT - 1)).roundToInt()
            val left = if (horizontal) offset else (scaled.width - INPUT_SIZE) / 2
            val top = if (horizontal) (scaled.height - INPUT_SIZE) / 2 else offset
            runModel(scaled, left, top, buffers)
        }
    }

    /** Repeated halving prevents severe aliasing of manga screentones on Android. */
    private fun downscaleWithPrefilter(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        var current = bitmap
        while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== bitmap) current.recycle()
            current = next
        }
        if (current.width == targetWidth && current.height == targetHeight) return current

        val result = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
        if (current !== bitmap) current.recycle()
        return result
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap = Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        Matrix().apply { postRotate(degrees.toFloat()) },
        true,
    )

    private fun runModel(bitmap: Bitmap, left: Int, top: Int, buffers: ModelBuffers): FloatArray {
        bitmap.getPixels(buffers.pixels, 0, INPUT_SIZE, left, top, INPUT_SIZE, INPUT_SIZE)

        buffers.pixels.forEachIndexed { index, pixel ->
            buffers.input[index] = (((pixel shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            buffers.input[CHANNEL_SIZE + index] = (((pixel shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
            buffers.input[CHANNEL_SIZE * 2 + index] = ((pixel and 0xFF) / 255f - 0.406f) / 0.225f
        }

        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(buffers.input),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        ).use { tensor ->
            session.run(Collections.singletonMap(session.inputNames.first(), tensor)).use { result ->
                return FloatArray(4).also { scores ->
                    (result[0] as OnnxTensor).floatBuffer.get(scores)
                }
            }
        }
    }

    private suspend fun recognize(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap,
    ): OcrEvidence {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        var characters = 0
        var elements = 0
        var score = 0.0
        var verticalMass = 0f
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val box = line.boundingBox
                val width = box?.width()?.toFloat() ?: 0f
                val height = box?.height()?.toFloat() ?: 0f
                val verticalDominant = height >= width * BOX_ASPECT_RATIO
                val horizontalDominant = width >= height * BOX_ASPECT_RATIO
                line.elements.forEach { element ->
                    val count = element.text.codePoints().filter(::isCjkCodePoint).count().toInt()
                    if (count == 0) return@forEach
                    characters += count
                    elements++
                    score += count * element.confidence.coerceAtLeast(0f)
                    // A still-vertical text line on a stored-sideways page is impossible:
                    // accumulate confident vertical-line mass for the geometry guard.
                    if (!horizontalDominant || verticalDominant) {
                        verticalMass += count * element.confidence.coerceAtLeast(0f)
                    }
                }
            }
        }
        return OcrEvidence(characters, elements, score, verticalMass)
    }

    /**
     * Accept a correction only when all four pixel rotations agree on the same
     * aligned class and their strongest-crop confidences average high enough.
     * Requiring rotation equivariance plus a global mean gate proved more
     * robust on manga than per-crop confidence/margin thresholds, which let a
     * single lucky crop dominate or killed pages whose evidence was merely
     * spread across rotations.
     */
    internal fun selectConsistentProposal(scoresByPixelRotation: Map<Int, List<FloatArray>>): Int? {
        if (scoresByPixelRotation.keys != QUARTER_TURNS.toSet()) return null
        var meanConfidence = 0f
        val alignedClasses = QUARTER_TURNS.map { pixelRotation ->
            val (orientationClass, confidence) =
                selectOrientationClass(scoresByPixelRotation.getValue(pixelRotation))
            meanConfidence += confidence / QUARTER_TURNS.size
            (orientationClass - pixelRotation / 90 + 4) % 4
        }
        if (alignedClasses.distinct().size != 1 || meanConfidence < MIN_MEAN_CONFIDENCE) return null

        val orientationClass = alignedClasses.first()
        if (orientationClass == 0) return null
        return (360 - orientationClass * 90) % 360
    }

    /** Strongest-crop class with its confidence; thresholds applied by the caller. */
    internal fun selectOrientationClass(cropScores: List<FloatArray>): Pair<Int, Float> {
        require(cropScores.isNotEmpty() && cropScores.all { it.size == 4 })
        val bestCrop = cropScores.maxBy { scores -> scores.max() }
        val best = bestCrop.indices.maxBy(bestCrop::get)
        return best to bestCrop[best]
    }

    internal fun hasSufficientText(evidence: OcrEvidence): Boolean =
        evidence.validCharacters >= MIN_VALID_CHARACTERS && evidence.textElements >= MIN_TEXT_ELEMENTS

    private fun isCjkCodePoint(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
    }

    internal data class OcrEvidence(
        val validCharacters: Int,
        val textElements: Int,
        val score: Double,
        val textMass: Float = 0f,
    )

    private data class ModelBuffers(
        val pixels: IntArray,
        val input: FloatArray,
    )

    internal data class ScriptEvidence(
        val chinese: OcrEvidence,
        val japanese: OcrEvidence?,
    )

    internal data class Diagnostic(
        val correctionDegrees: Int,
        val cropScoresByPixelRotation: Map<Int, List<FloatArray>>,
        val textEvidence: ScriptEvidence?,
        val reason: String,
    ) {
        override fun toString(): String = buildString {
            append("correction=").append(correctionDegrees)
            append(" reason=").append(reason)
            append(" classes=").append(
                cropScoresByPixelRotation.mapValues { (_, scores) ->
                    selectOrientationClass(scores)
                },
            )
            append(" text=").append(textEvidence)
        }
    }

    private val QUARTER_TURNS = listOf(0, 90, 180, 270)
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        continuation.tryResume(result)?.let(continuation::completeResume)
    }
    addOnFailureListener { error ->
        continuation.tryResumeWithException(error)?.let(continuation::completeResume)
    }
    addOnCanceledListener { continuation.cancel(CancellationException("Text recognition cancelled")) }
}
