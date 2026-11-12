package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (hostile consumer, iOS): the documented contract of `BackgroundTaskWakeScheduler.handleWake` — "onDone runs
 * even if the drain throws" — and the wake configuration, none of which had a test of its own. Nothing here submits
 * a request to BGTaskScheduler.
 */
class WakeHandlerRound8AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class Handler : UploaderHandler<String> {
        override val type: String = "wake"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Success
    }

    private class AlwaysFailingReads(inner: TestUploaderStore) : UploaderStore by inner {
        override suspend fun getAllActive(): List<UploaderItem> = error("database is locked")
    }

    private suspend fun wake(config: BackgroundTaskWakeConfig): Boolean {
        val scheduler: BackgroundTaskWakeScheduler = createBackgroundTaskWakeScheduler(config)
        val done = CompletableDeferred<Boolean>()
        scheduler.handleWake { drained -> done.complete(drained) }
        return withContext(Dispatchers.Default) { withTimeout(10.seconds) { done.await() } }
    }

    private val quick = BackgroundTaskWakeConfig(engineWait = 50.milliseconds, drainBudget = 200.milliseconds)

    @Test
    fun `with no engine registered the wake reports unfinished once the engine wait is over`() = runTest {
        assertFalse(wake(quick))
    }

    @Test
    fun `with an engine and an empty queue the wake reports drained`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(Handler()), backgroundScope)
        UploaderEngineRegistry.register(engine)

        assertTrue(wake(quick))
        engine.close()
    }

    @Test
    fun `a store that fails every read ends the wake as unfinished and still calls onDone`() = runTest {
        val engine: UploaderEngine = testEngine(AlwaysFailingReads(TestUploaderStore()), listOf(Handler()), backgroundScope)
        UploaderEngineRegistry.register(engine)

        assertFalse(wake(quick))
        engine.close()
    }

    @Test
    fun `two wakes at once each report exactly once`() = runTest {
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(Handler()), backgroundScope)
        UploaderEngineRegistry.register(engine)
        val scheduler: BackgroundTaskWakeScheduler = createBackgroundTaskWakeScheduler(quick)
        val calls = MutableStateFlow<List<Boolean>>(emptyList())
        val both = CompletableDeferred<Unit>()

        repeat(2) {
            scheduler.handleWake { drained ->
                calls.update { it + drained }
                if (calls.value.size == 2) both.complete(Unit)
            }
        }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { both.await() } }
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) }

        assertEquals(listOf(true, true), calls.value)
        engine.close()
    }

    @Test
    fun `the task identifier is the configured one verbatim or the bundle derived default`() {
        assertEquals("com.example.drain", createBackgroundTaskWakeScheduler(BackgroundTaskWakeConfig(taskIdentifier = "com.example.drain")).taskIdentifier)
        assertTrue(createBackgroundTaskWakeScheduler().taskIdentifier.endsWith(".uploader.drain"))
    }

    @Test
    fun `the wake configuration rejects blank identifiers and non-positive durations`() {
        for (blank in listOf("", " ", "\t")) assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(taskIdentifier = blank) }
        for (d in listOf(0.milliseconds, (-1).milliseconds)) {
            assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(engineWait = d) }
            assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(drainBudget = d) }
        }
        assertEquals(BackgroundTaskWakeConfig(), BackgroundTaskWakeConfig().copy())
    }

    @Test
    fun `cancelWake is harmless when nothing was scheduled and when called twice`() {
        val scheduler: BackgroundTaskWakeScheduler = createBackgroundTaskWakeScheduler(quick)
        scheduler.cancelWake()
        scheduler.cancelWake()
    }

    @Suppress("unused")
    private fun TestScope.unused(): Unit = Unit
}
