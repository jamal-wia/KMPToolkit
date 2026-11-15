@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 3 of group loc-heading (decision D-23, ticket item LOC-11): the common [headingStream] when the
 * release itself throws.
 *
 * Since round 3 both platform providers document that an `Error` thrown by the app's logger is not absorbed, but
 * that every registration is released before anything is logged: so the handle's `close()` CAN throw an `Error`
 * after it released everything (Android release handle, iOS `close()` with an inline confine). The stream's
 * `finally` then runs `close()` while the collection's own failure is in flight. The Kotlin library's rule for a
 * resource closed on a failure path (`AutoCloseable.use`) is that neither throwable is lost: the original is thrown
 * with the close failure suppressed. `registerCollection` already applies that rule to its own undo. A plain
 * `finally { close() }` instead replaces the collection's failure and drops it without a trace.
 *
 * Deterministic: unconfined collector, synchronous writes. No sleeps, no wall clock, no randomness.
 */
class HeadingStreamBreakerR3Test {

    private fun Throwable.chain(): List<Throwable> = listOf(this) + suppressedExceptions.flatMap { suppressed: Throwable -> suppressed.chain() }

    @Test
    fun `a collector failure stays reachable when the release then throws an Error`() = runTest {
        var inputs: HeadingInputs<Double>? = null
        var releases = 0
        val releaseError = AssertionError("a logger that throws, met by the release after it released everything")
        val boom = IllegalStateException("the collector's own failure")
        val stream = headingStream(
            minChangeDegrees = 0.0,
            initial = { Double.NaN },
            compose = { magnetic: Double -> if (magnetic.isNaN()) null else Heading(magnetic, null, null, false) },
            register = { registered: HeadingInputs<Double> ->
                inputs = registered
                AutoCloseable {
                    releases++
                    throw releaseError
                }
            },
        )
        val seen: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                stream.collect { throw boom }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                seen += thrown
            }
        }
        val registered: HeadingInputs<Double> = checkNotNull(inputs) { "control: the collection registered" }

        registered.update { 42.0 }
        collection.join()

        assertEquals(1, releases, "control: released exactly once")
        val chain: List<Throwable> = seen.single().chain()
        assertTrue(
            chain.any { it === boom } && chain.any { it === releaseError },
            "neither the collection's failure nor the release's Error may be lost (one thrown, the other suppressed); " +
                "the collector saw ${seen.single()} with suppressed ${seen.single().suppressedExceptions}",
        )
    }
}
