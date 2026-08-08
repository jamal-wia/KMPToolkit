package io.github.jamal_wia.kmptoolkit.logging.overlay

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose UI tests for [LogOverlayPanel] and [LogOverlayHost], run on the JVM through Robolectric.
 *
 * They assert the rendering contract stated in `docs/kmptoolkit-logging-overlay/04-api-reference.md`
 * — what is drawn for an empty buffer, that every chrome string comes from [LogOverlayLabels], and
 * that the panel's two buttons drive [LogOverlayState] rather than any private UI state.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned, not left to default: the default is the module's compileSdk (37), which no released
// Robolectric ships an emulated runtime for, and the failure is an opaque IllegalArgumentException
// from DefaultSdkPicker rather than anything naming the SDK level. Raise this when Robolectric
// catches up; nothing under test is API-level sensitive.
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class LogOverlayUiTest {

    @Test
    fun `an empty buffer shows the empty label and no clear button`() = runComposeUiTest {
        val state = LogOverlayState()
        setContent { LogOverlayPanel(state = state, labels = LABELS) }

        onNodeWithText(LABELS.empty).assertIsDisplayed()
        onNodeWithText(LABELS.clear).assertDoesNotExist()
        onNodeWithText(LABELS.close).assertIsDisplayed()
    }

    @Test
    fun `the title carries the record count`() = runComposeUiTest {
        val state = LogOverlayState()
        state.record(LogLevel.INFO, "Sync", "one")
        state.record(LogLevel.INFO, "Sync", "two")

        setContent { LogOverlayPanel(state = state, labels = LABELS) }

        onNodeWithText("${LABELS.title} (2)").assertIsDisplayed()
    }

    @Test
    fun `every retained record is rendered with its tag and message`() = runComposeUiTest {
        val state = LogOverlayState()
        state.record(LogLevel.WARN, "Disk", "almost full")
        state.record(LogLevel.ERROR, "Net", "timed out")

        setContent { LogOverlayPanel(state = state, labels = LABELS) }

        onNodeWithText("[Disk] almost full").assertIsDisplayed()
        onNodeWithText("[Net] timed out").assertIsDisplayed()
        onNodeWithText(LABELS.empty).assertDoesNotExist()
    }

    @Test
    fun `a record recorded after the first composition appears`() = runComposeUiTest {
        val state = LogOverlayState()
        setContent { LogOverlayPanel(state = state, labels = LABELS) }
        onNodeWithText(LABELS.empty).assertIsDisplayed()

        state.record(LogLevel.INFO, "Late", "arrived")
        waitForIdle()

        onNodeWithText("[Late] arrived").assertIsDisplayed()
        onNodeWithText(LABELS.empty).assertDoesNotExist()
    }

    @Test
    fun `the clear button empties the buffer and the list`() = runComposeUiTest {
        val state = LogOverlayState()
        state.record(LogLevel.INFO, "Sync", "one")
        setContent { LogOverlayPanel(state = state, labels = LABELS) }

        onNodeWithText(LABELS.clear).performClick()
        waitForIdle()

        assertEquals(emptyList(), state.records.value)
        onNodeWithText("[Sync] one").assertDoesNotExist()
        onNodeWithText(LABELS.empty).assertIsDisplayed()
    }

    @Test
    fun `the close button hides the state`() = runComposeUiTest {
        val state = LogOverlayState()
        state.show()
        setContent { LogOverlayPanel(state = state, labels = LABELS) }

        onNodeWithText(LABELS.close).performClick()
        waitForIdle()

        assertFalse(state.isVisible.value)
    }

    @Test
    fun `tapping a record toggles its stack trace`() = runComposeUiTest {
        val state = LogOverlayState()
        state.record(LogLevel.ERROR, "Net", "request failed", IllegalStateException("boom"))
        val trace: String = requireNotNull(state.records.value.single().throwableText)
        setContent { LogOverlayPanel(state = state, labels = LABELS) }

        onNodeWithText(trace).assertDoesNotExist()

        onNodeWithText("[Net] request failed").performClick()
        waitForIdle()
        onNodeWithText(trace).assertIsDisplayed()

        onNodeWithText("[Net] request failed").performClick()
        waitForIdle()
        onNodeWithText(trace).assertDoesNotExist()
    }

    @Test
    fun `a record without a throwable expands to nothing`() = runComposeUiTest {
        val state = LogOverlayState()
        state.record(LogLevel.INFO, "Sync", "no cause")
        setContent { LogOverlayPanel(state = state, labels = LABELS) }

        onNodeWithText("[Sync] no cause").performClick()
        waitForIdle()

        // The row is still there and nothing else appeared to expand into.
        onNodeWithText("[Sync] no cause").assertIsDisplayed()
        assertTrue(state.records.value.single().throwableText == null)
    }

    @Test
    fun `the host draws content while hidden and the panel once visible`() = runComposeUiTest {
        val state = LogOverlayState()
        state.record(LogLevel.INFO, "Sync", "one")
        setContent {
            LogOverlayHost(state = state, labels = LABELS) { Text(text = CONTENT_MARKER) }
        }

        onNodeWithText(CONTENT_MARKER).assertIsDisplayed()
        onNodeWithText("[Sync] one").assertDoesNotExist()

        state.show()
        waitForIdle()
        onNodeWithText("[Sync] one").assertIsDisplayed()

        state.hide()
        waitForIdle()
        onNodeWithText("[Sync] one").assertDoesNotExist()
        onNodeWithText(CONTENT_MARKER).assertIsDisplayed()
    }

    @Test
    fun `default labels are used when none are supplied`() = runComposeUiTest {
        val state = LogOverlayState()
        val defaults = LogOverlayLabels()
        setContent { LogOverlayPanel(state = state) }

        onNodeWithText(defaults.empty).assertIsDisplayed()
        onNodeWithText("${defaults.title} (0)").assertIsDisplayed()
    }

    private companion object {
        // Deliberately not the defaults: a test that passes with the default strings cannot tell
        // whether the labels parameter is honored or ignored.
        val LABELS = LogOverlayLabels(
            title = "Diagnostics",
            clear = "Wipe",
            close = "Dismiss",
            empty = "Nothing recorded",
        )
        const val CONTENT_MARKER = "app-content"
    }
}
