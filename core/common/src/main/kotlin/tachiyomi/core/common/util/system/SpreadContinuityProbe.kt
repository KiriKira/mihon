package tachiyomi.core.common.util.system

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Experimental, model-free measurements for offline spread-feature ablations.
 *
 * This is NOT a spread classifier. Even unrelated images can have identical row profiles. Neither
 * a high correlation nor agreement across bands authorizes splitting or keeping a page. No reader
 * path calls this probe; it does no decoding, resizing, IO, training, or probability calibration.
 *
 * The caller supplies a bounded grayscale raster and an excluded gutter [seamStart, seamEnd).
 * Two adjacent strips produce row luminance and horizontal-total-variation profiles. Pearson
 * correlation searches one global vertical shift with the same row support at every shift. Pure
 * horizontal rules can match luminance perfectly but have no horizontal-variation evidence.
 */
internal object SpreadContinuityProbe {

    data class Config(
        val stripWidth: Int = 16,
        val guard: Int = 2,
        val maxShift: Int = 8,
        val minStdDeviation: Double = 1.0 / 255.0,
    ) {
        init {
            require(stripWidth in 4..32) { "stripWidth must be in 4..32" }
            require(guard in 0..8) { "guard must be in 0..8" }
            require(maxShift in 0..16) { "maxShift must be in 0..16" }
            require(minStdDeviation.isFinite() && minStdDeviation > 0.0 && minStdDeviation <= 1.0) {
                "minStdDeviation must be finite and in (0, 1]"
            }
        }
    }

    enum class Status { MEASURED, LOW_TEXTURE, INSUFFICIENT_INTERIOR, INSUFFICIENT_ROWS }

    data class ShiftScore(val shift: Int, val correlation: Double?)

    data class Result(
        val status: Status,
        val sampleRows: Int = 0,
        val luminanceCorrelationAtZeroShift: Double? = null,
        val bestShift: Int? = null,
        val bestCorrelation: Double? = null,
        val luminanceCorrelationAtBestShift: Double? = null,
        /** Best minus best competitor more than two pixels away; not a significance statistic. */
        val peakSeparation: Double? = null,
        val atSearchBoundary: Boolean = false,
        val informativeBands: Int = 0,
        /** Local optima within one pixel of the global optimum; diagnostic, not independent votes. */
        val agreeingBands: Int = 0,
        val bandBestShifts: List<Int?> = emptyList(),
        val shiftScores: List<ShiftScore> = emptyList(),
    )

    /**
     * Positive shift means right-side content lies lower: compare left[y] with right[y + shift].
     * Coordinates refer to the already normalized input. Zero-width gutters are permitted.
     * Invalid input is a caller error; insufficient usable support is an explicit measurement status.
     */
    fun analyze(
        luminance: IntArray,
        width: Int,
        height: Int,
        seamStart: Int,
        seamEnd: Int,
        config: Config = Config(),
    ): Result {
        // Validate bounds before multiplication or allocating profiles. Input is borrowed, never mutated.
        require(width in 1..512 && height in 1..512) { "raster dimensions must be in 1..512" }
        require(luminance.size >= width * height) { "luminance buffer too small" }
        require(seamStart in 0..width && seamEnd in seamStart..width) { "invalid gutter interval" }
        for (i in 0 until width * height) require(luminance[i] in 0..255) { "luminance outside 0..255" }

        val leftStart = seamStart - config.guard - config.stripWidth
        val rightStart = seamEnd + config.guard
        if (leftStart < 0 || rightStart + config.stripWidth > width) {
            return Result(Status.INSUFFICIENT_INTERIOR)
        }
        // Smoothing needs one neighbor row. All shifts, including band diagnostics, use fixed support.
        val from = config.maxShift + 1
        val until = height - config.maxShift - 1
        val rows = until - from
        if (rows < 4 * 8) return Result(Status.INSUFFICIENT_ROWS)

        val left = profiles(luminance, width, height, leftStart, config.stripWidth)
        val right = profiles(luminance, width, height, rightStart, config.stripWidth)
        val zeroLuminance = correlate(left.luminance, right.luminance, from, until, 0, config)
        val scores = search(left.texture, right.texture, from, until, config)
        val best = best(scores) ?: return Result(
            status = Status.LOW_TEXTURE,
            sampleRows = rows,
            luminanceCorrelationAtZeroShift = zeroLuminance,
            shiftScores = scores,
        )
        val alternative = scores.filter { abs(it.shift - best.shift) > 2 }
            .mapNotNull { it.correlation }
            .maxOrNull()
        val localShifts = List(4) { band ->
            val bandFrom = from + rows * band / 4
            val bandUntil = from + rows * (band + 1) / 4
            best(search(left.texture, right.texture, bandFrom, bandUntil, config))?.shift
        }
        return Result(
            status = Status.MEASURED,
            sampleRows = rows,
            luminanceCorrelationAtZeroShift = zeroLuminance,
            bestShift = best.shift,
            bestCorrelation = best.correlation,
            luminanceCorrelationAtBestShift = correlate(
                left.luminance,
                right.luminance,
                from,
                until,
                best.shift,
                config,
            ),
            peakSeparation = alternative?.let { max(0.0, best.correlation!! - it) },
            atSearchBoundary = config.maxShift > 0 && abs(best.shift) == config.maxShift,
            informativeBands = localShifts.count { it != null },
            agreeingBands = localShifts.count { it != null && abs(it - best.shift) <= 1 },
            bandBestShifts = localShifts,
            shiftScores = scores,
        )
    }

    private data class Profiles(val luminance: DoubleArray, val texture: DoubleArray)

    private fun profiles(pixels: IntArray, width: Int, height: Int, start: Int, count: Int): Profiles {
        val mean = DoubleArray(height)
        val texture = DoubleArray(height)
        for (y in 0 until height) {
            val offset = y * width + start
            var sum = 0
            var variation = 0
            for (x in 0 until count) {
                sum += pixels[offset + x]
                if (x > 0) variation += abs(pixels[offset + x] - pixels[offset + x - 1])
            }
            mean[y] = sum / (255.0 * count)
            texture[y] = variation / (255.0 * (count - 1))
        }
        smooth(mean)
        smooth(texture)
        return Profiles(mean, texture)
    }

    /** In-place [1, 2, 1]/4 smoothing; untouched end rows are excluded by the common support. */
    private fun smooth(profile: DoubleArray) {
        var previous = profile[0]
        for (y in 1 until profile.lastIndex) {
            val current = profile[y]
            profile[y] = (previous + 2 * current + profile[y + 1]) / 4
            previous = current
        }
    }

    private fun search(
        left: DoubleArray,
        right: DoubleArray,
        from: Int,
        until: Int,
        config: Config,
    ): List<ShiftScore> = (-config.maxShift..config.maxShift).map { shift ->
        ShiftScore(shift, correlate(left, right, from, until, shift, config))
    }

    private fun best(scores: List<ShiftScore>): ShiftScore? {
        var selected: ShiftScore? = null
        for (candidate in scores) {
            val score = candidate.correlation ?: continue
            val current = selected
            if (
                current == null || score > current.correlation!! + 1e-12 ||
                (
                    abs(score - current.correlation) <= 1e-12 &&
                        (
                            abs(candidate.shift) < abs(current.shift) ||
                                (abs(candidate.shift) == abs(current.shift) && candidate.shift < current.shift)
                            )
                    )
            ) {
                selected = candidate
            }
        }
        return selected
    }

    /** Centered two-pass Pearson correlation. Low variance is missing evidence, never a zero match. */
    private fun correlate(
        left: DoubleArray,
        right: DoubleArray,
        from: Int,
        until: Int,
        shift: Int,
        config: Config,
    ): Double? {
        val count = until - from
        var leftMean = 0.0
        var rightMean = 0.0
        for (y in from until until) {
            leftMean += left[y]
            rightMean += right[y + shift]
        }
        leftMean /= count
        rightMean /= count
        var leftVariance = 0.0
        var rightVariance = 0.0
        var covariance = 0.0
        for (y in from until until) {
            val l = left[y] - leftMean
            val r = right[y + shift] - rightMean
            leftVariance += l * l
            rightVariance += r * r
            covariance += l * r
        }
        val minVariance = count * config.minStdDeviation * config.minStdDeviation
        if (leftVariance < minVariance || rightVariance < minVariance) return null
        return (covariance / sqrt(leftVariance * rightVariance)).coerceIn(-1.0, 1.0)
    }
}
