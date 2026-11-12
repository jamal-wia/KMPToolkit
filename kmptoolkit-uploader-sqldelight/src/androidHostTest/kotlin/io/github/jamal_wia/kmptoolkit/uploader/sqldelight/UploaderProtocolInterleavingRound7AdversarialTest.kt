package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 7 (A1), attack 1 and 2: the engine protocol checked as a whole over the real SQLite store. Every ordering of
 * the store calls of three or four operations (with a bounded number of preemptions) is replayed, and after each
 * ordering the documented contract is asserted: no attempt of a withdrawn item starts after the withdrawal returned,
 * an enqueued item that nobody withdrew runs, nothing runs twice when engines do not overlap, a closed engine runs
 * nothing, a unique key is held by one live row.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UploaderProtocolInterleavingRound7AdversarialTest : InterleavingTestBase() {

    @Test
    fun `pending A with keep, replace and cancel of A in every ordering`() =
        explore(Scenario("keepB+replaceC+cancelA/PENDING_A/p2", Seed.PENDING_A, listOf(Op.KEEP_B, Op.REPLACE_C, Op.CANCEL_A), emptyList(), true, 1), 20000)

    @Test
    fun `pending A with replace, tag wipe and a free enqueue under close and restart`() =
        explore(Scenario("replaceC+cancelTag+freeD/PENDING_A/close+restart", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_TAG, Op.FREE_D), listOf(Meta.CLOSE, Meta.RESTART), true, 1), 20000)

    @Test
    fun `empty queue with keep, replace, a tag wipe and a trigger`() =
        explore(Scenario("keepB+replaceC+cancelTag/EMPTY/trigger", Seed.EMPTY, listOf(Op.KEEP_B, Op.REPLACE_C, Op.CANCEL_TAG), listOf(Meta.TRIGGER), true, 1), 20000)

    @Test
    fun `a gated A with replace, cancel, a lease-sized time step and a close`() =
        explore(Scenario("replaceC+cancelA/GATED_A/advance+close", Seed.GATED_A, listOf(Op.REPLACE_C, Op.CANCEL_A), listOf(Meta.ADVANCE, Meta.CLOSE), true, 1), 20000)

    @Test
    fun `an overlapping replacement engine started without waiting for the closed one`() =
        explore(Scenario("keepB+cancelTag/PENDING_A/close+restart-relaxed", Seed.PENDING_A, listOf(Op.KEEP_B, Op.CANCEL_TAG), listOf(Meta.CLOSE, Meta.RESTART), false, 1), 20000)

    @Test
    fun `a close landing in the middle of any store call, with replace, cancel and a restart`() =
        explore(Scenario("replaceC+cancelA/PENDING_A/close-mid+restart", Seed.PENDING_A, listOf(Op.REPLACE_C, Op.CANCEL_A), listOf(Meta.CLOSE_MID, Meta.RESTART), true, 2), 20000)

    @Test
    fun `an owner scope cancelled instead of closed, with keep and a tag wipe and a restart`() =
        explore(Scenario("keepB+cancelTag/PENDING_A/kill+restart", Seed.PENDING_A, listOf(Op.KEEP_B, Op.CANCEL_TAG), listOf(Meta.KILL, Meta.RESTART), true, 2), 20000)

    @Test
    fun `negative control - a store that hides what a tag wipe removed is caught by the harness`() = runBlocking {
        withTimeout(120.seconds) {
            val storage: UploaderStorage = open()
            val testScheduler = TestCoroutineScheduler()
            val scenario = Scenario("control", Seed.PENDING_A, listOf(Op.CANCEL_TAG), emptyList(), true, 2, blindDeleteByTag = true)
            val runner = ScenarioRunner(storage, testScheduler, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), scenario)
            val pending: ArrayDeque<List<Int>> = ArrayDeque()
            pending.addLast(emptyList())
            var caught = 0
            var explored = 0
            while (pending.isNotEmpty() && explored < 2000) {
                val prefix: List<Int> = pending.removeLast()
                val result: RunResult = runner.run(prefix)
                explored++
                for (depth in prefix.size until result.optionCounts.size) {
                    for (alt in 1 until result.optionCounts[depth]) pending.addLast(result.choices.take(depth) + alt)
                }
                if (result.violations.any { it.contains("ran after its withdrawal") }) caught++
            }
            println("R7 control: $explored schedules, $caught caught")
            assertEquals(true, caught > 0, "the harness did not notice a withdrawal that the engine was never told about")
        }
    }
}
