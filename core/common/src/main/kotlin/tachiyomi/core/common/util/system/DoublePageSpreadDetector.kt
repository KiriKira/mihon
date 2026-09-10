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
        val localSeamSupportRatio: Double = 0.0,
    )

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
     * Measure both global and local evidence that artwork continues across a physical
     * gutter. Global correlations alone are not trustworthy: two unrelated pages can
     * accidentally have very similar top-to-bottom density profiles. The local seam
     * score therefore asks whether several short vertical windows immediately beside
     * the gutter also have matching luminance structure.
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
            localSeamSupportRatio = findLocalSeamSupportRatio(
                luminance = luminance,
                width = width,
                height = height,
                gutter = gutter,
                maxDistance = min(4, usableContext),
            ),
        )
    }

    /**
     * Enhanced spread veto based on corroborated evidence rather than a growing set
     * of special-case threshold paths.
     *
     * 1. Require some meaningful content on both sides of the gutter.
     * 2. Accept a global continuity signal only when short local windows corroborate
     *    it. This rejects accidental full-height correlations between unrelated pages.
     * 3. A very strong local seam signal may stand on its own because it represents
     *    repeated, spatially-local continuation right next to the physical fold.
     */
    fun isLikelyContinuousSpread(
        stats: ContinuityStats,
        minBothSidesActiveRatio: Double = 0.25,
        minSideMeanActivity: Double = 0.25,
        rowCorrelationThreshold: Double = 0.45,
        nearSeamCorrelationThreshold: Double = 0.55,
        localSupportThreshold: Double = 0.05,
        strongLocalSupportThreshold: Double = 0.25,
    ): Boolean {
        val weakerSideMeanActivity = min(stats.leftMeanActiveDensity, stats.rightMeanActiveDensity)
        val enoughTwoSidedContent = stats.bothSidesActiveRatio >= minBothSidesActiveRatio &&
            weakerSideMeanActivity >= minSideMeanActivity
        if (!enoughTwoSidedContent) return false

        val globalContinuity = stats.rowProfileCorrelation >= rowCorrelationThreshold ||
            stats.nearSeamLuminanceCorrelation >= nearSeamCorrelationThreshold
        val locallyCorroborated = stats.localSeamSupportRatio >= localSupportThreshold
        val strongLocalContinuity = stats.localSeamSupportRatio >= strongLocalSupportThreshold

        return (globalContinuity && locallyCorroborated) || strongLocalContinuity
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

    private fun findLocalSeamSupportRatio(
        luminance: IntArray,
        width: Int,
        height: Int,
        gutter: GutterRun,
        maxDistance: Int,
        minProfileStddev: Double = 8.0,
        correlationThreshold: Double = 0.65,
        maxMeanAbsoluteDifference: Double = 50.0,
    ): Double {
        if (maxDistance <= 0) return 0.0

        val windowHeight = max(16, (height * 0.09).toInt())
        val stride = max(1, windowHeight / 2)
        if (windowHeight > height) return 0.0

        var informativeWindows = 0
        var supportedWindows = 0
        var yStart = 0
        while (yStart + windowHeight <= height) {
            var informative = false
            var supported = false

            for (distance in 1..maxDistance) {
                val leftX = gutter.startX - distance
                val rightX = gutter.endX + distance
                if (leftX !in 0 until width || rightX !in 0 until width) continue

                val leftProfile = DoubleArray(windowHeight)
                val rightProfile = DoubleArray(windowHeight)
                var absoluteDifferenceSum = 0.0
                for (offset in 0 until windowHeight) {
                    val rowOffset = (yStart + offset) * width
                    val left = luminance[rowOffset + leftX].toDouble()
                    val right = luminance[rowOffset + rightX].toDouble()
                    leftProfile[offset] = left
                    rightProfile[offset] = right
                    absoluteDifferenceSum += abs(left - right)
                }

                if (standardDeviation(leftProfile) < minProfileStddev ||
                    standardDeviation(rightProfile) < minProfileStddev
                ) {
                    continue
                }
                informative = true

                val correlation = pearsonCorrelation(leftProfile, rightProfile)
                val meanAbsoluteDifference = absoluteDifferenceSum / windowHeight
                if (correlation >= correlationThreshold &&
                    meanAbsoluteDifference <= maxMeanAbsoluteDifference
                ) {
                    supported = true
                    break
                }
            }

            if (informative) {
                informativeWindows++
                if (supported) supportedWindows++
            }
            yStart += stride
        }

        return if (informativeWindows == 0) {
            0.0
        } else {
            supportedWindows.toDouble() / informativeWindows
        }
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

    private fun standardDeviation(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        var variance = 0.0
        for (value in values) {
            val delta = value - mean
            variance += delta * delta
        }
        return sqrt(variance / values.size)
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
