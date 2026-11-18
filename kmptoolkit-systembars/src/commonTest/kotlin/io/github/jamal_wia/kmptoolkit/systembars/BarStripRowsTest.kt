package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The geometry of the Android whole-window copy ([barStripRows], plan issue-13 section 3.4): which
 * rows of an `8 × R` destination belong to the status bar and which to the navigation bar. Pure
 * function, so every boundary is pinned here rather than through a window.
 */
class BarStripRowsTest {

    private fun rows(windowHeight: Int, statusPx: Int, navPx: Int): BarStripRows =
        assertNotNull(barStripRows(windowHeight, statusPx, navPx), "H=$windowHeight status=$statusPx nav=$navPx")

    @Test
    fun `a 2400 px window with a 63 px status bar and a 126 px navigation bar is 77 rows`() {
        val r: BarStripRows = rows(2400, 63, 126)

        assertEquals(77, r.destRows)
        assertEquals(0..1, r.status)
        assertEquals(73..76, r.nav)
    }

    @Test
    fun `a strip thinner than a destination row under the cap falls back to the nearest edge row`() {
        // 2H / 1 = 4800 rows wanted, the cap binds at 256 (a 9.4 px row): no centre lies inside 1 px.
        val r: BarStripRows = rows(2400, 1, 0)

        assertEquals(MAX_DEST_ROWS, r.destRows)
        assertEquals(0..0, r.status)
        assertTrue(r.nav.isEmpty(), "no navigation strip, nav = ${r.nav}")
    }

    @Test
    fun `a navigation strip thinner than a destination row falls back to the last row`() {
        val r: BarStripRows = rows(2400, 0, 1)

        assertEquals(MAX_DEST_ROWS, r.destRows)
        assertTrue(r.status.isEmpty(), "no status strip, status = ${r.status}")
        assertEquals((MAX_DEST_ROWS - 1)..(MAX_DEST_ROWS - 1), r.nav)
    }

    @Test
    fun `a zero status strip is empty and the navigation bar is still computed`() {
        val r: BarStripRows = rows(2400, 0, 126)

        assertTrue(r.status.isEmpty(), "status = ${r.status}")
        assertEquals(expectedRows(2400, 126), r.destRows)
        assertTrue(!r.nav.isEmpty(), "nav = ${r.nav}")
        assertEquals(r.destRows - 1, r.nav.last)
    }

    @Test
    fun `a zero navigation strip is empty and the status bar is still computed`() {
        val r: BarStripRows = rows(2400, 63, 0)

        assertTrue(r.nav.isEmpty(), "nav = ${r.nav}")
        assertEquals(expectedRows(2400, 63), r.destRows)
        assertEquals(0, r.status.first)
        assertTrue(r.status.last >= 1, "at least two rows inside the strip, status = ${r.status}")
    }

    @Test
    fun `both strips zero reads nothing`() {
        assertNull(barStripRows(2400, 0, 0))
    }

    @Test
    fun `negative strips count as zero`() {
        assertNull(barStripRows(2400, -5, -1))
        val r: BarStripRows = rows(2400, -5, 126)
        assertTrue(r.status.isEmpty(), "status = ${r.status}")
        assertTrue(!r.nav.isEmpty(), "nav = ${r.nav}")
    }

    @Test
    fun `an empty or negative window reads nothing`() {
        assertNull(barStripRows(0, 63, 126))
        assertNull(barStripRows(-1, 63, 126))
        assertNull(barStripRows(Int.MIN_VALUE, 63, 126))
    }

    @Test
    fun `a strip taller than the window is clamped to it`() {
        val r: BarStripRows = rows(100, 5000, 0)

        assertEquals(2, r.destRows, "2H / 100 = 2 rows")
        assertEquals(0..1, r.status, "every row is inside the clamped strip")
        assertTrue(r.nav.isEmpty(), "nav = ${r.nav}")
    }

    @Test
    fun `a status strip and a navigation strip that both cover the window overlap on every row`() {
        val r: BarStripRows = rows(100, Int.MAX_VALUE, Int.MAX_VALUE)

        assertEquals(2, r.destRows)
        assertEquals(0..1, r.status)
        assertEquals(0..1, r.nav)
    }

    @Test
    fun `equal strips give each bar the same number of rows`() {
        val r: BarStripRows = rows(2400, 100, 100)

        assertEquals(expectedRows(2400, 100), r.destRows)
        assertEquals(r.status.count(), r.nav.count(), "status = ${r.status}, nav = ${r.nav}")
        assertEquals(0, r.status.first)
        assertEquals(r.destRows - 1, r.nav.last)
    }

    @Test
    fun `a window shorter than twice the strip never asks for more rows than it has pixels`() {
        val r: BarStripRows = rows(3, 1, 0)

        assertEquals(3, r.destRows, "2H / 1 = 6 rows wanted, coerced to H")
        assertEquals(0..0, r.status)
    }

    @Test
    fun `a one pixel window reads its only row for either bar`() {
        val status: BarStripRows = rows(1, 1, 0)
        assertEquals(1, status.destRows)
        assertEquals(0..0, status.status)

        val both: BarStripRows = rows(1, 1, 1)
        assertEquals(1, both.destRows)
        assertEquals(0..0, both.status)
        assertEquals(0..0, both.nav)
    }

    @Test
    fun `the cap never lets the destination exceed 256 rows even for the thinnest strip on the tallest window`() {
        val r: BarStripRows = rows(Int.MAX_VALUE, 1, 1)

        assertEquals(MAX_DEST_ROWS, r.destRows)
        assertEquals(0..0, r.status)
        assertEquals((MAX_DEST_ROWS - 1)..(MAX_DEST_ROWS - 1), r.nav)
    }

    @Test
    fun `a strip of exactly the window height owns every row`() {
        val r: BarStripRows = rows(2400, 2400, 0)

        assertEquals(2, r.destRows)
        assertEquals(0..1, r.status)
    }

    /**
     * Every property of the contract, over a seeded sweep, against an independent exact oracle (integer
     * arithmetic on `(2i + 1) * H` versus `2 * edge * R`, no floating point).
     */
    @Test
    fun `the contract holds across a seeded sweep of windows and strips`() {
        val seed = 1313L
        val random = Random(seed)
        val heights: List<Int> = listOf(1, 2, 3, 7, 50, 100, 480, 800, 1080, 2400, 3200, 100_000) +
            List(200) { random.nextInt(1, 5000) }
        var checked = 0
        for (h in heights) {
            val strips: List<Int> = listOf(0, 1, 2, 3, h / 4, h / 2, h, h + 1) +
                List(6) { random.nextInt(0, h + 50) }
            for (s in strips) for (n in strips) {
                val msg = "seed=$seed H=$h status=$s nav=$n"
                val result: BarStripRows? = barStripRows(h, s, n)
                val sc: Int = s.coerceIn(0, h)
                val nc: Int = n.coerceIn(0, h)
                if (sc == 0 && nc == 0) {
                    assertNull(result, msg)
                    continue
                }
                val r: BarStripRows = assertNotNull(result, msg)
                checked++

                val thinnest: Int = listOf(sc, nc).filter { it > 0 }.min()
                val wanted: Long = (2L * h + thinnest - 1) / thinnest
                val expectedRows: Int = wanted.coerceIn(1L, minOf(MAX_DEST_ROWS, h).toLong()).toInt()
                assertEquals(expectedRows, r.destRows, "$msg destRows")
                assertTrue(r.destRows in 1..MAX_DEST_ROWS && r.destRows <= h, "$msg destRows=${r.destRows}")

                for (range in listOf(r.status, r.nav)) {
                    if (range.isEmpty()) continue
                    assertTrue(range.first >= 0 && range.last < r.destRows, "$msg range=$range of ${r.destRows}")
                }
                // Oracle by exact comparison.
                val statusOracle: List<Int> = (0 until r.destRows).filter { i -> (2L * i + 1) * h < 2L * sc * r.destRows }
                val navOracle: List<Int> = (0 until r.destRows).filter { i -> (2L * i + 1) * h >= 2L * (h - nc) * r.destRows }
                val statusExpected: IntRange = when {
                    sc == 0 -> IntRange.EMPTY
                    statusOracle.isEmpty() -> 0..0
                    else -> statusOracle.first()..statusOracle.last()
                }
                val navExpected: IntRange = when {
                    nc == 0 -> IntRange.EMPTY
                    navOracle.isEmpty() -> (r.destRows - 1)..(r.destRows - 1)
                    else -> navOracle.first()..navOracle.last()
                }
                assertEquals(statusExpected, r.status, "$msg status")
                assertEquals(navExpected, r.nav, "$msg nav")

                // Shape: status is a prefix, nav is a suffix.
                if (!r.status.isEmpty()) assertEquals(0, r.status.first, "$msg status prefix")
                if (!r.nav.isEmpty()) assertEquals(r.destRows - 1, r.nav.last, "$msg nav suffix")

                // Unless the cap (or the window height) bound the row count, at least two row centres
                // fall inside every non-zero strip.
                if (wanted <= minOf(MAX_DEST_ROWS, h)) {
                    if (sc > 0) assertTrue(statusOracle.size >= 2, "$msg two centres in status")
                    if (nc > 0) assertTrue(navOracle.size >= 2, "$msg two centres in nav")
                }
            }
        }
        assertTrue(checked > 1000, "seed=$seed the sweep checked only $checked combinations")
    }

    /** The row count the contract asks for, restated by hand for the cases above. */
    private fun expectedRows(h: Int, thinnest: Int): Int =
        ((2L * h + thinnest - 1) / thinnest).coerceIn(1L, minOf(MAX_DEST_ROWS, h).toLong()).toInt()
}
