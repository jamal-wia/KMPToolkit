package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.ui.graphics.PixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The per-bar luminance decision [AutoSystemBarsIconStyle] takes from its scratch rows: each bar
 * reads only its own rows, bounds are clamped, an empty strip gives no decision.
 */
class SampleRowsTest {

    private val white: Int = 0xFFFFFFFF.toInt()
    private val black: Int = 0xFF000000.toInt()

    /** A [PixelMap] whose rows are each filled with one ARGB value. */
    private fun rows(vararg rowColors: Int, width: Int = 8): PixelMap =
        PixelMap(IntArray(width * rowColors.size) { i -> rowColors[i / width] }, width, rowColors.size, 0, width)

    @Test
    fun `bars are decided from their own rows only`() {
        val scratch: PixelMap = rows(white, white, black, black)
        assertEquals(SystemBarIconStyle.DarkIcons, sampleRows(scratch, startY = 0, rows = 2))
        assertEquals(SystemBarIconStyle.LightIcons, sampleRows(scratch, startY = 2, rows = 2))
    }

    @Test
    fun `single-row bar is read from its one row`() {
        assertEquals(SystemBarIconStyle.DarkIcons, sampleRows(rows(white, black), startY = 0, rows = 1))
        assertEquals(SystemBarIconStyle.LightIcons, sampleRows(rows(white, black), startY = 1, rows = 1))
    }

    @Test
    fun `rows past the bitmap are clamped instead of read out of bounds`() {
        assertEquals(SystemBarIconStyle.DarkIcons, sampleRows(rows(white, white), startY = 1, rows = 5))
    }

    @Test
    fun `empty strip yields no decision`() {
        assertNull(sampleRows(rows(white, white), startY = 2, rows = 2))
        assertNull(sampleRows(rows(white, white), startY = 0, rows = 0))
        assertNull(sampleRows(PixelMap(IntArray(0), 0, 0, 0, 0), startY = 0, rows = 2))
    }

    @Test
    fun `transparent pixels count as dim`() {
        // What a not-yet-drawn layer or a row outside a too-short source produces.
        assertEquals(SystemBarIconStyle.LightIcons, sampleRows(rows(0, 0), startY = 0, rows = 2))
    }

    @Test
    fun `half white half black row averages to dim at the threshold`() {
        // 8 columns: 4 white + 4 black → luminance exactly 0.5, which counts as dim.
        val mixed = IntArray(8) { x -> if (x < 4) white else black }
        assertEquals(SystemBarIconStyle.LightIcons, sampleRows(PixelMap(mixed, 8, 1, 0, 8), startY = 0, rows = 1))
    }
}
