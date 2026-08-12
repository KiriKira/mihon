package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageOrientationDetectorTest {

    @Test
    fun `selects a decisive crop orientation`() {
        assertEquals(3, PageOrientationDetector.selectOrientationClass(listOf(scores(0.03f, 0.03f, 0.04f, 0.90f))))
        assertNull(PageOrientationDetector.selectOrientationClass(listOf(scores(0.20f, 0.10f, 0.15f, 0.55f))))
    }

    @Test
    fun `accepts only rotation-equivariant proposal`() {
        val equivariant = mapOf(
            0 to listOf(scores(0.02f, 0.02f, 0.03f, 0.93f)),
            90 to listOf(scores(0.93f, 0.02f, 0.02f, 0.03f)),
            180 to listOf(scores(0.03f, 0.93f, 0.02f, 0.02f)),
            270 to listOf(scores(0.02f, 0.03f, 0.93f, 0.02f)),
        )
        assertEquals(90, PageOrientationDetector.selectConsistentProposal(equivariant))

        val inconsistent = equivariant + (270 to listOf(scores(0.93f, 0.02f, 0.03f, 0.02f)))
        assertNull(PageOrientationDetector.selectConsistentProposal(inconsistent))
    }

    @Test
    fun `upright equivariant result needs no correction`() {
        val upright = mapOf(
            0 to listOf(scores(0.93f, 0.02f, 0.03f, 0.02f)),
            90 to listOf(scores(0.02f, 0.93f, 0.03f, 0.02f)),
            180 to listOf(scores(0.02f, 0.03f, 0.93f, 0.02f)),
            270 to listOf(scores(0.02f, 0.03f, 0.02f, 0.93f)),
        )
        assertNull(PageOrientationDetector.selectConsistentProposal(upright))
    }

    @Test
    fun `requires enough CJK text`() {
        assertTrue(PageOrientationDetector.hasSufficientText(evidence(6, 2)))
        assertFalse(PageOrientationDetector.hasSufficientText(evidence(5, 2)))
        assertFalse(PageOrientationDetector.hasSufficientText(evidence(20, 1)))
    }

    private fun scores(vararg values: Float) = values

    private fun evidence(characters: Int, elements: Int) =
        PageOrientationDetector.OcrEvidence(characters, elements, characters.toDouble())
}
