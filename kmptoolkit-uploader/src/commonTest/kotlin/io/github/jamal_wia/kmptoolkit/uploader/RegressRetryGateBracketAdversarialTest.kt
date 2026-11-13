package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * RG2 of the A1 refactor-regression round: after a store write that commits and then throws, a retry starts no earlier
 * than the later of its two gates and no later than that gate plus the alarm floor and one clock slice.
 *
 * The two gates are the persisted one (the row's `nextRunAtEpochMillis`, written by the failed `recordFailure` itself:
 * the retry policy's delay after the failure) and the in-memory one that a failure outside the handler arms (the time
 * of that failure plus the engine's growing backoff, `minAlarmDelay` doubling per consecutive failure of the same
 * item). With a 300 ms policy and a 50 ms floor the first failure arms 50 ms, below the persisted 300 ms, and the
 * fourth arms 400 ms, above it (the later gate wins and the engine re-arms its alarm for it).
 *
 * Deterministic by construction: virtual time, a hand-driven clock synced to it in 5 ms slices, a failure counter in
 * place of any randomness, a heartbeat of one hour so that only the gates start a retry.
 */
class RegressRetryGateBracketAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    /** Commits every `recordFailure`, then throws from the first [failures] of them, noting the clock at each throw. */
    private class CommitThenThrowStore(
        private val inner: TestUploaderStore,
        private val clock: TestClock,
        private val failures: Int,
    ) : UploaderStore by inner {
        val throwTimes: MutableList<Long> = mutableListOf()

        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean {
            val result: Boolean = inner.recordFailure(
                id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition,
            )
            if (throwTimes.size < failures) {
                throwTimes += clock.millis
                throw IllegalStateException("committed then dropped")
            }
            return result
        }
    }

    private class Start(val at: Long, val persistedGate: Long)

    private suspend fun TestScope.slice(clock: TestClock) {
        advanceTimeBy(SLICE_MILLIS.milliseconds)
        clock.millis = START_MILLIS + testScheduler.currentTime
        runCurrent()
    }

    /** Runs one plain item that always answers `Retry` over a store failing [failures] times in a row, and checks every retry start. */
    private fun bracket(failures: Int, expectOutsideBinding: Boolean?) = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(START_MILLIS)
        val spy = CommitThenThrowStore(store, clock, failures)
        val starts: MutableList<Start> = mutableListOf()
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = POLICY_MILLIS),
            onExecute = { context, _ ->
                starts += Start(clock.millis, store.find(context.id)?.nextRunAtEpochMillis ?: 0L)
                AttemptResult.Retry(IllegalStateException("again"))
            },
        )
        val config = UploaderConfig(heartbeatInterval = 1.hours)
        val engine: UploaderEngine = testEngine(store = spy, handlers = listOf(handler), scope = backgroundScope, clock = clock, config = config)
        try {
            engine.start()
            assertNotNull(engine.enqueue(handler, "payload"))
            runCurrent()
            var slices = 0
            while (starts.size < failures + EXTRA_STARTS && slices++ < MAX_SLICES) slice(clock)
            assertEquals(failures + EXTRA_STARTS, starts.size, "the retries did not reach start number ${failures + EXTRA_STARTS} within $MAX_SLICES slices")
            assertEquals(failures, spy.throwTimes.size, "every one of the $failures store failures must have been hit")

            val floor: Long = config.minAlarmDelay.inWholeMilliseconds
            var outsideBound = false
            for (index in 1 until starts.size) {
                val start: Start = starts[index]
                // The k-th consecutive failure of this item (k = index while the store keeps failing) armed its
                // outside gate at its own throw time; an attempt that wrote cleanly cleared it.
                val outsideGate: Long? = if (index <= failures) {
                    spy.throwTimes[index - 1] + minOf(floor shl (index - 1), config.heartbeatInterval.inWholeMilliseconds)
                } else {
                    null
                }
                val bound: Long = maxOf(start.persistedGate, outsideGate ?: Long.MIN_VALUE)
                if (outsideGate != null && outsideGate > start.persistedGate) outsideBound = true
                assertTrue(
                    start.at >= bound,
                    "retry $index of $failures started at ${start.at}, before its gate $bound (persisted ${start.persistedGate}, outside $outsideGate)",
                )
                assertTrue(
                    start.at <= bound + floor + SLICE_MILLIS,
                    "retry $index of $failures started at ${start.at}, more than the alarm floor and one slice after its gate $bound (persisted ${start.persistedGate}, outside $outsideGate)",
                )
            }
            if (expectOutsideBinding != null) {
                assertEquals(expectOutsideBinding, outsideBound, "the outside gate must ${if (expectOutsideBinding) "" else "not "}exceed the persisted gate at some start of this scenario")
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `retries without a store failure start at their persisted gate`() = bracket(failures = 0, expectOutsideBinding = null)

    @Test
    fun `one committed store failure with an outside gate below the persisted gate starts at the persisted gate`() =
        bracket(failures = 1, expectOutsideBinding = false)

    @Test
    fun `four committed store failures with an outside gate above the persisted gate start at the outside gate`() =
        bracket(failures = 4, expectOutsideBinding = true)

    private companion object {
        const val START_MILLIS: Long = 1_000L
        const val POLICY_MILLIS: Long = 300L
        const val SLICE_MILLIS: Long = 5L
        const val EXTRA_STARTS: Int = 3
        const val MAX_SLICES: Int = 4_000
    }
}
