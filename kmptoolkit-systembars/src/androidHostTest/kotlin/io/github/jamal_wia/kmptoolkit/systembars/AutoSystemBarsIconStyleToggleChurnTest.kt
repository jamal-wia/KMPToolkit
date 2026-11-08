package io.github.jamal_wia.kmptoolkit.systembars

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `enabled` flipping and the status bar inset appearing and disappearing (a hidden bar, immersive mode)
 * while the host stays STARTED: the sampler holds exactly one claim while it has something to read and
 * none otherwise, never samples while it has nothing to read, and leaves no claim or trigger observer
 * behind after any number of flips.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSystemBarsIconStyleToggleChurnTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private class Host : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class CountingProbe : StatusBarLuminanceProbe {
        private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val triggers: SharedFlow<Unit> = flow
        val observerCount: Int get() = flow.subscriptionCount.value
        override fun triggerRecalculation() {
            flow.tryEmit(Unit)
        }
    }

    private val host = Host()
    private val probe = CountingProbe()
    private val controller = RecordingSystemBarsController()
    private var samples: Int = 0
    private var enabled: Boolean by mutableStateOf(true)

    private fun setInset(px: Int) {
        composeTestRule.runOnUiThread {
            val insets: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeTestRule.waitForIdle()
    }

    private fun frames(count: Int) {
        repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun `twenty five enabled flips and inset flips leave at most one claim and no leftovers`() {
        composeTestRule.mainClock.autoAdvance = false
        controller.recordApplications = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(
                    intervalMs = INTERVAL_MS,
                    controller = controller,
                    probe = probe,
                    enabled = enabled,
                    onSampled = { samples++ },
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        composeTestRule.runOnUiThread { host.registry.currentState = Lifecycle.State.STARTED }
        setInset(STATUS_BAR_PX)
        frames(4)
        assertTrue(samples > 0, "control: the sampler must be running before the churn, or nothing below proves anything")

        val random = kotlin.random.Random(7)
        var enabledNow = true
        var inset = STATUS_BAR_PX
        repeat(25) { round ->
            when (random.nextInt(3)) {
                0 -> { enabledNow = !enabledNow; composeTestRule.runOnUiThread { enabled = enabledNow }; composeTestRule.waitForIdle() }
                1 -> { inset = if (inset == 0) STATUS_BAR_PX else 0; setInset(inset) }
                else -> probe.triggerRecalculation()
            }
            frames(4)
            val shouldRun: Boolean = enabledNow && inset > 0
            assertEquals(if (shouldRun) 1 else 0, controller.activeOverrideCount, "round $round: enabled=$enabledNow inset=$inset claims")
            assertEquals(if (shouldRun) 1 else 0, probe.observerCount, "round $round: enabled=$enabledNow inset=$inset observers")
            if (!shouldRun) {
                val before: Int = samples
                probe.triggerRecalculation()
                composeTestRule.mainClock.advanceTimeBy(INTERVAL_MS * 3)
                composeTestRule.waitForIdle()
                assertEquals(before, samples, "round $round: sampled with nothing to read")
            }
        }
        composeTestRule.runOnUiThread { enabled = false }
        composeTestRule.waitForIdle()
        frames(3)
        assertEquals(0, controller.activeOverrideCount)
        assertEquals(0, probe.observerCount)
    }

    private companion object {
        const val STATUS_BAR_PX = 64
        const val INTERVAL_MS = 300L
    }
}
