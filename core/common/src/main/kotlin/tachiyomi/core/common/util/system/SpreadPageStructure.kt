package tachiyomi.core.common.util.system

import kotlin.math.max
import kotlin.math.min

/**
 * Enhanced spread classification based on page layout rather than pixel continuity.
 *
 * Two independent pages scanned side by side each keep their own page margins: an
 * inner margin next to the binding, which shows up as a wide blank strip in the
 * middle of the image, and blank rows above and below the panels next to that strip.
 * A double-page spread (大跨页) is drawn across the binding instead, so its artwork
 * runs up to the seam and usually to the top and bottom edges as well, leaving at most
 * a thin scan gutter.
 *
 * Comparing pixels on both sides of the seam is not reliable for this: the panel
 * borders and white margins of two unrelated pages line up with each other just as
 * well as continuous artwork does.
 */
internal object SpreadPageStructure {

    data class Structure(
        /** Width of the blank strip around the center, as a fraction of the image width. */
        val seamWidthFraction: Double,
        val left: HalfMargins,
        val right: HalfMargins,
    )

    /** Blank rows at the top and bottom of one half, next to the seam, as fractions of the height. */
    data class HalfMargins(val top: Double, val bottom: Double) {
        fun any(minMargin: Double) = top >= minMargin || bottom >= minMargin
        fun both(minMargin: Double) = top >= minMargin && bottom >= minMargin
    }

    /**
     * @param luminance row-major luminance values in 0..255.
     * @return null when the image has no light paper-colored seam to analyse.
     */
    fun analyze(
        luminance: IntArray,
        width: Int,
        height: Int,
        inkContrast: Int = 70,
        seamSearchFraction: Double = 0.05,
        maxSeamInkFraction: Double = 0.03,
        stripWidthFraction: Double = 0.08,
        maxBlankRowInkFraction: Double = 0.03,
        maxEdgeRowsFraction: Double = 0.01,
        maxNoiseRowsFraction: Double = 0.005,
    ): Structure? {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(luminance.size >= width * height) { "luminance buffer too small" }

        val paper = paperLevel(luminance, width * height)
        val inkThreshold = paper - inkContrast
        if (inkThreshold <= 0) return null
        fun isInk(x: Int, y: Int) = luminance[y * width + x] < inkThreshold

        val columnInk = DoubleArray(width) { x ->
            var count = 0
            for (y in 0 until height) if (isInk(x, y)) count++
            count.toDouble() / height
        }

        // Start from the blankest column near the center and grow the seam over the
        // neighbouring blank columns.
        val bandWidth = max(1, (width * seamSearchFraction).toInt())
        val searchStart = max(0, (width - bandWidth) / 2)
        val searchEnd = min(width, searchStart + bandWidth)
        var seed = searchStart
        for (x in searchStart until searchEnd) {
            if (columnInk[x] < columnInk[seed]) seed = x
        }
        if (columnInk[seed] >= maxSeamInkFraction) return null

        var seamStart = seed
        var seamEnd = seed
        while (seamStart > 0 && columnInk[seamStart - 1] < maxSeamInkFraction) seamStart--
        while (seamEnd < width - 1 && columnInk[seamEnd + 1] < maxSeamInkFraction) seamEnd++

        val stripWidth = max(1, (width * stripWidthFraction).toInt())
        // Tolerate scan specks and stray dots in otherwise blank margin rows
        val maxInkPerRow = max(2, (stripWidth * maxBlankRowInkFraction).toInt())

        fun margins(xFrom: Int, xUntil: Int): HalfMargins {
            if (xFrom >= xUntil) return HalfMargins(0.0, 0.0)
            fun isBlankRow(y: Int): Boolean {
                var count = 0
                for (x in xFrom until xUntil) {
                    if (isInk(x, y) && ++count > maxInkPerRow) return false
                }
                return true
            }
            val maxEdgeRows = max(1, (height * maxEdgeRowsFraction).toInt())
            val maxNoiseRows = max(1, (height * maxNoiseRowsFraction).toInt())

            // Count the blank rows of a margin starting at the outer edge, row by row via [rowAt].
            // A dark scan edge of up to maxEdgeRows rows at the very edge is skipped, and single
            // non-blank rows (scan noise) are tolerated up to maxNoiseRows in total.
            fun margin(rowAt: (Int) -> Int): Int {
                val limit = height / 2
                var i = 0
                while (i <= maxEdgeRows && i < limit && !isBlankRow(rowAt(i))) i++
                // Content reaches the edge: no margin
                if (i > maxEdgeRows) return 0
                var blank = 0
                var noise = 0
                while (i < limit) {
                    if (isBlankRow(rowAt(i))) {
                        blank++
                    } else if (noise < maxNoiseRows && i + 1 < limit && isBlankRow(rowAt(i + 1))) {
                        noise++
                    } else {
                        break
                    }
                    i++
                }
                return blank
            }

            val top = margin { it }
            val bottom = margin { height - 1 - it }
            return HalfMargins(top.toDouble() / height, bottom.toDouble() / height)
        }

        return Structure(
            seamWidthFraction = (seamEnd - seamStart + 1).toDouble() / width,
            left = margins(max(0, seamStart - stripWidth), seamStart),
            right = margins(seamEnd + 1, min(width, seamEnd + 1 + stripWidth)),
        )
    }

    /**
     * Whether the image looks like two separate pages that should be split.
     *
     * Both signals are required, so a spread with a wider scan gutter, or with blank sky
     * at the top of the artwork, is still kept whole.
     */
    fun isTwoPages(
        structure: Structure,
        minSeamWidthFraction: Double = 0.015,
        minMargin: Double = 0.02,
    ): Boolean {
        if (structure.seamWidthFraction < minSeamWidthFraction) return false
        val left = structure.left
        val right = structure.right
        return left.any(minMargin) && right.any(minMargin) &&
            (left.both(minMargin) || right.both(minMargin))
    }

    /** The brightness of the paper: the 99th percentile luminance. */
    private fun paperLevel(luminance: IntArray, size: Int): Int {
        val histogram = IntArray(256)
        for (i in 0 until size) histogram[luminance[i].coerceIn(0, 255)]++
        var remaining = size / 100
        for (level in 255 downTo 0) {
            remaining -= histogram[level]
            if (remaining < 0) return level
        }
        return 0
    }
}
