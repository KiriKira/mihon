package tachiyomi.core.common.util.system

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Heuristic detector that decides whether a landscape page is a "stitched" double
 * page scan (two single pages glued side-by-side, which should be split for
 * comfortable reading) versus an intentional double-page spread (大跨页, where the
 * artwork crosses the gutter and must not be split).
 *
 * The check is intentionally cheap: it samples a thin vertical strip centered on
 * the image and looks at the luminance distribution. A near-uniform strip whose
 * mean luminance is close to pure white or pure black is treated as a gutter
 * between two stitched pages. Anything else is assumed to be artwork crossing
 * the center, i.e. a real spread.
 *
 * The same heuristic is widely used by manga tooling such as Kindle Comic
 * Converter and various Tachiyomi forks; the threshold values below were tuned
 * to match the values reported in those projects.
 */
internal object DoublePageSpreadDetector {

    data class CenterStripStats(val mean: Double, val stddev: Double)

    /**
     * Analyse a centered vertical strip of an image.
     *
     * @param luminance row-major array of luminance values in `[0, 255]`. Must
     *                  have at least `width * height` entries.
     * @param width image width in [luminance].
     * @param height image height in [luminance].
     * @param stripFraction fraction of the image width to consider, centered.
     *                      Must be in `(0, 1]`. Defaults to 5%.
     */
    fun analyzeCenterStrip(
        luminance: IntArray,
        width: Int,
        height: Int,
        stripFraction: Double = 0.05,
    ): CenterStripStats {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(luminance.size >= width * height) { "luminance buffer too small" }
        require(stripFraction > 0.0 && stripFraction <= 1.0) { "stripFraction out of range" }

        val stripWidth = max(1, (width * stripFraction).toInt())
        val xStart = max(0, (width - stripWidth) / 2)
        val xEnd = min(width, xStart + stripWidth)

        var n = 0L
        var sum = 0.0
        var sumSq = 0.0
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in xStart until xEnd) {
                val v = luminance[rowOffset + x]
                sum += v
                sumSq += v.toDouble() * v
                n++
            }
        }
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        return CenterStripStats(mean = mean, stddev = sqrt(max(0.0, variance)))
    }

    /**
     * Decide whether a wide image is a stitched double-page scan (and therefore
     * should be split) rather than an intentional spread.
     *
     * Returns `true` when the center strip looks like a near-uniform white or
     * black gutter; in that case the caller should split the image. Returns
     * `false` when the center contains artwork (real spread).
     *
     * @param stddevThreshold maximum allowed standard deviation of luminance in
     *                        the center strip for it to count as "uniform".
     * @param edgeMargin how close to pure white (`255`) or pure black (`0`) the
     *                   mean luminance must be.
     */
    fun isStitchedDoublePage(
        stats: CenterStripStats,
        stddevThreshold: Double = 12.0,
        edgeMargin: Int = 25,
    ): Boolean {
        if (stats.stddev > stddevThreshold) return false
        return stats.mean <= edgeMargin || stats.mean >= 255 - edgeMargin
    }
}
