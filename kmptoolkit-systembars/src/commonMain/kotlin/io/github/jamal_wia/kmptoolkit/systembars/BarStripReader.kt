package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import kotlin.math.min

/**
 * Icon styles derived for the two bars from one captured frame. A `null` axis means that bar had no
 * strip to read (zero inset, or nothing to decide from) and is left unclaimed.
 */
internal class BarStripStyles(val status: SystemBarIconStyle?, val nav: SystemBarIconStyle?)

/**
 * Where [AutoSystemBarsIconStyle]'s pixels come from on one platform. Owns both the draw-phase hook
 * and the read, so a platform that reads the window (Android 8.0+) records nothing and a platform
 * that reads a layer records it exactly where it is read.
 */
internal interface BarStripReader {

    /**
     * Draws the probe's content for this frame; may capture it for a later [read]. Must call
     * `drawContent()` exactly once.
     */
    fun ContentDrawScope.drawContentAndCapture()

    /**
     * Styles for both bars from one frame, or `null` to skip this sample (nothing published,
     * nothing booked). May suspend; must be cancellable; called on the composition's thread, one
     * call at a time.
     */
    suspend fun read(statusPx: Int, navPx: Int): BarStripStyles?
}

/**
 * The reader for this platform and API level, remembered for the composition; `null` means this
 * platform has none and [AutoSystemBarsIconStyle] never samples.
 */
@Composable
internal expect fun rememberBarStripReader(): BarStripReader?

/**
 * Destination rows of a whole-window copy for each bar. [destRows] is the height of the `8 × R`
 * destination; [status] is a prefix `0..k` of it, [nav] a suffix `m..R-1`; a bar with no strip
 * has an empty range. See [barStripRows].
 */
internal class BarStripRows(val destRows: Int, val status: IntRange, val nav: IntRange)

/**
 * The geometry of a whole-window copy that is scaled down into an `8 × R` bitmap.
 *
 * `R = ceil(2 * H / sMin)` where `sMin` is the thinnest non-zero strip, coerced to
 * `[1, min(MAX_DEST_ROWS, H)]`, so at least two destination row centres fall inside the thinnest
 * strip unless the cap binds. Destination row `i` is centred at source `y = (i + 0.5) * H / R`; the
 * status bar owns the rows whose centre is above [statusPx], the navigation bar the rows whose
 * centre is at or below `H - navPx`. A bar with a strip but no row centre inside it (the cap bound,
 * or a strip thinner than a destination row) falls back to its nearest edge row. A strip taller
 * than the window is clamped to it.
 *
 * @return `null` when the window is empty or both strips are zero (nothing to read).
 */
internal fun barStripRows(windowHeight: Int, statusPx: Int, navPx: Int): BarStripRows? {
    if (windowHeight <= 0) return null
    val s: Int = statusPx.coerceIn(0, windowHeight)
    val n: Int = navPx.coerceIn(0, windowHeight)
    if (s == 0 && n == 0) return null

    val thinnest: Int = if (s == 0) n else if (n == 0) s else min(s, n)
    val height: Long = windowHeight.toLong()
    // Integer ceiling of 2H / thinnest: exact, no floating-point rounding at a boundary.
    val wanted: Long = (2L * height + thinnest - 1L) / thinnest
    val rows: Int = wanted.coerceIn(1L, min(MAX_DEST_ROWS, windowHeight).toLong()).toInt()

    // Row i is centred at source y = (i + 0.5) * H / R, i.e. (2i + 1) * H / (2R). Comparing it with
    // a strip edge e as (2i + 1) * H against 2 * e * R keeps the decision exact.
    var statusRows = 0
    while (statusRows < rows && (2L * statusRows + 1L) * height < 2L * s * rows) statusRows++
    var navFirst: Int = rows
    while (navFirst > 0 && (2L * (navFirst - 1) + 1L) * height >= 2L * (windowHeight - n) * rows) navFirst--

    val status: IntRange = when {
        s == 0 -> IntRange.EMPTY
        statusRows == 0 -> 0..0
        else -> 0 until statusRows
    }
    val nav: IntRange = when {
        n == 0 -> IntRange.EMPTY
        navFirst == rows -> (rows - 1)..(rows - 1)
        else -> navFirst until rows
    }
    return BarStripRows(rows, status, nav)
}

/** Most destination rows a whole-window copy asks for: at most `8 × 256 × 4 B` = 8 KB. */
internal const val MAX_DEST_ROWS: Int = 256
