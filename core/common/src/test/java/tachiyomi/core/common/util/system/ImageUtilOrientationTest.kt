package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageUtilOrientationTest {

    @Test
    fun `rotates when page and viewport orientations differ`() {
        assertTrue(ImageUtil.shouldRotateToMatchViewport(2400, 1600, 1080, 2400))
        assertTrue(ImageUtil.shouldRotateToMatchViewport(1600, 2400, 2400, 1080))
    }

    @Test
    fun `does not rotate when page and viewport orientations match`() {
        assertFalse(ImageUtil.shouldRotateToMatchViewport(1600, 2400, 1080, 2400))
        assertFalse(ImageUtil.shouldRotateToMatchViewport(2400, 1600, 2400, 1080))
    }

    @Test
    fun `does not rotate square or invalid dimensions`() {
        assertFalse(ImageUtil.shouldRotateToMatchViewport(1000, 1000, 1080, 2400))
        assertFalse(ImageUtil.shouldRotateToMatchViewport(1000, 1500, 0, 2400))
    }
}
