package io.github.jamal_wia.kmptoolkit.systembars

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A non-positive `intervalMs` is rejected up front. It used to be accepted and, once sampling
 * started, `delay(0)` never suspended so the main thread spun forever. The validity of the parameter
 * must not depend on the device's insets, so the check is made even though Robolectric's window
 * has no bar insets and the probe would otherwise return before its sampling effect.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSystemBarsZeroIntervalTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private class TestProbe : StatusBarLuminanceProbe {
        private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val triggers: SharedFlow<Unit> = flow
        override fun triggerRecalculation() {
            flow.tryEmit(Unit)
        }
    }

    private fun compose(intervalMs: Long) {
        composeTestRule.setContent {
            AutoSystemBarsIconStyle(
                intervalMs = intervalMs,
                controller = RecordingSystemBarsController(),
                probe = TestProbe(),
            ) {
                Box(Modifier.fillMaxSize())
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `an interval of zero is rejected`() {
        val failure: IllegalArgumentException = assertFailsWith<IllegalArgumentException> { compose(0L) }
        assertTrue("intervalMs" in failure.message.orEmpty(), "message was: ${failure.message}")
    }

    @Test
    fun `a negative interval is rejected`() {
        val failure: IllegalArgumentException = assertFailsWith<IllegalArgumentException> { compose(-1L) }
        assertTrue("intervalMs" in failure.message.orEmpty(), "message was: ${failure.message}")
    }
}
