@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 4 of group loc-heading (LOC-11, decision D-23): the round-4 `finally` of [headingStream], attacked
 * where the executor's matrix does not look. Contract (stream KDoc): when `close()` throws, the stream still
 * finishes its release and ends with that throwable; the collection's earlier end is attached as suppressed, so
 * neither is lost.
 *
 * - A release that throws a `CancellationException` while a collector failure is in flight: which wins, and is the
 *   collector's failure still reachable (it is the case where a `launch` would treat the end as a plain
 *   cancellation, so the attached failure is the only trace).
 * - A release whose `Error` is already attached to the collector's failure (a suppressed cycle): attaching back
 *   must neither throw nor make the trace of what surfaced unprintable (a cycle must not recurse forever).
 * - A release that throws the in-flight `CancellationException` itself (same instance, under cancellation).
 *
 * Deterministic: unconfined collector, synchronous writes, no randomness, no sleeps.
 */
class HeadingStreamBreakerR4Test {

    private class Probe(closeFailure: () -> Throwable?) {
        var inputs: HeadingInputs<Double>? = null
        var releases = 0
        val stream = headingStream(
            minChangeDegrees = 0.0,
            initial = { Double.NaN },
            compose = { magnetic: Double -> if (magnetic.isNaN()) null else Heading(magnetic, null, null, false) },
            register = { registered: HeadingInputs<Double> ->
                inputs = registered
                AutoCloseable {
                    releases++
                    closeFailure()?.let { failure: Throwable -> throw failure }
                }
            },
        )
    }

    @Test
    fun `a release that throws a CancellationException while a collector failure is in flight keeps the failure attached`() = runTest {
        val collectorFailure = IllegalStateException("the collector's own failure")
        val releaseCancellation = CancellationException("a release that throws a cancellation")
        val probe = Probe { releaseCancellation }
        val surfaced: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                probe.stream.collect { throw collectorFailure }
            } catch (thrown: Throwable) {
                surfaced += thrown
            }
        }
        assertEquals(0, probe.releases, "control: nothing released while open")
        probe.inputs!!.update { 10.0 }
        collection.join()

        assertEquals(1, probe.releases, "released exactly once")
        assertSame(releaseCancellation, surfaced.single(), "the release's throwable ends the collection (stream KDoc)")
        assertTrue(
            releaseCancellation.suppressedExceptions.any { suppressed: Throwable -> suppressed === collectorFailure },
            "the collector's failure must stay attached, saw ${releaseCancellation.suppressedExceptions}",
        )
    }

    @Test
    fun `a release Error already attached to the collector failure forms no unprintable cycle and keeps both`() = runTest {
        val releaseError = AssertionError("the release's Error, already attached to the collector's failure")
        val collectorFailure = IllegalStateException("the collector's own failure").apply { addSuppressed(releaseError) }
        val probe = Probe { releaseError }
        val surfaced: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                probe.stream.collect { throw collectorFailure }
            } catch (thrown: Throwable) {
                surfaced += thrown
            }
        }
        probe.inputs!!.update { 20.0 }
        collection.join()

        assertEquals(1, probe.releases, "released exactly once")
        assertSame(releaseError, surfaced.single(), "the release's Error ends the collection")
        assertTrue(releaseError.suppressedExceptions.any { it === collectorFailure }, "the collector's failure is attached")
        val trace: String = surfaced.single().stackTraceToString()
        assertTrue(trace.contains("the collector's own failure"), "the printed trace shows the collector's failure")
        assertTrue(trace.length < 1_000_000, "a suppressed cycle must not blow up the printed trace (length=${trace.length})")
    }

    @Test
    fun `a release that throws the in-flight cancellation itself surfaces it unchanged after one release`() = runTest {
        var inFlight: CancellationException? = null
        val probe = Probe { inFlight }
        val surfaced: MutableList<Throwable> = mutableListOf()
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                probe.stream.collect { }
            } catch (thrown: Throwable) {
                surfaced += thrown
            }
        }
        val cancellation = CancellationException("the collection's cancellation")
        inFlight = cancellation
        collection.cancel(cancellation)
        collection.join()

        assertEquals(1, probe.releases, "released exactly once")
        assertTrue(surfaced.single() is CancellationException, "the collection ends cancelled, saw $surfaced")
        val all: List<Throwable> = surfaced + surfaced.flatMap { thrown: Throwable -> thrown.suppressedExceptions }
        assertTrue(all.none { it.message?.contains("Self-suppression") == true }, "no self-suppression failure, saw $all")
        assertTrue(cancellation.suppressedExceptions.none { it === cancellation }, "nothing attached to itself")
    }
}
