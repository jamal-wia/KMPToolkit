package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression round, re-entrancy through the component seams: an executor that calls back into the engine from
 * inside its own attempt (drain, close, cancel of its own item), and a store written against the 4.x contract whose
 * keep insert commits and then fails with a ClassCastException (what the engine sees from a store that still answers
 * with a Boolean).
 */
class RegressReentrancyAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    @Test
    fun `a handler that closes the engine from inside its own attempt still has its delivery recorded`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { _, _ ->
                engine.close()
                AttemptResult.Success
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("re")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "x"))

            engine.drain()

            assertNull(store.find(id), "the delivery is a fact (P2) and is recorded although the engine closed during the attempt")
            assertEquals(1, handler.attempts.size)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a handler that cancels its own item from inside the attempt leaves no row and records no outcome`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            lateinit var engine: UploaderEngine
            lateinit var myId: String
            val handler = TestHandler(onExecute = { _, _ ->
                engine.cancel(myId)
                AttemptResult.Retry(IllegalStateException("after my own cancel"))
            })
            engine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("re")::next)
            myId = assertNotNull(engine.enqueue(handler, "x"))

            engine.drain()

            assertNull(store.find(myId), "the withdrawal stands: no retry row is written for a withdrawn item")
            assertEquals(1, handler.attempts.size)
        } finally {
            parent.cancel()
        }
    }

    /** A store whose keep insert commits and then fails the way a 4.x-compiled store does against the 5.x interface. */
    private class FourDotXStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        override suspend fun insertKeep(record: UploaderItem): KeepResult {
            inner.insertKeep(record)
            throw ClassCastException("java.lang.Boolean cannot be cast to KeepResult")
        }
    }

    @Test
    fun `a keep insert that commits and then throws leaves a row that the next pass still runs once`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(FourDotXStore(inner), listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("re")::next)
            engine.start()

            assertFailsWith<ClassCastException> { engine.enqueue(handler, "x", uniqueKey = "k") }
            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(1, handler.attempts.size, "the committed row ran once although the enqueue call threw")
            assertTrue(inner.items.isEmpty(), "and was delivered")
        } finally {
            parent.cancel()
        }
    }
}
