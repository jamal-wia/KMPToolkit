package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `close()` called while the drain pass is running, against the published in-memory store (whose calls do
 * not suspend, as a custom store's need not): the queue's remaining items must not run after the close.
 *
 * Hazard: a logout path closes the engine and wipes the account's data while a handler is mid-attempt; an
 * item that runs after `close()` returned runs for an account that is gone.
 */
class UploaderEngineCloseMidPassAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private object OneSecondPolicy : RetryPolicy {
        override val maxDelayMillis: Long = 1_000L
        override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
        override fun backoffMillis(attempts: Int): Long = 1_000L
    }

    private class ClosingHandler(private val onFirst: () -> Unit) : UploaderHandler<String> {
        override val type: String = "adv.closing"
        override val retryPolicy: RetryPolicy = OneSecondPolicy
        val executed: MutableList<String> = mutableListOf()
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            executed += payload
            if (executed.size == 1) onFirst()
            return AttemptResult.Success
        }
    }

    @Test
    fun `close called from inside a handler stops the pass so no later item runs after the close`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
                val store = InMemoryUploaderStore()
                lateinit var engine: UploaderEngine
                val handler = ClosingHandler(onFirst = { engine.close() })
                engine = createUploaderEngine(store, listOf(handler), scope)
                listOf("a", "b", "c").forEach { engine.enqueue(handler, it) }

                engine.start()
                runCurrent()

                assertEquals(listOf("a"), handler.executed, "items ran after close() had returned: ${handler.executed}")
                assertEquals(listOf("b", "c"), store.snapshot().map { it.payload }, "b and c are still owed")
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `cancelling the parent scope from inside a handler stops the pass so no later item runs`() =
        runTest(timeout = 30.seconds) {
            val parent = Job()
            try {
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
                val store = InMemoryUploaderStore()
                val handler = ClosingHandler(onFirst = { parent.cancel() })
                val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), scope)
                listOf("a", "b", "c").forEach { engine.enqueue(handler, it) }

                engine.start()
                runCurrent()

                assertEquals(listOf("a"), handler.executed, "items ran after the owning scope was cancelled: ${handler.executed}")
            } finally {
                parent.cancel()
            }
        }
}
