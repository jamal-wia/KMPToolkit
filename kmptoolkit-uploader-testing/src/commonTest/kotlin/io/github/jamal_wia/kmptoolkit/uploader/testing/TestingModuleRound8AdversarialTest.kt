package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResponse
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (hostile consumer): the -testing module as the consumer's tool. Do the doubles behave like the classes
 * they stand in for, does the contract hold a wrong store to account, and does the engine survive hostile
 * collaborators (logger, wake scheduler, clock, id generator, transport).
 */
class TestingModuleRound8AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class StringHandler(override val type: String = "h") : UploaderHandler<String> {
        val seen: MutableList<String> = mutableListOf()
        var behave: () -> AttemptResult = { AttemptResult.Success }
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            seen += payload
            return behave()
        }
    }

    private class FileHandler(
        transport: io.github.jamal_wia.kmptoolkit.uploader.UploadTransport,
        override val type: String = "up",
    ) : UploadHandler<String>(transport) {
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(
                UploadRequest(
                    url = "https://example.com/u",
                    headers = mapOf("Authorization" to "Bearer SECRET-TOKEN"),
                    fields = listOf(io.github.jamal_wia.kmptoolkit.uploader.UploadField.Text("f", "SECRET-FIELD")),
                ),
            )
    }

    // ---- doubles versus the real classes -------------------------------------------------------------

    @Test
    fun `MutableUploaderClock never loses an advance across threads and moves backwards on request`() = runTest {
        val clock = MutableUploaderClock(initial = 100L)

        withContext(Dispatchers.Default) {
            (0 until 8).map { async { repeat(5_000) { clock.advanceBy(3) } } }.awaitAll()
        }
        assertEquals(100L + 8 * 5_000 * 3, clock.nowEpochMillis())
        clock.advanceBy(-1_000_000)
        assertEquals(100L + 120_000 - 1_000_000, clock.nowMillis)
        clock.nowMillis = Long.MIN_VALUE
        assertEquals(Long.MIN_VALUE, clock.nowEpochMillis())
    }

    @Test
    fun `RecordingWakeScheduler counts every call across threads and isArmed follows the last call`() = runTest {
        val wake = RecordingWakeScheduler()

        withContext(Dispatchers.Default) {
            (0 until 8).map { async { repeat(3_000) { wake.scheduleWake(); wake.cancelWake() } } }.awaitAll()
        }

        assertEquals(24_000, wake.scheduleCount)
        assertEquals(24_000, wake.cancelCount)
        wake.scheduleWake()
        assertTrue(wake.isArmed)
        wake.cancelWake()
        assertFalse(wake.isArmed)
    }

    @Test
    fun `MutableConstraintProvider publishes every change as a StateFlow value`() = runTest {
        val provider = MutableConstraintProvider("k", satisfied = false)
        assertEquals("k", provider.key)
        assertFalse(provider.satisfied.value)
        provider.set(true)
        assertTrue(provider.satisfied.value)
        provider.block()
        assertFalse(provider.satisfied.value)
        provider.satisfy()
        provider.satisfy()
        assertTrue(provider.satisfied.value)
        assertTrue(MutableConstraintProvider("d").satisfied.value, "the default is satisfied")
    }

    @Test
    fun `InMemoryUploaderStore copies its initial items and its snapshot never changes underneath a reader`() = runTest {
        val initial: MutableList<UploaderItem> = mutableListOf(item("a"), item("b"))
        val store = InMemoryUploaderStore(initial)
        initial.clear()
        val before: List<UploaderItem> = store.snapshot()

        store.deleteById("a")
        store.insertKeep(item("c"))

        assertEquals(listOf("a", "b"), before.map { it.id })
        assertEquals(listOf("b", "c"), store.snapshot().map { it.id })
        assertEquals(store.snapshot(), store.items.value)
    }

    @Test
    fun `a launch the platform scheduler refuses becomes a retry and a later launch goes through`() = runTest {
        val clock = MutableUploaderClock(1_000_000L)
        val transport = RecordingUploadTransport()
        transport.failLaunchWith = IllegalStateException("scheduler unavailable")
        val handler = FileHandler(transport)
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, clock = clock)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "p")

        withTimeout(5.seconds) { engine.drain() }

        val item: UploaderItem = store.snapshot().single()
        assertEquals(UploaderItemState.PENDING, item.state)
        assertEquals(1, item.attempts)
        assertEquals(emptyList(), transport.launches)

        transport.failLaunchWith = null
        clock.advanceBy(600_000)
        withTimeout(5.seconds) { engine.drain() }

        assertEquals(1, transport.launches.size)
        assertEquals(UploaderItemState.IN_FLIGHT, store.snapshot().single().state)
        engine.close()
    }

    @Test
    fun `the transport double records the re-hand flag the engine sets after an expired lease`() = runTest {
        val clock = MutableUploaderClock(1_000L)
        val transport = RecordingUploadTransport(leaseMillis = 5_000L)
        val handler = FileHandler(transport)
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope, clock = clock)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "p")
        engine.drain()
        clock.advanceBy(5_001)

        engine.drain()

        assertEquals(listOf(false, true), transport.launches.map { it.isRehandOff })
        assertEquals(1, transport.launches.map { it.itemId }.toSet().size)
        engine.close()
    }

    @Test
    fun `a transport double with a non-positive lease parks the item instead of spinning`() = runTest {
        for (lease in listOf(0L, -1L, Long.MIN_VALUE)) {
            val transport = RecordingUploadTransport(leaseMillis = lease)
            val handler = FileHandler(transport)
            val store = InMemoryUploaderStore()
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            UploaderEngineRegistry.register(engine)
            engine.enqueue(handler, "p")

            withTimeout(5.seconds) { engine.drain() }

            assertEquals(UploaderItemState.PARKED, store.snapshot().single().state, "lease $lease")
            assertTrue(transport.launches.size <= 1)
            engine.close()
        }
    }

    // ---- the contract holds wrong stores to account -----------------------------------------------------

    private abstract class Mutant(val inner: InMemoryUploaderStore) : UploaderStore by inner

    private fun mutants(): Map<String, () -> UploaderStore> = mapOf(
        "markInFlight ignores the expected claim" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun markInFlight(
                    id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long,
                ): Boolean = inner.getById(id)?.let { cur ->
                    inner.markInFlight(id, leaseUntilEpochMillis, cur.state, cur.claim)
                } ?: false
            }
        },
        "park ignores the expected claim" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
                    inner.getById(id)?.let { cur -> inner.park(id, lastError, cur.state, cur.claim) } ?: false
            }
        },
        "deleteByTag returns nothing" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun deleteByTag(tag: String): List<UploaderItem> {
                    inner.deleteByTag(tag)
                    return emptyList()
                }
            }
        },
        "getAllActive in reverse order" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun getAllActive(): List<UploaderItem> = inner.getAllActive().reversed()
            }
        },
        "insertKeep always inserts" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun insertKeep(record: UploaderItem): KeepResult = inner.insertReplace(record).let { KeepResult.Inserted(null) }
            }
        },
        "getById drops the last error" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun getById(id: String): UploaderItem? = inner.getById(id)?.copy(lastError = null)
            }
        },
        "deleteById removes by id prefix" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun deleteById(id: String) {
                    inner.getAllActive().filter { it.id.startsWith(id) }.forEach { inner.deleteById(it.id) }
                }
            }
        },
        "recordFailure never clears the lease" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun recordFailure(
                    id: String, attempts: Int, nextRunAtEpochMillis: Long, lastError: String?,
                    expectedState: UploaderItemState, expectedClaim: Long,
                    disposition: io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition,
                ): Boolean {
                    val lease: Long = inner.getById(id)?.leaseUntilEpochMillis ?: 0L
                    val applied: Boolean = inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
                    if (applied && lease != 0L && disposition == io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition.RETRY) {
                        // re-stamp the old lease on the retried row
                        inner.markInFlight(id, lease, UploaderItemState.PENDING, expectedClaim + 1)
                    }
                    return applied
                }
            }
        },
        "clearAll leaves parked rows" to {
            object : Mutant(InMemoryUploaderStore()) {
                override suspend fun clearAll() {
                    inner.getAllActive().forEach { inner.deleteById(it.id) }
                }
            }
        },
    )

    @Test
    fun `the contract rejects every mutant store with a violation that names the broken invariant`() = runTest {
        val survivors: MutableList<String> = mutableListOf()
        for ((name, factory) in mutants()) {
            val outcome: Throwable? = runCatching { UploaderStoreContract(factory).verifyAll() }.exceptionOrNull()
            if (outcome == null) {
                survivors += name
            } else {
                assertIs<UploaderStoreContractViolation>(outcome, "mutant '$name' failed with $outcome")
                assertTrue(outcome.message.orEmpty().isNotBlank(), "mutant '$name' gave an empty message")
                assertIs<AssertionError>(outcome)
            }
        }
        assertEquals(emptyList(), survivors, "mutants the contract did not catch")
    }

    @Test
    fun `a factory that returns one shared store is a misuse the contract reports as a violation not a hang`() = runTest {
        val shared = InMemoryUploaderStore()

        val outcome: Throwable? = withTimeout(30.seconds) {
            runCatching { UploaderStoreContract { shared }.verifyAll() }.exceptionOrNull()
        }

        assertNotNull(outcome, "leftovers of one check must be noticed")
    }

    // ---- hostile collaborators ---------------------------------------------------------------------------

    private class RecordingLogger : Logger {
        override val tag: String = "r8"
        val lines: MutableList<String> = mutableListOf()
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            lines += message() + (throwable?.let { " :: $it" } ?: "")
        }
    }

    @Test
    fun `no log line of a full upload lifecycle contains the payload a header value a field value or the body`() = runTest {
        val logger = RecordingLogger()
        val transport = RecordingUploadTransport()
        val handler = object : UploadHandler<String>(transport) {
            override val type: String = "leak"
            override fun encodePayload(payload: String): String = payload
            override fun decodePayload(raw: String): String = raw
            override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
                FileHandler(transport).prepareUpload(context, payload)
        }
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope, logger = logger)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "SECRET-PAYLOAD")
        engine.enqueue(handler, "SECRET-PAYLOAD-2", tag = "t", uniqueKey = "k")
        engine.drain()
        val ids: List<String> = transport.launches.map { it.itemId }
        UploadGateway.progress(ids[0], 0.5f)
        UploadGateway.complete(
            ids[0],
            UploadResponse(500, mapOf("Authorization" to "SECRET-RESP-HEADER"), "SECRET-BODY".encodeToByteArray(), true),
        )
        UploadGateway.complete(
            ids[1],
            UploadResponse(201, mapOf("X-Token" to "SECRET-RESP-HEADER"), "SECRET-BODY".encodeToByteArray(), false),
        )
        engine.cancelByTag("none")
        engine.settle(ids[0], 0, SettleResult.Park("why"))
        engine.close()

        assertTrue(logger.lines.isNotEmpty(), "the logger saw the lifecycle")
        val leaks: List<String> = logger.lines.filter { "SECRET" in it }
        assertEquals(emptyList(), leaks)
    }

    @Test
    fun `a logger that throws from isLoggable and from log cannot stop the engine`() = runTest {
        val hostile = object : Logger {
            override val tag: String = "boom"
            override fun isLoggable(level: LogLevel): Boolean = error("isLoggable")
            override fun log(level: LogLevel, throwable: Throwable?, message: () -> String): Unit = error("log")
        }
        val handler = StringHandler()
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, logger = hostile)

        val id: String? = engine.enqueue(handler, "x")
        withTimeout(5.seconds) { engine.drain() }
        engine.cancel("nothing")

        assertNotNull(id)
        assertEquals(listOf("x"), handler.seen)
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `a wake scheduler that throws despite its contract leaves exactly one persisted row and a working engine`() = runTest {
        val bad = object : WakeScheduler {
            override fun scheduleWake(): Unit = error("scheduler")
            override fun cancelWake(): Unit = error("scheduler")
        }
        val handler = StringHandler()
        val store = InMemoryUploaderStore()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope, wakeScheduler = bad)

        runCatching { engine.enqueue(handler, "x") }
        runCatching { withTimeout(5.seconds) { engine.drain() } }

        assertTrue(store.snapshot().size <= 1)
        assertTrue(handler.seen.size <= 1, "never delivered twice: ${handler.seen}")
    }

    @Test
    fun `an id generator that throws fails the enqueue cleanly and one that repeats an id never loses the first item`() =
        runTest {
            val handler = StringHandler()
            val storeA = InMemoryUploaderStore()
            val throwing = createUploaderEngine(storeA, listOf(handler), backgroundScope, idGenerator = { error("ids") })
            assertFailsWith<IllegalStateException> { throwing.enqueue(handler, "x") }
            assertEquals(emptyList(), storeA.snapshot())

            val storeB = InMemoryUploaderStore()
            val constant = createUploaderEngine(storeB, listOf(handler), backgroundScope, idGenerator = { "same" })
            val first: String? = constant.enqueue(handler, "one")
            runCatching { constant.enqueue(handler, "two") }

            assertEquals("same", first)
            assertTrue(storeB.snapshot().any { it.payload == "one" } || storeB.snapshot().any { it.payload == "two" })
        }

    @Test
    fun `a clock at the end of the Long range never wraps a gate or a lease to a negative time`() = runTest {
        for (now in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 400_000)) {
            val clock = MutableUploaderClock(now)
            val handler = StringHandler()
            handler.behave = { AttemptResult.Retry() }
            val store = InMemoryUploaderStore()
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope, clock = clock)
            engine.enqueue(handler, "x")

            withTimeout(5.seconds) { engine.drain() }
            val retried: UploaderItem = store.snapshot().single()
            assertTrue(retried.nextRunAtEpochMillis >= now, "clock $now: gate ${retried.nextRunAtEpochMillis} wrapped")

            handler.behave = { AttemptResult.Detached(60_000) }
            store.deleteById(retried.id)
            engine.enqueue(handler, "y")
            withTimeout(5.seconds) { engine.drain() }
            val leased: UploaderItem = store.snapshot().single()
            assertTrue(leased.leaseUntilEpochMillis >= now, "clock $now: lease ${leased.leaseUntilEpochMillis} wrapped")
        }
    }

    // ---- registry misuse -----------------------------------------------------------------------------------

    @Test
    fun `registry - double register foreign unregister and unregister of nothing are all harmless`() = runTest {
        val handler = StringHandler()
        val a = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        val b = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)

        UploaderEngineRegistry.unregister(a) // never registered
        UploaderEngineRegistry.register(a)
        UploaderEngineRegistry.register(a)
        assertTrue(UploaderEngineRegistry.current === a)
        UploaderEngineRegistry.register(b)
        UploaderEngineRegistry.unregister(a) // a no longer holds the slot
        assertTrue(UploaderEngineRegistry.current === b)
        b.close()
        assertNull(UploaderEngineRegistry.current)
        a.close()
        UploaderEngineRegistry.register(a) // closed: ignored
        assertNull(UploaderEngineRegistry.current)
        assertNull(UploaderEngineRegistry.await(0.seconds))
        assertNull(UploaderEngineRegistry.await((-3).seconds))
    }

    @Test
    fun `gateway - no engine returns unavailable and false within the wait bad fractions never throw`() = runTest {
        assertEquals(io.github.jamal_wia.kmptoolkit.uploader.UploadAttempt.EngineUnavailable, UploadGateway.prepareAttempt("x", 0.seconds))
        assertFalse(UploadGateway.complete("x", UploadResult.Completed(200), 0.seconds))
        assertFalse(UploadGateway.complete("x", UploadResponse(200, emptyMap(), ByteArray(0), false), (-1).seconds))
        for (fraction in listOf(Float.NaN, -1f, 2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE)) {
            UploadGateway.progress("x", fraction)
        }
        val handler = StringHandler()
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        for (fraction in listOf(Float.NaN, -1f, 2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            UploadGateway.progress("unknown", fraction)
            UploadGateway.progress("", fraction)
        }
        assertEquals(io.github.jamal_wia.kmptoolkit.uploader.UploadAttempt.NothingOwed, UploadGateway.prepareAttempt("", 0.seconds))
        assertTrue(UploadGateway.complete("", UploadResult.TransportFailure(null), 0.seconds))
        engine.close()
    }

    @Test
    fun `a REPLACE of an in-flight upload through the doubles cancels the old upload and the old id completes as a no-op`() =
        runTest {
            val transport = RecordingUploadTransport()
            val handler = FileHandler(transport)
            val store = InMemoryUploaderStore()
            val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
            UploaderEngineRegistry.register(engine)
            engine.enqueue(handler, "v1", uniqueKey = "k")
            engine.drain()
            val old: String = transport.launches.single().itemId
            engine.enqueue(handler, "v2", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)

            assertEquals(listOf(old), transport.cancels)
            assertTrue(UploadGateway.complete(old, UploadResponse(201, emptyMap(), ByteArray(0), false)))
            assertEquals(listOf("v2"), store.snapshot().map { it.payload })
            engine.close()
        }

    private fun item(id: String): UploaderItem = UploaderItem(
        id = id, type = "t", payload = "p", schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L,
        lastError = null,
    )
}
