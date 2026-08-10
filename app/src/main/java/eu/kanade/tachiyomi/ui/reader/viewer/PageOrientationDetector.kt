package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Detects the rotation needed to make Chinese glyphs in a page upright. */
internal object PageOrientationDetector {

    private const val PREVIEW_MAX_DIMENSION = 1600
    private const val MIN_CHINESE_CHARACTERS = 2
    private const val MIN_WINNING_MARGIN = 1.12
    private const val MIN_ANGLE_WINNING_MARGIN = 1.25
    private const val MAX_QUARTER_TURN_ERROR = 30f

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val recognitionMutex = Mutex()

    /**
     * Returns one of 0, 90, 180, or 270 degrees. A zero result also means the
     * detector did not have enough confident Chinese text to change the page.
     */
    suspend fun detectCorrection(imageSource: BufferedSource): Float = recognitionMutex.withLock {
        val bitmap = decodePreview(imageSource) ?: return@withLock 0f
        try {
            val unrotatedText = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            currentCoroutineContext().ensureActive()

            selectCorrectionFromAngles(angleObservations(unrotatedText))?.let {
                return@withLock it.toFloat()
            }

            val scores = buildList {
                add(score(0, unrotatedText))
                for (rotation in listOf(90, 180, 270)) {
                    val rotatedText = recognizer.process(InputImage.fromBitmap(bitmap, rotation)).await()
                    currentCoroutineContext().ensureActive()
                    add(score(rotation, rotatedText))
                }
            }
            selectCorrection(scores).toFloat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to detect page text orientation" }
            0f
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodePreview(imageSource: BufferedSource): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / PREVIEW_MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, options)
    }

    private fun score(rotation: Int, text: Text): RotationScore {
        var chineseCharacters = 0
        var weightedConfidence = 0.0
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    val count = element.text.codePoints().filter(::isChineseCodePoint).count().toInt()
                    if (count > 0) {
                        chineseCharacters += count
                        val confidence = element.confidence.takeIf { it > 0f } ?: 0.5f
                        weightedConfidence += count * (0.5 + confidence)
                    }
                }
            }
        }
        return RotationScore(rotation, chineseCharacters, weightedConfidence)
    }

    /**
     * ML Kit can recognize sideways text well enough that comparing OCR confidence
     * between four rotations is often ambiguous. Its bundled model also reports the
     * angle of each recognized symbol, which is a direct signal for glyph orientation.
     */
    private fun angleObservations(text: Text): List<AngleObservation> = buildList {
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    element.symbols.forEach { symbol ->
                        val chineseCharacters = symbol.text.codePoints().filter(::isChineseCodePoint).count().toInt()
                        if (chineseCharacters > 0) {
                            val confidence = symbol.confidence.takeIf { it > 0f } ?: 0.5f
                            add(
                                AngleObservation(
                                    angleDegrees = symbol.angle,
                                    chineseCharacters = chineseCharacters,
                                    score = chineseCharacters * (0.5 + confidence),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    internal fun selectCorrectionFromAngles(observations: List<AngleObservation>): Int? {
        val votes = observations.mapNotNull { observation ->
            val correction = correctionForAngle(observation.angleDegrees) ?: return@mapNotNull null
            correction to observation
        }
        if (votes.sumOf { it.second.chineseCharacters } < MIN_CHINESE_CHARACTERS) return null

        val ranked = votes
            .groupBy({ it.first }, { it.second })
            .map { (rotation, rotationVotes) ->
                RotationScore(
                    rotationDegrees = rotation,
                    chineseCharacters = rotationVotes.sumOf { it.chineseCharacters },
                    score = rotationVotes.sumOf { it.score },
                )
            }
            .sortedWith(
                compareByDescending<RotationScore> { it.score }
                    .thenByDescending { it.chineseCharacters }
                    .thenBy { it.rotationDegrees },
            )

        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && best.score < runnerUp.score * MIN_ANGLE_WINNING_MARGIN) return null
        return best.rotationDegrees
    }

    private fun correctionForAngle(angleDegrees: Float): Int? {
        if (!angleDegrees.isFinite()) return null
        val normalizedAngle = ((angleDegrees % 360f) + 360f) % 360f
        val nearestQuarterTurn = (normalizedAngle / 90f).roundToInt() * 90
        val snappedAngle = nearestQuarterTurn % 360
        val error = abs(normalizedAngle - snappedAngle).let { minOf(it, 360f - it) }
        if (error > MAX_QUARTER_TURN_ERROR) return null
        return (360 - snappedAngle) % 360
    }

    internal fun selectCorrection(scores: List<RotationScore>): Int {
        require(scores.isNotEmpty())
        val ranked = scores.sortedWith(
            compareByDescending<RotationScore> { it.score }
                .thenByDescending { it.chineseCharacters }
                .thenBy { it.rotationDegrees },
        )
        val best = ranked.first()
        if (best.chineseCharacters < MIN_CHINESE_CHARACTERS) return 0
        if (best.rotationDegrees == 0) return 0

        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && best.score < runnerUp.score * MIN_WINNING_MARGIN) return 0
        return best.rotationDegrees
    }

    private fun isChineseCodePoint(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    internal data class RotationScore(
        val rotationDegrees: Int,
        val chineseCharacters: Int,
        val score: Double,
    )

    internal data class AngleObservation(
        val angleDegrees: Float,
        val chineseCharacters: Int,
        val score: Double,
    )
}

private suspend fun <T> Task<T>.await(): T = suspendCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.resumeWithException(CancellationException("Text recognition cancelled")) }
}
