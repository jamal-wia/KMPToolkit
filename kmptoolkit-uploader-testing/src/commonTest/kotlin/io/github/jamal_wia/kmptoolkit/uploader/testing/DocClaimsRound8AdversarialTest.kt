package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResponse
import io.github.jamal_wia.kmptoolkit.uploader.UploaderConfig
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (documentation as a test), part 2: single observable sentences of docs/kmptoolkit-uploader 04-api-reference
 * and 03-guide that no earlier round pinned one by one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocClaimsRound8AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class H(
        override val type: String = "h",
        override val schemaVersion: Int = 1,
        override val constraintKeys: Set<String> = emptySet(),
        val decode: (String) -> String = { it },
    ) : UploaderHandler<String> {
        val seen: MutableList<String> = mutableListOf()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = decode(raw)
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            seen += payload
            return AttemptResult.Success
        }
    }

    // ---- 04: UploaderHandler table ------------------------------------------------------------------------

    @Test
    fun `a row with a newer schema version than the handler parks instead of being decoded`() = runTest {
        val store = InMemoryUploaderStore()
        val newer = H(schemaVersion = 2)
        createUploaderEngine(store, listOf(newer), backgroundScope).enqueue(newer, "from-the-future")
        val older = H(schemaVersion = 1)
        val engine = createUploaderEngine(store, listOf(older), backgroundScope)

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(emptyList(), older.seen)
        assertEquals(UploaderItemState.PARKED, store.snapshot().single().state)
    }

    @Test
    fun `renaming a type orphans its rows and they park visibly`() = runTest {
        val store = InMemoryUploaderStore()
        val before = H(type = "old.name")
        createUploaderEngine(store, listOf(before), backgroundScope).enqueue(before, "x")
        val after = H(type = "new.name")
        val engine = createUploaderEngine(store, listOf(after), backgroundScope)

        withTimeout(5.seconds) { engine.drain() }

        val item: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.PARKED, item.state)
        assertEquals(emptyList(), after.seen)
        assertEquals(listOf(item), engine.observe("old.name").first())
    }

    @Test
    fun `a decodePayload that throws parks the item with the failure attached and a leaked cancellation does too`() = runTest {
        for (failure in listOf<Throwable>(IllegalStateException("bad json"), CancellationException("leaked"))) {
            val store = InMemoryUploaderStore()
            val handler = H(decode = { throw failure })
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            engine.enqueue(handler, "x")

            withTimeout(5.seconds) { engine.drain() }

            val item: UploaderItem = store.snapshot().single()
            assertEquals(UploaderItemState.PARKED, item.state, "$failure")
            assertTrue(item.lastError.orEmpty().isNotBlank(), "the failure is attached for $failure")
        }
    }

    // ---- 04: ConstraintProvider ----------------------------------------------------------------------------

    private class ExplodingConstraint(override val key: String) : ConstraintProvider {
        override val satisfied: StateFlow<Boolean> = object : StateFlow<Boolean> {
            override val replayCache: List<Boolean> get() = error("replay")
            override val value: Boolean get() = error("cannot read the constraint")
            override suspend fun collect(collector: FlowCollector<Boolean>): Nothing = error("cannot collect the constraint")
        }
    }

    @Test
    fun `a constraint that throws on read or on collect skips its items and never the ones behind them`() = runTest {
        val store = InMemoryUploaderStore()
        val gated = H(type = "gated", constraintKeys = setOf("boom"))
        val free = H(type = "free")
        val engine = createUploaderEngine(
            store, listOf(gated, free), backgroundScope, constraintProviders = listOf(ExplodingConstraint("boom")),
        )
        engine.start()
        runCurrent()
        engine.enqueue(gated, "g")
        engine.enqueue(free, "f")
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf("f"), free.seen, "the item behind the unreadable constraint still ran")
        assertEquals(emptyList(), gated.seen, "an unreadable constraint is treated as unsatisfied")
        assertEquals(listOf("g"), store.snapshot().map { it.payload })
        engine.close()
    }

    // ---- 04: awaitDrained -------------------------------------------------------------------------------------

    private class FailingReads(inner: InMemoryUploaderStore) : UploaderStore by inner {
        var reads: Int = 0
        override suspend fun getAllActive(): List<UploaderItem> {
            reads++
            error("database is locked")
        }
    }

    @Test
    fun `awaitDrained treats a failing store read as not drained re-reads and ends in false at the timeout`() = runTest {
        val store = FailingReads(InMemoryUploaderStore())
        val engine = createUploaderEngine(
            store, listOf(H()), backgroundScope, config = UploaderConfig(drainPollInterval = 100.milliseconds),
        )

        val drained: Boolean = engine.awaitDrained(2.seconds)

        assertFalse(drained)
        assertTrue(store.reads >= 5, "re-read at the poll interval, read ${store.reads} times")
    }

    @Test
    fun `an in-flight item counts as not drained`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = object : UploaderHandler<String> {
            override val type: String = "d"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
                AttemptResult.Detached(600_000)
        }
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "x")
        engine.drain()

        assertFalse(engine.awaitDrained(1.seconds))
    }

    // ---- 04: UploadHandler.maxResponseBodyBytes and the header cap -----------------------------------------------

    private class Capture(transport: RecordingUploadTransport, private val limit: Int) : UploadHandler<String>(transport) {
        var delivered: UploadResponse? = null
        override val type: String = "cap"
        override val maxResponseBodyBytes: Int = limit
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com", fields = emptyList()))
        override suspend fun onDelivered(payload: String, response: UploadResponse?) { delivered = response }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.deliver(limit: Int, response: UploadResponse): UploadResponse {
        val transport = RecordingUploadTransport()
        val handler = Capture(transport, limit)
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "p")
        engine.drain()
        UploadGateway.complete(transport.launches.single().itemId, response)
        engine.close()
        return assertNotNull(handler.delivered)
    }

    @Test
    fun `the body limit is clamped to zero through one MiB and a longer body is cut and flagged`() = runTest {
        val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val cases: List<Triple<Int, Int, Boolean>> = listOf(
            Triple(Int.MIN_VALUE, 0, true),
            Triple(-1, 0, true),
            Triple(0, 0, true),
            Triple(1, 1, true),
            Triple(65_536, 65_536, true),
            Triple(1024 * 1024, 1024 * 1024, true),
            Triple(1024 * 1024 + 1, 1024 * 1024, true),
            Triple(Int.MAX_VALUE, 1024 * 1024, true),
        )
        for ((limit, expectedSize, truncated) in cases) {
            val got: UploadResponse = deliver(limit, UploadResponse(200, mapOf("h" to "v"), body, false))
            assertEquals(expectedSize, got.body().size, "limit $limit")
            assertEquals(truncated, got.isBodyTruncated, "limit $limit")
            assertEquals(body.copyOf(expectedSize).toList(), got.body().toList(), "limit $limit keeps the first bytes")
            assertEquals("v", got.header("h"))
        }
    }

    @Test
    fun `limit zero with an empty body is not truncated and a body exactly at the limit is not truncated`() = runTest {
        assertFalse(deliver(0, UploadResponse(204, emptyMap(), ByteArray(0), false)).isBodyTruncated)
        val exact: UploadResponse = deliver(4, UploadResponse(200, emptyMap(), byteArrayOf(1, 2, 3, 4), false))
        assertFalse(exact.isBodyTruncated)
        assertEquals(4, exact.body().size)
        assertTrue(deliver(4, UploadResponse(200, emptyMap(), byteArrayOf(1), true)).isBodyTruncated, "a transport's flag survives")
    }

    @Test
    fun `the engine keeps at most 64K characters of header names and values and cuts in arrival order`() = runTest {
        val headers: Map<String, String> = (0 until 1_000).associate { "h${it.toString().padStart(4, '0')}" to "v".repeat(100) }

        val got: UploadResponse = deliver(1_024, UploadResponse(200, headers, ByteArray(0), false))

        val chars: Int = got.headers.entries.sumOf { it.key.length + it.value.length }
        assertTrue(chars <= 64 * 1024, "kept $chars characters")
        assertTrue(got.headers.size in 1 until 1_000, "some kept, some cut: ${got.headers.size}")
        assertEquals(headers.keys.take(got.headers.size), got.headers.keys.toList(), "the first headers are the ones kept")
        assertFalse(got.isBodyTruncated, "a header cut is not a body truncation")
    }

    // ---- 04: ExponentialBackoffRetryPolicy formula --------------------------------------------------------------------

    @Test
    fun `the backoff is exactly minmax base times two to the n minus one without jitter and within the spread with it`() {
        for ((base, max) in listOf(1_000L to 300_000L, 1L to 1L, 7L to 1_000_000L, 2_000L to 600_000L)) {
            val flat = ExponentialBackoffRetryPolicy(base, max, jitterRatio = 0.0)
            val jittered = ExponentialBackoffRetryPolicy(base, max, jitterRatio = 0.2, random = Random(42))
            for (n in 1..64) {
                var exact: Long = minOf(base, max)
                for (i in 2..n) exact = if (exact > max / 2) max else minOf(max, exact * 2)
                assertEquals(exact, flat.backoffMillis(n), "base=$base max=$max n=$n")
                val spread: Long = jittered.backoffMillis(n)
                assertTrue(spread in 0..max, "jittered $spread outside 0..$max")
                assertTrue(spread >= (exact * 0.8).toLong() - 1 && spread <= minOf(max, (exact * 1.2).toLong() + 1), "n=$n jitter $spread vs $exact")
            }
        }
    }

    // ---- 04: lastError bound ------------------------------------------------------------------------------------------

    @Test
    fun `a failure text is cut on a character boundary near 4096 characters and a throwing message getter is read safely`() = runTest {
        val long: String = "a".repeat(4_095) + "\uD83D\uDE00" + "b".repeat(1_000_000)
        val causes: List<Throwable> = listOf(
            IllegalStateException(long),
            IllegalStateException("x".repeat(4_096)),
            object : RuntimeException() { override val message: String get() = error("getter throws") },
            IllegalStateException(null as String?),
            IllegalStateException("\u0000".repeat(10_000)),
        )
        for (cause in causes) {
            val store = InMemoryUploaderStore()
            val handler = object : UploaderHandler<String> {
                override val type: String = "f"
                override fun encodePayload(payload: String): String = payload
                override fun decodePayload(raw: String): String = raw
                override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Retry(cause)
            }
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            engine.enqueue(handler, "x")

            withTimeout(5.seconds) { engine.drain() }

            val text: String = assertNotNull(store.snapshot().single().lastError, "a failure text exists")
            assertTrue(text.length <= 4_096 + 64, "text of ${text.length} characters")
            val withoutSuffix: String = text.substringBefore("[truncated")
            if (withoutSuffix.isNotEmpty()) {
                assertFalse(withoutSuffix.last().isHighSurrogate(), "the cut split a surrogate pair")
            }
        }
    }
}
