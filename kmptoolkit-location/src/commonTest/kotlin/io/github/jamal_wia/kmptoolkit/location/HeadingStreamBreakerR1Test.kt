@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 1 of group loc-heading (decision D-23): a livelock attack on the shared heading stream.
 *
 * A platform callback that refills the conflated wake-up as fast as the collector drains it, with states the
 * minimum-change filter always rejects, leaves the collector in a loop that never suspends: a receive that finds
 * a value does not suspend, and nothing is emitted, so the flow's own emit-time cancellation check never runs.
 * Cancelling that collection must still end it at once and release the registration.
 *
 * Deterministic and single-threaded: the refill happens synchronously on every compose (the worst case of a
 * callback that always wins the race), and the cancel is issued from inside that same loop after
 * [CANCEL_AT] turns. A cap stops refilling after [CAP] turns, so a broken stream fails the assertions instead of
 * hanging the run.
 *
 * Mutation by reading (and run once by the breaker): removing `ensureActive()` from the loop in `headingStream`
 * makes the collector keep spinning after the cancel until the cap, which turns `turns` from [CANCEL_AT] into
 * [CAP] and this test red.
 */
class HeadingStreamBreakerR1Test {

    @Test
    fun `a collection spinning on rejected states that a callback keeps refilling ends at the cancel and releases once`() = runTest {
        var turns = 0
        var releases = 0
        var collection: Job? = null
        val registered: CompletableDeferred<HeadingInputs<Int>> = CompletableDeferred()
        lateinit var inputs: HeadingInputs<Int>

        collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            headingStream(
                minChangeDegrees = 10.0,
                initial = { 0 },
                compose = { state: Int ->
                    turns++
                    if (turns == CANCEL_AT) collection?.cancel()
                    // The platform calls back again before the collector gets back to its receive.
                    if (turns < CAP) inputs.update { value: Int -> value + 1 }
                    // Always the same direction: after the first emission the filter rejects every state.
                    Heading(magneticDegrees = 90.0 + (state % 2) * 0.001, trueDegrees = null, accuracyDegrees = null, needsCalibration = false)
                },
                register = { registeredInputs: HeadingInputs<Int> ->
                    inputs = registeredInputs
                    registered.complete(registeredInputs)
                    AutoCloseable { releases++ }
                },
            ).collect { }
        }
        assertTrue(registered.isCompleted, "control: the collection registered")
        assertEquals(0, releases, "control: live")

        // One callback starts the storm; with an unconfined collector it all runs inside this call.
        registered.await().update { value: Int -> value + 1 }
        testScheduler.advanceUntilIdle()

        assertTrue(collection.isCancelled, "control: the cancel was issued")
        assertEquals(CANCEL_AT, turns, "the loop must stop at the cancel, not when the callback stops refilling")
        assertEquals(1, releases, "released exactly once")
    }

    private companion object {
        const val CANCEL_AT: Int = 1_000
        const val CAP: Int = 100_000
    }
}
