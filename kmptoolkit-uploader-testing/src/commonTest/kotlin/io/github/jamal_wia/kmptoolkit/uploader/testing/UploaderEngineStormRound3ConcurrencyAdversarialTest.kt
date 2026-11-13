package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderClock
import io.github.jamal_wia.kmptoolkit.uploader.UploaderConfig
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlinx.coroutines.flow.StateFlow
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Round 3 (concurrency-storm), real threads, real engine over [InMemoryUploaderStore]: enqueue, replace,
 * withdraw and wipe storms, awaitDrained under contention, an engine replaced mid-storm, clocks that jump
 * wildly while leases and gates are being written, and a logger sink that throws on every call.
 *
 * Every test is bounded by a real-time timeout and closes every engine in a finally.
 */
class UploaderEngineStormRound3ConcurrencyAdversarialTest {
    /**
     * The seed of the clock chaos: the jumps of the clock test are drawn from `Random(seed + iteration)` and the
     * reading jitter of call number n from `Random(seed + iteration + n)`. A constant because common test code has no
     * system properties; to explore another sequence edit it. The clock test quotes it in its failures. Which thread
     * makes call number n stays free.
     */
    private val seed: Long = 20261113L

    private object QuickPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 50L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 20L
    }

    private class StormHandler(
        private val behave: suspend (AttemptContext, String) -> AttemptResult = { _, _ -> AttemptResult.Success },
    ) : UploaderHandler<String> {
        override val type: String = "adv.storm"
        override val retryPolicy: RetryPolicy = QuickPolicy
        val executed: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            val outcome: AttemptResult = behave(context, payload)
            if (outcome !is AttemptResult.Retry) executed.update { it + payload }
            yield()
            return outcome
        }
    }

    private class ChaosClock(private val seed: Long) : UploaderClock {
        private val reading: MutableStateFlow<Long> = MutableStateFlow(BASE)
        private val chaos: MutableStateFlow<Boolean> = MutableStateFlow(true)
        private val calls: MutableStateFlow<Long> = MutableStateFlow(0L)
        fun jump(to: Long) {
            reading.value = to
        }

        fun settle(at: Long) {
            chaos.value = false
            reading.value = at
        }

        override fun nowEpochMillis(): Long = if (chaos.value) reading.value + Random(seed + calls.updateAndGet { it + 1 }).nextLong(-1_000L, 1_000L) else reading.value

        companion object {
            const val BASE: Long = 1_700_000_000_000L
        }
    }

    private fun scopeOf(job: Job): CoroutineScope = CoroutineScope(Dispatchers.Default + job)

    private fun realTest(seconds: Int = 90, body: suspend CoroutineScope.() -> Unit) =
        runTest(timeout = (seconds + 10).seconds) { withContext(Dispatchers.Default) { withTimeout(seconds.seconds) { body() } } }

    private suspend fun awaitCondition(what: String, condition: () -> Boolean) {
        withTimeout(20.seconds) { while (!condition()) delay(5) }
    }

    @Test
    fun `an enqueue replace withdraw and wipe storm drains to empty without running a payload twice`() = realTest {
        repeat(3) { iteration ->
            val store = InMemoryUploaderStore()
            val handler = StormHandler()
            val job = Job()
            val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), scopeOf(job))
            try {
                engine.start()
                val start = CompletableDeferred<Unit>()
                val enqueuers: List<Deferred<Unit>> = List(8) { worker ->
                    async {
                        start.await()
                        repeat(150) { i ->
                            engine.enqueue(
                                handler,
                                "e$worker-$i",
                                uniqueKey = if (i % 3 == 0) "k${i % 5}" else null,
                                tag = "t${i % 3}",
                                conflictPolicy = if (i % 2 == 0) ConflictPolicy.REPLACE else ConflictPolicy.KEEP,
                            )
                        }
                    }
                }
                val cancellers: List<Deferred<Unit>> = List(3) { n ->
                    val random = Random(seed + iteration + n) // one Random per canceller: none is shared between coroutines
                    async {
                        start.await()
                        repeat(60) { step ->
                            if (step % 2 == 0) {
                                engine.cancelByTag("t${(n + step) % 3}")
                            } else {
                                store.snapshot().randomOrNull(random)?.let { engine.cancel(it.id) }
                            }
                            yield()
                        }
                    }
                }
                start.complete(Unit)
                (enqueuers + cancellers).awaitAll()

                assertTrue(engine.awaitDrained(30.seconds), "iteration $iteration: the queue never drained: ${store.snapshot().size} left (seed=$seed)")
                assertEquals(emptyList(), store.snapshot())
                val ran: List<String> = handler.executed.value
                assertEquals(ran.size, ran.toSet().size, "iteration $iteration: a payload ran twice (seed=$seed)")
                engine.enqueue(handler, "after-storm")
                awaitCondition("after-storm ran") { "after-storm" in handler.executed.value }
            } finally {
                engine.close()
                job.cancel()
            }
        }
    }

    @Test
    fun `forty awaitDrained callers racing a stream of enqueues all see true once the stream stops`() = realTest {
        val store = InMemoryUploaderStore()
        val handler = StormHandler()
        val job = Job()
        val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), scopeOf(job))
        try {
            engine.start()
            val start = CompletableDeferred<Unit>()
            val producers: List<Deferred<Unit>> = List(4) { w ->
                async { start.await(); repeat(100) { engine.enqueue(handler, "p$w-$it"); yield() } }
            }
            val zeroWaiters: List<Deferred<Boolean>> = List(8) { async { start.await(); engine.awaitDrained(Duration.ZERO) } }
            start.complete(Unit)
            zeroWaiters.awaitAll() // any answer, but no throw
            producers.awaitAll()
            val waiters: List<Deferred<Boolean>> = List(40) { async { engine.awaitDrained(30.seconds) } }
            assertTrue(waiters.awaitAll().all { it }, "an awaitDrained caller timed out although the stream had stopped")
            assertEquals(400, handler.executed.value.size)
        } finally {
            engine.close()
            job.cancel()
        }
    }

    @Test
    fun `an engine closed and replaced over the same store under a live enqueue stream loses nothing`() = realTest {
        val store = InMemoryUploaderStore()
        val handler = StormHandler()
        val jobs: MutableList<Job> = mutableListOf()
        fun newEngine(): UploaderEngine {
            val job = Job().also { jobs += it }
            return createUploaderEngine(store, listOf(handler), scopeOf(job)).also { it.start() }
        }
        var current: UploaderEngine = newEngine()
        val live: MutableStateFlow<UploaderEngine> = MutableStateFlow(current)
        try {
            val start = CompletableDeferred<Unit>()
            val enqueued: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
            val producers: List<Deferred<Unit>> = List(6) { w ->
                async {
                    start.await()
                    repeat(120) { i ->
                        val payload = "p$w-$i"
                        live.value.enqueue(handler, payload)
                        enqueued.update { it + payload }
                        yield()
                    }
                }
            }
            val churn: Deferred<Unit> = async {
                start.await()
                repeat(25) {
                    val old: UploaderEngine = live.value
                    val fresh: UploaderEngine = newEngine()
                    live.value = fresh
                    old.close()
                    delay(3)
                }
            }
            start.complete(Unit)
            (producers + churn).awaitAll()
            current = live.value

            assertTrue(current.awaitDrained(30.seconds), "queue left: ${store.snapshot().size}")
            val ran: Set<String> = handler.executed.value.toSet()
            val lost: List<String> = enqueued.value.filter { it !in ran }
            assertEquals(emptyList(), lost, "enqueued payloads that never ran")
        } finally {
            live.value.close()
            jobs.forEach { it.cancel() }
        }
    }

    @Test
    fun `an awaitDrained caller of an engine closed mid-wait is released at its timeout and sees a successor drain`() = realTest {
        val store = InMemoryUploaderStore()
        val gate = CompletableDeferred<Unit>()
        val handler = StormHandler(behave = { _, p -> if (p == "slow") gate.await(); AttemptResult.Success })
        val jobA = Job()
        val jobB = Job()
        val a: UploaderEngine = createUploaderEngine(store, listOf(handler), scopeOf(jobA))
        try {
            a.start()
            a.enqueue(handler, "slow")
            val waiter: Deferred<Boolean> = async { a.awaitDrained(20.seconds) }
            delay(50)
            a.close() // the slow handler is cancelled with the engine
            val b: UploaderEngine = createUploaderEngine(store, listOf(handler), scopeOf(jobB))
            try {
                gate.complete(Unit)
                b.start()
                assertTrue(waiter.await(), "the old engine's waiter must see the successor drain the shared store")
            } finally {
                b.close()
            }
        } finally {
            a.close()
            jobA.cancel()
            jobB.cancel()
        }
    }

    @Test
    fun `a clock that jumps wildly while gates and leases are written never leaves an item stuck once it settles`() = realTest(120) {
        repeat(3) { iteration ->
            val clock = ChaosClock(seed + iteration)
            val store = InMemoryUploaderStore()
            val leases: List<Long> = listOf(1L, 60_000L, 24L * 3_600_000L + 1, 30L * 24 * 3_600_000L, Long.MAX_VALUE)
            val handler = StormHandler(
                behave = { ctx, payload ->
                    val n: Int = payload.substringAfter('-').toInt()
                    when {
                        n % 4 == 0 && ctx.attempts == 0 && !ctx.wasDetached -> AttemptResult.Detached(leases[n % leases.size])
                        n % 4 == 0 -> AttemptResult.Success
                        ctx.attempts < 2 -> AttemptResult.Retry(IllegalStateException("flaky"))
                        else -> AttemptResult.Success
                    }
                },
            )
            val job = Job()
            val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), scopeOf(job), clock = clock)
            try {
                engine.start()
                val start = CompletableDeferred<Unit>()
                val jumps = Random(seed + iteration)
                val jumper: Deferred<Unit> = async {
                    start.await()
                    repeat(300) {
                        clock.jump(ChaosClock.BASE + jumps.nextLong(-300L * 24 * 3_600_000, 200L * 365 * 24 * 3_600_000))
                        delay(1)
                    }
                }
                val producers: List<Deferred<Unit>> = List(4) { w ->
                    async { start.await(); repeat(60) { i -> engine.enqueue(handler, "c$w-${i * 4 + (w % 4)}"); yield() } }
                }
                start.complete(Unit)
                (producers + jumper).awaitAll()
                clock.settle(ChaosClock.BASE)

                // Settle whatever is detached, as the executors would, until the queue is empty.
                withTimeout(40.seconds) {
                    while (store.snapshot().isNotEmpty()) {
                        store.snapshot().filter { it.state == UploaderItemState.IN_FLIGHT }.forEach {
                            engine.settle(it.id, it.claim, SettleResult.Delivered)
                        }
                        engine.trigger()
                        delay(20)
                    }
                }
                assertEquals(emptyList(), store.snapshot(), "iteration $iteration (seed=$seed)")
                assertEquals(240, handler.executed.value.toSet().size, "iteration $iteration: items lost (seed=$seed)")
            } finally {
                engine.close()
                job.cancel()
            }
        }
    }

    private class AlwaysThrowingSink : Logger {
        override val tag: String = "sink"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            throw IllegalStateException("sink: ${message()}")
        }
    }

    @Test
    fun `a logger that throws on every line under eight concurrent callers loses lines and no work`() = realTest {
        val store = InMemoryUploaderStore()
        val handler = StormHandler(behave = { ctx, _ -> if (ctx.attempts == 0) AttemptResult.Retry(IllegalStateException("once")) else AttemptResult.Success })
        val job = Job()
        val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), scopeOf(job), logger = AlwaysThrowingSink())
        try {
            engine.start()
            val start = CompletableDeferred<Unit>()
            val callers: List<Deferred<Unit>> = List(8) { w ->
                async {
                    start.await()
                    repeat(40) { i ->
                        val id: String? = engine.enqueue(handler, "l$w-$i", tag = "t$w")
                        if (i % 10 == 9 && id != null) engine.cancel(id)
                        yield()
                    }
                }
            }
            start.complete(Unit)
            callers.awaitAll()
            assertTrue(engine.awaitDrained(30.seconds), "queue left ${store.snapshot().size}")
            engine.enqueue(handler, "after")
            awaitCondition("after ran") { "after" in handler.executed.value }
        } finally {
            engine.close()
            job.cancel()
        }
    }

    private class ArmedFlag : WakeScheduler {
        val armed: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override fun scheduleWake() {
            armed.value = true
        }

        override fun cancelWake() {
            armed.value = false
        }
    }

    private class Offline : ConstraintProvider {
        override val key: String = "net"
        override val satisfied: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private class GatedHandler : UploaderHandler<String> {
        override val type: String = "adv.gated"
        override val constraintKeys: Set<String> = setOf("net")
        override val retryPolicy: RetryPolicy = QuickPolicy
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Success
    }

    @Test
    fun `while rows stay owed the platform wake is armed after every storm of enqueues and empty passes`() = realTest(120) {
        repeat(40) { iteration ->
            val store = InMemoryUploaderStore()
            val wake = ArmedFlag()
            val plain = StormHandler()
            val gated = GatedHandler()
            val job = Job()
            val engine: UploaderEngine = createUploaderEngine(
                store,
                listOf(plain, gated),
                scopeOf(job),
                constraintProviders = listOf(Offline()),
                wakeScheduler = wake,
            )
            try {
                engine.start()
                val start = CompletableDeferred<Unit>()
                val producers: List<Deferred<Unit>> = List(6) { w ->
                    async {
                        start.await()
                        repeat(30) { i ->
                            if (w == 0 && i == 20) engine.enqueue(gated, "owed") else engine.enqueue(plain, "q$w-$i")
                            yield()
                        }
                    }
                }
                start.complete(Unit)
                producers.awaitAll()
                awaitCondition("plain items drained") { store.snapshot().all { it.type == gated.type } }
                delay(30)

                assertEquals(1, store.snapshot().size, "iteration $iteration")
                assertTrue(wake.armed.value, "iteration $iteration: a row is owed but the platform wake is disarmed")
            } finally {
                engine.close()
                job.cancel()
            }
        }
    }

    @Test
    fun `with a one hour heartbeat every enqueue from eight threads is picked up by its trigger alone`() = realTest(120) {
        val store = InMemoryUploaderStore()
        val handler = StormHandler()
        val job = Job()
        val engine: UploaderEngine = createUploaderEngine(
            store,
            listOf(handler),
            scopeOf(job),
            config = UploaderConfig(heartbeatInterval = 3600.seconds),
        )
        try {
            engine.start()
            val start = CompletableDeferred<Unit>()
            val workers: List<Deferred<Unit>> = List(8) { w ->
                async {
                    start.await()
                    repeat(250) { i ->
                        val payload = "lw$w-$i"
                        engine.enqueue(handler, payload)
                        withTimeout(5.seconds) { while (payload !in handler.executed.value) delay(1) }
                    }
                }
            }
            start.complete(Unit)
            workers.awaitAll()
            assertEquals(2000, handler.executed.value.size)
        } finally {
            engine.close()
            job.cancel()
        }
    }

    @Test
    fun `sixteen threads hammering the FakeUploader get distinct gapless ordinals and every call is counted`() = realTest {
        val fake = FakeUploader()
        val handler = StormHandler()
        val start = CompletableDeferred<Unit>()
        val workers: List<Deferred<List<String?>>> = List(16) { w ->
            async {
                start.await()
                List(200) { i ->
                    fake.trigger()
                    if (i % 5 == 0) fake.cancel("c$w-$i")
                    if (i % 7 == 0) fake.cancelByTag("t$w")
                    fake.enqueue(handler, "f$w-$i")
                }
            }
        }
        start.complete(Unit)
        val ids: List<String?> = workers.awaitAll().flatten()

        assertEquals(3200, fake.enqueued.size)
        assertEquals((1..3200).toList(), fake.enqueued.map { it.ordinal }.sorted())
        assertEquals(3200, ids.toSet().size, "ids returned by the default hook are not distinct")
        assertEquals(3200, fake.triggerCount)
        assertEquals(16 * 40, fake.cancelled.size)
        assertEquals(16 * 29, fake.cancelledByTag.size)
    }

    // --- fixer round 3, group 8 ---

    @Test
    fun `the mutable clock loses no millisecond when sixteen threads advance it at once`() = realTest {
        val clock = MutableUploaderClock(0L)
        val start = CompletableDeferred<Unit>()
        val workers: List<Deferred<Unit>> = List(16) { async { start.await(); repeat(50_000) { clock.advanceBy(1L) } } }
        start.complete(Unit)
        workers.awaitAll()

        assertEquals(800_000L, clock.nowMillis)
    }

    @Test
    fun `the recording wake scheduler loses no call when sixteen threads schedule at once`() = realTest {
        val scheduler = RecordingWakeScheduler()
        val start = CompletableDeferred<Unit>()
        val workers: List<Deferred<Unit>> = List(16) { async { start.await(); repeat(10_000) { scheduler.scheduleWake() } } }
        start.complete(Unit)
        workers.awaitAll()

        assertEquals(160_000, scheduler.scheduleCount)
        assertEquals(true, scheduler.isArmed)
    }
}
