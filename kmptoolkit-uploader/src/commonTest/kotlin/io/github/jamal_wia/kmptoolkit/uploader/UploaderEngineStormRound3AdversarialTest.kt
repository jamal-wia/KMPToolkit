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

    // --- fixer round 3, group 2 ---

    @Test
    fun `a retry whose cause has a throwing message still spends its attempt and takes its backoff`() = runTest {
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Retry(HostileMessage()) })
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            val id: String = assertNotNull(engine.enqueue(handler, "p"))

            engine.drain()
            clock.millis += 100L // far less than the one-second backoff
            engine.drain()
            engine.drain()

            assertEquals(1, handler.payloads.size, "the item was attempted again inside its backoff")
            assertEquals(1, store.find(id)?.attempts, "the failed attempt was never recorded")
        } finally {
            engine.close()
        }
    }

    private class HostileMessage : RuntimeException() {
        override val message: String
            get() = throw IllegalStateException("message getter blew up")
    }

    @Test
    fun `a failure message of three megabytes is stored truncated`() = runTest {
        val message: String = "head-" + "x".repeat(3_000_000)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Retry(IllegalStateException(message)) })
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            val id: String = assertNotNull(engine.enqueue(handler, "p"))

            engine.drain()

            val stored: String = assertNotNull(store.find(id)?.lastError)
            assertTrue(stored.length <= 4096 + 64, "the stored failure text is ${stored.length} characters long")
            assertTrue(stored.startsWith("IllegalStateException: head-"), "the truncated text must keep the head of the message")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `truncation never leaves an unpaired surrogate`() = runTest {
        // The text is "IllegalStateException: " + message, cut at 4096: place a pair across that boundary.
        val prefixLength: Int = "IllegalStateException: ".length
        val message: String = "a".repeat(4096 - prefixLength - 1) + "\uD83D\uDE00" + "b".repeat(100)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Retry(IllegalStateException(message)) })
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            val id: String = assertNotNull(engine.enqueue(handler, "p"))

            engine.drain()

            val stored: String = assertNotNull(store.find(id)?.lastError)
            assertTrue(stored.contains("truncated"), "the text was expected to be cut")
            stored.forEachIndexed { index, c ->
                if (c.isHighSurrogate()) assertTrue(index + 1 < stored.length && stored[index + 1].isLowSurrogate(), "lone high surrogate at $index")
                if (c.isLowSurrogate()) assertTrue(index > 0 && stored[index - 1].isHighSurrogate(), "lone low surrogate at $index")
            }
        } finally {
            engine.close()
        }
    }

    // --- fixer round 3, group 3 ---

    @Test
    fun `a backoff gate is recognised as a clock anomaly when the clock reads the lowest Long`() = runTest {
        val handler = TestHandler()
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            val id: String = assertNotNull(engine.enqueue(handler, "p"))
            val row: UploaderItem = assertNotNull(store.find(id))
            // A gate written by a sane clock, read by an insane one: it is astronomically far ahead.
            val gated: UploaderItem = row.copy(nextRunAtEpochMillis = 1_700_000_000_000L)
            store.deleteById(id)
            store.insertKeep(gated)
            clock.millis = Long.MIN_VALUE

            engine.drain()

            assertEquals(1, handler.payloads.size, "a gate 9.2e18 ms ahead was honoured instead of read as a clock jump")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `two handler types whose type and ordering key concatenate alike do not share an ordering channel`() = runTest {
        // "chat" + "#" + "room#1" and "chat#room" + "#" + "1" are the same string.
        val stuck = TestHandler(
            type = "chat",
            ordering = { "room#1" },
            onExecute = { _, _ -> AttemptResult.Retry(IllegalStateException("server down")) },
        )
        val independent = TestHandler(type = "chat#room", ordering = { "1" })
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(stuck, independent))
        try {
            engine.enqueue(stuck, "first")
            engine.enqueue(independent, "second")

            engine.drain()

            assertEquals(listOf("second"), independent.payloads, "a failing item of another handler type held this one back")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `saturatingSubtract holds the result at both ends of the Long range`() {
        assertEquals(2L, saturatingSubtract(5L, 3L))
        assertEquals(Long.MAX_VALUE, saturatingSubtract(Long.MAX_VALUE, -1L))
        assertEquals(Long.MAX_VALUE, saturatingSubtract(0L, Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, saturatingSubtract(Long.MIN_VALUE, 1L))
        assertEquals(Long.MIN_VALUE, saturatingSubtract(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, saturatingSubtract(-1L, Long.MAX_VALUE))
    }

    // --- fixer round 3, group 4 ---

    @Test
    fun `a constraint provider whose flow throws on collect does not leak an uncaught exception or stop the drain`() = runTest {
        val uncaught: MutableList<Throwable> = mutableListOf()
        val handler = TestHandler()
        val scope = CoroutineScope(backgroundScope.coroutineContext + CoroutineExceptionHandler { _, e -> uncaught += e })
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = scope,
            constraintProviders = listOf(ThrowingCollectProvider()),
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            engine.enqueue(handler, "p")
            advanceTimeBy(1.seconds)

            assertEquals(emptyList(), uncaught.map { it.message }, "the provider's failure escaped to the thread's uncaught handler")
            assertEquals(listOf("p"), handler.payloads, "the drain stopped working")
        } finally {
            engine.close()
        }
    }

    private class ThrowingCollectProvider : ConstraintProvider {
        override val key: String = "net"
        override val satisfied: StateFlow<Boolean> = object : StateFlow<Boolean> {
            override val value: Boolean = true
            override val replayCache: List<Boolean> = listOf(true)
            override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
                throw IllegalStateException("connectivity service died")
            }
        }
    }

    // --- fixer round 3, group 5a ---

    private class FailsNthRead(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        var failOnCall: Int = -1
        var calls: Int = 0
        var failed: Boolean = false
        override suspend fun getAllActive(): List<UploaderItem> {
            calls++
            if (calls == failOnCall) {
                failed = true
                throw IllegalStateException("database is locked")
            }
            return delegate.getAllActive()
        }
    }

    @Test
    fun `awaitDrained keeps nudging the drain so a pass that failed transiently under it still ends drained`() = runTest {
        val handler = TestHandler()
        val flaky = FailsNthRead(store)
        val engine: UploaderEngine = createUploaderEngine(
            store = flaky,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            runCurrent()
            // The item lands from elsewhere (another process's wake, a feeder engine): nothing triggers `engine`.
            val feeder: UploaderEngine = engineOf(backgroundScope, listOf(handler))
            feeder.enqueue(handler, "owed")
            runCurrent()
            assertEquals(emptyList(), handler.payloads, "setup: the engine under test must not have been triggered")
            flaky.calls = 0
            flaky.failOnCall = 2 // awaitDrained's own read is call 1; the drain it triggers reads second

            val drained: Boolean = engine.awaitDrained(5.seconds)

            assertTrue(flaky.failed, "setup: the drain's read was meant to fail")
            assertTrue(drained, "a wake job's drain failed once on a locked database and was never retried within its budget")
        } finally {
            engine.close()
        }
    }

    // --- fixer round 3, group 5b ---

    @Test
    fun `an effect whose confirming delete keeps failing is not repeated at the alarm floor for ten seconds`() = runTest {
        val handler = TestHandler()
        val engine: UploaderEngine = createUploaderEngine(
            store = FailingDelete(store),
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.enqueue(handler, "send-message")
            engine.start()
            repeat(100) { // ten virtual seconds, the wall clock moving with them
                advanceTimeBy(100.milliseconds)
                clock.millis += 100
            }
            runCurrent()

            assertTrue(
                handler.payloads.size <= 20,
                "the same effect ran ${handler.payloads.size} times in ten seconds while its confirming delete kept failing",
            )
        } finally {
            engine.close()
        }
    }

    private class FailingDelete(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        override suspend fun deleteById(id: String) {
            throw IllegalStateException("disk full")
        }
    }

    private class FailingRecordFailure(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean = throw IllegalStateException("database is locked")
    }

    @Test
    fun `a store failing recordFailure backs the attempt off instead of retrying at the alarm floor`() = runTest {
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Retry(IllegalStateException("down")) })
        val engine: UploaderEngine = createUploaderEngine(
            store = FailingRecordFailure(store),
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.enqueue(handler, "p")
            engine.start()
            repeat(100) { // ten virtual seconds, the wall clock moving with them
                advanceTimeBy(100.milliseconds)
                clock.millis += 100
            }
            runCurrent()

            assertTrue(handler.payloads.size <= 10, "the attempt ran ${handler.payloads.size} times in ten seconds with its bookkeeping failing")
        } finally {
            engine.close()
        }
    }

    // --- fixer round 3, group 6 ---

    @Test
    fun `a plain item the drain read before a logout wipe is not executed after the wipe returned`() = runTest {
        val handler = TestHandler()
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            engine.enqueue(handler, "account-a-message", tag = "account-a")

            // The drain has read the queue; the user logs out and the wipe completes; only then does the
            // drain act on its snapshot.
            store.afterNextGetAllActive = { engine.cancelByTag("account-a") }
            engine.drain()

            assertEquals(emptyList(), handler.payloads, "a wiped effect was executed after cancelByTag had returned")
            assertEquals(emptyList(), store.items)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a plain item the drain read before its withdrawal is not executed after cancel returned`() = runTest {
        val handler = TestHandler()
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            val id: String = assertNotNull(engine.enqueue(handler, "withdrawn"))

            store.afterNextGetAllActive = { engine.cancel(id) }
            engine.drain()

            assertEquals(emptyList(), handler.payloads, "a withdrawn effect was executed after cancel had returned")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a plain item replaced after the drain read it never runs with the superseded payload`() = runTest {
        val handler = TestHandler()
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            engine.enqueue(handler, "old", uniqueKey = "draft")

            store.afterNextGetAllActive = {
                engine.enqueue(handler, "new", uniqueKey = "draft", conflictPolicy = ConflictPolicy.REPLACE)
            }
            engine.drain()

            assertEquals(listOf("new"), handler.payloads, "the superseded payload must not be executed once REPLACE returned")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a detached plain item delivered after the drain read its expired lease is not handed off again`() = runTest {
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 30_000L) })
        val engine: UploaderEngine = engineOf(backgroundScope, listOf(handler))
        try {
            val id: String = assertNotNull(engine.enqueue(handler, "p"))
            engine.drain()
            assertEquals(1, handler.payloads.size)
            val claim: Long = assertNotNull(store.find(id)).claim
            clock.millis += 31_000L // the lease runs out

            // The drain reads the expired lease; the executor's Delivered lands before the drain re-hands.
            store.afterNextGetAllActive = { engine.settle(id, claim, SettleResult.Delivered) }
            engine.drain()

            assertEquals(1, handler.payloads.size, "an already delivered effect was handed off a second time")
        } finally {
            engine.close()
        }
    }

    private class CountingRecordFailure(private val delegate: TestUploaderStore) : UploaderStore by delegate {
        var recorded: Int = 0
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean {
            recorded++
            return delegate.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }
    }

    @Test
    fun `a REPLACE stops the running attempt of the superseded plain item and the replacement runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        var sawCancellation = false
        val handler = TestHandler(
            onExecute = { _, payload ->
                if (payload == "old") {
                    running.complete(Unit)
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        sawCancellation = true
                        throw e
                    }
                }
                AttemptResult.Success
            },
        )
        val counting = CountingRecordFailure(store)
        val engine: UploaderEngine = createUploaderEngine(
            store = counting,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            engine.enqueue(handler, "old", uniqueKey = "draft")
            runCurrent()
            assertTrue(running.isCompleted, "precondition: the attempt is running")

            engine.enqueue(handler, "new", uniqueKey = "draft", conflictPolicy = ConflictPolicy.REPLACE)
            advanceTimeBy(2.seconds)

            assertTrue(sawCancellation, "the superseded item's attempt kept running")
            assertEquals(0, counting.recorded, "the superseded attempt spent an attempt")
            assertEquals(listOf("old", "new"), handler.payloads)
            assertEquals(emptyList(), store.items)
        } finally {
            gate.complete(Unit)
            engine.close()
        }
    }

    @Test
    fun `a withdrawn running attempt spends no attempt and records no error`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        val handler = TestHandler(
            onExecute = { _, _ ->
                running.complete(Unit)
                gate.await()
                AttemptResult.Retry(IllegalStateException("late"))
            },
        )
        val counting = CountingRecordFailure(store)
        val engine: UploaderEngine = createUploaderEngine(
            store = counting,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            val id: String = assertNotNull(engine.enqueue(handler, "p"))
            runCurrent()
            assertTrue(running.isCompleted, "precondition: the attempt is running")

            engine.cancel(id)
            gate.complete(Unit) // a handler that ignores the withdrawal and finishes with a failure anyway
            advanceTimeBy(2.seconds)

            assertEquals(emptyList(), store.items)
            assertEquals(0, counting.recorded, "a withdrawn attempt recorded a failure")
        } finally {
            gate.complete(Unit)
            engine.close()
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
