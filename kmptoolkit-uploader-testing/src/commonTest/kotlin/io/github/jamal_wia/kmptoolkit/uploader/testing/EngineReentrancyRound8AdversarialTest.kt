package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (hostile consumer): a handler that calls back into the engine from inside `execute`, an engine whose scope
 * the consumer cancels, and the cost of the consumer's own doubles at large counts.
 */
class EngineReentrancyRound8AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class Chain(val engine: () -> UploaderEngine) : UploaderHandler<String> {
        val follow: Follow = Follow()
        val seen: MutableList<String> = mutableListOf()
        override val type: String = "chain"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            seen += payload
            if (payload == "first") engine().enqueue(follow, "second")
            return AttemptResult.Success
        }
    }

    private class Follow : UploaderHandler<String> {
        val seen: MutableList<String> = mutableListOf()
        override val type: String = "follow"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            seen += payload
            return AttemptResult.Success
        }
    }

    @Test
    fun `a handler may enqueue a follow-up effect of another type from inside execute and it is delivered`() = runTest {
        val store = InMemoryUploaderStore()
        lateinit var engine: UploaderEngine
        val chain = Chain { engine }
        engine = createUploaderEngine(store, listOf(chain, chain.follow), backgroundScope)
        engine.enqueue(chain, "first")

        withTimeout(5.seconds) { engine.drain() }
        withTimeout(5.seconds) { engine.drain() }

        assertEquals(listOf("first"), chain.seen)
        assertEquals(listOf("second"), chain.follow.seen)
        assertEquals(emptyList(), store.snapshot())
    }

    private class SelfWithdraw(val engine: () -> UploaderEngine, val how: String) : UploaderHandler<String> {
        var runs: Int = 0
        var afterWithdraw: Boolean = false
        override val type: String = "self"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            runs++
            when (how) {
                "cancel" -> engine().cancel(context.id)
                "tag" -> engine().cancelByTag("mine")
                "replace" -> if (payload == "v1") {
                    engine().enqueue(this, "v2", uniqueKey = context.uniqueKey, tag = "mine", conflictPolicy = ConflictPolicy.REPLACE)
                }
            }
            afterWithdraw = true
            return AttemptResult.Retry()
        }
    }

    @Test
    fun `an effect that withdraws itself from inside execute leaves nothing behind and records no failure`() = runTest {
        for (how in listOf("cancel", "tag")) {
            val store = InMemoryUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = SelfWithdraw({ engine }, how)
            engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            engine.enqueue(handler, "x", tag = "mine")

            runCatching { withTimeout(5.seconds) { engine.drain() } }
            runCatching { withTimeout(5.seconds) { engine.drain() } }

            assertEquals(emptyList(), store.snapshot(), "$how: the withdrawn row stays gone")
            assertEquals(1, handler.runs, "$how: it was not run again")
        }
    }

    @Test
    fun `an effect that replaces its own key from inside execute is superseded and the replacement stays queued untouched`() = runTest {
        val store = InMemoryUploaderStore()
        lateinit var engine: UploaderEngine
        val handler = SelfWithdraw({ engine }, "replace")
        engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "v1", uniqueKey = "k", tag = "mine")

        runCatching { withTimeout(5.seconds) { engine.drain() } }

        val left: List<UploaderItem> = store.snapshot()
        assertEquals(1, left.size, "only the replacement remains: $left")
        assertEquals("v2", left.single().payload)
        assertTrue(left.single().attempts <= 1, "the superseded attempt spent nothing: ${left.single()}")
        assertEquals(UploaderItemState.PENDING, left.single().state)
    }

    @Test
    fun `cancelling the scope an engine was created in makes it inert and unregistered and nothing hangs`() = runTest {
        val scope = CoroutineScope(Job())
        val store = InMemoryUploaderStore()
        val handler = Follow()
        val engine = createUploaderEngine(store, listOf(handler), scope)
        engine.start()
        UploaderEngineRegistry.register(engine)
        scope.coroutineContext[Job]!!.cancel()
        runCurrent()

        val id: String? = engine.enqueue(handler, "late")
        val drained: Boolean = withTimeout(10.seconds) { engine.awaitDrained(0.seconds) }
        UploaderEngineRegistry.register(engine)

        assertNotNull(id)
        assertNull(UploaderEngineRegistry.current, "a stopped engine is never handed out")
        assertTrue(!drained)
        engine.close()
    }

    @Test
    fun `an engine built on a scope that is already cancelled starts as a no-op and is not registered`() = runTest {
        val scope = CoroutineScope(Job()).also { it.coroutineContext[Job]!!.cancel() }
        val handler = Follow()
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), scope)

        engine.start()
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "x")
        runCurrent()

        assertEquals(emptyList(), handler.seen)
        assertNull(UploaderEngineRegistry.current)
    }

    @Test
    fun `the in-memory store takes ten thousand enqueues in reasonable time`() = runTest {
        val follow = Follow()
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(follow), backgroundScope)
        val storeMillis: Long = withContext(Dispatchers.Default) {
            val mark = TimeSource.Monotonic.markNow()
            repeat(10_000) { engine.enqueue(follow, "p$it") }
            mark.elapsedNow().inWholeMilliseconds
        }
        assertEquals(10_000, store.snapshot().size)
        assertTrue(storeMillis < 30_000, "10000 enqueues into the in-memory store took $storeMillis ms")
    }

    @Test
    fun `a FakeUploader result function that throws surfaces to the caller after the call was recorded`() = runTest {
        val fake = FakeUploader { error("test double hook failed") }

        val outcome: Throwable? = runCatching { fake.enqueue(Follow(), "x") }.exceptionOrNull()

        assertTrue(outcome is IllegalStateException)
        assertEquals(1, fake.enqueued.size)
    }
}
