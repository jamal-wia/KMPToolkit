package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 1: two documented promises read together, and the rulings that followed.
 *
 * The engine KDoc and guide 03 promise that "within one process an effect whose handler succeeded but whose row could
 * not be deleted is never run again". The close KDoc and P6 describe a replacement engine started in the same process
 * after close() (P3 even keeps its claim book, [ClaimsInDoubt], per process for exactly that case). These tests run the
 * delivered-but-unconfirmed effect through such a replacement (decision D-29: the memory is per process).
 */
class EngineBossRound1AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    /** Fails the next [failures] confirming deletes as a locked database would: nothing written. */
    private class FlakyDeleteStore(private val inner: TestUploaderStore, var failures: Int) : UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            if (failures > 0) {
                failures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    @Test
    fun `control - the engine that saw the delivery retries only the delete`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock)
            val id: String = engine.enqueue(handler, "send-message")!!
            engine.drain()
            assertEquals(1, handler.payloads.size)
            clock.millis += 60_000L
            engine.drain()
            assertEquals(1, handler.payloads.size, "the same engine ran the delivered effect again")
            assertNull(inner.find(id), "the confirming delete was never retried")
        } finally {
            parent.cancel()
        }
    }

    private fun TestScope.engineOn(
        store: UploaderStore,
        handler: TestHandler,
        parent: Job,
        clock: TestClock,
        ids: SequentialIds? = null,
    ): UploaderEngine =
        if (ids == null) testEngine(store, listOf(handler), scopeOn(parent), clock)
        else testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = ids::next)

    @Test
    fun `a replacement engine in the same process does not run an effect whose delete failed again`() = runTest {
        val parent1 = Job()
        val parent2 = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent1), clock)
            val id: String = first.enqueue(handler, "send-message")!!
            first.drain()
            assertEquals(1, handler.payloads.size, "setup: the effect ran once and its delete failed")
            first.close() // the owner rebuilds its engine, say after a logout; the process lives on

            clock.millis += 60_000L
            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent2), clock)
            second.drain()
            assertEquals(
                1,
                handler.payloads.size,
                "within one process the effect whose confirming delete failed ran again (in the replacement engine)",
            )
            assertNull(inner.find(id), "the replacement never confirmed the delivery")
        } finally {
            parent1.cancel()
            parent2.cancel()
        }
    }

    @Test
    fun `a delete that fails in two engines in a row is confirmed by the third without a second run`() = runTest {
        val parents: List<Job> = List(3) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 2)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val ids = SequentialIds("s1a")
            val first: UploaderEngine = engineOn(store, handler, parents[0], clock, ids)
            val id: String = first.enqueue(handler, "send-message")!!
            first.drain()
            first.close()
            val second: UploaderEngine = engineOn(store, handler, parents[1], clock, ids)
            second.drain()
            assertEquals(1, handler.payloads.size, "the second engine ran the delivered effect again")
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "setup: the second delete failed too")
            second.close()
            val third: UploaderEngine = engineOn(store, handler, parents[2], clock, ids)
            third.drain()
            assertEquals(1, handler.payloads.size, "the third engine ran the delivered effect again")
            assertNull(inner.find(id), "the third engine did not confirm the delivery")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    @Test
    fun `an item of another store with the same id runs and does not erase the entry of the first`() = runTest {
        val parents: List<Job> = List(3) { Job() }
        try {
            val innerOne = TestUploaderStore()
            val storeOne = FlakyDeleteStore(innerOne, failures = 1)
            val innerTwo = TestUploaderStore()
            val handlerOne = TestHandler()
            val handlerTwo = TestHandler()
            val clock = TestClock(1_000_000L)
            val sameId: () -> String = { "s1b-shared-id" }
            val one: UploaderEngine = testEngine(storeOne, listOf(handlerOne), scopeOn(parents[0]), clock, idGenerator = sameId)
            one.enqueue(handlerOne, "first-store")!!
            one.drain()
            assertEquals(1, handlerOne.payloads.size, "setup: the effect ran and its delete failed")
            one.close()

            clock.millis += 5_000L
            val two: UploaderEngine = testEngine(innerTwo, listOf(handlerTwo), scopeOn(parents[1]), clock, idGenerator = sameId)
            two.enqueue(handlerTwo, "second-store")!!
            two.drain()
            assertEquals(listOf("second-store"), handlerTwo.payloads, "an item that only shares the id must run once")
            assertNull(innerTwo.find("s1b-shared-id"), "its delivery was not confirmed")

            clock.millis += 60_000L
            val replacement: UploaderEngine = testEngine(storeOne, listOf(handlerOne), scopeOn(parents[2]), clock)
            replacement.drain()
            assertEquals(1, handlerOne.payloads.size, "the other store's delivery erased the first store's entry")
            assertNull(innerOne.find("s1b-shared-id"), "the replacement did not confirm the first store's delivery")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    @Test
    fun `an item withdrawn by a replacement engine is forgotten and runs again under a reused id`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val sameId: () -> String = { "s1c-reused-id" }
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock, idGenerator = sameId)
            first.enqueue(handler, "payload")!!
            first.drain()
            first.close()
            assertEquals(1, handler.payloads.size, "setup: the effect ran and its delete failed")

            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[1]), clock, idGenerator = sameId)
            second.cancel("s1c-reused-id")
            assertNull(inner.find("s1c-reused-id"), "the replacement's cancel left the row")
            assertEquals(1, handler.payloads.size, "withdrawing ran the effect")
            // The id and every field of the fingerprint come back: a stale entry would take it for the delivered one.
            second.enqueue(handler, "payload")!!
            second.drain()
            assertEquals(2, handler.payloads.size, "a new item under a withdrawn id was taken for the delivered one")
        } finally {
            parents.forEach { it.cancel() }
        }
    }

    @Test
    fun `an item withdrawn by tag by a replacement engine is forgotten too`() = runTest {
        val parents: List<Job> = List(2) { Job() }
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val sameId: () -> String = { "s1d-reused-id" }
            val first: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[0]), clock, idGenerator = sameId)
            first.enqueue(handler, "payload", tag = "account")!!
            first.drain()
            first.close()
            assertEquals(1, handler.payloads.size, "setup: the effect ran and its delete failed")

            val second: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parents[1]), clock, idGenerator = sameId)
            second.cancelByTag("account")
            assertNull(inner.find("s1d-reused-id"), "the wipe left the row")
            second.enqueue(handler, "payload")!!
            second.drain()
            assertEquals(2, handler.payloads.size, "a new item under a wiped id was taken for the delivered one")
        } finally {
            parents.forEach { it.cancel() }
        }
    }
}
