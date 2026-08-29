package io.github.jamal_wia.kmptoolkit.uploader

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
class UploaderEngineRegistryTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    @Test
    fun `nothing is registered until something registers`() {
        assertNull(UploaderEngineRegistry.current)
    }

    @Test
    fun `register makes the engine visible`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)

        UploaderEngineRegistry.register(engine)

        assertSame(engine, UploaderEngineRegistry.current)
    }

    @Test
    fun `registering a second engine replaces the first`() = runTest {
        val first: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        val second: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)

        UploaderEngineRegistry.register(first)
        UploaderEngineRegistry.register(second)

        assertSame(second, UploaderEngineRegistry.current)
    }

    @Test
    fun `unregister only clears the engine it names`() = runTest {
        val first: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        val second: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        UploaderEngineRegistry.register(second)

        UploaderEngineRegistry.unregister(first)

        assertSame(second, UploaderEngineRegistry.current, "a stale unregister must not evict a new engine")
    }

    @Test
    fun `await returns an already registered engine immediately`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        UploaderEngineRegistry.register(engine)

        assertSame(engine, UploaderEngineRegistry.await(1.seconds))
    }

    @Test
    fun `await suspends until a late bootstrap registers`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        val waiting: Deferred<UploaderEngine?> = async { UploaderEngineRegistry.await(10.seconds) }

        launch {
            delay(2.seconds) // the app is still starting up
            UploaderEngineRegistry.register(engine)
        }

        assertSame(engine, waiting.await())
    }

    @Test
    fun `await gives up and returns null when nothing registers`() = runTest {
        assertNull(UploaderEngineRegistry.await(50.milliseconds))
    }

    @Test
    fun `closing the registered engine clears the slot`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), backgroundScope)
        UploaderEngineRegistry.register(engine)

        engine.close()

        assertEquals(null, UploaderEngineRegistry.current)
    }
}
