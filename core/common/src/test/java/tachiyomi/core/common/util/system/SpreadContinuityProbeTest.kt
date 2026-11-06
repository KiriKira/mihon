package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SpreadContinuityProbeTest {

    private val width = 96
    private val height = 128
    private val seamStart = 44
    private val seamEnd = 52
    private val config = SpreadContinuityProbe.Config(stripWidth = 16, maxShift = 8)

    private fun amplitude(y: Int): Int {
        val n = Math.floorMod(y, 997)
        return 4 + (n * n * 17 + n * 31 + 11) % 25
    }

    private fun scan(
        shift: Int = 0,
        seam: Int = 255,
        rightScale: Int = 1,
        rightOffset: Int = 0,
        reverseRightPattern: Boolean = false,
    ) = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        when {
            x in seamStart until seamEnd -> seam
            x < seamStart -> 100 + if (x % 2 == 0) amplitude(y) else -amplitude(y)
            else -> {
                val sign = if ((x % 2 == 0) != reverseRightPattern) 1 else -1
                100 + rightOffset + sign * rightScale * amplitude(y - shift)
            }
        }
    }

    private fun analyze(pixels: IntArray) =
        SpreadContinuityProbe.analyze(pixels, width, height, seamStart, seamEnd, config)

    @Test
    fun `uniform halves do not produce texture evidence`() {
        val result = analyze(IntArray(width * height) { 220 })
        assertEquals(SpreadContinuityProbe.Status.LOW_TEXTURE, result.status)
        assertNull(result.bestShift)
        assertNull(result.bestCorrelation)
        assertNull(result.luminanceCorrelationAtZeroShift)
    }

    @Test
    fun `aligned horizontal panel rules can fool luminance but not supply horizontal texture`() {
        val pixels = IntArray(width * height) { i -> if ((i / width) % 19 < 3) 20 else 240 }
        val result = analyze(pixels)
        assertEquals(1.0, result.luminanceCorrelationAtZeroShift!!, 1e-10)
        assertEquals(SpreadContinuityProbe.Status.LOW_TEXTURE, result.status)
        assertNull(result.bestCorrelation)
    }

    @Test
    fun `a vertical rule repeated on every row is also low information`() {
        val pixels = IntArray(width * height) { i -> if (i % width % 13 == 0) 20 else 240 }
        assertEquals(SpreadContinuityProbe.Status.LOW_TEXTURE, analyze(pixels).status)
    }

    @Test
    fun `positive shift means right hand content is lower`() {
        val result = analyze(scan(shift = 5))
        assertEquals(SpreadContinuityProbe.Status.MEASURED, result.status)
        assertEquals(5, result.bestShift)
        assertEquals(1.0, result.bestCorrelation!!, 1e-10)
        assertEquals(4, result.informativeBands)
        assertEquals(4, result.agreeingBands)
        assertFalse(result.atSearchBoundary)
    }

    @Test
    fun `negative shifts use the same convention`() {
        val result = analyze(scan(shift = -4))
        assertEquals(-4, result.bestShift)
        assertEquals(1.0, result.bestCorrelation!!, 1e-10)
    }

    @Test
    fun `constant exposure offset and contrast scaling do not change the shift`() {
        val result = analyze(scan(shift = 3, rightScale = 2, rightOffset = 60))
        assertEquals(3, result.bestShift)
        assertEquals(1.0, result.bestCorrelation!!, 1e-10)
    }

    @Test
    fun `white gray and black excluded gutters give identical evidence`() {
        val white = analyze(scan(shift = 2, seam = 255))
        assertEquals(white, analyze(scan(shift = 2, seam = 220)))
        assertEquals(white, analyze(scan(shift = 2, seam = 10)))
    }

    @Test
    fun `guard pixels next to the gutter are excluded`() {
        val pixels = scan(shift = 2)
        val reference = analyze(pixels)
        for (y in 0 until height) {
            for (x in seamStart - config.guard until seamStart) pixels[y * width + x] = 0
            for (x in seamEnd until seamEnd + config.guard) pixels[y * width + x] = 0
        }
        assertEquals(reference, analyze(pixels))
    }

    @Test
    fun `all shifts use the same number of rows`() {
        val result = analyze(scan(shift = 3))
        assertEquals(height - 2 * config.maxShift - 2, result.sampleRows)
        assertEquals(2 * config.maxShift + 1, result.shiftScores.size)
        assertTrue(result.shiftScores.all { it.correlation != null })
    }

    @Test
    fun `periodic texture exposes an equally good distant peak`() {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val a = intArrayOf(4, 20, 7, 25, 12, 28)[y % 6]
            100 + if (x % 2 == 0) a else -a
        }
        val result = analyze(pixels)
        assertEquals(0, result.bestShift)
        assertEquals(1.0, result.bestCorrelation!!, 1e-10)
        assertEquals(0.0, result.peakSeparation!!, 1e-10)
    }

    @Test
    fun `search boundary optimum is reported rather than treated as confidence`() {
        val result = analyze(scan(shift = config.maxShift))
        assertEquals(config.maxShift, result.bestShift)
        assertTrue(result.atSearchBoundary)
    }

    @Test
    fun `different image patterns can have identical profiles and perfect correlation`() {
        val original = scan(shift = 2)
        val reversed = scan(shift = 2, reverseRightPattern = true)
        assertFalse(original.contentEquals(reversed))
        // This is a deliberate counterexample, not a test that either image is a true spread.
        // The result has no spread label, split decision, probability or confidence field.
        assertEquals(analyze(original), analyze(reversed))
        assertEquals(1.0, analyze(reversed).bestCorrelation!!, 1e-10)
    }

    @Test
    fun `different local shifts expose inconsistent support`() {
        val pixels = scan()
        for (y in 0 until height) {
            val shift = if (y < height / 2) 4 else -4
            for (x in seamEnd until width) {
                pixels[y * width + x] = 100 + if (x % 2 == 0) amplitude(y - shift) else -amplitude(y - shift)
            }
        }
        val result = analyze(pixels)
        assertEquals(4, result.informativeBands)
        assertTrue(result.bandBestShifts.filterNotNull().any { it == 4 })
        assertTrue(result.bandBestShifts.filterNotNull().any { it == -4 })
        assertTrue(result.agreeingBands < result.informativeBands)
    }

    @Test
    fun `one featureless half cannot produce a match`() {
        val pixels = scan()
        for (y in 0 until height) {
            for (x in seamEnd until width) pixels[y * width + x] = 200
        }
        assertEquals(SpreadContinuityProbe.Status.LOW_TEXTURE, analyze(pixels).status)
    }

    @Test
    fun `zero search range has no alternative peak`() {
        val result = SpreadContinuityProbe.analyze(
            scan(),
            width,
            height,
            seamStart,
            seamEnd,
            config.copy(maxShift = 0),
        )
        assertEquals(0, result.bestShift)
        assertNull(result.peakSeparation)
        assertFalse(result.atSearchBoundary)
    }

    @Test
    fun `horizontal reflection reverses an unambiguous shift`() {
        val pixels = scan(shift = 3)
        val reflected = IntArray(pixels.size) { i -> pixels[(i / width) * width + width - 1 - i % width] }
        assertEquals(-3, analyze(reflected).bestShift)
    }

    @Test
    fun `a zero width gutter is accepted without guessing a blank column`() {
        val center = width / 2
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width - if (x >= center) 3 else 0
            100 + if (x % 2 == 0) amplitude(y) else -amplitude(y)
        }
        val result = SpreadContinuityProbe.analyze(pixels, width, height, center, center, config)
        assertEquals(3, result.bestShift)
        assertEquals(1.0, result.bestCorrelation!!, 1e-10)
    }

    @Test
    fun `maximum raster and search bounds terminate with finite scores`() {
        val size = 512
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            100 + if (x % 2 == 0) amplitude(y) else -amplitude(y)
        }
        val result = SpreadContinuityProbe.analyze(
            pixels,
            size,
            size,
            250,
            262,
            config.copy(stripWidth = 32, maxShift = 16),
        )
        assertEquals(0, result.bestShift)
        assertEquals(33, result.shiftScores.size)
        assertTrue(result.shiftScores.all { it.correlation?.isFinite() == true })
    }

    @Test
    fun `invalid or unbounded inputs fail before allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpreadContinuityProbe.analyze(intArrayOf(), Int.MAX_VALUE, 128, 1, 2)
        }
        assertThrows(IllegalArgumentException::class.java) { analyze(intArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { analyze(scan().also { it[0] = -1 }) }
        assertThrows(IllegalArgumentException::class.java) { analyze(scan().also { it[0] = 256 }) }
        assertThrows(IllegalArgumentException::class.java) {
            SpreadContinuityProbe.analyze(scan(), width, height, seamEnd, seamStart, config)
        }
        assertThrows(IllegalArgumentException::class.java) { config.copy(maxShift = 17) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(minStdDeviation = Double.NaN) }
    }

    @Test
    fun `small or clipped sampling regions abstain without padding`() {
        val clipped = SpreadContinuityProbe.analyze(scan(), width, height, 2, 3, config)
        assertEquals(SpreadContinuityProbe.Status.INSUFFICIENT_INTERIOR, clipped.status)
        val short = SpreadContinuityProbe.analyze(IntArray(width * 32), width, 32, seamStart, seamEnd, config)
        assertEquals(SpreadContinuityProbe.Status.INSUFFICIENT_ROWS, short.status)
    }

    @Test
    fun `the input buffer is unchanged and results are deterministic`() {
        val pixels = scan(shift = 3)
        val copy = pixels.copyOf()
        val first = analyze(pixels)
        assertEquals(first, analyze(pixels))
        assertArrayEquals(copy, pixels)
        assertNotNull(first.bestCorrelation)
        assertTrue(first.shiftScores.all { it.correlation == null || abs(it.correlation) <= 1.0 })
    }
}
