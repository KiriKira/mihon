package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpreadPageStructureTest {

    private val width = 512
    private val height = 376

    /** Hatched artwork: alternating ink and paper, so every area has some ink. */
    private fun art(x: Int, y: Int) = if ((x + y) % 4 < 2) 30 else 220

    private fun image(pixel: (x: Int, y: Int) -> Int) = IntArray(width * height) { pixel(it % width, it / width) }

    private fun analyze(luminance: IntArray) = SpreadPageStructure.analyze(luminance, width, height)

    /** Full-bleed artwork with a blank seam of [seam] columns in the middle. */
    private fun spread(seam: Int, blankTopRows: Int = 0) = image { x, y ->
        val center = width / 2
        when {
            x in center - seam / 2 until center - seam / 2 + seam -> 255
            y < blankTopRows -> 255
            else -> art(x, y)
        }
    }

    /** Two framed pages, each with page margins on every side. */
    private fun twoPages(innerMargin: Int = 8, outerMargin: Int = 20, verticalMargin: Int = 22) = image { x, y ->
        val pageWidth = width / 2
        val xInPage = x % pageWidth
        val left = if (x < pageWidth) outerMargin else innerMargin
        val right = pageWidth - if (x < pageWidth) innerMargin else outerMargin
        val inside = xInPage in left until right && y in verticalMargin until height - verticalMargin
        if (inside) art(x, y) else 255
    }

    @Test
    fun `full-bleed spread with a thin gutter is kept whole`() {
        val structure = analyze(spread(seam = 2))
        assertNotNull(structure)
        assertFalse(SpreadPageStructure.isTwoPages(structure!!))
    }

    @Test
    fun `full-bleed spread with a wide gutter is kept whole`() {
        val structure = analyze(spread(seam = 16))!!
        assertTrue(structure.seamWidthFraction >= 0.03)
        assertFalse(SpreadPageStructure.isTwoPages(structure))
    }

    @Test
    fun `spread with blank sky at the top is kept whole`() {
        val structure = analyze(spread(seam = 16, blankTopRows = 40))!!
        assertFalse(SpreadPageStructure.isTwoPages(structure))
    }

    @Test
    fun `vertically misaligned spread halves are kept whole`() {
        // The right half was scanned 12 rows lower than the left one
        val shift = 12
        val structure = analyze(
            image { x, y ->
                val center = width / 2
                when {
                    x in center - 8 until center + 8 -> 255
                    x < center && y >= height - shift -> 255
                    x >= center && y < shift -> 255
                    else -> art(x, y)
                }
            },
        )!!
        assertFalse(SpreadPageStructure.isTwoPages(structure))
    }

    @Test
    fun `two framed pages with their own margins are split`() {
        val structure = analyze(twoPages())!!
        assertEquals(16.0 / width, structure.seamWidthFraction, 1e-9)
        assertTrue(SpreadPageStructure.isTwoPages(structure))
    }

    @Test
    fun `specks in the margins do not hide them`() {
        val pages = twoPages()
        // A few stray dark pixels in the seam and in the top margin
        for (x in listOf(230, 280)) pages[5 * width + x] = 0
        pages[200 * width + width / 2] = 0
        assertTrue(SpreadPageStructure.isTwoPages(analyze(pages)!!))
    }

    @Test
    fun `dark scan edges at the top and bottom do not hide the margins`() {
        val pages = twoPages()
        for (x in 0 until width) {
            pages[x] = 0
            pages[(height - 1) * width + x] = 0
            pages[(height - 2) * width + x] = 0
        }
        assertTrue(SpreadPageStructure.isTwoPages(analyze(pages)!!))
    }

    @Test
    fun `a stray line of noise inside a margin does not end it`() {
        val pages = twoPages()
        for (x in 0 until width) pages[10 * width + x] = 0
        val structure = analyze(pages)!!
        assertTrue(structure.left.top >= 20.0 / height)
        assertTrue(SpreadPageStructure.isTwoPages(structure))
    }

    @Test
    fun `artwork reaching the edge is not mistaken for a dark scan edge`() {
        val structure = analyze(spread(seam = 16))!!
        assertEquals(0.0, structure.left.top)
        assertEquals(0.0, structure.right.bottom)
    }

    @Test
    fun `pages whose inner margins were cropped away are kept whole`() {
        // Known limitation: without a blank seam the layout looks like a spread.
        val structure = analyze(twoPages(innerMargin = 1))!!
        assertFalse(SpreadPageStructure.isTwoPages(structure))
    }

    @Test
    fun `image without a light seam is not analysed`() {
        assertNull(analyze(image { x, y -> art(x, y) }))
    }
}
