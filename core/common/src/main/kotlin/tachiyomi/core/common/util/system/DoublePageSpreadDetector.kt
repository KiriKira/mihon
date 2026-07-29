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
 * The check is intentionally cheap: it scans a narrow band of columns centered
 * on the image and picks the single most-uniform column as the gutter
 * candidate. If that column has near-zero luminance variance and its mean is
 * close to pure white or pure black, the page is treated as a stitched scan
 * and should be split. Anything else (no uniform column near the center, or a
 * uniform column at mid grey) is assumed to be artwork crossing the center,
 * i.e. a real spread.
 *
 * Using the single best column instead of averaging across a wide strip is
 * important: real-world manga gutters are often just a handful of pixels wide
 * (after downsampling, sometimes a single column) and averaging mixes that
 * pure-white seam with noisy artwork columns next to it, masking the signal.
 */
internal object DoublePageSpreadDetector {

    data class ColumnStats(val mean: Double, val stddev: Double, val x: Int)

    /**
     * Search a centered band of columns of [luminance] and return the column
     * (within the band) whose luminance has the lowest standard deviation. That
     * column is the strongest gutter candidate.
     *
     * @param luminance row-major array of luminance values in `[0, 255]`. Must
     *                  have at least `width * height` entries.
     * @param width image width in [luminance].
     * @param height image height in [luminance].
     * @param searchFraction fraction of the image width to scan, centered.
     *                       Must be in `(0, 1]`. Defaults to 5%.
     */
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
            var sum = 0.0
            var sumSq = 0.0
            for (y in 0 until height) {
                val v = luminance[y * width + x]
                sum += v
                sumSq += v.toDouble() * v
            }
            val mean = sum / height
            val variance = (sumSq / height) - mean * mean
            val stddev = sqrt(max(0.0, variance))
            val isCloserToCenter = abs(x - centerX) < abs(bestX - centerX)
            if (stddev < bestStddev || (stddev == bestStddev && isCloserToCenter)) {
                bestStddev = stddev
                bestMean = mean
                bestX = x
            }
        }
        return ColumnStats(mean = bestMean, stddev = bestStddev, x = bestX)
    }

    /**
     * Decide whether a wide image is a stitched double-page scan (and therefore
     * should be split) rather than an intentional spread.
     *
     * Returns `true` when the strongest center column is near-uniform AND its
     * mean luminance is close to pure white or pure black; in that case the
     * caller should split the image. Returns `false` otherwise.
     *
     * @param stddevThreshold maximum allowed standard deviation of luminance in
     *                        the candidate column for it to count as "uniform".
     * @param edgeMargin how close to pure white (`255`) or pure black (`0`) the
     *                   mean luminance must be.
     * @param imageWidth full analysed image width. When supplied, the gutter
     *                   candidate must be close enough to the exact center for
     *                   a fixed half split to preserve both pages.
     * @param centerToleranceFraction maximum normalized distance between the
     *                                gutter candidate and image center.
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
}
