package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageRotationCalculatorTest {

    @Test
    fun `rotates when page and viewport orientations differ`() {
        assertTrue(PageRotationCalculator.shouldRotateToMatchViewport(2400, 1600, 1080, 2400))
        assertTrue(PageRotationCalculator.shouldRotateToMatchViewport(1600, 2400, 2400, 1080))
    }

    @Test
    fun `does not rotate when page and viewport orientations match`() {
        assertFalse(PageRotationCalculator.shouldRotateToMatchViewport(1600, 2400, 1080, 2400))
        assertFalse(PageRotationCalculator.shouldRotateToMatchViewport(2400, 1600, 2400, 1080))
    }

    @Test
    fun `does not rotate square or invalid dimensions`() {
        assertFalse(PageRotationCalculator.shouldRotateToMatchViewport(1000, 1000, 1080, 2400))
        assertFalse(PageRotationCalculator.shouldRotateToMatchViewport(1000, 1500, 0, 2400))
    }
}
