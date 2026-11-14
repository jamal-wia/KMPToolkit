package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 1: killers of two hand-made mutants (B18, B25) that the whole uploader suite let through. They
 * drive the engine with [UploaderEngine.start], the way a close() really lands in an attempt: in the engine's own
 * coroutine, which close() cancels. (The boss's first versions used a manual drain(), which close() did not stop
 * before decision D-29, so they could not tell the mutants apart.)
 */
class EngineBossStartKillersRound1AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 15 * 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    /** A handler whose preparation runs [during] (a close), without suspending, and then answers or throws as told. */
    private class PreparingHandler(transport: UploadTransport, private val answer: () -> UploadPreparation) :
        UploadHandler<String>(transport) {
        var during: (() -> Unit)? = null
        override val type: String = "boss1.killer"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            during?.let { hook ->
                during = null
                hook()
            }
            return answer()
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /**
     * P2 and P5: every answer of a cancelled attempt records nothing. A handler that ignores the close landing during its
     * preparation and answers Drop must not delete the row of the closed engine. (Mutant B18: the "abandoned hand-off"
     * check after prepareUpload removed.)
     */
    @Test
    fun `a drop answered after close by a started engine's preparation deletes nothing`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = Transport()
            val handler = PreparingHandler(transport) { UploadPreparation.Drop("not needed any more") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            handler.during = { engine.close() }
            engine.start()
            runCurrent()
            assertEquals(UploaderItemState.PENDING, store.find(id)?.state, "the closed engine's attempt dropped the row")
            assertEquals(emptyList(), transport.events)
        } finally {
            parent.cancel()
        }
    }

    /**
     * P5: a throw from prepareUpload that arrives after the attempt's cancellation spends no attempt. (Mutant B25: the
     * drain side's catch charges it.)
     */
    @Test
    fun `a preparation that throws after close in a started engine spends no attempt`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = Transport()
            val handler = PreparingHandler(transport) { throw IllegalStateException("socket closed by the stop") }
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(1_000L))
            val id: String = engine.enqueue(handler, "x")!!
            handler.during = { engine.close() }
            engine.start()
            runCurrent()
            val row: UploaderItem? = store.find(id)
            assertEquals(UploaderItemState.PENDING, row?.state)
            assertEquals(0, row?.attempts, "the stop's own failure was charged to the item")
            assertEquals(emptyList(), transport.events)
        } finally {
            parent.cancel()
        }
    }
}
