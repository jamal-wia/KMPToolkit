package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Lifecycle chaos around a running attempt: an item withdrawn while its handler runs, an engine closed
 * mid-attempt, a handler that closes or re-enters its own engine, and a constraint that flaps.
 *
 * Hazard: teardown and user actions land in the middle of an attempt, in whatever order the framework
 * delivers them. Nothing may be resurrected, double-spent or wedged.
 */
class UploaderEngineChaosAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    @Test
    fun `an item withdrawn while its handler runs is not resurrected by whatever the handler then reports`() = runTest {
        val outcomes: List<AttemptResult> = listOf(
            AttemptResult.Success,
            AttemptResult.Retry(IllegalStateException("late")),
            AttemptResult.Park("late"),
            AttemptResult.Drop("late"),
            AttemptResult.Detached(60_000L),
        )
        for (outcome in outcomes) {
            val store = TestUploaderStore()
            val gate = CompletableDeferred<Unit>()
            val handler = TestHandler(onExecute = { _, _ -> gate.await(); outcome })
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
            val id: String = engine.enqueue(handler, "p")!!
            engine.start()
            runCurrent()
            assertEquals(1, handler.attempts.size, "$outcome: the attempt should be parked in the handler")

            engine.cancel(id)
            gate.complete(Unit)
            runCurrent()

            assertNull(store.find(id), "$outcome: the withdrawn item came back")
            engine.close()
        }
    }

    @Test
    fun `an engine closed mid-attempt spends no retry budget and the next engine runs the item as a first attempt`() =
        runTest {
            val store = TestUploaderStore()
            val parent = Job()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            try {
            val blocking = TestHandler(onExecute = { _, _ -> CompletableDeferred<Unit>().await(); AttemptResult.Success })
            val first: UploaderEngine = testEngine(store, listOf(blocking), scope)
            val id: String = first.enqueue(blocking, "p")!!
            first.start()
            runCurrent()
            assertEquals(1, blocking.attempts.size)

            first.close()
            advanceUntilIdle()

            val after: UploaderItem = store.find(id)!!
            assertEquals(0, after.attempts, "a cancelled attempt was charged as a failure")
            assertEquals(UploaderItemState.PENDING, after.state)

            val healthy = TestHandler()
            val second: UploaderEngine = testEngine(store, listOf(healthy), scope)
            second.start()
            runCurrent()
            assertEquals(listOf(0), healthy.attempts.map { it.attempts })
            assertEquals(emptyList(), store.items)
            second.close()
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `five hundred constraint flaps run a gated item exactly once`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = false)
        val handler = TestHandler(constraintKeys = setOf("network"))
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, constraintProviders = listOf(network))
        engine.enqueue(handler, "p")
        engine.start()
        runCurrent()
        assertEquals(0, handler.attempts.size)

        repeat(500) {
            network.set(true)
            network.set(false)
        }
        network.set(true)
        runCurrent()

        assertEquals(1, handler.attempts.size, "the item ran ${handler.attempts.size} times")
        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `a handler that closes its own engine leaves an item that a new engine finishes`() = runTest {
        val store = TestUploaderStore()
        val parent = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        try {
        lateinit var engine: UploaderEngine
        val suicidal = TestHandler(onExecute = { _, _ -> engine.close(); AttemptResult.Success })
        engine = testEngine(store, listOf(suicidal), scope)
        engine.enqueue(suicidal, "p")
        engine.start()
        advanceUntilIdle()

        assertTrue(parent.isActive)
        val healthy = TestHandler()
        val next: UploaderEngine = testEngine(store, listOf(healthy), scope)
        next.start()
        runCurrent()

        assertEquals(emptyList(), store.items, "the queue was left wedged by a close inside a handler")
        next.close()
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a handler that enqueues its own follow-ups and withdraws itself never wedges the drain`() = runTest {
        val store = TestUploaderStore()
        lateinit var engine: UploaderEngine
        lateinit var handler: TestHandler
        val seen: MutableList<String> = mutableListOf()
        handler = TestHandler(
            onExecute = { context, payload ->
                seen += payload
                val remaining: Int = payload.removePrefix("n").toInt()
                if (remaining > 0) engine.enqueue(handler, "n${remaining - 1}")
                if (remaining == 5) engine.cancel(context.id) // withdraw itself, then report Success
                AttemptResult.Success
            },
        )
        engine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "n10")
        engine.start()
        runCurrent()

        assertEquals((10 downTo 0).map { "n$it" }, seen)
        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `an engine built over an already cancelled scope persists enqueues and bounds awaitDrained`() = runTest {
        val store = TestUploaderStore()
        val dead = Job().also { it.cancel() }
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler) + dead))

        engine.start()
        val id: String? = engine.enqueue(handler, "p")
        runCurrent()

        assertTrue(id != null, "an enqueue is persisted whatever the scope's state")
        assertEquals(1, store.items.size)
        assertEquals(0, handler.attempts.size, "nothing may run in a cancelled scope")
        assertEquals(false, engine.awaitDrained(kotlin.time.Duration.parse("5s")))
        engine.close()
    }

    @Test
    fun `two hundred failure and drain cycles never grow the engine's set of coroutines`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = true)
        val handler = TestHandler(constraintKeys = setOf("network"), onExecute = { _, _ -> AttemptResult.Retry(null) })
        val clock = TestClock(0L)
        val engine: UploaderEngine = testEngine(store, listOf(handler), scope, clock = clock, constraintProviders = listOf(network))
        // The scope is not the test's background scope, so a failed assertion would leave the engine's
        // heartbeat for runTest's cleanup to advance forever: always tear it down, whatever happens.
        try {
            engine.start()
            engine.enqueue(handler, "first")
            runCurrent()
            // One heartbeat, one drain, one constraint collector, and the one alarm armed by the failed attempt.
            val baseline: Int = parent.children.single().children.count()
            assertEquals(4, baseline, "unexpected engine shape: ${parent.children.single().children.toList()}")

            repeat(200) { cycle ->
                engine.enqueue(handler, "p$cycle")
                clock.millis += 10_000L // every earlier gate has passed, so each pass retries them all and re-arms the alarm
                engine.trigger()
                runCurrent()
                network.set(false)
                network.set(true)
                runCurrent()
                assertEquals(baseline, parent.children.single().children.count(), "cycle $cycle: the engine accumulated coroutines")
            }
        } finally {
            engine.close()
            parent.cancel()
        }
        runCurrent()
        assertEquals(0, parent.children.count())
    }

    @Test
    fun `awaitDrained survives one transient store failure instead of throwing into the wake job`() = runTest {
        val store = TestUploaderStore()
        val engine: UploaderEngine = testEngine(store, listOf(TestHandler()), backgroundScope)
        store.failNextGetAllActive = IllegalStateException("database is locked")

        val result: Result<Boolean> = runCatching { engine.awaitDrained(10.seconds) }

        assertTrue(
            result.getOrNull() == true,
            "awaitDrained threw ${result.exceptionOrNull()} on a single transient read failure with an empty queue",
        )
    }

    @Test
    fun `awaitDrained reports false at the timeout when the store keeps failing and does not throw`() = runTest {
        val store = object : io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore by TestUploaderStore() {
            override suspend fun getAllActive(): List<UploaderItem> = throw IllegalStateException("database is locked")
        }
        val engine: UploaderEngine = testEngine(store, listOf(TestHandler()), backgroundScope)

        val result: Result<Boolean> = runCatching { engine.awaitDrained(3.seconds) }

        assertEquals(false, result.getOrNull(), "a persistently failing store means not drained, got $result")
    }

    @Test
    fun `awaitDrained with a zero timeout on an empty queue reports it drained`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)

        assertTrue(engine.awaitDrained(kotlin.time.Duration.ZERO), "an empty queue is drained whatever the remaining budget")
    }

    @Test
    fun `awaitDrained with a zero timeout on a non-empty queue reports it not drained`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "owed")

        assertEquals(false, engine.awaitDrained(kotlin.time.Duration.ZERO))
    }

    @Test
    fun `a faulty constraint provider does not starve the items of unrelated handlers`() = runTest {
        val store = TestUploaderStore()
        val faulty = object : io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider {
            override val key: String = "faulty"
            override val satisfied: kotlinx.coroutines.flow.StateFlow<Boolean> = object : kotlinx.coroutines.flow.StateFlow<Boolean> {
                override val value: Boolean get() = throw IllegalStateException("provider is broken")
                override val replayCache: List<Boolean> get() = emptyList()
                override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Boolean>): Nothing =
                    kotlinx.coroutines.awaitCancellation()
            }
        }
        val gated = TestHandler(type = "gated", constraintKeys = setOf("faulty"))
        val plain = TestHandler(type = "plain")
        val engine: UploaderEngine = testEngine(store, listOf(gated, plain), backgroundScope, constraintProviders = listOf(faulty))
        engine.enqueue(gated, "first") // enqueued first, so the drain reaches it first
        engine.enqueue(plain, "second")
        engine.start()
        runCurrent()

        assertEquals(listOf("second"), plain.payloads, "an unrelated handler's item starved behind a throwing provider")
        assertEquals(emptyList(), gated.payloads, "an item whose constraint cannot be read is not attempted")
        assertEquals(1, store.items.size, "the gated item stays queued, nothing is deleted silently")
    }
}
