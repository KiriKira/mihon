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
 * Enhanced continuity analysis is kept separate so callers that do not opt in retain
 * exactly the legacy classification semantics.
 */
internal object DoublePageSpreadDetector {

    data class ColumnStats(val mean: Double, val stddev: Double, val x: Int)

    data class GutterRun(
        val startX: Int,
        val endX: Int,
        val mean: Double,
    )

    data class ContinuityStats(
        val bothSidesActiveRatio: Double,
        val rowProfileCorrelation: Double,
        val leftMeanActiveDensity: Double,
        val rightMeanActiveDensity: Double,
        val nearSeamLuminanceCorrelation: Double = 0.0,
    )

    /**
     * Search a centered band of columns of [luminance] and return the column
     * (within the band) whose luminance has the lowest standard deviation. That
     * column is the strongest gutter candidate.
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
     * Decide whether a wide image is a stitched double-page scan (and therefore
     * should be split) rather than an intentional spread.
     *
     * This is the legacy classifier. Do not add enhanced continuity heuristics here;
     * keeping this function stable is what guarantees that the new feature can be
     * disabled without changing existing behavior.
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

    /**
     * Expand a legacy gutter candidate into the contiguous run of gutter-like
     * columns around it. A run must keep the same dark/bright polarity as the
     * candidate so nearby artwork cannot be absorbed merely because it is uniform.
     */
    fun findGutterRun(
        luminance: IntArray,
        width: Int,
        height: Int,
        candidate: ColumnStats,
        stddevThreshold: Double = 12.0,
        edgeMargin: Int = 25,
    ): GutterRun {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(luminance.size >= width * height) { "luminance buffer too small" }
        require(candidate.x in 0 until width) { "candidate outside image" }

        val darkGutter = candidate.mean <= edgeMargin
        fun isGutterLike(x: Int): Boolean {
            val stats = columnStats(luminance, width, height, x)
            if (stats.stddev > stddevThreshold) return false
            return if (darkGutter) {
                stats.mean <= edgeMargin
            } else {
                stats.mean >= 255 - edgeMargin
            }
        }

        var start = candidate.x
        var end = candidate.x
        while (start > 0 && isGutterLike(start - 1)) start--
        while (end + 1 < width && isGutterLike(end + 1)) end++

        var sum = 0.0
        for (x in start..end) {
            sum += columnStats(luminance, width, height, x).mean
        }
        return GutterRun(startX = start, endX = end, mean = sum / (end - start + 1))
    }

    /**
     * Measure visual continuity immediately outside the gutter.
     *
     * Two complementary signals are collected:
     *
     * 1. activity-density profiles, which work well when matching shapes/effects are
     *    distributed across both sides of the fold;
     * 2. near-seam luminance correlation, which handles dense painted spreads where
     *    both sides are almost always active and therefore the binary activity
     *    profiles have little useful variance.
     */
    fun analyzeCrossGutterContinuity(
        luminance: IntArray,
        width: Int,
        height: Int,
        gutter: GutterRun,
        contextWidth: Int,
        activityDelta: Int = 35,
        activeRowDensity: Double = 0.20,
    ): ContinuityStats {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(luminance.size >= width * height) { "luminance buffer too small" }
        require(gutter.startX in 0 until width && gutter.endX in gutter.startX until width) {
            "gutter outside image"
        }
        require(contextWidth > 0) { "contextWidth must be positive" }
        require(activityDelta >= 0) { "activityDelta must be non-negative" }
        require(activeRowDensity in 0.0..1.0) { "activeRowDensity out of range" }

        val usableContext = min(contextWidth, min(gutter.startX, width - gutter.endX - 1))
        if (usableContext <= 0) return ContinuityStats(0.0, 0.0, 0.0, 0.0)

        val leftDensity = DoubleArray(height)
        val rightDensity = DoubleArray(height)
        var bothActiveRows = 0

        for (y in 0 until height) {
            val rowOffset = y * width
            var leftActive = 0
            var rightActive = 0

            for (x in gutter.startX - usableContext until gutter.startX) {
                if (abs(luminance[rowOffset + x] - gutter.mean) >= activityDelta) leftActive++
            }
            for (x in gutter.endX + 1..gutter.endX + usableContext) {
                if (abs(luminance[rowOffset + x] - gutter.mean) >= activityDelta) rightActive++
            }

            leftDensity[y] = leftActive.toDouble() / usableContext
            rightDensity[y] = rightActive.toDouble() / usableContext
            if (leftDensity[y] >= activeRowDensity && rightDensity[y] >= activeRowDensity) {
                bothActiveRows++
            }
        }

        return ContinuityStats(
            bothSidesActiveRatio = bothActiveRows.toDouble() / height,
            rowProfileCorrelation = pearsonCorrelation(leftDensity, rightDensity),
            leftMeanActiveDensity = leftDensity.average(),
            rightMeanActiveDensity = rightDensity.average(),
            nearSeamLuminanceCorrelation = findNearSeamLuminanceCorrelation(
                luminance = luminance,
                width = width,
                height = height,
                gutter = gutter,
                maxDistance = min(3, usableContext),
            ),
        )
    }

    /**
     * Enhanced spread veto.
     *
     * Normal spreads use correlated activity profiles. Dense full-bleed spreads are
     * a separate case: both sides can be active on nearly every row, making the
     * activity correlation weak or even negative. For those pages, require very
     * high two-sided activity plus a matching near-seam luminance profile.
     */
    fun isLikelyContinuousSpread(
        stats: ContinuityStats,
        bothSidesActiveThreshold: Double = 0.35,
        minSideMeanActivityThreshold: Double = 0.30,
        correlationThreshold: Double = 0.45,
        denseBothSidesActiveThreshold: Double = 0.80,
        denseMinSideMeanActivityThreshold: Double = 0.75,
        nearSeamCorrelationThreshold: Double = 0.55,
    ): Boolean {
        val weakerSideMeanActivity = min(stats.leftMeanActiveDensity, stats.rightMeanActiveDensity)
        val correlatedActivity = stats.bothSidesActiveRatio >= bothSidesActiveThreshold &&
            weakerSideMeanActivity >= minSideMeanActivityThreshold &&
            stats.rowProfileCorrelation >= correlationThreshold

        val denseSeamContinuity = stats.bothSidesActiveRatio >= denseBothSidesActiveThreshold &&
            weakerSideMeanActivity >= denseMinSideMeanActivityThreshold &&
            stats.nearSeamLuminanceCorrelation >= nearSeamCorrelationThreshold

        return correlatedActivity || denseSeamContinuity
    }

    private fun findNearSeamLuminanceCorrelation(
        luminance: IntArray,
        width: Int,
        height: Int,
        gutter: GutterRun,
        maxDistance: Int,
        maxMeanAbsoluteDifference: Double = 45.0,
    ): Double {
        if (maxDistance <= 0) return 0.0

        var bestCorrelation = 0.0
        for (distance in 1..maxDistance) {
            val leftX = gutter.startX - distance
            val rightX = gutter.endX + distance
            if (leftX !in 0 until width || rightX !in 0 until width) continue

            val leftProfile = DoubleArray(height)
            val rightProfile = DoubleArray(height)
            var absoluteDifferenceSum = 0.0
            for (y in 0 until height) {
                val rowOffset = y * width
                val left = luminance[rowOffset + leftX].toDouble()
                val right = luminance[rowOffset + rightX].toDouble()
                leftProfile[y] = left
                rightProfile[y] = right
                absoluteDifferenceSum += abs(left - right)
            }

            val meanAbsoluteDifference = absoluteDifferenceSum / height
            if (meanAbsoluteDifference > maxMeanAbsoluteDifference) continue

            bestCorrelation = max(bestCorrelation, pearsonCorrelation(leftProfile, rightProfile))
        }
        return bestCorrelation
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

    private fun pearsonCorrelation(left: DoubleArray, right: DoubleArray): Double {
        if (left.isEmpty() || left.size != right.size) return 0.0

        val leftMean = left.average()
        val rightMean = right.average()
        var covariance = 0.0
        var leftVariance = 0.0
        var rightVariance = 0.0
        for (i in left.indices) {
            val l = left[i] - leftMean
            val r = right[i] - rightMean
            covariance += l * r
            leftVariance += l * l
            rightVariance += r * r
        }
        if (leftVariance <= 1e-9 || rightVariance <= 1e-9) return 0.0
        return covariance / sqrt(leftVariance * rightVariance)
    }
}
