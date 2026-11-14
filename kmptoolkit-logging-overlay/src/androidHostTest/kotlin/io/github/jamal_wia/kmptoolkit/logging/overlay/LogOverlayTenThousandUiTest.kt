package io.github.jamal_wia.kmptoolkit.logging.overlay

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panel over a buffer of ten thousand records: only the rows that fit are composed (not one node per record),
 * the title counts the whole buffer, and records that keep arriving at the bound evict the oldest while the newest
 * stays on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class LogOverlayTenThousandUiTest {

    @Test
    fun `ten thousand records compose only the visible rows and the title counts them all`() = runComposeUiTest {
        val state = LogOverlayState(maxRecords = 10_000)
        repeat(10_000) { state.record(LogLevel.INFO, "T", "row$it") }
        setContent { LogOverlayPanel(state = state) }
        waitForIdle()

        onNodeWithText("Logs (10000)").assertIsDisplayed()
        onNodeWithText("[T] row9999").assertIsDisplayed()
        val composedRows: Int = onAllNodesWithText("[T] row", substring = true).fetchSemanticsNodes().size
        assertTrue(composedRows in 1..200, "composed $composedRows rows for 10000 records")
        assertEquals(10_000, state.records.value.size)
    }

    @Test
    fun `records arriving at the bound evict the oldest and the newest stays displayed`() = runComposeUiTest {
        val state = LogOverlayState(maxRecords = 10_000)
        repeat(10_000) { state.record(LogLevel.INFO, "T", "row$it") }
        setContent { LogOverlayPanel(state = state) }
        waitForIdle()

        repeat(10) { round ->
            repeat(50) { state.record(LogLevel.WARN, "T", "late${round * 50 + it}") }
            waitForIdle()
            onNodeWithText("[T] late${round * 50 + 49}").assertIsDisplayed()
        }

        assertEquals(10_000, state.records.value.size)
        assertEquals("row500", state.records.value.first().message)
        assertEquals(500L + 1L, state.records.value.first().id)
        onNodeWithText("Logs (10000)").assertIsDisplayed()
    }
}
