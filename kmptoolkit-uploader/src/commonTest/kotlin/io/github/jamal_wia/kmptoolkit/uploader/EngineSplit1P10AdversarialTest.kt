package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The tests the A1 engine split (step 1) adds to the boss round 4 suspects for rule P10 (a fact outranks a verdict): the
 * verdict paths that suspect did not reach and the order of the drain's checks for a row held as delivered.
 */
class EngineSplit1P10AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Fails the next [deleteFailures] deletes: a locked database. */
    private class FlakyDeleteStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var deleteFailures: Int = 0
        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    @Test
    fun `P10 a Failed settle that gives up with a park does not bury a delivery whose delete failed`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = TestHandler(
                type = "split1.failed",
                retryPolicy = FixedRetryPolicy(giveUp = GiveUpPolicy.ParkAfterAttempts(1)),
                onExecute = { context, _ ->
                    contexts += context
                    AttemptResult.Detached(60_000L)
                },
            )
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("split1a")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            engine.drain()
            clock.millis += 61_000L
            engine.drain()
            assertEquals(2, contexts.size, "setup: two executors hold claims of the item")

            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { engine.settle(id, contexts[0].claim, SettleResult.Delivered) }
            engine.settle(id, contexts[1].claim, SettleResult.Failed(RuntimeException("the current executor failed")))
            clock.millis += 61_000L
            engine.drain()
            engine.drain()

            assertEquals(2, handler.payloads.size, "nothing ran again")
            assertEquals(null, inner.find(id)?.state, "the delivered row is deleted, not parked by the later give-up")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `P10 a row held as delivered is confirmed by an engine that has lost its handler instead of being parked as an orphan`() = runTest {
        val first = Job()
        val second = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val clock = TestClock(3_000_000L)
            val handler = TestHandler(type = "split1.gone")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(first), clock, idGenerator = SequentialIds("split1b")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            store.deleteFailures = 1
            engine.drain()
            assertEquals(1, handler.payloads.size, "setup: the effect ran and its delete failed")
            assertNotNull(inner.find(id), "setup: the row is still there, held as delivered")
            first.cancel()
            clock.millis += 61_000L

            val replacement: UploaderEngine = testEngine(store, listOf(TestHandler(type = "split1.other")), scopeOn(second), clock)
            replacement.drain()

            assertEquals(null, inner.find(id)?.state, "the replacement confirms the delivery; it does not park it as an orphan")
            assertEquals(1, handler.payloads.size, "the effect never ran again")
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun `P10 a row held as delivered is confirmed by an engine whose handler has an older schema instead of being parked`() = runTest {
        val first = Job()
        val second = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val clock = TestClock(3_000_000L)
            val handler = TestHandler(type = "split1.schema", schemaVersion = 2)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(first), clock, idGenerator = SequentialIds("split1c")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            store.deleteFailures = 1
            engine.drain()
            assertNotNull(inner.find(id), "setup: the row is still there, held as delivered")
            first.cancel()
            clock.millis += 61_000L

            val older = TestHandler(type = "split1.schema", schemaVersion = 1)
            val replacement: UploaderEngine = testEngine(store, listOf(older), scopeOn(second), clock)
            replacement.drain()

            assertEquals(null, inner.find(id)?.state, "the delivery is confirmed, not parked as coming from a newer schema")
            assertEquals(0, older.payloads.size, "the older handler never ran it")
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun `P10 a row held as delivered behind an unmet constraint is confirmed in the same drain`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val clock = TestClock(3_000_000L)
            val network = TestConstraint("split1.network", satisfied = true)
            val handler = TestHandler(type = "split1.constrained", constraintKeys = setOf("split1.network"))
            val engine: UploaderEngine = testEngine(
                store, listOf(handler), scopeOn(parent), clock,
                constraintProviders = listOf(network), idGenerator = SequentialIds("split1d")::next,
            )
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            store.deleteFailures = 1
            engine.drain()
            assertNotNull(inner.find(id), "setup: the row is still there, held as delivered")
            network.set(false)
            clock.millis += 61_000L

            engine.drain()

            assertEquals(null, inner.find(id)?.state, "a delete needs no network: the delivery is confirmed while the constraint is unmet")
            assertEquals(1, handler.payloads.size, "the effect never ran again")
        } finally {
            parent.cancel()
        }
    }
}
