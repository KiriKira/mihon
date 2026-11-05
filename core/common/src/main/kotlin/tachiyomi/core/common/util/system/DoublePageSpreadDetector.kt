package tachiyomi.core.common.util.system

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Heuristic detector that decides whether a landscape page is a "stitched" double
 * page scan (two single pages glued side-by-side, which should be split for
 * comfortable reading) versus an intentional double-page spread (大跨页, where the
 * artwork crosses the gutter and must not be split).
 *
 * The legacy check is intentionally cheap: it scans a narrow band of columns centered
 * on the image and picks the single most-uniform column as the gutter candidate.
 * The opt-in enhanced check lives in [SpreadPageStructure] so callers that do not
 * opt in retain exactly the legacy classification semantics.
 */
internal object DoublePageSpreadDetector {

    data class ColumnStats(val mean: Double, val stddev: Double, val x: Int)

    fun findBestGutterColumn(
        luminance: IntArray,
        width: Int,
        height: Int,
        searchFraction: Double = 0.05,
    ): ColumnStats {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(luminance.size >= width * height) { "luminance buffer too small" }
        require(searchFraction > 0.0 && searchFraction <= 1.0) { "searchFraction out of range" }

        val bandWidth = max(1, (width * searchFraction).toInt())
        val xStart = max(0, (width - bandWidth) / 2)
        val xEnd = min(width, xStart + bandWidth)

        val centerX = width / 2.0
        var bestX = xStart
        var bestMean = 128.0
        var bestStddev = Double.MAX_VALUE
        for (x in xStart until xEnd) {
            val stats = columnStats(luminance, width, height, x)
            val isCloserToCenter = abs(x - centerX) < abs(bestX - centerX)
            if (stats.stddev < bestStddev || (stats.stddev == bestStddev && isCloserToCenter)) {
                bestStddev = stats.stddev
                bestMean = stats.mean
                bestX = x
            }
        }
        return ColumnStats(mean = bestMean, stddev = bestStddev, x = bestX)
    }

    /**
     * Legacy classifier. Keep this stable so disabling enhanced detection restores
     * the previous behavior exactly.
     */
    fun isStitchedDoublePage(
        stats: ColumnStats,
        stddevThreshold: Double = 12.0,
        edgeMargin: Int = 25,
        imageWidth: Int? = null,
        centerToleranceFraction: Double = 0.015,
    ): Boolean {
        if (imageWidth != null) {
            require(imageWidth > 0) { "imageWidth must be positive" }
            val centerOffset = abs(stats.x - imageWidth / 2.0) / imageWidth
            if (centerOffset > centerToleranceFraction) return false
        }
        if (stats.stddev > stddevThreshold) return false
        return stats.mean <= edgeMargin || stats.mean >= 255 - edgeMargin
    }

    private fun columnStats(
        luminance: IntArray,
        width: Int,
        height: Int,
        x: Int,
    ): ColumnStats {
        var sum = 0.0
        var sumSq = 0.0
        for (y in 0 until height) {
            val v = luminance[y * width + x]
            sum += v
            sumSq += v.toDouble() * v
        }
        val mean = sum / height
        val variance = (sumSq / height) - mean * mean
        return ColumnStats(mean = mean, stddev = sqrt(max(0.0, variance)), x = x)
    }
}
