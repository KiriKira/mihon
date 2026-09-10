package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnhancedSpreadContinuityTest {

    @Test
    fun `sparse but strongly correlated activity is treated as continuous`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.39,
            rowProfileCorrelation = 0.54,
            leftMeanActiveDensity = 0.36,
            rightMeanActiveDensity = 0.48,
        )

        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `dense painted spread can use near seam luminance continuity`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.93,
            rowProfileCorrelation = -0.18,
            leftMeanActiveDensity = 0.97,
            rightMeanActiveDensity = 0.89,
            nearSeamLuminanceCorrelation = 0.65,
        )

        assertTrue(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `dense unrelated pages are not protected without seam correlation`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.94,
            rowProfileCorrelation = 0.02,
            leftMeanActiveDensity = 0.91,
            rightMeanActiveDensity = 0.88,
            nearSeamLuminanceCorrelation = 0.18,
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
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }

    @Test
    fun `similar activity amount without correlation remains stitched`() {
        val stats = DoublePageSpreadDetector.ContinuityStats(
            bothSidesActiveRatio = 0.50,
            rowProfileCorrelation = 0.10,
            leftMeanActiveDensity = 0.55,
            rightMeanActiveDensity = 0.57,
        )

        assertFalse(DoublePageSpreadDetector.isLikelyContinuousSpread(stats))
    }
}
