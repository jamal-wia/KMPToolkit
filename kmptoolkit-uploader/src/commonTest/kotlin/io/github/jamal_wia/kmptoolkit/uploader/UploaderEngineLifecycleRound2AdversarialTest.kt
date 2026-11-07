package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Lifecycle chaos, round two: an engine whose owning scope is cancelled without `close()`, a clock that
 * steps back while a delivery is detached under a lease, and the registry that hands engines to the OS.
 *
 * Every test owns its parent job and cancels it in a `finally`: the engine's heartbeat never idles, so a
 * failed assertion must not leave it to the runner's cleanup. No `advanceUntilIdle` anywhere.
 */
class UploaderEngineLifecycleRound2AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun scopeOf(scheduler: TestCoroutineScheduler, parent: Job): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(scheduler) + parent)

    @Test
    fun `a scope cancelled engine does not evict its live successor and a late close of it changes nothing`() =
        runTest(timeout = 30.seconds) {
            val oldParent = Job()
            val newParent = Job()
            try {
                val store = TestUploaderStore()
                val old: UploaderEngine = testEngine(store, listOf(TestHandler()), scopeOf(testScheduler, oldParent))
                old.start()
                UploaderEngineRegistry.register(old)
                oldParent.cancel()
                runCurrent()

                val successor: UploaderEngine = testEngine(store, listOf(TestHandler()), scopeOf(testScheduler, newParent))
                successor.start()
                UploaderEngineRegistry.register(successor)
                runCurrent()
                old.close() // the owner finally gets around to it

                assertSame(successor, UploaderEngineRegistry.current)
                assertSame(successor, UploaderEngineRegistry.await(1.seconds))
            } finally {
                oldParent.cancel()
                newParent.cancel()
            }
        }

    @Test
    fun `an enqueue on an engine whose scope was cancelled still persists and arms the wake and awaitDrained is bounded`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val store = TestUploaderStore()
                val wake = TestWakeScheduler()
                val handler = TestHandler()
                val engine: UploaderEngine =
                    testEngine(store, listOf(handler), scopeOf(testScheduler, parent), wakeScheduler = wake)
                engine.start()
                runCurrent()
                parent.cancel()
                runCurrent()

                val id: String? = engine.enqueue(handler, "after-the-scope-died")
                runCurrent()

                assertEquals(listOf(id), store.items.map { it.id }, "the row must be persisted: the store is independent")
                assertTrue(wake.armed, "the platform wake must be armed for the persisted row")
                assertEquals(emptyList(), handler.payloads, "nothing may run on a dead engine")
                val before: Long = testScheduler.currentTime
                assertFalse(engine.awaitDrained(5.seconds), "an undrained queue must report false at the timeout")
                assertTrue(testScheduler.currentTime - before <= 5_500L, "awaitDrained overran its timeout")
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `control - without a clock step the same detached item is re-handed once its lease expires`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val clock = TestClock(100 * 86_400_000L)
                val store = TestUploaderStore()
                val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(60_000L) })
                val first: UploaderEngine =
                    testEngine(store, listOf(handler), scopeOf(testScheduler, parent), clock = clock)
                first.start()
                first.enqueue(handler, "payload")
                runCurrent()
                first.close()
                val second: UploaderEngine =
                    testEngine(store, listOf(handler), scopeOf(testScheduler, parent), clock = clock)
                second.start()
                runCurrent()
                repeat(20) {
                    advanceTimeBy(30_000L)
                    clock.millis += 30_000L
                    runCurrent()
                }
                assertTrue(handler.attempts.size >= 2, "the lease never expired: ${handler.attempts.size} attempts")
                assertTrue(handler.attempts.last().wasDetached)
                second.close()
            } finally {
                parent.cancel()
            }
        }


    @Test
    fun `an engine whose parent scope was cancelled without close is no longer handed out by the registry`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val engine: UploaderEngine =
                    testEngine(TestUploaderStore(), listOf(TestHandler()), scopeOf(testScheduler, parent))
                engine.start()
                UploaderEngineRegistry.register(engine)
                runCurrent()
                assertSame(engine, UploaderEngineRegistry.current)

                parent.cancel() // the owning scope goes away (a cleared ViewModel, a destroyed process-bound scope)
                runCurrent()

                assertNull(
                    UploaderEngineRegistry.current,
                    "the registry still hands out an engine whose scope is cancelled: it drains nothing, ever",
                )
                assertNull(UploaderEngineRegistry.await(1.seconds), "a wake job would wait on a dead engine")
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `registering an engine whose scope is already cancelled does not install it`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val engine: UploaderEngine =
                    testEngine(TestUploaderStore(), listOf(TestHandler()), scopeOf(testScheduler, parent))
                parent.cancel()
                engine.start()
                UploaderEngineRegistry.register(engine)
                runCurrent()

                assertNull(UploaderEngineRegistry.current, "a dead engine was installed into the process-wide slot")
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `an item detached under a lease before the wall clock stepped back is re-handed within a lease and not after the whole jump`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val day = 86_400_000L
                val clock = TestClock(100 * day)
                val store = TestUploaderStore()
                val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(60_000L) })
                val first: UploaderEngine =
                    testEngine(store, listOf(handler), scopeOf(testScheduler, parent), clock = clock)
                first.start()
                first.enqueue(handler, "payload")
                runCurrent()
                assertEquals(1, handler.attempts.size)
                assertEquals(UploaderItemState.IN_FLIGHT, store.items.single().state)
                first.close() // the process dies while the executor holds the claim and never reports back

                clock.millis -= 10 * day // the user (or a bad RTC after boot) corrects the clock ten days back
                val second: UploaderEngine =
                    testEngine(store, listOf(handler), scopeOf(testScheduler, parent), clock = clock)
                second.start()
                runCurrent()
                // Ten real minutes pass: the wall clock ticks along with them.
                repeat(20) {
                    advanceTimeBy(30_000L)
                    clock.millis += 30_000L
                    runCurrent()
                }

                // At least one re-hand: the item is not frozen for the jump. (It is then re-handed once per
                // lease like the control test's, so the exact count is not the contract.)
                assertTrue(
                    handler.attempts.size >= 2,
                    "a lease written before a backwards clock step froze the item (and its channel) for the whole jump: ${handler.attempts.size} attempt(s)",
                )
                second.close()
            } finally {
                parent.cancel()
            }
        }


    private object NoCeilingHourlyPolicy : RetryPolicy {
        override val maxDelayMillis: Long = Long.MAX_VALUE // "no ceiling": positive, so within the contract
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 3_600_000L
    }

    @Test
    fun `a retry policy with no ceiling still gets its backoff gate honored`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val clock = TestClock(1_000_000L)
                val store = TestUploaderStore()
                var calls = 0
                val handler = TestHandler(
                    retryPolicy = NoCeilingHourlyPolicy,
                    // Bounded by construction: whatever the engine does, the 200th call parks the item.
                    onExecute = { _, _ -> if (++calls >= 200) AttemptResult.Park("test bound") else AttemptResult.Retry(null) },
                )
                val engine: UploaderEngine =
                    testEngine(store, listOf(handler), scopeOf(testScheduler, parent), clock = clock)
                engine.enqueue(handler, "payload")
                engine.start()
                runCurrent()
                repeat(10) {
                    advanceTimeBy(6_000L)
                    clock.millis += 6_000L
                    runCurrent()
                }

                assertEquals(1, handler.attempts.size, "the one-hour backoff gate was ignored: ${handler.attempts.size} attempts in one minute")
                engine.close()
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `an effectively unbounded lease holds its item in flight and is not re-handed on every pass`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val clock = TestClock(1_000_000L)
                val store = TestUploaderStore()
                var calls = 0
                val handler = TestHandler(
                    // Bounded by construction: whatever the engine does, the 200th call parks the item.
                    onExecute = { _, _ -> if (++calls >= 200) AttemptResult.Park("test bound") else AttemptResult.Detached(Long.MAX_VALUE) },
                )
                val engine: UploaderEngine =
                    testEngine(store, listOf(handler), scopeOf(testScheduler, parent), clock = clock)
                engine.enqueue(handler, "payload")
                engine.start()
                runCurrent()
                repeat(10) {
                    advanceTimeBy(6_000L)
                    clock.millis += 6_000L
                    runCurrent()
                }

                assertEquals(1, handler.attempts.size, "a lease of Long.MAX_VALUE overflowed and the item was re-handed ${handler.attempts.size} times")
                engine.close()
            } finally {
                parent.cancel()
            }
        }


    @Test
    fun `a zero wait still finds an engine that is already registered`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val engine: UploaderEngine =
                    testEngine(TestUploaderStore(), listOf(TestHandler()), scopeOf(testScheduler, parent))
                engine.start()
                UploaderEngineRegistry.register(engine)
                runCurrent()

                assertSame(engine, UploaderEngineRegistry.await(1.seconds), "control: a positive wait finds it")
                assertSame(engine, UploaderEngineRegistry.await(Duration.ZERO), "a zero wait reported no engine although one is registered")
                assertEquals(
                    UploadAttempt.NothingOwed,
                    UploadGateway.prepareAttempt("no-such-item", Duration.ZERO),
                    "the gateway read a registered engine as unavailable because its wait budget was zero",
                )
            } finally {
                parent.cancel()
            }
        }


    private class ThrowingSinkLogger : Logger {
        override val tag: String = "throwing"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            if (level >= LogLevel.WARN) throw IllegalStateException("the crash reporter is not initialised")
        }
    }

    @Test
    fun `a logging sink that throws on errors does not kill the drain for good`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val store = TestUploaderStore()
                val handler = TestHandler()
                val engine: UploaderEngine = createUploaderEngine(
                    store = store,
                    handlers = listOf(handler),
                    scope = scopeOf(testScheduler, parent),
                    logger = ThrowingSinkLogger(),
                    clock = TestClock(),
                )
                store.failNextGetAllActive = RuntimeException("database is locked") // the first pass fails and logs an error
                engine.start()
                runCurrent()

                engine.enqueue(handler, "after-the-failure")
                runCurrent()
                advanceTimeBy(31_000L) // and the heartbeat has had its say as well
                runCurrent()

                assertEquals(listOf("after-the-failure"), handler.payloads, "the drain coroutine died on the logger's exception and never ran again")
                engine.close()
            } finally {
                parent.cancel()
            }
        }
}
