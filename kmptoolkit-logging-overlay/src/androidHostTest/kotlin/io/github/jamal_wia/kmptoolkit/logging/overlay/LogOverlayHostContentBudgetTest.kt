package io.github.jamal_wia.kmptoolkit.logging.overlay

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The host's documented cost model (04-api-reference: "Recomposes on `isVisible` and on the record list; `content`
 * is not affected by either"), counted rather than timed: how many times the app's own content lambda runs while
 * records arrive hidden, records arrive with the panel open, and the panel is toggled over and over. A content
 * subtree that re-ran per record would cost the app a full frame of its own UI for every log line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class LogOverlayHostContentBudgetTest {

    @Test
    fun `records arriving while the panel is hidden never run the content again`() = runComposeUiTest {
        val state = LogOverlayState(maxRecords = 100)
        var contentRuns = 0
        setContent { LogOverlayHost(state) { contentRuns++ } }
        waitForIdle()
        val baseline: Int = contentRuns

        repeat(500) { state.record(LogLevel.INFO, "T", "hidden$it") }
        waitForIdle()

        assertEquals(baseline, contentRuns, "content ran again for hidden records")
        assertEquals(100, state.records.value.size)
    }

    @Test
    fun `records arriving while the panel is open never run the content again`() = runComposeUiTest {
        val state = LogOverlayState(maxRecords = 100)
        var contentRuns = 0
        setContent { LogOverlayHost(state) { contentRuns++ } }
        state.show()
        waitForIdle()
        val baseline: Int = contentRuns

        repeat(100) { frame ->
            repeat(10) { state.record(LogLevel.WARN, "T", "open${frame * 10 + it}") }
            waitForIdle()
        }

        onNodeWithText("[T] open999").assertIsDisplayed()
        assertEquals(baseline, contentRuns, "content ran again for records shown in the panel")
    }

    @Test
    fun `toggling the panel fifty times never runs the content again`() = runComposeUiTest {
        val state = LogOverlayState(maxRecords = 10)
        var contentRuns = 0
        setContent { LogOverlayHost(state) { contentRuns++ } }
        waitForIdle()
        val baseline: Int = contentRuns

        repeat(50) {
            state.toggle()
            waitForIdle()
            state.toggle()
            waitForIdle()
        }

        assertEquals(baseline, contentRuns, "content ran again for a visibility change")
    }
}
