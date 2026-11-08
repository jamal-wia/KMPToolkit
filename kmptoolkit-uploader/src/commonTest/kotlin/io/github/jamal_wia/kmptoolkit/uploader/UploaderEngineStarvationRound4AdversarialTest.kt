package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 4 (resource-starvation + contract-lawyer): documented guarantees tested literally, and loops that
 * could spin. No test uses advanceUntilIdle; every engine that is started is closed in a finally; every loop
 * a suspect could spin in is bounded by the test itself.
 */
class UploaderEngineStarvationRound4AdversarialTest {

    private val store = TestUploaderStore()
    private val clock = TestClock(millis = 1_000L)

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun engineOf(scope: CoroutineScope, vararg handlers: UploaderHandler<*>): UploaderEngine =
        createUploaderEngine(
            store = store,
            handlers = handlers.toList(),
            scope = scope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )

    private class NoopTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class ParkingUploadHandler(private val reason: String) : UploadHandler<String>(NoopTransport()) {
        override val type: String = "r4.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Park(reason)
    }

    // --- every documented text cap, on every route that persists failure text -----------------------

    @Test
    fun `no route that persists a failure text stores more than the documented cap`() = runTest {
        val huge: String = "x".repeat(1_000_000)
        val emoji: String = "😀".repeat(500_000) // surrogate pairs all the way
        val detaching = TestHandler(
            type = "r4.detach",
            onExecute = { _, _ -> AttemptResult.Detached(60_000L) },
        )
        val parking = TestHandler(type = "r4.park", onExecute = { _, _ -> AttemptResult.Park(emoji) })
        val retrying = TestHandler(
            type = "r4.giveup",
            retryPolicy = FixedRetryPolicy(giveUp = GiveUpPolicy.ParkAfterAttempts(1)),
            onExecute = { _, _ -> AttemptResult.Retry(IllegalStateException(huge)) },
        )
        val undecodable = TestHandler(type = "r4.undecodable", decode = { throw IllegalStateException(huge) })
        val upload = ParkingUploadHandler(huge)
        val engine: UploaderEngine = engineOf(backgroundScope, detaching, parking, retrying, undecodable, upload)
        try {
            val settled: String = assertNotNull(engine.enqueue(detaching, "d"))
            val parkId: String = assertNotNull(engine.enqueue(parking, "p"))
            val giveUpId: String = assertNotNull(engine.enqueue(retrying, "g"))
            val undecodableId: String = assertNotNull(engine.enqueue(undecodable, "u"))
            val uploadId: String = assertNotNull(engine.enqueue(upload, "up"))
            engine.drain()
            val claim: Long = detaching.attempts.single().claim
            engine.settle(settled, claim, SettleResult.Park(huge))

            for ((name: String, id: String) in listOf(
                "AttemptResult.Park" to parkId,
                "give-up" to giveUpId,
                "undecodable payload" to undecodableId,
                "UploadPreparation.Park" to uploadId,
                "SettleResult.Park" to settled,
            )) {
                val text: String = assertNotNull(store.find(id)?.lastError, "$name stored no text")
                assertTrue(text.length <= 4096 + 40, "$name stored ${text.length} chars")
                assertEquals(1, Regex("\\[truncated").findAll(text).count(), "$name: expected one truncation suffix")
                assertTrue(!text.dropLast(30).last().isHighSurrogate(), "$name cut inside a surrogate pair")
            }
        } finally {
            engine.close()
        }
    }

    // --- a logger sink that evaluates the message and a cause that cannot be printed -----------------

    private class HostileToString : RuntimeException("boom") {
        override fun toString(): String = throw IllegalStateException("toString is broken")
    }

    private class EvaluatingSink : Logger {
        override val tag: String = "sink"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            message()
            throwable?.toString()
        }
    }

    @Test
    fun `a cause whose toString throws never costs a settle or an attempt its bookkeeping`() = runTest {
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Retry(HostileToString()) })
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            logger = EvaluatingSink(),
            idGenerator = SequentialIds()::next,
        )
        try {
            val id: String = assertNotNull(engine.enqueue(handler, "p"))
            engine.settle("unknown", 0L, SettleResult.Failed(HostileToString())) // logs "$result" of an unknown id
            engine.drain()
            // a pending item is settled by the claim its next hand-off would write (a fast executor)
            engine.settle(id, store.find(id)!!.claim + 1, SettleResult.Failed(HostileToString()))

            assertEquals(2, store.find(id)?.attempts, "both failures must have been recorded")
        } finally {
            engine.close()
        }
    }

    // --- D-48: a withdrawn attempt that does not cooperate records nothing ---------------------------

    @Test
    fun `a withdrawn attempt that swallows the cancellation and reports any outcome records nothing`() = runTest {
        for (outcome in listOf<AttemptResult>(
            AttemptResult.Retry(IllegalStateException("late")),
            AttemptResult.Detached(60_000L),
            AttemptResult.Park("late"),
            AttemptResult.Success,
        )) {
            val localStore = TestUploaderStore()
            val gate = CompletableDeferred<Unit>()
            val handler = TestHandler(
                onExecute = { _, payload ->
                    if (payload == "old") {
                        try {
                            gate.await()
                        } catch (_: CancellationException) {
                            // swallowed: the handler reports an outcome for the row that was withdrawn
                        }
                        outcome
                    } else {
                        AttemptResult.Detached(3_600_000L)
                    }
                },
            )
            val engine: UploaderEngine = createUploaderEngine(
                store = localStore,
                handlers = listOf(handler),
                scope = backgroundScope,
                clock = clock,
                idGenerator = SequentialIds("o")::next,
            )
            try {
                engine.start()
                val oldId: String = assertNotNull(engine.enqueue(handler, "old", uniqueKey = "k"))
                runCurrent()
                val newId: String = assertNotNull(engine.enqueue(handler, "new", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE))
                advanceTimeBy(1.seconds)

                assertEquals(null, localStore.find(oldId), "$outcome: the superseded row must stay gone")
                val replacement: UploaderItem = assertNotNull(localStore.find(newId))
                assertEquals(0, replacement.attempts, "$outcome: the replacement was charged for the old attempt")
                assertTrue(replacement.lastError == null, "$outcome: the replacement carries the old attempt's text")
            } finally {
                gate.complete(Unit)
                engine.close()
            }
        }
    }

    // --- the 24 h lease cap, literally -----------------------------------------------------------------

    @Test
    fun `a detached lease of Long MAX_VALUE or one millisecond over a day holds exactly one day`() = runTest {
        for (lease in listOf(Long.MAX_VALUE, 24L * 3_600_000L + 1L)) {
            val localStore = TestUploaderStore()
            val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(lease) })
            val engine: UploaderEngine = createUploaderEngine(
                store = localStore,
                handlers = listOf(handler),
                scope = backgroundScope,
                clock = clock,
                idGenerator = SequentialIds()::next,
            )
            try {
                clock.millis = 1_000L
                engine.enqueue(handler, "p")
                engine.drain()
                assertEquals(1, handler.payloads.size)

                clock.millis = 1_000L + 24L * 3_600_000L - 1L
                engine.drain()
                assertEquals(1, handler.payloads.size, "lease $lease: re-handed before the day ran out")

                clock.millis = 1_000L + 24L * 3_600_000L + 1L
                // the re-hand detaches again and the loop in drain ends on its own lease gate
                engine.drain()
                assertEquals(2, handler.payloads.size, "lease $lease: not re-handed after the day")
            } finally {
                engine.close()
            }
        }
    }

    // --- coroutine leaks over create and close cycles -------------------------------------------------

    private class CountingState(private val inner: MutableStateFlow<Boolean>) : StateFlow<Boolean> by inner {
        var collectors: Int = 0
        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
            collectors++
            try {
                inner.collect(collector)
            } finally {
                collectors--
            }
        }
    }

    @Test
    fun `no constraint subscription or child job outlives a hundred and fifty started and closed engines`() =
        runTest(timeout = 60.seconds) {
            val parent = Job()
            val constraint = CountingState(MutableStateFlow(true))
            val provider = object : ConstraintProvider {
                override val key: String = "net"
                override val satisfied: StateFlow<Boolean> = constraint
            }
            try {
                repeat(150) { round ->
                    val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
                    val engine: UploaderEngine = createUploaderEngine(
                        store = store,
                        handlers = listOf(TestHandler(constraintKeys = setOf("net"))),
                        scope = scope,
                        constraintProviders = listOf(provider),
                        clock = clock,
                        idGenerator = SequentialIds("c$round")::next,
                    )
                    engine.start()
                    runCurrent()
                    assertEquals(1, constraint.collectors, "round $round: the subscription is not live")
                    engine.close()
                    runCurrent()
                    assertEquals(0, constraint.collectors, "round $round: a subscription outlived close()")
                }
                assertEquals(0, parent.children.count(), "child jobs outlived their engines")
            } finally {
                parent.cancel()
            }
        }

    // --- spins ---------------------------------------------------------------------------------------

    private class ZeroBackoffPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 1_000L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 0L // legal: "never negative"
    }

    @Test
    fun `a retry with a zero backoff is not re-run inside the same pass`() = runTest {
        var runs = 0
        val handler = TestHandler(
            retryPolicy = ZeroBackoffPolicy(),
            onExecute = { _, _ ->
                // the test bounds the spin itself: the 300th run parks the item so the pass can end
                if (++runs >= 300) AttemptResult.Park("test bound") else AttemptResult.Retry(IllegalStateException("x"))
            },
        )
        val engine: UploaderEngine = engineOf(backgroundScope, handler)
        try {
            engine.enqueue(handler, "p")
            engine.drain()

            assertEquals(1, runs, "one drain pass ran a failing item $runs times back to back")
        } finally {
            engine.close()
        }
    }

    private class SpinGuard : RuntimeException("the drain read the queue again and again without waiting")

    private class SkewedStore(private val inner: TestUploaderStore) : io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore by inner {
        var reads = 0
        override suspend fun getAllActive(): List<UploaderItem> {
            if (++reads > 200) throw SpinGuard()
            return inner.getAllActive()
        }

        // a store whose two read paths disagree about one field (a normalised last error, say)
        override suspend fun getById(id: String): UploaderItem? = inner.getById(id)?.copy(lastError = "normalised")
    }

    @Test
    fun `a store whose point read differs from its list read does not make the drain spin`() = runTest {
        val handler = TestHandler()
        val skewed = SkewedStore(store)
        val engine: UploaderEngine = createUploaderEngine(
            store = skewed,
            handlers = listOf(handler),
            scope = backgroundScope,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.enqueue(handler, "p")
            try {
                engine.drain()
            } catch (_: SpinGuard) {
                // the guard ended the spin
            }

            assertTrue(skewed.reads <= 10, "the drain re-read the whole queue ${skewed.reads} times in one pass")
        } finally {
            engine.close()
        }
    }

    // --- the public backoff policy at the end of the Long range ----------------------------------------

    @Test
    fun `an exponential policy never shrinks its delay when the doubling overflows`() {
        val policy = ExponentialBackoffRetryPolicy(
            baseDelayMillis = 1L shl 62,
            maxDelayMillis = Long.MAX_VALUE,
            jitterRatio = 0.0,
        )

        val first: Long = policy.backoffMillis(1)
        val second: Long = policy.backoffMillis(2)
        val third: Long = policy.backoffMillis(3)

        assertTrue(second >= first, "attempt 2 waits $second ms after attempt 1 waited $first ms")
        assertTrue(third >= second, "attempt 3 waits $third ms after attempt 2 waited $second ms")
    }

    @Test
    fun `a one hour guard keeps the heartbeat quiet after close`() = runTest(timeout = 30.seconds) {
        // control for the leak test above: a closed engine arms nothing in virtual time
        val wake = TestWakeScheduler()
        val handler = TestHandler()
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(handler),
            scope = backgroundScope,
            wakeScheduler = wake,
            clock = clock,
            idGenerator = SequentialIds()::next,
        )
        engine.start()
        runCurrent()
        engine.close()
        val cancelsAtClose: Int = wake.cancelCount
        advanceTimeBy(1.hours)

        assertEquals(cancelsAtClose, wake.cancelCount, "a closed engine kept ticking")
    }
}
