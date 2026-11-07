package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
            parent.cancel()
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
        parent.cancel()
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
}
