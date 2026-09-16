package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rows [AutoSystemBarsIconStyle] copies into its scratch layer: two per bar, status bar first,
 * each entry the source `y` that scratch row must show.
 */
class ScratchRowSourcesTest {

    @Test
    fun `two rows per bar with the status bar first and nav rows taken from the bottom`() {
        // Pixel 7: 2400 tall, status 136, nav 69 → status rows 0 and 68, nav rows 2331 and 2365.
        assertContentEquals(
            intArrayOf(0, 68, 2331, 2365),
            scratchRowSources(statusPx = 136, navPx = 69, sourceHeight = 2400),
        )
    }

    @Test
    fun `hidden status bar contributes no rows`() {
        assertContentEquals(intArrayOf(2331, 2365), scratchRowSources(statusPx = 0, navPx = 69, sourceHeight = 2400))
    }

    @Test
    fun `hidden navigation bar contributes no rows`() {
        assertContentEquals(intArrayOf(0, 68), scratchRowSources(statusPx = 136, navPx = 0, sourceHeight = 2400))
    }

    @Test
    fun `both bars hidden give an empty plan`() {
        assertContentEquals(intArrayOf(), scratchRowSources(statusPx = 0, navPx = 0, sourceHeight = 2400))
    }

    @Test
    fun `one-pixel bar is read once rather than twice`() {
        assertEquals(1, scratchRowCount(1))
        assertContentEquals(intArrayOf(0, 2399), scratchRowSources(statusPx = 1, navPx = 1, sourceHeight = 2400))
    }

    @Test
    fun `negative inset contributes no rows`() {
        assertEquals(0, scratchRowCount(-5))
        assertContentEquals(intArrayOf(2331, 2365), scratchRowSources(statusPx = -5, navPx = 69, sourceHeight = 2400))
        assertContentEquals(intArrayOf(0, 68), scratchRowSources(statusPx = 136, navPx = -5, sourceHeight = 2400))
    }

    @Test
    fun `every row lies inside its bar for thin and odd-sized bars`() {
        val sourceHeight = 2400
        for ((statusPx, navPx) in listOf(2 to 2, 3 to 3, 137 to 71, 1 to 200, 200 to 1)) {
            val rows: IntArray = scratchRowSources(statusPx, navPx, sourceHeight)
            val statusRows: Int = scratchRowCount(statusPx)
            assertEquals(statusRows + scratchRowCount(navPx), rows.size, "status=$statusPx nav=$navPx")
            rows.forEachIndexed { index, y ->
                val inBar: Boolean =
                    if (index < statusRows) y in 0 until statusPx else y in (sourceHeight - navPx) until sourceHeight
                assertTrue(inBar, "status=$statusPx nav=$navPx row $index at $y")
            }
        }
    }

    @Test
    fun `source shorter than the bars clamps every row into the source`() {
        // A momentary size mismatch (rotation): still one entry per planned row, each pointing at a
        // real source row rather than outside the layer.
        assertContentEquals(intArrayOf(0, 49, 0, 15), scratchRowSources(statusPx = 136, navPx = 69, sourceHeight = 50))
    }

    @Test
    fun `empty source plans nothing`() {
        assertContentEquals(intArrayOf(), scratchRowSources(statusPx = 136, navPx = 69, sourceHeight = 0))
    }
}
