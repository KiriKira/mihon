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
        val stats = DoublePageSpreadDetector.analyzeCenterStrip(luminance, width, height)
        assertEquals(255.0, stats.mean, 0.001)
        assertEquals(0.0, stats.stddev, 0.001)
        assertTrue(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `solid black gutter is detected as stitched`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { 0 }
        val stats = DoublePageSpreadDetector.analyzeCenterStrip(luminance, width, height)
        assertEquals(0.0, stats.mean, 0.001)
        assertEquals(0.0, stats.stddev, 0.001)
        assertTrue(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `noisy center strip is treated as real spread`() {
        val width = 100
        val height = 50
        // Alternate between dark and light pixels to simulate artwork crossing the center.
        val luminance = IntArray(width * height) { idx -> if (idx % 2 == 0) 20 else 220 }
        val stats = DoublePageSpreadDetector.analyzeCenterStrip(luminance, width, height)
        assertFalse(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `mid-grey uniform strip is treated as real spread`() {
        // Uniform but not near pure white/black -> not a gutter.
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { 128 }
        val stats = DoublePageSpreadDetector.analyzeCenterStrip(luminance, width, height)
        assertFalse(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }

    @Test
    fun `white background with stray ink in center is treated as real spread`() {
        val width = 100
        val height = 50
        val luminance = IntArray(width * height) { 255 }
        // Inject dark ink across the center strip on a few rows.
        for (y in 10 until 40) {
            for (x in 45 until 55) {
                luminance[y * width + x] = 20
            }
        }
        val stats = DoublePageSpreadDetector.analyzeCenterStrip(luminance, width, height)
        assertFalse(DoublePageSpreadDetector.isStitchedDoublePage(stats))
    }
}
