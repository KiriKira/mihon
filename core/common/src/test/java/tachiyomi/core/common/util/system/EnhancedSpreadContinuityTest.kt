package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnhancedSpreadContinuityTest {

    @Test
    fun `enhanced analysis uses power of two decode before fixed resize`() {
        assertEquals(2, calculateEnhancedSpreadSampleSize(sourceWidth = 1536))
        assertEquals(4, calculateEnhancedSpreadSampleSize(sourceWidth = 2048))
        assertEquals(1, calculateEnhancedSpreadSampleSize(sourceWidth = 900))
    }

    @Test
    fun `sparse correlated spread is protected when local seam evidence agrees`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.39,
            rowProfileCorrelation = 0.54,
            leftMeanActiveDensity = 0.36,
            rightMeanActiveDensity = 0.48,
            nearSeamLuminanceCorrelation = 0.45,
            localSeamSupportRatio = 0.06,
        )

        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `dense painted spread is protected by seam luminance plus local evidence`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.93,
            rowProfileCorrelation = -0.18,
            leftMeanActiveDensity = 0.97,
            rightMeanActiveDensity = 0.89,
            nearSeamLuminanceCorrelation = 0.65,
            localSeamSupportRatio = 0.37,
        )

        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `mixed artwork spread can use local seam evidence when row correlation is weak`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.42,
            rowProfileCorrelation = 0.30,
            leftMeanActiveDensity = 0.50,
            rightMeanActiveDensity = 0.41,
            nearSeamLuminanceCorrelation = 0.59,
            localSeamSupportRatio = 0.32,
        )

        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `accidental global correlation is rejected without local seam evidence`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.37,
            rowProfileCorrelation = 0.61,
            leftMeanActiveDensity = 0.40,
            rightMeanActiveDensity = 0.38,
            nearSeamLuminanceCorrelation = 0.68,
            localSeamSupportRatio = 0.0,
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `dense unrelated pages are not protected without enough local support`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.94,
            rowProfileCorrelation = 0.02,
            leftMeanActiveDensity = 0.91,
            rightMeanActiveDensity = 0.88,
            nearSeamLuminanceCorrelation = 0.18,
            localSeamSupportRatio = 0.10,
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `correlation alone is not enough when one side is mostly blank`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.38,
            rowProfileCorrelation = 0.70,
            leftMeanActiveDensity = 0.12,
            rightMeanActiveDensity = 0.55,
            nearSeamLuminanceCorrelation = 0.72,
            localSeamSupportRatio = 0.30,
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `similar activity amount without global or strong local evidence remains stitched`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.50,
            rowProfileCorrelation = 0.10,
            leftMeanActiveDensity = 0.55,
            rightMeanActiveDensity = 0.57,
            nearSeamLuminanceCorrelation = 0.30,
            localSeamSupportRatio = 0.04,
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `repeated strong local seam evidence can stand on its own`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.45,
            rowProfileCorrelation = 0.20,
            leftMeanActiveDensity = 0.44,
            rightMeanActiveDensity = 0.40,
            nearSeamLuminanceCorrelation = 0.42,
            localSeamSupportRatio = 0.30,
        )

        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }
}
