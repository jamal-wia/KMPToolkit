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

/**
 * Fifty STARTED to CREATED cycles with overlapping triggers, then leaving composition: nothing is
 * sampled after the leave, the override claim is released and no trigger collector is left behind.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSystemBarsIconStyleCycleChurnTest {

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
    private var shown: Boolean by mutableStateOf(true)

    private fun setUp() {
        composeTestRule.mainClock.autoAdvance = false
        controller.recordApplications = false
        composeTestRule.setContent {
            if (shown) {
                CompositionLocalProvider(LocalLifecycleOwner provides host) {
                    AutoSystemBarsIconStyle(
                        intervalMs = INTERVAL_MS,
                        controller = controller,
                        probe = probe,
                        onSampled = { samples++ },
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        composeTestRule.runOnUiThread {
            val insets: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeTestRule.waitForIdle()
    }

    private fun moveTo(state: Lifecycle.State) {
        composeTestRule.runOnUiThread { host.registry.currentState = state }
        composeTestRule.waitForIdle()
    }

    private fun frames(count: Int) {
        repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    private fun advance(ms: Long) {
        composeTestRule.mainClock.advanceTimeBy(ms)
        composeTestRule.waitForIdle()
    }

    private fun leave() {
        composeTestRule.runOnUiThread { shown = false }
        composeTestRule.waitForIdle()
        // The test clock is manual: the cancelled effect's finally block runs on the next dispatch.
        frames(2)
    }

    @Test
    fun `fifty started and created cycles with triggers leave nothing sampled after leaving composition`() {
        setUp()
        repeat(CYCLES) { cycle ->
            moveTo(Lifecycle.State.STARTED)
            frames(3)
            repeat(3) { probe.triggerRecalculation() }
            frames(1)
            probe.triggerRecalculation()
            frames(2)
            moveTo(Lifecycle.State.CREATED)
            val whileStopped: Int = samples
            repeat(2) { probe.triggerRecalculation() }
            advance(INTERVAL_MS * 3)
            assertEquals(whileStopped, samples, "cycle $cycle: a sample ran while the host was below STARTED")
            assertEquals(0, probe.observerCount, "cycle $cycle: a trigger collector outlived its STARTED cycle")
        }
        assertEquals(1, controller.activeOverrideCount, "exactly one claim while composed")

        moveTo(Lifecycle.State.STARTED)
        frames(2)
        probe.triggerRecalculation()
        leave()

        assertEquals(0, controller.activeOverrideCount, "the claim was not released on leaving")
        assertEquals(0, probe.observerCount, "an observer is left after leaving")
        val atLeave: Int = samples
        repeat(5) { probe.triggerRecalculation() }
        advance(INTERVAL_MS * 10)
        frames(5)
        assertEquals(atLeave, samples, "a sample ran after leaving composition")
    }

    @Test
    fun `entering and leaving composition thirty times with the host started leaves no claim or observer`() {
        setUp()
        moveTo(Lifecycle.State.STARTED)
        repeat(30) { round ->
            frames(3)
            probe.triggerRecalculation()
            leave()
            assertEquals(0, controller.activeOverrideCount, "round $round: claim left")
            assertEquals(0, probe.observerCount, "round $round: observer left")
            composeTestRule.runOnUiThread { shown = true }
            composeTestRule.waitForIdle()
            frames(1)
        }
    }

    private companion object {
        const val CYCLES = 50
        const val STATUS_BAR_PX = 64
        const val INTERVAL_MS = 300L
    }
}
