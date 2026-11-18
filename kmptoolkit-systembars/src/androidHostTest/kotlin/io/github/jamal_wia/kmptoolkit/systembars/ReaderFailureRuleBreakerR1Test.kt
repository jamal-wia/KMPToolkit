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
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * Breaker round 1 of group sb-core (ticket release-6-0-0, item SB-1): a seeded model check of the
 * loop's failure and booking rules against the plan's contract (issue-13 sections 2.5, 2.7, 3.2
 * `sampleOnce`), through the internal overload and a scripted reader.
 *
 * The model, written from the plan and not from the code: a success publishes its styles (an `update`
 * only when they differ from what was last published) and resets the failure count; a failed attempt
 * (`null` or a throw) publishes nothing until the [FAILED_READS_BEFORE_RELEASE]-th consecutive one,
 * which publishes `None` once (no `update` when `None` is already published); the count resets at each
 * STARTED cycle, the last published override does not; the claim (one handle) lives as long as the
 * composable. Random walks mix triggered attempts with stop/start cycles; after every step the
 * controller's configuration, the `update` count, the read count, the `onSampled` count and the
 * number of live claims must equal the model's.
 *
 * Mutations that turn this red (by reading): a release at K-1 or K+1; a release that re-publishes
 * `None` on every further failure; the count not reset at a cycle start or reset by a failure; the
 * count reset by a skipped (idle) tick; `lastPublished` reset at a cycle start; a thrown read that is
 * not counted. Every failure message starts with `seed=`.
 *
 * Deterministic: fixed seeds, Compose test clock with `autoAdvance = false`, the periodic interval far
 * beyond the test so that every attempt is either a cycle's first tick or a trigger.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderFailureRuleBreakerR1Test {

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

    private class CountingController(
        val inner: RecordingSystemBarsController = RecordingSystemBarsController(BASE),
    ) : SystemBarsController by inner {
        val updates: AtomicInteger = AtomicInteger()

        override fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle {
            val handle: SystemBarsOverrideHandle = inner.applyOverride(override)
            return object : SystemBarsOverrideHandle {
                override fun update(override: SystemBarsOverride) {
                    updates.incrementAndGet()
                    handle.update(override)
                }

                override fun release() {
                    handle.release()
                }
            }
        }
    }

    /** What one attempt answers. */
    private enum class Outcome { LIGHT, DARK_STATUS_ONLY, NO_DECISION, NULL, THROW }

    /** The plan's rules, independent of the production code. */
    private class Model {
        var published: SystemBarsOverride = SystemBarsOverride.None
        var failures = 0
        var updates = 0
        var reads = 0
        var attempts = 0

        fun cycleStart() {
            failures = 0
        }

        fun attempt(outcome: Outcome) {
            reads++
            attempts++
            val styles: SystemBarsOverride? = when (outcome) {
                Outcome.LIGHT -> SystemBarsOverride(SystemBarIconStyle.LightIcons, SystemBarIconStyle.LightIcons)
                Outcome.DARK_STATUS_ONLY -> SystemBarsOverride(SystemBarIconStyle.DarkIcons, null)
                Outcome.NO_DECISION -> SystemBarsOverride.None
                Outcome.NULL, Outcome.THROW -> null
            }
            if (styles != null) {
                publish(styles)
                failures = 0
            } else {
                failures++
                if (failures >= RELEASE_AFTER) publish(SystemBarsOverride.None)
            }
        }

        private fun publish(next: SystemBarsOverride) {
            if (next != published) updates++
            published = next
        }

        fun expectedConfig(): SystemBarsConfig = BASE.copy(
            statusBarIcons = published.statusBarIcons ?: BASE.statusBarIcons,
            navigationBarIcons = published.navigationBarIcons ?: BASE.navigationBarIcons,
        )
    }

    private fun stepFor(outcome: Outcome): Step = when (outcome) {
        Outcome.LIGHT -> Step.Styles(BarStripStyles(SystemBarIconStyle.LightIcons, SystemBarIconStyle.LightIcons))
        Outcome.DARK_STATUS_ONLY -> Step.Styles(BarStripStyles(SystemBarIconStyle.DarkIcons, null))
        Outcome.NO_DECISION -> Step.Styles(BarStripStyles(null, null))
        Outcome.NULL -> Step.Null
        Outcome.THROW -> Step.Throw(IllegalStateException("scripted failure"))
    }

    @Test
    fun `seed 1301 random walk of attempts and cycles matches the plan's failure model`() = walk(seed = 1301)

    @Test
    fun `seed 1302 random walk of attempts and cycles matches the plan's failure model`() = walk(seed = 1302)

    @Test
    fun `seed 1303 random walk of attempts and cycles matches the plan's failure model`() = walk(seed = 1303)

    private fun walk(seed: Int) {
        val random = Random(seed)
        val host = Host()
        val probe = TestProbe()
        val controller = CountingController()
        val reader = ScriptedBarStripReader(fallback = Step.Null)
        val samples: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList()
        val model = Model()

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(
                    controller = controller,
                    probe = probe,
                    enabled = true,
                    intervalMs = NO_PERIODIC_TICK_MS,
                    onSampled = { samples += it },
                    reader = reader,
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        composeTestRule.runOnUiThread {
            val insets: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 64, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 64))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeTestRule.waitForIdle()

        fun frames(count: Int) = repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }

        // Failures at FAILURE_PERCENT and a cycle at CYCLE_ONE_IN: tuned so that the patterns that
        // tell a wrong K or a missing per-cycle reset apart (a decision, failures, a stop, more
        // failures) occur many times per walk.
        val successes: Array<Outcome> = arrayOf(Outcome.LIGHT, Outcome.DARK_STATUS_ONLY, Outcome.NO_DECISION)
        fun nextOutcome(): Outcome =
            if (random.nextInt(100) < FAILURE_PERCENT) {
                if (random.nextBoolean()) Outcome.NULL else Outcome.THROW
            } else {
                successes[random.nextInt(successes.size)]
            }

        fun check(step: Int, what: String) {
            val where = "seed=$seed step=$step ($what)"
            assertEquals(model.reads, reader.reads.get(), "$where: reads")
            assertEquals(model.attempts, samples.size, "$where: onSampled once per attempt")
            assertEquals(model.updates, controller.updates.get(), "$where: handle updates")
            assertEquals(model.expectedConfig(), controller.currentConfig, "$where: controller configuration")
            assertEquals(1, controller.inner.activeOverrideCount, "$where: exactly one live claim")
        }

        fun startCycle(step: Int) {
            val outcome: Outcome = nextOutcome()
            reader.enqueue(stepFor(outcome))
            composeTestRule.runOnUiThread { host.registry.currentState = Lifecycle.State.STARTED }
            composeTestRule.waitForIdle()
            frames(FRAMES_PER_ATTEMPT)
            model.cycleStart()
            model.attempt(outcome)
            check(step, "cycle start, first tick $outcome")
        }

        startCycle(step = 0)
        for (step in 1..STEPS) {
            if (random.nextInt(CYCLE_ONE_IN) == 0) {
                composeTestRule.runOnUiThread { host.registry.currentState = Lifecycle.State.CREATED }
                composeTestRule.waitForIdle()
                frames(1)
                check(step, "stopped")
                startCycle(step)
            } else {
                val outcome: Outcome = nextOutcome()
                reader.enqueue(stepFor(outcome))
                probe.triggerRecalculation()
                frames(FRAMES_PER_ATTEMPT)
                model.attempt(outcome)
                check(step, "trigger $outcome")
            }
        }
    }

    private companion object {
        val BASE: SystemBarsConfig = SystemBarsConfig.ForLightBackground

        /** The plan's K (section 2.5), written here as the contract's number, not read from the code. */
        const val RELEASE_AFTER = 3
        const val STEPS = 120
        const val FAILURE_PERCENT = 60
        const val CYCLE_ONE_IN = 3
        const val FRAMES_PER_ATTEMPT = 3
        const val NO_PERIODIC_TICK_MS = 3_600_000L
    }
}
