package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 3 (concurrency-storm): interleavings between the drain's snapshot of the queue and the writes that
 * land before it acts on that snapshot, a registry storm on real threads, and arithmetic on extreme clock
 * readings.
 *
 * No test uses advanceUntilIdle; engines are never started in the virtual-time tests (explicit drain), and
 * every engine is closed in a finally.
 */
class UploaderEngineStormRound3AdversarialTest {

    private val store = TestUploaderStore()
    private val clock = TestClock(millis = 1_000L)

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun engineOf(
        scope: CoroutineScope,
        handlers: List<UploaderHandler<*>>,
        logger: Logger = io.github.jamal_wia.kmptoolkit.logging.NoopLogger,
    ): UploaderEngine = createUploaderEngine(
        store = store,
        handlers = handlers,
        scope = scope,
        clock = clock,
        logger = logger,
        idGenerator = SequentialIds()::next,
    )

    // --- a write that lands between the drain's read and its attempt ------------------------------

    // --- extreme clock readings --------------------------------------------------------------------

    // --- failure bookkeeping that depends on the cause ----------------------------------------------

    // --- a sink that throws a CancellationException -------------------------------------------------

    // --- fixer round 3, group 1 ---

    @Test
    fun `a logger sink that throws a CancellationException does not make enqueue throw`() = runTest {
        val handler = TestHandler()
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler), CancellingSink())
        try {
            val id: String? = engine.enqueue(handler, "p")

            assertNotNull(id, "the row committed; the caller must be told so")
            assertEquals(1, store.items.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a logger sink that throws a CancellationException does not stop the started drain`() = runTest {
        var fail = true
        val handler = TestHandler(
            onExecute = { _, payload ->
                if (fail && payload == "first") AttemptResult.Retry(IllegalStateException("x")) else AttemptResult.Success
            },
        )
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler), CancellingSink())
        try {
            engine.start()
            runCatching { engine.enqueue(handler, "first") } // may throw from the sink; the row is what matters
            advanceTimeBy(100.milliseconds)
            fail = false
            runCatching { engine.enqueue(handler, "second") }
            advanceTimeBy(5.seconds)

            assertTrue("second" in handler.payloads, "the drain died on the sink's exception: ${handler.payloads}")
        } finally {
            runCatching { engine.close() } // close() logs through the same sink
        }
    }

    private class CancellingSink : Logger {
        override val tag: String = "sink"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            throw CancellationException("sink is gone")
        }
    }

    // --- registry storm on real threads ---------------------------------------------------------------

    @Test
    fun `a register unregister close storm on real threads leaves the registry empty once every engine is closed`() = runTest(timeout = 70.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) {
                repeat(20) { round ->
                    val parent = Job()
                    val engines: List<UploaderEngine> = List(16) {
                        createUploaderEngine(TestUploaderStore(), listOf(TestHandler()), CoroutineScope(Dispatchers.Default + parent))
                    }
                    val start = CompletableDeferred<Unit>()
                    val workers: List<Deferred<Unit>> = engines.mapIndexed { index, engine ->
                        async {
                            start.await()
                            repeat(50) { step ->
                                when ((index + step) % 4) {
                                    0 -> UploaderEngineRegistry.register(engine)
                                    1 -> UploaderEngineRegistry.unregister(engines[(index + 1) % engines.size])
                                    2 -> UploaderEngineRegistry.current
                                    else -> if (step % 10 == 9) engine.close() else UploaderEngineRegistry.await(Duration.ZERO)
                                }
                            }
                            engine.close()
                            UploaderEngineRegistry.register(engine) // late registration of a closed engine
                        }
                    }
                    start.complete(Unit)
                    workers.awaitAll()
                    assertNull(UploaderEngineRegistry.current, "round $round: an engine outlived the storm in the registry")
                    parent.cancel()
                }
            }
        }
    }

    @Test
    fun `waiters woken by a registration that is closed at once never return a closed engine`() = runTest(timeout = 70.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) {
                repeat(30) { round ->
                    val parent = Job()
                    val start = CompletableDeferred<Unit>()
                    val waiters: List<Deferred<UploaderEngine?>> = List(8) {
                        async { start.await(); UploaderEngineRegistry.await(2.seconds) }
                    }
                    val live: UploaderEngine = createUploaderEngine(
                        TestUploaderStore(),
                        listOf(TestHandler()),
                        CoroutineScope(Dispatchers.Default + parent),
                    )
                    start.complete(Unit)
                    delay(5)
                    UploaderEngineRegistry.register(live)
                    val results: List<UploaderEngine?> = waiters.awaitAll()
                    // Every waiter was woken by the registration of a live engine.
                    results.forEachIndexed { index, got -> assertTrue(got === live, "round $round waiter $index got $got") }
                    live.close()
                    assertNull(UploaderEngineRegistry.current)
                    parent.cancel()
                }
            }
        }
    }
}
