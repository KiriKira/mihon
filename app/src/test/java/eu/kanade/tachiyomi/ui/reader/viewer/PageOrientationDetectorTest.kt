package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageOrientationDetectorTest {

    @Test
    fun `selects decisive rotated Chinese text`() {
        val correction = PageOrientationDetector.selectCorrection(
            listOf(
                score(0, 3, 2.0),
                score(90, 8, 10.0),
                score(180, 2, 1.8),
                score(270, 1, 1.0),
            ),
        )

        assertEquals(90, correction)
    }

    @Test
    fun `keeps page unchanged when candidates are ambiguous`() {
        val correction = PageOrientationDetector.selectCorrection(
            listOf(
                score(0, 4, 5.0),
                score(90, 4, 5.2),
                score(180, 1, 1.0),
                score(270, 1, 1.0),
            ),
        )

        assertEquals(0, correction)
    }

    @Test
    fun `keeps page unchanged without enough Chinese text`() {
        val correction = PageOrientationDetector.selectCorrection(
            listOf(
                score(0, 0, 0.0),
                score(90, 1, 4.0),
                score(180, 0, 0.0),
                score(270, 0, 0.0),
            ),
        )

        assertEquals(0, correction)
    }

    private fun score(rotation: Int, characters: Int, score: Double) =
        PageOrientationDetector.RotationScore(rotation, characters, score)
}
