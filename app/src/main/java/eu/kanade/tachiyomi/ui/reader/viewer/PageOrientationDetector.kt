package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import kotlin.math.max

/** Detects the rotation needed to make Chinese glyphs in a page upright. */
internal object PageOrientationDetector {

    private const val PREVIEW_MAX_DIMENSION = 1600
    private const val MIN_CHINESE_CHARACTERS = 2
    private const val MIN_WINNING_MARGIN = 1.12
    private const val MAX_GLYPH_SAMPLES = 32
    private const val GLYPH_COLUMNS = 8
    private const val GLYPH_TILE_SIZE = 96
    private const val GLYPH_TILE_PADDING = 8f

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

            val glyphBounds = chineseGlyphBounds(bitmap, unrotatedText)
            if (glyphBounds.size >= MIN_CHINESE_CHARACTERS) {
                val glyphScores = listOf(0, 90, 180, 270).map { rotation ->
                    val glyphSheet = createGlyphSheet(bitmap, glyphBounds, rotation)
                    try {
                        val glyphText = recognizer.process(InputImage.fromBitmap(glyphSheet, 0)).await()
                        currentCoroutineContext().ensureActive()
                        score(rotation, glyphText)
                    } finally {
                        glyphSheet.recycle()
                    }
                }
                return@withLock selectCorrection(glyphScores).toFloat()
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

    private fun chineseGlyphBounds(bitmap: Bitmap, text: Text): List<Rect> = buildList {
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    symbolLoop@ for (symbol in element.symbols) {
                        if (symbol.text.codePoints().noneMatch(::isChineseCodePoint)) continue@symbolLoop
                        val bounds = symbol.boundingBox ?: continue@symbolLoop
                        if (bounds.width() <= 0 || bounds.height() <= 0) continue@symbolLoop

                        val padding = (max(bounds.width(), bounds.height()) * 0.15f).toInt()
                        add(
                            Rect(
                                (bounds.left - padding).coerceAtLeast(0),
                                (bounds.top - padding).coerceAtLeast(0),
                                (bounds.right + padding).coerceAtMost(bitmap.width),
                                (bounds.bottom + padding).coerceAtMost(bitmap.height),
                            ),
                        )
                        if (size == MAX_GLYPH_SAMPLES) return@buildList
                    }
                }
            }
        }
    }

    /**
     * Places isolated Chinese glyphs in a neutral grid before OCR. Comparing whole
     * pages is unreliable because ML Kit can auto-detect a sideways text line, while
     * the line angle itself cannot distinguish upright vertical typesetting from a
     * rotated page. Isolating the glyphs makes the score reflect glyph orientation.
     */
    private fun createGlyphSheet(bitmap: Bitmap, glyphBounds: List<Rect>, rotation: Int): Bitmap {
        val rows = (glyphBounds.size + GLYPH_COLUMNS - 1) / GLYPH_COLUMNS
        val sheet = Bitmap.createBitmap(
            GLYPH_COLUMNS * GLYPH_TILE_SIZE,
            rows * GLYPH_TILE_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(sheet).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        glyphBounds.forEachIndexed { index, source ->
            val centerX = (index % GLYPH_COLUMNS + 0.5f) * GLYPH_TILE_SIZE
            val centerY = (index / GLYPH_COLUMNS + 0.5f) * GLYPH_TILE_SIZE
            val availableSize = GLYPH_TILE_SIZE - GLYPH_TILE_PADDING * 2
            val scale = availableSize / max(source.width(), source.height())
            val destination = RectF(
                centerX - source.width() * scale / 2,
                centerY - source.height() * scale / 2,
                centerX + source.width() * scale / 2,
                centerY + source.height() * scale / 2,
            )

            canvas.save()
            canvas.rotate(rotation.toFloat(), centerX, centerY)
            canvas.drawBitmap(bitmap, source, destination, paint)
            canvas.restore()
        }
        return sheet
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
}

private suspend fun <T> Task<T>.await(): T = suspendCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.resumeWithException(CancellationException("Text recognition cancelled")) }
}
