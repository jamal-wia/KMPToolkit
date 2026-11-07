package io.github.jamal_wia.kmptoolkit.permission

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Race and cancellation hazards of [PermissionRequestFlow]: callers that start, refresh, reset and
 * walk away in random orders while the "dialog" and the "platform read" are parked.
 *
 * Everything runs on one virtual-time dispatcher, so an interleaving is decided by the seed alone and
 * a stuck flow shows as a state, not as a hang.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRequestFlowAdversarialTest {

    private class Round(val seed: Int) {
        val controller = ScriptedPermissionController(
            status = PermissionStatus.NotDetermined,
            requestOutcome = PermissionStatus.Granted,
        )
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        var dialog: CompletableDeferred<Unit> = CompletableDeferred()
        var resets: Int = 0
        val history: MutableList<PermissionFlowState> = mutableListOf()

        init {
            controller.whileRequesting = { dialog.await() }
            // A slow platform read: the answer was taken before the suspension, as a real read is.
            controller.whileChecking = { yield() }
        }
    }

    private fun TestScope.runRound(seed: Int) {
        val round = Round(seed)
        val random = Random(seed)
        val jobs: MutableList<Job> = mutableListOf()
        // Unconfined, so every published state is seen and none is conflated away.
        val collector: Job = launch(UnconfinedTestDispatcher(testScheduler)) { round.flow.state.collect { round.history += it } }
        runCurrent()

        repeat(6 + random.nextInt(10)) {
            when (random.nextInt(6)) {
                0, 1 -> jobs += launch(StandardTestDispatcher(testScheduler)) { round.flow.start() }
                2 -> jobs += launch(StandardTestDispatcher(testScheduler)) { round.flow.refresh() }
                3 -> {
                    round.resets++
                    round.flow.reset()
                }
                4 -> if (jobs.isNotEmpty()) jobs.removeAt(random.nextInt(jobs.size)).cancel()
                else -> {
                    // The user answers the dialog that is up.
                    round.dialog.complete(Unit)
                    round.dialog = CompletableDeferred()
                }
            }
            if (random.nextBoolean()) runCurrent()
        }

        // Every caller ends: the ones still running are cancelled, the dialog is answered.
        jobs.forEach { it.cancel() }
        round.dialog.complete(Unit)
        advanceUntilIdle()
        collector.cancel()

        val seedNote = "seed ${round.seed}, history ${round.history}"
        assertTrue(jobs.none { it.isActive }, "a caller never ended ($seedNote)")
        assertNotEquals(PermissionFlowState.Requesting, round.flow.state.value, "stuck in Requesting after every caller ended ($seedNote)")

        // A granted outcome is only ever left by a reset (or another start the controller answers
        // as granted): a stale refresh, which read the status before the grant, must not undo it.
        val grantedToIdle: Int = round.history.zipWithNext().count { (a, b) ->
            a == PermissionFlowState.Granted && b == PermissionFlowState.Idle
        }
        assertTrue(grantedToIdle <= round.resets, "a stale result overwrote a granted outcome with Idle ($seedNote)")
    }

    @Test
    fun `two hundred random interleavings of start refresh reset and cancel never leave the flow stuck or overwrite an outcome`() =
        runTest(timeout = 60.seconds) {
            repeat(200) { seed -> runRound(seed) }
        }

    @Test
    fun `after any interleaving the flow still reaches the controller and ends granted`() =
        runTest(timeout = 60.seconds) {
            repeat(200) { seed ->
                val round = Round(1_000 + seed)
                val random = Random(seed)
                val jobs: MutableList<Job> = mutableListOf()
                repeat(4 + random.nextInt(6)) {
                    when (random.nextInt(4)) {
                        0, 1 -> jobs += launch(StandardTestDispatcher(testScheduler)) { round.flow.start() }
                        2 -> jobs += launch(StandardTestDispatcher(testScheduler)) { round.flow.refresh() }
                        else -> if (jobs.isNotEmpty()) jobs.removeAt(random.nextInt(jobs.size)).cancel()
                    }
                    runCurrent()
                }
                jobs.forEach { it.cancel() }
                advanceUntilIdle()

                round.controller.status = PermissionStatus.NotDetermined
                round.dialog.complete(Unit)
                round.flow.reset()
                val before: Int = round.controller.requestCount
                val last: Job = launch(StandardTestDispatcher(testScheduler)) { round.flow.start() }
                advanceUntilIdle()

                assertTrue(last.isCompleted, "the final start never ended (seed ${round.seed})")
                assertEquals(before + 1, round.controller.requestCount, "the final start did not reach the controller (seed ${round.seed})")
                assertEquals(PermissionFlowState.Granted, round.flow.state.value, "seed ${round.seed}")
            }
        }
}
