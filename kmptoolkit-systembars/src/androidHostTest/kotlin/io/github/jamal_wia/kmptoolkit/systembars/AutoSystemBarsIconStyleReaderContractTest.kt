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
import io.github.jamal_wia.kmptoolkit.systembars.ScriptedBarStripReader.Step
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the sampling loop of [AutoSystemBarsIconStyle] does with a [BarStripReader]'s answer (plan
 * issue-13 sections 2.5, 2.7 and 6.2): a `null` read, a thrown one and a suspended one are skipped
 * samples that publish nothing and book nothing, the third consecutive one withdraws the claim,
 * a success resumes and resets, cancellation leaves nothing behind. Driven through the internal
 * overload with a [ScriptedBarStripReader]; nothing here depends on how a real reader gets pixels.
 *
 * Time is the Compose test clock (`autoAdvance = false`): one `advance(INTERVAL_MS)` crosses exactly
 * one periodic tick, and a triggered sample sits behind three frames. A tick after a success is
 * idle-skipped for six ticks (nothing drew since), so the helpers wait for a read count, not for a
 * fixed number of ticks.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSystemBarsIconStyleReaderContractTest {

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

    /** The real controller, with the handle's `update` and `release` counted. */
    private class CountingController(
        val inner: RecordingSystemBarsController = RecordingSystemBarsController(BASE),
    ) : SystemBarsController by inner {
        val updates: AtomicInteger = AtomicInteger()
        val releases: AtomicInteger = AtomicInteger()
        val claims: AtomicInteger = AtomicInteger()

        override fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle {
            claims.incrementAndGet()
            val handle: SystemBarsOverrideHandle = inner.applyOverride(override)
            return object : SystemBarsOverrideHandle {
                override fun update(override: SystemBarsOverride) {
                    updates.incrementAndGet()
                    handle.update(override)
                }

                override fun release() {
                    releases.incrementAndGet()
                    handle.release()
                }
            }
        }
    }

    private val host = Host()
    private val probe = TestProbe()
    private val samples: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList()
    private val controller = CountingController()

    private fun setProbe(reader: BarStripReader?, content: () -> Unit = {}) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(
                    controller = controller,
                    probe = probe,
                    enabled = true,
                    intervalMs = INTERVAL_MS,
                    onSampled = { samples += it },
                    reader = reader,
                ) {
                    content()
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        injectStatusBar()
    }

    private fun injectStatusBar() {
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

    private fun start() {
        moveTo(Lifecycle.State.STARTED)
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)
    }

    /** Ticks (one interval each) until the reader has been asked [target] times; fails when it never is. */
    private fun advanceUntilReads(reader: ScriptedBarStripReader, target: Int) {
        var ticks = 0
        while (reader.reads.get() < target && ticks < MAX_TICKS) {
            advance(INTERVAL_MS)
            ticks++
        }
        assertEquals(target, reader.reads.get(), "reads after $ticks ticks")
    }

    private fun trigger() {
        probe.triggerRecalculation()
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)
    }

    private val styled = BarStripStyles(SystemBarIconStyle.LightIcons, SystemBarIconStyle.LightIcons)

    @Test
    fun `the reader is asked for exactly the strips the gate saw`() {
        val reader = ScriptedBarStripReader()
        setProbe(reader)
        start()

        assertEquals(listOf(STATUS_BAR_PX to 0), reader.calls.toList(), "statusPx and navPx of the first read")
    }

    @Test
    fun `a null read publishes nothing and books nothing, so the next tick reads again`() {
        val reader = ScriptedBarStripReader(fallback = Step.Null)
        setProbe(reader)
        start()
        assertEquals(1, reader.reads.get(), "the first tick read")

        advance(INTERVAL_MS)
        assertEquals(2, reader.reads.get(), "a failed read is not idle-skipped: the next tick reads again")
        assertEquals(0, controller.updates.get(), "nothing was published")
        assertEquals(BASE, controller.currentConfig, "the bars follow the base")
        assertEquals(2, samples.size, "onSampled fires for a skipped attempt")
    }

    @Test
    fun `a null read keeps the previous override`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Styles(styled), Step.Null)
        setProbe(reader)
        start()
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons, "the first read decided")
        assertEquals(1, controller.updates.get())

        trigger()

        assertEquals(2, reader.reads.get(), "the trigger read, and it was a skip")
        assertEquals(1, controller.updates.get(), "a skipped read publishes nothing")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons, "the last decision still holds")
    }

    @Test
    fun `a thrown exception is one failed attempt and the next tick reads`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Throw(IllegalStateException("window is gone")), Step.Styles(styled))
        setProbe(reader)
        start()
        assertEquals(1, reader.reads.get())
        assertEquals(0, controller.updates.get(), "the throw published nothing")
        assertEquals(1, samples.size, "onSampled fires for a thrown attempt")

        advance(INTERVAL_MS)

        assertEquals(2, reader.reads.get(), "the loop is alive: the next tick read")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `a read suspended forever and then a host below STARTED publishes nothing and reads no more`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Park)
        setProbe(reader)
        start()
        assertEquals(1, reader.reads.get())
        assertNotNull(reader.parked, "control: the read is parked")
        assertEquals(1, reader.inFlight.get(), "control: the read is in flight")

        moveTo(Lifecycle.State.CREATED)
        advance(TEN_INTERVALS_MS)

        assertEquals(1, reader.reads.get(), "no further read while stopped")
        assertEquals(1, reader.cancelledReads.get(), "the parked read was cancelled")
        assertEquals(0, reader.inFlight.get())
        assertEquals(0, controller.updates.get(), "nothing was published")
        assertEquals(0, samples.size, "a cancelled attempt is not an attempt")
        assertEquals(1, controller.inner.activeOverrideCount, "the claim is kept while the composable lives")
        assertEquals(0, controller.releases.get(), "the handle was not released")
    }

    @Test
    fun `a read suspended forever and then the composable leaving composition releases the handle`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Park)
        var shown: Boolean by mutableStateOf(true)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                if (shown) {
                    AutoSystemBarsIconStyle(
                        controller = controller,
                        probe = probe,
                        enabled = true,
                        intervalMs = INTERVAL_MS,
                        onSampled = { samples += it },
                        reader = reader,
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        injectStatusBar()
        start()
        assertEquals(1, reader.inFlight.get(), "control: the read is in flight")
        assertEquals(1, controller.inner.activeOverrideCount, "control: the claim exists")

        composeTestRule.runOnUiThread { shown = false }
        composeTestRule.waitForIdle()
        advance(TEN_INTERVALS_MS)

        assertEquals(0, controller.inner.activeOverrideCount, "the handle is released")
        assertEquals(1, controller.releases.get())
        assertEquals(1, reader.cancelledReads.get(), "the parked read was cancelled")
        assertEquals(0, reader.inFlight.get())
        assertEquals(0, controller.updates.get())
        assertEquals(0, samples.size)
        assertEquals(1, reader.reads.get(), "no further read")
    }

    @Test
    fun `a late completion after cancellation is ignored`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Park)
        setProbe(reader)
        start()
        val parked = assertNotNull(reader.parked, "control: the read is parked")
        moveTo(Lifecycle.State.CREATED)
        assertTrue(parked.isCancelled, "control: the parked continuation was cancelled with the cycle")

        // The platform callback fires after the sampler was cancelled.
        composeTestRule.runOnUiThread { reader.completeParked(styled) }
        composeTestRule.waitForIdle()
        advance(TEN_INTERVALS_MS)

        assertEquals(0, controller.updates.get(), "a late completion publishes nothing")
        assertEquals(BASE, controller.currentConfig)
        assertEquals(0, samples.size, "a late completion is not an attempt: onSampled is not called")
        assertEquals(1, reader.reads.get())
    }

    @Test
    fun `a trigger while a read is in flight waits for it and the reads never overlap`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Park)
        setProbe(reader)
        start()
        assertEquals(1, reader.inFlight.get(), "control: the first read is in flight")

        trigger()
        advance(INTERVAL_MS)
        assertEquals(1, reader.reads.get(), "the trigger's read waits behind the one in flight")

        composeTestRule.runOnUiThread { reader.completeParked(styled) }
        composeTestRule.waitForIdle()
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)

        assertEquals(2, reader.reads.get(), "the waiting read ran afterwards")
        assertEquals(1, reader.maxInFlight.get(), "never two reads at once")
        assertEquals(0, reader.inFlight.get())
    }

    @Test
    fun `fail twice then succeed publishes only the styles`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Null, Step.Null, Step.Styles(styled))
        setProbe(reader)
        start()
        advanceUntilReads(reader, 3)

        assertEquals(1, controller.updates.get(), "one write: the styles, no withdrawal in between")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `fail three times withdraws the claim exactly once and five times still once`() {
        val reader = ScriptedBarStripReader(fallback = Step.Null)
        reader.enqueue(Step.Styles(styled))
        setProbe(reader)
        start()
        assertEquals(1, controller.updates.get(), "control: the styles were published")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)

        advanceUntilReads(reader, 3)
        assertEquals(1, controller.updates.get(), "two failed attempts hold the claim")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)

        advanceUntilReads(reader, 4)
        assertEquals(2, controller.updates.get(), "the third consecutive failure withdrew it")
        assertEquals(BASE, controller.currentConfig, "the bars follow the base again")

        advanceUntilReads(reader, 6)
        assertEquals(2, controller.updates.get(), "failures four to five publish nothing more")
        assertEquals(1, controller.inner.activeOverrideCount, "the claim is withdrawn, not released")
    }

    @Test
    fun `a thrown read counts toward the release like a null one`() {
        val reader = ScriptedBarStripReader(fallback = Step.Throw(IllegalStateException("boom")))
        reader.enqueue(Step.Styles(styled))
        setProbe(reader)
        start()

        advanceUntilReads(reader, 4)

        assertEquals(2, controller.updates.get(), "three thrown attempts withdrew the claim")
        assertEquals(BASE, controller.currentConfig)
    }

    @Test
    fun `a success after the release republishes the styles`() {
        val reader = ScriptedBarStripReader(fallback = Step.Null)
        reader.enqueue(Step.Styles(styled), Step.Null, Step.Null, Step.Null, Step.Styles(styled))
        setProbe(reader)
        start()
        advanceUntilReads(reader, 4)
        assertEquals(BASE, controller.currentConfig, "control: released after three failures")

        advanceUntilReads(reader, 5)

        assertEquals(3, controller.updates.get(), "styles, withdrawal, styles again")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.navigationBarIcons)
    }

    @Test
    fun `alternating failure and success never releases`() {
        val reader = ScriptedBarStripReader(fallback = Step.Null)
        reader.enqueue(
            Step.Styles(styled), Step.Null, Step.Styles(styled), Step.Null, Step.Styles(styled),
            Step.Null, Step.Styles(styled), Step.Null, Step.Styles(styled),
        )
        setProbe(reader)
        start()

        advanceUntilReads(reader, 9)

        assertEquals(1, controller.updates.get(), "only the first success wrote; no failure ever withdrew")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `the failure count starts again at a new STARTED cycle`() {
        val reader = ScriptedBarStripReader(fallback = Step.Null)
        reader.enqueue(Step.Styles(styled))
        setProbe(reader)
        start()
        advanceUntilReads(reader, 3)
        assertEquals(1, controller.updates.get(), "control: two failures so far, claim held")

        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.STARTED)
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)

        assertEquals(4, reader.reads.get(), "the new cycle's first tick read (one failure)")
        assertEquals(1, controller.updates.get(), "one failure in the new cycle does not withdraw: the count was reset")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `onSampled fires once per attempt for a success, a null and a throw`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.Styles(styled), Step.Null, Step.Throw(IllegalStateException("x")))
        setProbe(reader)
        start()
        advanceUntilReads(reader, 3)

        assertEquals(3, samples.size, "one callback per attempt")
        assertTrue(samples.all { it >= 0L }, "durations ${samples.toList()}")
    }

    @Test
    fun `onSampled fires after a suspended read finished, never while it is parked`() {
        val reader = ScriptedBarStripReader()
        reader.enqueue(Step.AfterFrames(frames = 4, then = Step.Styles(styled)))
        val events: MutableList<String> = CopyOnWriteArrayList()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(
                    controller = controller,
                    probe = probe,
                    enabled = true,
                    intervalMs = INTERVAL_MS,
                    onSampled = { events += "sampled" },
                    reader = reader,
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        injectStatusBar()
        moveTo(Lifecycle.State.STARTED)
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)
        assertEquals(1, reader.inFlight.get(), "control: the read is waiting for frames")
        assertEquals(emptyList(), events.toList(), "no callback while the read is suspended")

        advanceFrames(5)

        assertEquals(0, reader.inFlight.get())
        assertEquals(listOf("sampled"), events.toList(), "the callback came after the read, and once")
        assertEquals(listOf("read-start", "read-end"), reader.events.toList())
    }

    @Test
    fun `idle skipped ticks call neither the reader nor onSampled`() {
        val reader = ScriptedBarStripReader()
        setProbe(reader)
        start()
        assertEquals(1, reader.reads.get())
        assertEquals(1, samples.size)

        repeat(TICKS_UNDER_IDLE_BOUND) { advance(INTERVAL_MS) }

        assertEquals(1, reader.reads.get(), "nothing drew since the successful read: the ticks are redundant")
        assertEquals(1, samples.size, "a skipped tick is not an attempt")
    }

    @Test
    fun `without a reader nothing is claimed, nothing is sampled and the content composes`() {
        var composed = 0
        setProbe(reader = null) { composed++ }
        start()
        advance(TEN_INTERVALS_MS)

        assertTrue(composed > 0, "the content composed")
        assertEquals(0, controller.inner.activeOverrideCount, "no handle")
        assertEquals(0, controller.claims.get(), "applyOverride was never called")
        assertEquals(0, samples.size)
    }

    private companion object {
        val BASE: SystemBarsConfig = SystemBarsConfig.ForLightBackground
        const val STATUS_BAR_PX = 64
        const val INTERVAL_MS = 300L
        const val TEN_INTERVALS_MS = 10 * INTERVAL_MS
        const val FRAMES_BEFORE_FIRST_TICK = 3
        const val TICKS_UNDER_IDLE_BOUND = 6
        const val MAX_TICKS = 60
    }
}
