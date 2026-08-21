package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageOrientationDetectorTest {

    @Test
    fun `strongest crop class ignores thresholds`() {
        val decisive = listOf(scores(0.03f, 0.03f, 0.04f, 0.90f))
        assertEquals(3 to 0.90f, PageOrientationDetector.selectOrientationClass(decisive))
        // low confidence is no longer rejected here; the global mean gate decides
        val weak = listOf(scores(0.20f, 0.10f, 0.15f, 0.55f))
        assertEquals(3 to 0.55f, PageOrientationDetector.selectOrientationClass(weak))
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
    fun `equivariant proposal below mean confidence is rejected`() {
        // all four rotations agree on class 3 (=> correction 90) but confidence is weak
        val weak = mapOf(
            0 to listOf(scores(0.30f, 0.10f, 0.15f, 0.45f)),
            90 to listOf(scores(0.45f, 0.10f, 0.15f, 0.30f)),
            180 to listOf(scores(0.10f, 0.45f, 0.15f, 0.30f)),
            270 to listOf(scores(0.10f, 0.15f, 0.45f, 0.30f)),
        )
        assertNull(PageOrientationDetector.selectConsistentProposal(weak))
    }

    @Test
    fun `one weak rotation no longer kills an otherwise consistent proposal`() {
        // three rotations are decisive and the fourth sits below the old per-crop
        // confidence/margin gates; the global mean (0.85) still clears the bar
        val mostlyDecisive = mapOf(
            0 to listOf(scores(0.02f, 0.02f, 0.03f, 0.95f)),
            90 to listOf(scores(0.95f, 0.02f, 0.02f, 0.03f)),
            180 to listOf(scores(0.03f, 0.95f, 0.02f, 0.02f)),
            270 to listOf(scores(0.10f, 0.15f, 0.55f, 0.20f)),
        )
        assertEquals(90, PageOrientationDetector.selectConsistentProposal(mostlyDecisive))
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
