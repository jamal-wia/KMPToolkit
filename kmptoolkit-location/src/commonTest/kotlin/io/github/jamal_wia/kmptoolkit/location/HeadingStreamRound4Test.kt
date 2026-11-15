@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Round 4 of group loc-heading (decision D-23, finding m1 of breaker round 3): what [headingStream] ends with
 * when the release of its registration throws while the collection is already ending in some way.
 *
 * The rule, from the stream's KDoc: the stream always finishes its release; when `close()` throws, that
 * throwable is what the collection ends with, and the collection's own end (a failing collector, a failing
 * compose, cancellation, the abort of `first()`) rides along as suppressed, so neither is lost. A release
 * that throws the very same instance as the collection's failure is not wrapped in itself. A release that
 * does not throw changes nothing about how the collection ends.
 *
 * Deterministic: unconfined collector, synchronous writes, one seeded run with `seed=` in every message.
 */
class HeadingStreamRound4Test {

    private enum class End { COLLECTOR_FAILS, COMPOSE_FAILS, CANCELLED, FIRST }

    private enum class Close { OK, EXCEPTION, ERROR, SAME_AS_FAILURE }

    /** What one collection ended with: [terminal] is what the collecting coroutine caught, `null` if it returned. */
    private class Outcome(val terminal: Throwable?, val releases: Int, val received: Int, val boom: Throwable, val closeFailure: Throwable?)

    private fun Throwable.chain(): List<Throwable> = listOf(this) + suppressedExceptions.flatMap { suppressed: Throwable -> suppressed.chain() }

    private suspend fun TestScope.collect(end: End, close: Close): Outcome {
        var inputs: HeadingInputs<Double>? = null
        var releases = 0
        var received = 0
        val boom = IllegalStateException("the collection's own failure")
        val closeFailure: Throwable? = when (close) {
            Close.OK -> null
            Close.EXCEPTION -> IllegalArgumentException("the release's exception")
            Close.ERROR -> AssertionError("the release's Error")
            Close.SAME_AS_FAILURE -> boom
        }
        val stream = headingStream(
            minChangeDegrees = 0.0,
            initial = { Double.NaN },
            compose = { magnetic: Double ->
                if (magnetic.isNaN()) {
                    null
                } else {
                    if (magnetic == COMPOSE_FAILS_AT) throw boom
                    Heading(magnetic, null, null, false)
                }
            },
            register = { registered: HeadingInputs<Double> ->
                inputs = registered
                AutoCloseable {
                    releases++
                    if (closeFailure != null) throw closeFailure
                }
            },
        )
        var terminal: Throwable? = null
        val collection: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                when (end) {
                    End.COLLECTOR_FAILS -> stream.collect { throw boom }
                    End.COMPOSE_FAILS -> stream.collect { received++ }
                    End.CANCELLED -> stream.collect { received++ }
                    End.FIRST -> {
                        stream.first()
                        received++
                    }
                }
            } catch (thrown: Throwable) {
                terminal = thrown
            }
        }
        val registered: HeadingInputs<Double> = checkNotNull(inputs) { "control: the collection registered" }
        assertEquals(0, releases, "control: nothing released while the collection is open ($end, $close)")
        when (end) {
            End.COLLECTOR_FAILS, End.FIRST -> registered.update { 42.0 }
            End.COMPOSE_FAILS -> registered.update { COMPOSE_FAILS_AT }
            End.CANCELLED -> collection.cancel()
        }
        collection.join()
        return Outcome(terminal, releases, received, boom, closeFailure)
    }

    @Test
    fun `a release Error while the collector fails ends with the Error and keeps the failure suppressed`() = runTest {
        val outcome: Outcome = collect(End.COLLECTOR_FAILS, Close.ERROR)

        assertEquals(1, outcome.releases, "control: released exactly once")
        assertSame(outcome.closeFailure, outcome.terminal, "the release's Error is what the collection ends with")
        assertTrue(outcome.terminal!!.suppressedExceptions.any { it === outcome.boom }, "the collector's failure is suppressed on it")
    }

    @Test
    fun `a release Error while a compose fails keeps both reachable`() = runTest {
        val outcome: Outcome = collect(End.COMPOSE_FAILS, Close.ERROR)

        assertEquals(1, outcome.releases, "control: released exactly once")
        assertSame(outcome.closeFailure, outcome.terminal)
        assertTrue(outcome.terminal!!.chain().any { it === outcome.boom }, "the compose failure stays reachable")
    }

    @Test
    fun `a release Error under cancellation is not hidden and the cancellation stays reachable`() = runTest {
        val outcome: Outcome = collect(End.CANCELLED, Close.ERROR)

        assertEquals(1, outcome.releases, "control: released exactly once")
        assertSame(outcome.closeFailure, outcome.terminal, "the Error surfaces instead of vanishing under the cancellation")
        assertTrue(outcome.terminal!!.suppressedExceptions.isNotEmpty(), "the cancellation that ended the collection is attached")
    }

    @Test
    fun `a release Error under first still reaches the caller with the abort attached`() = runTest {
        val outcome: Outcome = collect(End.FIRST, Close.ERROR)

        assertEquals(1, outcome.releases, "control: released exactly once")
        assertSame(outcome.closeFailure, outcome.terminal, "first() would otherwise hide the Error behind its own return")
        assertTrue(outcome.terminal!!.suppressedExceptions.isNotEmpty(), "the abort that ended the collection is attached")
    }

    @Test
    fun `a release exception is treated like an Error and loses nothing`() = runTest {
        val outcome: Outcome = collect(End.COLLECTOR_FAILS, Close.EXCEPTION)

        assertEquals(1, outcome.releases, "control: released exactly once")
        assertSame(outcome.closeFailure, outcome.terminal)
        assertTrue(outcome.terminal!!.suppressedExceptions.any { it === outcome.boom })
    }

    @Test
    fun `a release that throws the failure's own instance does not suppress it into itself`() = runTest {
        val outcome: Outcome = collect(End.COLLECTOR_FAILS, Close.SAME_AS_FAILURE)

        assertEquals(1, outcome.releases, "control: released exactly once")
        assertSame(outcome.boom, outcome.terminal, "the one throwable, unchanged: a self-suppression would throw IllegalArgumentException")
        assertTrue(outcome.terminal!!.suppressedExceptions.isEmpty(), "nothing attached to itself")
    }

    @Test
    fun `a release that does not throw leaves every ending exactly as it was`() = runTest {
        val failing: Outcome = collect(End.COLLECTOR_FAILS, Close.OK)
        assertSame(failing.boom, failing.terminal, "the collector's failure surfaces unchanged")
        assertTrue(failing.terminal!!.suppressedExceptions.isEmpty())
        assertEquals(1, failing.releases)

        val composing: Outcome = collect(End.COMPOSE_FAILS, Close.OK)
        assertSame(composing.boom, composing.terminal)
        assertEquals(1, composing.releases)

        val cancelled: Outcome = collect(End.CANCELLED, Close.OK)
        assertTrue(cancelled.terminal is kotlin.coroutines.cancellation.CancellationException, "cancellation stays a cancellation")
        assertEquals(1, cancelled.releases)

        val first: Outcome = collect(End.FIRST, Close.OK)
        assertNull(first.terminal, "first() returns normally")
        assertEquals(1, first.received)
        assertEquals(1, first.releases)
    }

    @Test
    fun `a seeded run over every ending and every release behaviour follows the rule`() = runTest {
        val seed: Int = HEADING_TEST_SEED + 44
        val random = Random(seed)
        val ends: List<End> = End.entries
        val closes: List<Close> = Close.entries
        repeat(RUNS) { run: Int ->
            val end: End = ends[random.nextInt(ends.size)]
            val close: Close = closes[random.nextInt(closes.size)]
            // A release that throws the failure's own instance is only meaningful when there is a failure.
            if (close == Close.SAME_AS_FAILURE && (end == End.CANCELLED || end == End.FIRST)) return@repeat
            val label = "seed=$seed run=$run end=$end close=$close"

            val outcome: Outcome = collect(end, close)

            assertEquals(1, outcome.releases, "$label: released exactly once")
            val terminal: Throwable? = outcome.terminal
            when (close) {
                Close.OK -> when (end) {
                    End.COLLECTOR_FAILS, End.COMPOSE_FAILS -> assertSame(outcome.boom, terminal, "$label: the failure surfaces unchanged")
                    End.CANCELLED -> assertTrue(terminal is kotlin.coroutines.cancellation.CancellationException, "$label: a cancellation")
                    End.FIRST -> assertNull(terminal, "$label: first() returns")
                }
                Close.EXCEPTION, Close.ERROR -> {
                    assertSame(outcome.closeFailure, terminal, "$label: the release's throwable is the ending")
                    val chain: List<Throwable> = terminal!!.chain()
                    when (end) {
                        End.COLLECTOR_FAILS, End.COMPOSE_FAILS -> assertTrue(chain.any { it === outcome.boom }, "$label: the failure is attached")
                        End.CANCELLED, End.FIRST -> assertTrue(terminal.suppressedExceptions.isNotEmpty(), "$label: the ending of the collection is attached")
                    }
                }
                Close.SAME_AS_FAILURE -> {
                    assertSame(outcome.boom, terminal, "$label: the one throwable, unchanged")
                    assertTrue(terminal!!.suppressedExceptions.isEmpty(), "$label: not attached to itself")
                }
            }
        }
    }

    private companion object {
        const val COMPOSE_FAILS_AT: Double = 13.0
        const val RUNS: Int = 120
    }
}
