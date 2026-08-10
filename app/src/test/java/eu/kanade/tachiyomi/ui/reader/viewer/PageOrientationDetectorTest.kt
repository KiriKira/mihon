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

    @Test
    fun `uses sideways Chinese glyph angles for clockwise correction`() {
        val correction = PageOrientationDetector.selectCorrectionFromAngles(
            listOf(
                angle(-91f, 1, 1.4),
                angle(-88f, 1, 1.3),
                angle(-93f, 1, 1.2),
                angle(2f, 1, 0.7),
            ),
        )

        assertEquals(90, correction)
    }

    @Test
    fun `uses clockwise Chinese glyph angles for counterclockwise correction`() {
        val correction = PageOrientationDetector.selectCorrectionFromAngles(
            listOf(
                angle(89f, 2, 2.5),
                angle(94f, 2, 2.4),
            ),
        )

        assertEquals(270, correction)
    }

    @Test
    fun `keeps upright Chinese glyphs unchanged`() {
        val correction = PageOrientationDetector.selectCorrectionFromAngles(
            listOf(
                angle(-2f, 2, 2.2),
                angle(3f, 2, 2.1),
            ),
        )

        assertEquals(0, correction)
    }

    @Test
    fun `falls back when glyph angle votes are ambiguous`() {
        val correction = PageOrientationDetector.selectCorrectionFromAngles(
            listOf(
                angle(0f, 2, 2.0),
                angle(-90f, 2, 2.1),
            ),
        )

        assertEquals(null, correction)
    }

    private fun score(rotation: Int, characters: Int, score: Double) =
        PageOrientationDetector.RotationScore(rotation, characters, score)

    private fun angle(degrees: Float, characters: Int, score: Double) =
        PageOrientationDetector.AngleObservation(degrees, characters, score)
}
