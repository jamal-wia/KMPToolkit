package io.github.jamal_wia.kmptoolkit.outbox

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** The one piece of global state: registering, waiting, and clearing it. */
class OutboxEngineRegistryTest {

    @AfterTest
    fun clearRegistry() {
        OutboxEngineRegistry.current?.let { OutboxEngineRegistry.unregister(it) }
    }

    @Test
    fun `nothing is registered until something registers`() {
        assertNull(OutboxEngineRegistry.current)
    }

    @Test
    fun `register makes the engine visible`() = runTest {
        val engine: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)

        OutboxEngineRegistry.register(engine)

        assertSame(engine, OutboxEngineRegistry.current)
    }

    @Test
    fun `registering a second engine replaces the first`() = runTest {
        val first: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)
        val second: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)

        OutboxEngineRegistry.register(first)
        OutboxEngineRegistry.register(second)

        assertSame(second, OutboxEngineRegistry.current)
    }

    @Test
    fun `unregister only clears the engine it names`() = runTest {
        val first: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)
        val second: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)
        OutboxEngineRegistry.register(second)

        OutboxEngineRegistry.unregister(first)

        assertSame(second, OutboxEngineRegistry.current, "a stale unregister must not evict a new engine")
    }

    @Test
    fun `await returns an already registered engine immediately`() = runTest {
        val engine: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)
        OutboxEngineRegistry.register(engine)

        assertSame(engine, OutboxEngineRegistry.await(1.seconds))
    }

    @Test
    fun `await suspends until a late bootstrap registers`() = runTest {
        val engine: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)
        val waiting: Deferred<OutboxEngine?> = async { OutboxEngineRegistry.await(10.seconds) }

        launch {
            delay(2.seconds) // the app is still starting up
            OutboxEngineRegistry.register(engine)
        }

        assertSame(engine, waiting.await())
    }

    @Test
    fun `await gives up and returns null when nothing registers`() = runTest {
        assertNull(OutboxEngineRegistry.await(50.milliseconds))
    }

    @Test
    fun `closing the registered engine clears the slot`() = runTest {
        val engine: OutboxEngine = testEngine(TestOutboxStore(), listOf(TestHandler()), backgroundScope)
        OutboxEngineRegistry.register(engine)

        engine.close()

        assertEquals(null, OutboxEngineRegistry.current)
    }
}
