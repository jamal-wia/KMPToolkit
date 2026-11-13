package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Refactor-regression round, tests written for mutants of the six engine components that survived the existing suites:
 * the P10 verdict refusal on a held delivery acts at once (not at a later sweep), a caller cancelled before a
 * withdrawal's write removes nothing, and a transport that fails with any throwable never fails a withdrawal.
 */
class RegressSurvivorKillsAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class FlakyDeleteStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var failDeletes: Int = 0
        override suspend fun deleteById(id: String) {
            if (failDeletes > 0) {
                failDeletes--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    private class CollectingLogger : Logger {
        val messages: MutableList<String> = mutableListOf()
        override val tag: String = "regress"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            messages += message()
        }
    }

    private fun inFlight(id: String): UploaderItem = UploaderItem(
        id = id, type = TestHandler.DEFAULT_TYPE, payload = "p", schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
        state = UploaderItemState.IN_FLIGHT, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 7L, lastError = null,
        leaseUntilEpochMillis = 9_000_000_000L, claim = 1L,
    )

    private class Fixture(val store: FlakyDeleteStore, val inner: TestUploaderStore, val engine: UploaderEngine, val logger: CollectingLogger)

    private fun TestScope.fixture(parent: Job, id: String, handler: UploaderHandler<*> = TestHandler()): Fixture {
        val inner = TestUploaderStore(listOf(inFlight(id)))
        val store = FlakyDeleteStore(inner)
        val logger = CollectingLogger()
        val engine: UploaderEngine = createUploaderEngine(
            store = store, handlers = listOf(handler), scope = scopeOn(parent), clock = TestClock(5_000_000L), logger = logger,
            idGenerator = SequentialIds("sv")::next,
        )
        return Fixture(store, inner, engine, logger)
    }

    @Test
    fun `a park verdict met by a delivery whose delete failed confirms the delete at once`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val f = fixture(parent, "held-1")
            f.store.failDeletes = 1
            assertFailsWith<IllegalStateException> { f.engine.settle("held-1", 1L, SettleResult.Delivered) }
            assertNotNull(f.inner.find("held-1"), "setup: the delete failed, the row is still there and held")

            f.engine.settle("held-1", 1L, SettleResult.Park("late verdict"))

            assertNull(f.inner.find("held-1"), "the confirming delete ran instead of the park, with no drain in between")
            assertTrue(f.logger.messages.none { it.startsWith("Settled") && "as parked" in it }, "the park is reported as not applied: ${f.logger.messages}")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a failure verdict met by a delivery whose delete failed confirms the delete at once`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val f = fixture(parent, "held-2")
            f.store.failDeletes = 1
            assertFailsWith<IllegalStateException> { f.engine.settle("held-2", 1L, SettleResult.Delivered) }

            f.engine.settle("held-2", 1L, SettleResult.Failed(IllegalStateException("late failure")))

            assertNull(f.inner.find("held-2"), "no retry was written onto a delivered row; the delete ran instead")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a caller cancelled between the read and the delete of a cancel removes nothing`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val f = fixture(parent, "gone-1")
            var outcome: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    f.inner.afterNextGetById = { coroutineContext[Job]!!.cancel() }
                    f.engine.cancel("gone-1")
                } catch (e: CancellationException) {
                    outcome = e
                    throw e
                }
            }
            job.join()

            assertTrue(outcome is CancellationException, "the caller got its cancellation")
            assertNotNull(f.inner.find("gone-1"), "a caller cancelled before the write started removes nothing (P1)")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a caller that is already cancelled removes nothing by tag`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val inner = TestUploaderStore(listOf(inFlight("tagged-1").copy(tag = "t")))
            val engine: UploaderEngine = testEngine(inner, listOf(TestHandler()), scopeOn(parent), TestClock(5_000_000L))
            var outcome: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                coroutineContext[Job]!!.cancel()
                try {
                    engine.cancelByTag("t")
                } catch (e: CancellationException) {
                    outcome = e
                    throw e
                }
            }
            job.join()

            assertTrue(outcome is CancellationException)
            assertNotNull(inner.find("tagged-1"), "nothing was wiped for a cancelled caller")
        } finally {
            parent.cancel()
        }
    }

    private class AnyThrowingTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) = Unit
        override fun cancel(itemId: String): Unit = throw UnsupportedOperationException("cancel is not supported")
    }

    private class ProceedHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = TestHandler.DEFAULT_TYPE
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    @Test
    fun `a transport whose cancel throws any exception never fails the withdrawal`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val f = fixture(parent, "up-1", ProceedHandler(AnyThrowingTransport()))

            f.engine.cancel("up-1")

            assertNull(f.inner.find("up-1"), "the row is gone and the caller saw no exception")
        } finally {
            parent.cancel()
        }
    }
}
