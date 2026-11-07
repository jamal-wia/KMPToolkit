package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

}
