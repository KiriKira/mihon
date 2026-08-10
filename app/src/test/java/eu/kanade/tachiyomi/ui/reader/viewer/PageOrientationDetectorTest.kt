package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageOrientationDetectorTest {

    @Test
    fun `corrects supplied counterclockwise rotated page clockwise`() {
        val correction = PageOrientationDetector.selectCorrection(
            floatArrayOf(0.0594876f, 0.0221126f, 0.0332601f, 0.8851397f),
        )

        assertEquals(90, correction)
    }

    @Test
    fun `keeps supplied corrected page unchanged`() {
        val correction = PageOrientationDetector.selectCorrection(
            floatArrayOf(0.7688869f, 0.0772248f, 0.0243888f, 0.1294995f),
        )

        assertEquals(0, correction)
    }

    @Test
    fun `keeps uncertain rotated page unchanged`() {
        val correction = PageOrientationDetector.selectCorrection(
            floatArrayOf(0.1f, 0.35f, 0.3f, 0.25f),
        )

        assertEquals(0, correction)
    }

    @Test
    fun `inverts confident model orientations into viewer corrections`() {
        assertEquals(270, PageOrientationDetector.selectCorrection(floatArrayOf(0.01f, 0.97f, 0.01f, 0.01f)))
        assertEquals(180, PageOrientationDetector.selectCorrection(floatArrayOf(0.01f, 0.01f, 0.97f, 0.01f)))
        assertEquals(90, PageOrientationDetector.selectCorrection(floatArrayOf(0.01f, 0.01f, 0.01f, 0.97f)))
    }
}
