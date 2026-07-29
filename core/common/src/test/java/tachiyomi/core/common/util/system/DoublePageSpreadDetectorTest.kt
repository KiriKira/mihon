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
        // Mirrors the real failure mode from the test images: artwork is noisy
        // across the center, but the actual gutter is a thin white column. A
        // center-strip *average* would hide this; the column-wise scan must
        // still pick it up.
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            when {
                x == width / 2 -> 255
                // Noise must vary along y, otherwise non-gutter columns would
                // also have stddev=0 and could win the min-stddev search.
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
            // Noise must vary along y or non-gutter columns become constants
            // with stddev=0 and the search picks them instead of the gutter.
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
}
