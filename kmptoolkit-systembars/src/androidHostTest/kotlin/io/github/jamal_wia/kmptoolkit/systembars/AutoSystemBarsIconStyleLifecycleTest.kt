package io.github.jamal_wia.kmptoolkit.systembars

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
 * The probe samples only while its host is STARTED, and every cycle's first tick follows the
 * cycle's first drawn frame without waiting an interval. Counted through the `onSampled` hook,
 * which fires per sample *attempt* — whether Robolectric can rasterise the layer is beside the
 * point; what is asserted is that the loop runs, and stops, when it should.
 *
 * Robolectric hands out a window with no bar insets, and the probe returns before its effect when
 * both strips are zero, so a status-bar inset is dispatched to Compose's host view — the same trick
 * as `FormScreenContainerInsetsTest`. The lifecycle is a [LifecycleRegistry] provided through
 * [LocalLifecycleOwner], so the states are driven directly instead of through the Activity.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSystemBarsIconStyleLifecycleTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private class Host : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class TestProbe : StatusBarLuminanceProbe {
        private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val triggers: SharedFlow<Unit> = flow
        override fun triggerRecalculation() {
            flow.tryEmit(Unit)
        }
    }

    private val host = Host()
    private val probe = TestProbe()
    private val samples: MutableList<Long> = mutableListOf()

    private fun setProbe() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(
                    intervalMs = INTERVAL_MS,
                    controller = RecordingSystemBarsController(),
                    probe = probe,
                    onSampled = { samples += it },
                ) {
                    Box(Modifier.fillMaxSize())
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

    private fun advance(ms: Long) {
        composeTestRule.mainClock.advanceTimeBy(ms)
        composeTestRule.waitForIdle()
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    /**
     * The cycle's first tick sits behind two frames (the first frame's draw must have landed in
     * the layer) plus the dispatch that starts the cycle, which the test clock also pays in
     * frames — three in all, ~50 ms: well inside one interval, which is the point. A triggered
     * sample sits behind the same two frames plus its dispatch.
     */
    private fun advanceToFirstTick() = advanceFrames(FRAMES_BEFORE_FIRST_TICK)

    @Test
    fun `a host below STARTED is never sampled`() {
        setProbe()
        moveTo(Lifecycle.State.CREATED)

        advance(TEN_INTERVALS_MS)

        assertEquals(0, samples.size)
    }

    @Test
    fun `a started host samples on its first tick before the first interval has elapsed`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)

        advanceToFirstTick()
        assertEquals(1, samples.size, "the first tick follows the first drawn frame — no wait for the interval")
    }

    // Robolectric never runs the root draw pass (verified: a drawWithContent counter stays at 0
    // even after a state change), so the other half of the idle rule — a root draw makes the next
    // tick read again — is proven on a device by the harness's quiet regime, where every backdrop
    // step is picked up by the following tick.
    @Test
    fun `a settled scene is not read again on the following ticks`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()

        repeat(TICKS_UNDER_IDLE_BOUND) { advance(INTERVAL_MS) }
        assertEquals(1, samples.size, "nothing drew since the first sample — those ticks are redundant")
    }

    @Test
    fun `a settled scene is still re-read once the ticks span the idle bound`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()

        repeat(TICKS_UNDER_IDLE_BOUND + 1) { advance(INTERVAL_MS) }
        assertEquals(2, samples.size, "one insurance sample once the idle bound has passed")
    }

    @Test
    fun `stopping the host stops the periodic loop`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()
        advance(INTERVAL_MS)
        val before: Int = samples.size

        moveTo(Lifecycle.State.CREATED)
        advance(TEN_INTERVALS_MS)

        assertEquals(before, samples.size, "a stopped host must not keep ticking in the background")
    }

    @Test
    fun `returning to STARTED starts a fresh cycle whose first tick samples at once`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()
        advance(INTERVAL_MS)
        moveTo(Lifecycle.State.CREATED)
        // Part-way through an interval: a merely paused loop would still owe the rest of it.
        advance(INTERVAL_MS / 2)
        val before: Int = samples.size

        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()

        assertEquals(before + 1, samples.size, "the new cycle's first tick is right after its first drawn frame")
    }

    @Test
    fun `a trigger while STARTED is sampled on the frame after next`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()
        advance(INTERVAL_MS)
        assertEquals(1, samples.size, "one redundant periodic tick first")

        probe.triggerRecalculation()
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)

        assertEquals(2, samples.size, "a trigger reads regardless of the idle rule")
    }

    @Test
    fun `a trigger sample restarts the idle bookkeeping`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()
        repeat(3) { advance(INTERVAL_MS) }
        probe.triggerRecalculation()
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)
        assertEquals(2, samples.size)

        repeat(TICKS_UNDER_IDLE_BOUND) { advance(INTERVAL_MS) }
        assertEquals(2, samples.size, "the idle bound counts from the trigger's read, not from the first tick's")

        advance(INTERVAL_MS)
        assertEquals(3, samples.size, "the insurance sample lands seven ticks after the trigger's read")
    }

    @Test
    fun `the insurance sample restarts the idle count`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()

        repeat(2 * (TICKS_UNDER_IDLE_BOUND + 1) - 1) { advance(INTERVAL_MS) }
        assertEquals(2, samples.size, "after the insurance sample the next six ticks are redundant again")

        advance(INTERVAL_MS)
        assertEquals(3, samples.size)
    }

    @Test
    fun `a new cycle reads first even when the previous one stopped mid idle run`() {
        setProbe()
        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()
        repeat(3) { advance(INTERVAL_MS) }
        moveTo(Lifecycle.State.CREATED)
        val before: Int = samples.size

        moveTo(Lifecycle.State.STARTED)
        advanceToFirstTick()

        assertEquals(before + 1, samples.size, "no idle bookkeeping carries over into the new cycle")
    }

    @Test
    fun `a trigger fired while stopped is not collected`() {
        setProbe()
        moveTo(Lifecycle.State.CREATED)

        probe.triggerRecalculation()
        advance(TEN_INTERVALS_MS)

        assertEquals(0, samples.size)
    }

    private companion object {
        const val STATUS_BAR_PX = 64
        const val INTERVAL_MS = 300L
        const val TEN_INTERVALS_MS = 10 * INTERVAL_MS
        const val FRAMES_BEFORE_FIRST_TICK = 3

        /** 6 × 300 ms = 1.8 s, under the 2 s idle bound; the seventh tick crosses it. */
        const val TICKS_UNDER_IDLE_BOUND = 6
    }
}
