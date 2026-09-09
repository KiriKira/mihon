package tachiyomi.core.common.util.system

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import okio.BufferedSource

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
    val sampleSize = max(1, bounds.outWidth / targetWidth)
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = try {
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, decodeOptions)
    } catch (_: Exception) {
        null
    } ?: return true

    return try {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
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
        bitmap.recycle()
    }
}
