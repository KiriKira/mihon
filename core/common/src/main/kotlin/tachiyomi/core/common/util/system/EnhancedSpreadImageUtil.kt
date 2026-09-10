package tachiyomi.core.common.util.system

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okio.BufferedSource
import kotlin.math.max
import kotlin.math.min

/**
 * Opt-in second-stage spread analysis.
 *
 * The legacy [ImageUtil.isWideStitchedPage] result is authoritative unless it says
 * the page is safe to split. Only then do we run the more expensive continuity
 * check, which may veto the split when artwork appears to continue across a real
 * scanned gutter.
 */
fun ImageUtil.isWideStitchedPageEnhanced(imageSource: BufferedSource): Boolean {
    if (!isWideStitchedPage(imageSource)) return false

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, bounds)
    } catch (_: Exception) {
        return true
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return true
    if (bounds.outWidth <= bounds.outHeight) return false

    val targetWidth = 512

    // BitmapFactory.inSampleSize is not an exact resize request. Depending on the
    // decoder/image format, non-power-of-two values may produce different output
    // dimensions. That made thresholds change simply because a 1536 px image could
    // be analysed at 512, 768, or another width on different devices/decoders.
    //
    // Decode to a bounded power-of-two intermediate first, then explicitly scale to
    // the fixed analysis width. The enhanced detector therefore sees the same spatial
    // scale regardless of the source format or BitmapFactory sampling behaviour.
    val sampleSize = calculatePowerOfTwoSampleSize(bounds.outWidth, targetWidth)
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decodedBitmap = try {
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, decodeOptions)
    } catch (_: Exception) {
        null
    } ?: return true

    val analysisBitmap = normalizeAnalysisBitmap(decodedBitmap, targetWidth)

    return try {
        val width = analysisBitmap.width
        val height = analysisBitmap.height
        if (width <= 0 || height <= 0) return true

        val pixels = IntArray(width * height)
        analysisBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            luminance[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        val candidate = DoublePageSpreadDetector.findBestGutterColumn(
            luminance = luminance,
            width = width,
            height = height,
        )
        if (!DoublePageSpreadDetector.isStitchedDoublePage(candidate, imageWidth = width)) {
            return false
        }

        val gutter = DoublePageSpreadDetector.findGutterRun(
            luminance = luminance,
            width = width,
            height = height,
            candidate = candidate,
        )
        val contextWidth = max(1, (width * 0.03).toInt())
        val continuity = DoublePageSpreadDetector.analyzeCrossGutterContinuity(
            luminance = luminance,
            width = width,
            height = height,
            gutter = gutter,
            contextWidth = contextWidth,
        )

        !DoublePageSpreadDetector.isLikelyContinuousSpread(continuity)
    } finally {
        if (analysisBitmap !== decodedBitmap) {
            analysisBitmap.recycle()
        }
        decodedBitmap.recycle()
    }
}

internal fun calculateEnhancedSpreadSampleSize(sourceWidth: Int, targetWidth: Int = 512): Int {
    require(sourceWidth > 0) { "sourceWidth must be positive" }
    require(targetWidth > 0) { "targetWidth must be positive" }
    return calculatePowerOfTwoSampleSize(sourceWidth, targetWidth)
}

private fun calculatePowerOfTwoSampleSize(sourceWidth: Int, targetWidth: Int): Int {
    var sampleSize = 1
    while (sourceWidth / (sampleSize * 2) >= targetWidth) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun normalizeAnalysisBitmap(bitmap: Bitmap, targetWidth: Int): Bitmap {
    if (bitmap.width <= targetWidth) return bitmap

    val outputWidth = min(targetWidth, bitmap.width)
    val outputHeight = max(1, (bitmap.height.toLong() * outputWidth / bitmap.width).toInt())
    return Bitmap.createScaledBitmap(bitmap, outputWidth, outputHeight, true)
}
