package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoublePageSpreadDetectorTest {

    @Test
    fun `solid white gutter is detected as stitched`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { 255 }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertEquals(255.0, stats.mean, 0.001)
        assertEquals(0.0, stats.stddev, 0.001)
        assertTrue(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `solid black gutter is detected as stitched`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { 0 }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertEquals(0.0, stats.mean, 0.001)
        assertEquals(0.0, stats.stddev, 0.001)
        assertTrue(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `noisy center is treated as real spread`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if ((x + y) % 2 == 0) 20 else 220
        }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertFalse(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `mid-grey uniform column is treated as real spread`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { 128 }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertFalse(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `single white gutter column surrounded by noise is detected as stitched`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            when {
                x == width / 2 -> 255
                (x + y) % 2 == 0 -> 30
                else -> 200
            }
        }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertEquals(255.0, stats.mean, 0.001)
        assertEquals(0.0, stats.stddev, 0.001)
        assertTrue(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `slightly off-center gutter is still detected within the search band`() {
        val width = 200
        val height = 40
        val gutterX = width / 2 - 4
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (x == gutterX) 255 else (x * 37 + y * 53) % 200
        }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertEquals(gutterX, stats.x)
        assertTrue(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `centered gutter is safe for fixed half split`() {
        val width = 200
        val height = 40
        val gutterX = width / 2
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (x == gutterX) 255 else (x * 37 + y * 53) % 200
        }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertEquals(gutterX, stats.x)
        assertTrue(DoublePageSpreadDetector.isStitchedDoublePage(stats, imageWidth = width))
    }

    @Test
    fun `visibly off-center gutter is not safe for fixed half split`() {
        val width = 200
        val height = 40
        val gutterX = width / 2 + 4
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (x == gutterX) 255 else (x * 37 + y * 53) % 200
        }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertEquals(gutterX, stats.x)
        assertFalse(DoublePageSpreadDetector.isStitchedDoublePage(stats, imageWidth = width))
    }

    @Test
    fun `white background with ink crossing every center column is treated as real spread`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { 255 }
        for (y in 10 until 40) {
            for (x in 45 until 55) {
                luminance[y * width + x] = 20
            }
        }
        val stats = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        assertFalse(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `gutter run expands over adjacent matching columns only`() {
        val width = 80
        val height = 40
        val center = width / 2
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (x in center - 1..center + 1) 255 else (x * 17 + y * 31) % 210
        }
        val candidate = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        val gutter = DoublePageSpreadDetector.findGutterRun(luminance, width, height, candidate)

        assertEquals(center - 1, gutter.startX)
        assertEquals(center + 1, gutter.endX)
    }

    @Test
    fun `correlated artwork across white gutter is detected as continuous spread`() {
        val width = 100
        val height = 100
        val center = width / 2
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            when {
                x in center - 1..center + 1 -> 255
                x in center - 12 until center - 1 || x in center + 2..center + 12 -> {
                    if ((y / 10) % 2 == 0) 30 else 245
                }
                else -> (x * 11 + y * 7) % 220
            }
        }
        val candidate = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        val gutter = DoublePageSpreadDetector.findGutterRun(luminance, width, height, candidate)
        val continuity = DoublePageSpreadDetector.analyzeCrossGutterContinuity(
            luminance,
            width,
            height,
            gutter,
            contextWidth = 10,
        )

        assertTrue(continuity.bothSidesActiveRatio >= 0.45)
        assertTrue(continuity.rowProfileCorrelation >= 0.35)
        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(continuity))
    }

    @Test
    fun `correlated artwork across black gutter is detected as continuous spread`() {
        val width = 100
        val height = 100
        val center = width / 2
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            when {
                x in center - 1..center + 1 -> 0
                x in center - 12 until center - 1 || x in center + 2..center + 12 -> {
                    if ((y / 10) % 2 == 0) 230 else 10
                }
                else -> 40 + (x * 7 + y * 9) % 180
            }
        }
        val candidate = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        val gutter = DoublePageSpreadDetector.findGutterRun(luminance, width, height, candidate)
        val continuity = DoublePageSpreadDetector.analyzeCrossGutterContinuity(
            luminance,
            width,
            height,
            gutter,
            contextWidth = 10,
        )

        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(continuity))
    }

    @Test
    fun `independent side activity remains stitched`() {
        val width = 100
        val height = 100
        val center = width / 2
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            when {
                x == center -> 255
                x in center - 10 until center -> if (y < height / 2) 20 else 245
                x in center + 1..center + 10 -> if (y >= height / 2) 20 else 245
                else -> 180
            }
        }
        val candidate = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        val gutter = DoublePageSpreadDetector.findGutterRun(luminance, width, height, candidate)
        val continuity = DoublePageSpreadDetector.analyzeCrossGutterContinuity(
            luminance,
            width,
            height,
            gutter,
            contextWidth = 10,
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(continuity))
    }

    @Test
    fun `one-sided activity remains stitched`() {
        val width = 100
        val height = 100
        val center = width / 2
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            when {
                x == center -> 255
                x in center - 10 until center -> 20
                x in center + 1..center + 10 -> 245
                else -> 180
            }
        }
        val candidate = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        val gutter = DoublePageSpreadDetector.findGutterRun(luminance, width, height, candidate)
        val continuity = DoublePageSpreadDetector.analyzeCrossGutterContinuity(
            luminance,
            width,
            height,
            gutter,
            contextWidth = 10,
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(continuity))
    }

    @Test
    fun `constant row profiles do not create false correlation`() {
        val width = 100
        val height = 100
        val center = width / 2
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            when {
                x == center -> 255
                x in center - 10 until center || x in center + 1..center + 10 -> 20
                else -> 180
            }
        }
        val candidate = DoublePageSpreadDetector.findBestGutterColumn(luminance, width, height)
        val gutter = DoublePageSpreadDetector.findGutterRun(luminance, width, height, candidate)
        val continuity = DoublePageSpreadDetector.analyzeCrossGutterContinuity(
            luminance,
            width,
            height,
            gutter,
            contextWidth = 10,
        )

        assertEquals(0.0, continuity.rowProfileCorrelation, 0.001)
        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(continuity))
    }
}
