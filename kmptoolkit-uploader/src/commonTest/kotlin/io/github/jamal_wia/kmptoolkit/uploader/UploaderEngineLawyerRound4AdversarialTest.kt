package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Round 4 (contract-lawyer): the KDoc of `close`, `enqueue` and the handler contract, read literally. */
class UploaderEngineLawyerRound4AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class CountingStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var reads = 0
        override suspend fun getAllActive(): List<UploaderItem> {
            reads++
            return inner.getAllActive()
        }
    }

    @Test
    fun `close stops the heartbeat, the pending alarm and every later read of the queue`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        val inner = TestUploaderStore()
        val store = CountingStore(inner)
        val clock = TestClock(1_000L)
        val handler = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 60_000L), onExecute = { _, _ -> AttemptResult.Retry(null) })
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent),
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            engine.enqueue(handler, "gated")
            runCurrent()
            advanceTimeBy(1.seconds) // the attempt failed, a gate and an alarm are pending
            engine.close()
            runCurrent()
            val readsAtClose: Int = store.reads

            clock.millis += 3.hours.inWholeMilliseconds
            advanceTimeBy(2.hours)

            assertEquals(readsAtClose, store.reads, "a closed engine kept reading the queue (heartbeat or alarm alive)")
            assertEquals(0, parent.children.count(), "a closed engine kept a child job")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `enqueue on a closed engine persists and arms the wake and nothing runs`() = runTest(timeout = 30.seconds) {
        val store = TestUploaderStore()
        val wake = TestWakeScheduler()
        val handler = TestHandler()
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            wakeScheduler = wake,
            clock = TestClock(1_000L),
            idGenerator = SequentialIds()::next,
        )
        engine.start()
        runCurrent()
        engine.close()

        val id: String? = engine.enqueue(handler, "after-close")
        advanceTimeBy(5.seconds)

        assertNotNull(id)
        assertEquals(1, store.items.size, "the row must persist")
        assertTrue(wake.armed, "the wake must be armed for the persisted debt")
        assertEquals(emptyList(), handler.payloads, "a closed engine must not run anything")
    }

    @Test
    fun `an alarm storm over a gated queue leaves a constant number of child jobs`() = runTest(timeout = 60.seconds) {
        val parent = Job()
        val store = TestUploaderStore()
        val handler = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 3_600_000L), onExecute = { _, _ -> AttemptResult.Retry(null) })
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent),
            clock = TestClock(1_000L),
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            engine.enqueue(handler, "gated")
            runCurrent()
            val engineJob: Job = parent.children.single()
            val baseline: Int = engineJob.children.count()
            repeat(2_000) {
                engine.enqueue(handler, "x$it", uniqueKey = "k$it")
                runCurrent()
            }
            val during: Int = engineJob.children.count()

            assertTrue(during <= baseline + 1, "child jobs grew from $baseline to $during over a gated queue")
            engine.close()
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `an ordering key is derived once at enqueue however often the item is retried`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(1_000L)
        var derivations = 0
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 100L),
            ordering = { derivations++; "chan" },
            onExecute = { context, _ -> if (context.attempts < 3) AttemptResult.Retry(null) else AttemptResult.Success },
        )
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.enqueue(handler, "p")
            repeat(5) {
                engine.drain()
                clock.millis += 1_000L
            }

            assertEquals(1, derivations, "orderingKey is called once at enqueue and persisted")
            assertEquals(4, handler.payloads.size)
        } finally {
            engine.close()
        }
    }
}
