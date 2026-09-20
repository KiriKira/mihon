package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageRotationCalculatorTest {

    @Test
    fun `rotates landscape pages`() {
        assertTrue(PageRotationCalculator.shouldRotatePage(2400, 1600))
    }

    @Test
    fun `does not rotate portrait pages`() {
        assertFalse(PageRotationCalculator.shouldRotatePage(1600, 2400))
    }

    @Test
    fun `does not rotate square or invalid dimensions`() {
        assertFalse(PageRotationCalculator.shouldRotatePage(1000, 1000))
        assertFalse(PageRotationCalculator.shouldRotatePage(0, 1500))
        assertFalse(PageRotationCalculator.shouldRotatePage(1500, 0))
    }
}
