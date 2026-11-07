package io.github.jamal_wia.kmptoolkit.logging.overlay

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The panel lists the newest record first because "the reason to open this panel is almost always the
 * thing that just happened". With the panel open on a list longer than the screen and showing its top,
 * a record that arrives must be visible at the top, not parked above the viewport behind the row that
 * was first before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class LogOverlayLiveRecordTest {

    @Test
    fun `a record that arrives while a long list shows its top is displayed`() = runComposeUiTest {
        val state = LogOverlayState()
        repeat(30) { state.record(LogLevel.INFO, "T", "old$it") }
        setContent { LogOverlayPanel(state = state) }
        waitForIdle()
        onNodeWithText("[T] old29").assertIsDisplayed()

        state.record(LogLevel.ERROR, "T", "just happened")
        waitForIdle()

        onNodeWithText("[T] just happened").assertIsDisplayed()
    }
}
