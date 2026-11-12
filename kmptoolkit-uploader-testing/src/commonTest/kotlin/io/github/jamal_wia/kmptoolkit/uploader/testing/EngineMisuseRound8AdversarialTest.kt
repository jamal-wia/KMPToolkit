package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (hostile consumer): API misuse of the engine through the public surface only, over the
 * consumer's own tool (InMemoryUploaderStore). Every wait is bounded.
 */
class EngineMisuseRound8AdversarialTest {

    private class Handler(
        override val type: String = "r8",
        override val retryPolicy: RetryPolicy = TestPolicy(),
    ) : UploaderHandler<String> {
        val seen: MutableList<String> = mutableListOf()
        var behave: (String) -> AttemptResult = { AttemptResult.Success }
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            seen += payload
            return behave(payload)
        }
    }

    private class TestPolicy(
        val backoff: (Int) -> Long = { 1_000L },
        override val maxDelayMillis: Long = 60_000L,
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never,
    ) : RetryPolicy {
        override fun backoffMillis(attempts: Int): Long = backoff(attempts)
    }

    private fun TestScope.engineOf(
        store: InMemoryUploaderStore,
        vararg handlers: UploaderHandler<*>,
        clock: MutableUploaderClock = MutableUploaderClock(1_000_000L),
        scope: CoroutineScope = backgroundScope,
    ): UploaderEngine = createUploaderEngine(store, handlers.toList(), scope, clock = clock)

    // ---- use after close -------------------------------------------------------------------

    @Test
    fun `after close the engine persists a late enqueue and start is a no-op`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)
        engine.close()

        val id: String? = engine.enqueue(handler, "late")
        engine.start() // documented no-op
        runCurrent()

        assertNotNull(id)
        assertEquals(1, store.snapshot().size, "the row is persisted")
        assertEquals(emptyList(), handler.seen, "nothing drains a closed engine")
    }

    @Test
    fun `closing twice and awaitDrained after close neither throw nor hang`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)
        engine.start()
        engine.close()
        engine.close()
        engine.enqueue(handler, "x")

        val drained: Boolean = withTimeout(5.seconds) { engine.awaitDrained(0.seconds) }

        assertFalse(drained, "a persisted row on a closed engine is not drained")
    }

    @Test
    fun `settle and cancel still work against the store after close`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)
        val id: String = assertNotNull(engine.enqueue(handler, "x"))
        engine.close()

        engine.settle("unknown", 0, SettleResult.Delivered) // no-op
        engine.cancel(id)

        assertEquals(emptyList(), store.snapshot())
    }

    // ---- handler wiring mistakes -------------------------------------------------------------

    @Test
    fun `an item enqueued with a handler the engine does not know parks visibly and never hangs`() = runTest {
        val store = InMemoryUploaderStore()
        val registered = Handler(type = "known")
        val stranger = Handler(type = "stranger")
        val engine = engineOf(store, registered)

        engine.enqueue(stranger, "orphan")
        withTimeout(5.seconds) { engine.drain() }

        val item: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.PARKED, item.state)
        assertEquals(emptyList(), stranger.seen)
        assertTrue(item.lastError.orEmpty().contains("stranger"), "the reason names the type: ${item.lastError}")
    }

    @Test
    fun `a handler whose execute throws an Error from TODO is retried like an exception`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        handler.behave = { TODO("not written yet") }
        val engine = engineOf(store, handler)
        engine.enqueue(handler, "x")

        withTimeout(5.seconds) { engine.drain() }

        val item: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.PENDING, item.state)
        assertEquals(1, item.attempts)
    }

    @Test
    fun `a handler that throws an AssertionError is retried and the engine stays usable`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        handler.behave = { throw AssertionError("boom") }
        val clock = MutableUploaderClock(1_000_000L)
        val engine = engineOf(store, handler, clock = clock)
        engine.enqueue(handler, "x")
        withTimeout(5.seconds) { engine.drain() }
        handler.behave = { AttemptResult.Success }
        clock.advanceBy(10_000)

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(emptyList(), store.snapshot())
        assertEquals(listOf("x", "x"), handler.seen)
    }

    @Test
    fun `an encodePayload that throws surfaces to the caller and persists nothing`() = runTest {
        val store = InMemoryUploaderStore()
        val bad = object : UploaderHandler<String> {
            override val type: String = "bad"
            override fun encodePayload(payload: String): String = error("cannot encode")
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
                AttemptResult.Success
        }
        val engine = engineOf(store, bad)

        assertFailsWith<IllegalStateException> { engine.enqueue(bad, "x") }

        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `an orderingKey that throws surfaces to the caller and persists nothing`() = runTest {
        val store = InMemoryUploaderStore()
        val bad = object : UploaderHandler<String> {
            override val type: String = "bad"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override fun orderingKey(payload: String): String? = error("no key")
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
                AttemptResult.Success
        }
        val engine = engineOf(store, bad)

        assertFailsWith<IllegalStateException> { engine.enqueue(bad, "x") }

        assertEquals(emptyList(), store.snapshot())
    }

    // ---- hostile retry policies ----------------------------------------------------------------

    @Test
    fun `a custom policy returning Long MAX_VALUE never leaves a negative or wrapped gate`() = runTest {
        val store = InMemoryUploaderStore()
        val clock = MutableUploaderClock(5_000_000_000L)
        val handler = Handler(retryPolicy = TestPolicy(backoff = { Long.MAX_VALUE }, maxDelayMillis = Long.MAX_VALUE))
        handler.behave = { AttemptResult.Retry() }
        val engine = engineOf(store, handler, clock = clock)
        engine.enqueue(handler, "x")

        withTimeout(5.seconds) { engine.drain() }
        withTimeout(5.seconds) { engine.drain() }

        val item: UploaderItem = store.snapshot().single()
        assertEquals(1, handler.seen.size, "the item was not retried again before its gate: ${item.nextRunAtEpochMillis}")
        assertTrue(item.nextRunAtEpochMillis > clock.nowMillis, "gate ${item.nextRunAtEpochMillis} must lie in the future")
    }

    @Test
    fun `a custom policy returning a negative delay is not retried inside the same pass`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler(retryPolicy = TestPolicy(backoff = { -5_000L }))
        handler.behave = { AttemptResult.Retry() }
        val engine = engineOf(store, handler)
        engine.enqueue(handler, "x")

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(1, handler.seen.size)
        assertEquals(1, store.snapshot().single().attempts)
    }

    @Test
    fun `a custom policy that throws leaves the item queued not lost and not in flight`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler(retryPolicy = TestPolicy(backoff = { error("policy bug") }))
        handler.behave = { AttemptResult.Retry() }
        val engine = engineOf(store, handler)
        engine.enqueue(handler, "x")

        runCatching { withTimeout(5.seconds) { engine.drain() } }

        val item: UploaderItem = store.snapshot().single()
        assertTrue(item.state == UploaderItemState.PENDING || item.state == UploaderItemState.PARKED, "state ${item.state}")
    }

    @Test
    fun `a policy with a zero or negative maxDelay does not turn the queue into a spin`() = runTest {
        for (max in listOf(0L, -1L, Long.MIN_VALUE)) {
            val store = InMemoryUploaderStore()
            val handler = Handler(retryPolicy = TestPolicy(backoff = { 1_000L }, maxDelayMillis = max))
            handler.behave = { AttemptResult.Retry() }
            val engine = engineOf(store, handler)
            engine.enqueue(handler, "x")

            withTimeout(5.seconds) { engine.drain() }
            withTimeout(5.seconds) { engine.drain() }

            assertTrue(handler.seen.size <= 2, "max=$max: ${handler.seen.size} attempts in two drains")
            assertEquals(1, store.snapshot().size)
        }
    }

    // ---- odd arguments --------------------------------------------------------------------------

    @Test
    fun `the empty string is a real unique key and a real tag`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        handler.behave = { AttemptResult.Detached(60_000) }
        val engine = engineOf(store, handler)

        val first: String? = engine.enqueue(handler, "a", uniqueKey = "", tag = "")
        val second: String? = engine.enqueue(handler, "b", uniqueKey = "")
        engine.enqueue(handler, "c", uniqueKey = null, tag = "t")
        engine.cancelByTag("")

        assertNotNull(first)
        assertNull(second, "the empty key is a key: KEEP swallows the duplicate")
        assertEquals(listOf("c"), store.snapshot().map { it.payload })
    }

    @Test
    fun `cancel of an empty or unknown id and cancelByTag of an unknown tag change nothing`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)
        engine.enqueue(handler, "a", tag = "x")

        engine.cancel("")
        engine.cancel("no-such-id")
        engine.cancelByTag("")
        engine.cancelByTag("no-such-tag")

        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun `a megabyte unique key and tag and an empty payload round trip`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)
        val big: String = "k".repeat(1_000_000)

        val id: String? = engine.enqueue(handler, "", uniqueKey = big, tag = big)
        val dup: String? = engine.enqueue(handler, "", uniqueKey = big)
        withTimeout(10.seconds) { engine.drain() }

        assertNotNull(id)
        assertNull(dup)
        assertEquals(listOf(""), handler.seen)
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `an unusual handler type name still queues and delivers`() = runTest {
        for (type in listOf("", " ", "\u0000", "a/b\\c", "😀", "T".repeat(10_000))) {
            val store = InMemoryUploaderStore()
            val handler = Handler(type = type)
            val engine = engineOf(store, handler)
            engine.enqueue(handler, "p")
            withTimeout(5.seconds) { engine.drain() }
            assertEquals(listOf("p"), handler.seen, "type of length ${type.length}")
            assertEquals(emptyList(), store.snapshot())
        }
    }

    @Test
    fun `schema versions at the extremes park or deliver and never throw out of the drain`() = runTest {
        for (version in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            val store = InMemoryUploaderStore()
            val handler = object : UploaderHandler<String> {
                override val type: String = "v"
                override val schemaVersion: Int = version
                override fun encodePayload(payload: String): String = payload
                override fun decodePayload(raw: String): String = raw
                override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
                    AttemptResult.Success
            }
            val engine = engineOf(store, handler)
            engine.enqueue(handler, "p")
            withTimeout(5.seconds) { engine.drain() }
            assertEquals(emptyList(), store.snapshot(), "version $version")
        }
    }

    @Test
    fun `a lease of Long MAX_VALUE is shortened to a day`() = runTest {
        val store = InMemoryUploaderStore()
        val clock = MutableUploaderClock(10_000L)
        val handler = Handler()
        handler.behave = { AttemptResult.Detached(Long.MAX_VALUE) }
        val engine = engineOf(store, handler, clock = clock)
        engine.enqueue(handler, "x")

        withTimeout(5.seconds) { engine.drain() }

        val item: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.IN_FLIGHT, item.state)
        assertTrue(item.leaseUntilEpochMillis in 1..(10_000L + 24L * 3_600_000L), "lease ${item.leaseUntilEpochMillis}")
    }

    // ---- re-use of ids after cancel, wrong claims ------------------------------------------------

    @Test
    fun `an id reused after cancel settles nothing and a fresh enqueue gets a new id`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val contexts: MutableList<AttemptContext> = mutableListOf()
        val tracking = object : UploaderHandler<String> {
            override val type: String = "t2"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                contexts += context
                return AttemptResult.Detached(60_000)
            }
        }
        val engine = engineOf(store, handler, tracking)
        val id: String = assertNotNull(engine.enqueue(tracking, "x", uniqueKey = "k"))
        withTimeout(5.seconds) { engine.drain() }
        val context: AttemptContext = contexts.single()
        engine.cancel(id)
        engine.cancel(id)

        engine.settle(id, context.claim, SettleResult.Delivered)
        engine.settle(id, context.claim, SettleResult.Failed())
        val again: String? = engine.enqueue(tracking, "x", uniqueKey = "k")

        assertNotNull(again)
        assertTrue(again != id)
        assertEquals(UploaderItemState.PENDING, store.snapshot().single().state)
    }

    @Test
    fun `settle with a wildly wrong claim is a no-op for failed and park and applies for delivered`() = runTest {
        val store = InMemoryUploaderStore()
        val contexts: MutableList<AttemptContext> = mutableListOf()
        val handler = object : UploaderHandler<String> {
            override val type: String = "t3"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                contexts += context
                return AttemptResult.Detached(600_000)
            }
        }
        val engine = engineOf(store, handler)
        val id: String = assertNotNull(engine.enqueue(handler, "x"))
        withTimeout(5.seconds) { engine.drain() }
        for (claim in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            if (claim == contexts.single().claim) continue
            engine.settle(id, claim, SettleResult.Failed(RuntimeException("x")))
            engine.settle(id, claim, SettleResult.Park("x"))
        }
        assertEquals(UploaderItemState.IN_FLIGHT, store.snapshot().single().state)

        engine.settle(id, Long.MIN_VALUE, SettleResult.Delivered)

        assertEquals(emptyList(), store.snapshot())
    }

    // ---- threads and counts ----------------------------------------------------------------------

    @Test
    fun `start from sixteen threads at once runs each item exactly once`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler, scope = CoroutineScope(Dispatchers.Default))
        for (i in 0 until 50) engine.enqueue(handler, "p$i")

        withContext(Dispatchers.Default) {
            (0 until 16).map { async { engine.start() } }.awaitAll()
        }
        val drained: Boolean = withContext(Dispatchers.Default) { engine.awaitDrained(20.seconds) }
        engine.close()

        assertTrue(drained)
        assertEquals(50, handler.seen.size)
        assertEquals(50, handler.seen.toSet().size)
    }

    @Test
    fun `concurrent enqueue from many dispatchers keeps every item and distinct ids`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)

        val ids: List<String?> = withContext(Dispatchers.Default) {
            (0 until 400).map { async { engine.enqueue(handler, "p$it", tag = "t${it % 3}") } }.awaitAll()
        }

        assertEquals(400, ids.filterNotNull().toSet().size)
        assertEquals(400, store.snapshot().size)
        engine.cancelByTag("t0")
        assertTrue(store.snapshot().none { it.tag == "t0" })
    }

    @Test
    fun `three thousand items drain in one pass with a mixed ordering`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = object : UploaderHandler<String> {
            val order: MutableList<String> = mutableListOf()
            override val type: String = "bulk"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override fun orderingKey(payload: String): String? = payload.substringBefore(':')
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
                order += payload
                return AttemptResult.Success
            }
        }
        val engine = engineOf(store, handler)
        for (i in 0 until 3_000) engine.enqueue(handler, "c${i % 7}:$i")

        withTimeout(60.seconds) { engine.drain() }

        assertEquals(3_000, handler.order.size)
        for (channel in 0 until 7) {
            val seq: List<Int> = handler.order.filter { it.startsWith("c$channel:") }.map { it.substringAfter(':').toInt() }
            assertEquals(seq.sorted(), seq, "channel $channel kept its order")
        }
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `awaitDrained accepts zero and negative timeouts without throwing`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)
        assertTrue(engine.awaitDrained(0.seconds))
        assertTrue(engine.awaitDrained((-5).seconds))
        engine.enqueue(handler, "x")
        assertFalse(withTimeout(5.seconds) { engine.awaitDrained(0.seconds) })
        assertFalse(withTimeout(5.seconds) { engine.awaitDrained((-5).seconds) })
    }

    @Test
    fun `REPLACE of a key that has no queued item is a plain insert and returns an id`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = Handler()
        val engine = engineOf(store, handler)

        val id: String? = engine.enqueue(handler, "a", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)

        assertNotNull(id)
        assertEquals(1, store.snapshot().size)
    }

    // ---- wiring mistakes the factory documents -------------------------------------------------------------

    @Test
    fun `two handlers with one type or two providers with one key are refused at construction`() = runTest {
        val store = InMemoryUploaderStore()
        assertFailsWith<IllegalArgumentException> {
            createUploaderEngine(store, listOf(Handler("same"), Handler("same")), backgroundScope)
        }
        assertFailsWith<IllegalArgumentException> {
            createUploaderEngine(
                store, listOf(Handler()), backgroundScope,
                constraintProviders = listOf(MutableConstraintProvider("k"), MutableConstraintProvider("k")),
            )
        }
    }

    @Test
    fun `an engine with no handlers at all starts accepts a stranger's enqueue and parks it`() = runTest {
        val store = InMemoryUploaderStore()
        val stranger = Handler("stranger")
        val engine = createUploaderEngine(store, emptyList(), backgroundScope)
        engine.start()
        runCurrent()

        engine.enqueue(stranger, "x")
        runCurrent()

        assertEquals(UploaderItemState.PARKED, store.snapshot().single().state)
        engine.close()
    }

    @Test
    fun `a contract run whose store factory throws reports that failure and nothing else`() = runTest {
        val failure: Throwable? = runCatching {
            io.github.jamal_wia.kmptoolkit.uploader.testing.UploaderStoreContract { error("no database") }.verifyAll()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "got $failure")
    }
}
