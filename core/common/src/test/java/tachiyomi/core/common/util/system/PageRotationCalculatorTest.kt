package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageRotationCalculatorTest {

    @Test
    fun `rotates sufficiently wide pages in portrait reader`() {
        assertTrue(PageRotationCalculator.shouldRotatePage(2000, 1600, true))
        assertTrue(PageRotationCalculator.shouldRotatePage(2400, 1600, true))
    }

    @Test
    fun `does not rotate pages below 1_25 threshold`() {
        assertFalse(PageRotationCalculator.shouldRotatePage(1999, 1600, true))
        assertFalse(PageRotationCalculator.shouldRotatePage(1600, 1600, true))
        assertFalse(PageRotationCalculator.shouldRotatePage(1600, 2400, true))
    }

    @Test
    fun `does not rotate in landscape reader`() {
        assertFalse(PageRotationCalculator.shouldRotatePage(2400, 1600, false))
        assertFalse(PageRotationCalculator.shouldRotatePage(4000, 1000, false))
    }

    @Test
    fun `does not rotate invalid dimensions`() {
        assertFalse(PageRotationCalculator.shouldRotatePage(0, 1500, true))
        assertFalse(PageRotationCalculator.shouldRotatePage(1500, 0, true))
    }
}
