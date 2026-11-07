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
import kotlin.random.Random
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Random sequences of lifecycle moves, entering and leaving composition, enabling and disabling the
 * sampler, swapping the controller, hiding and showing the status bar inset, and triggers, on a manual
 * clock: the claim count, the trigger collector and the sampling stay consistent with the model.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSystemBarsIconStyleModelFuzzTest {

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
        override fun triggerRecalculation() { flow.tryEmit(Unit) }
    }

    private val host = Host()
    private val probe = CountingProbe()
    private val first = RecordingSystemBarsController()
    private val second = RecordingSystemBarsController()
    private var samples: Int = 0
    private var shown: Boolean by mutableStateOf(true)
    private var enabled: Boolean by mutableStateOf(true)
    private var inUse: SystemBarsController by mutableStateOf(first)

    private fun frames(count: Int) = repeat(count) {
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
    }

    private fun insets(top: Int) {
        composeTestRule.runOnUiThread {
            val value: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), value)
        }
        composeTestRule.waitForIdle()
    }

    private fun run(seed: Int) {
        val random = Random(seed)
        composeTestRule.mainClock.autoAdvance = false
        first.recordApplications = false
        second.recordApplications = false
        composeTestRule.setContent {
            if (shown) {
                CompositionLocalProvider(LocalLifecycleOwner provides host) {
                    AutoSystemBarsIconStyle(
                        intervalMs = 300L, controller = inUse, probe = probe, enabled = enabled,
                        onSampled = { samples++ },
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        var insetPx = 0
        insets(0)
        var state: Lifecycle.State = Lifecycle.State.INITIALIZED
        val log = StringBuilder()
        repeat(80) { step ->
            when (random.nextInt(8)) {
                0 -> { state = listOf(Lifecycle.State.CREATED, Lifecycle.State.STARTED, Lifecycle.State.RESUMED).random(random)
                    composeTestRule.runOnUiThread { host.registry.currentState = state }; log.append("move($state) ") }
                1 -> { composeTestRule.runOnUiThread { shown = !shown }; log.append("shown ") }
                2 -> { composeTestRule.runOnUiThread { enabled = !enabled }; log.append("enabled ") }
                3 -> { composeTestRule.runOnUiThread { inUse = if (inUse === first) second else first }; log.append("swap ") }
                4 -> { insetPx = if (insetPx == 0) 64 else 0; insets(insetPx); log.append("inset ") }
                5, 6 -> { probe.triggerRecalculation(); log.append("trig ") }
                else -> { composeTestRule.mainClock.advanceTimeBy(900); log.append("time ") }
            }
            composeTestRule.waitForIdle()
            // a composition that entered again reads the insets afresh: the platform dispatches them on attach
            frames(2)
            if (shown) insets(insetPx)
            frames(4)
            val composed: Boolean = shown && enabled && insetPx > 0
            val started: Boolean = state.isAtLeast(Lifecycle.State.STARTED)
            val live: RecordingSystemBarsController = inUse as RecordingSystemBarsController
            val idle: RecordingSystemBarsController = if (live === first) second else first
            assertEquals(if (composed) 1 else 0, live.activeOverrideCount, "seed $seed step $step ($log): claims on the live controller")
            assertEquals(0, idle.activeOverrideCount, "seed $seed step $step ($log): claims on the other controller")
            assertEquals(if (composed && started) 1 else 0, probe.observerCount, "seed $seed step $step ($log): trigger collectors")
        }
        composeTestRule.runOnUiThread { shown = false }
        composeTestRule.waitForIdle()
        frames(3)
        assertEquals(0, first.activeOverrideCount + second.activeOverrideCount, "seed $seed ($log): claims left after leaving")
        assertEquals(0, probe.observerCount, "seed $seed ($log): observer left after leaving")
        val atLeave: Int = samples
        probe.triggerRecalculation()
        composeTestRule.mainClock.advanceTimeBy(3000)
        frames(3)
        assertEquals(atLeave, samples, "seed $seed ($log): sampled after leaving")
    }

    @Test fun seed1() = run(1)
    @Test fun seed2() = run(2)
    @Test fun seed3() = run(3)
    @Test fun seed4() = run(4)
    @Test fun seed5() = run(5)
    @Test fun seed6() = run(6)
    @Test fun seed7() = run(7)
    @Test fun seed8() = run(8)
    @Test fun seed9() = run(9)
    @Test fun seed10() = run(10)
    @Test fun seed11() = run(11)
    @Test fun seed12() = run(12)
}
