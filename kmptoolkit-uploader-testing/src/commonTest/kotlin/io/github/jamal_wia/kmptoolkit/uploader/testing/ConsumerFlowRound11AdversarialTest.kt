package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResponse
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * A2 round 11, Q3: the public API the way a consumer drives it with the `-testing` doubles (the file layer of the
 * outcome journal belongs to the platform transports and is covered by their own tests): enqueue, progress, a failed
 * attempt with its backoff, the retry, a delivery with its captured body and headers, a tag wipe and a REPLACE. The
 * documented hooks run in the documented order, once each, and the transport sees the documented launches and cancels.
 */
class ConsumerFlowRound11AdversarialTest {

    private class FlowHandler(transport: RecordingUploadTransport, val events: MutableList<String>) :
        UploadHandler<String>(transport) {
        override val type: String = "r11.flow"
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 10_000L, maxDelayMillis = 60_000L, jitterRatio = 0.0)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            events += "prepare:$payload:attempt${context.attempts}:rehand=${context.wasDetached}"
            return UploadPreparation.Proceed(UploadRequest("https://example.com/up/$payload", fields = emptyList()))
        }

        override suspend fun onUploadProgress(payload: String, fraction: Float) {
            events += "progress:$payload:$fraction"
        }

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            events += "classify:$payload:$result:body=${response?.body()?.decodeToString()}"
            return super.classify(payload, result, response)
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            events += "delivered:$payload:${response?.statusCode}:${response?.header("Location")}:${response?.body()?.decodeToString()}"
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            events += "settled:$payload:attempts=$attempts:${result::class.simpleName}"
        }
    }

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    @Test
    fun `the documented flow runs its hooks in order and the transport sees the documented launches and cancels`() = runTest {
        val store = InMemoryUploaderStore()
        val clock = MutableUploaderClock(1_000_000L)
        val wake = RecordingWakeScheduler()
        val transport = RecordingUploadTransport()
        val events: MutableList<String> = mutableListOf()
        val handler = FlowHandler(transport, events)
        var n = 0
        val engine: UploaderEngine = createUploaderEngine(
            store = store, handlers = listOf(handler), scope = backgroundScope, wakeScheduler = wake, clock = clock,
            idGenerator = { "flow-${++n}" },
        )
        UploaderEngineRegistry.register(engine)

        // Enqueue and the first hand-off.
        val a: String = checkNotNull(engine.enqueue(handler, "a", tag = "photos"))
        engine.drain()
        assertEquals(listOf("prepare:a:attempt0:rehand=false"), events)
        assertEquals(listOf(a), transport.launches.map { it.itemId })
        assertEquals(UploaderItemState.IN_FLIGHT, store.snapshot().single().state)

        // Progress, then a server error: classified, settled as a failure, retried only after the backoff.
        UploadGateway.progress(a, 0.5f)
        assertTrue(UploadGateway.complete(a, UploadResponse(503, emptyMap(), "busy".encodeToByteArray(), false), 1.seconds))
        assertEquals(
            listOf(
                "prepare:a:attempt0:rehand=false",
                "progress:a:0.5",
                "classify:a:Completed(statusCode=503):body=busy",
                "settled:a:attempts=0:Failed" // attempts as at hand-off,
            ),
            events,
        )
        assertEquals(UploaderItemState.PENDING, store.snapshot().single().state)
        engine.drain()
        assertEquals(1, transport.launches.size, "no relaunch before the backoff elapsed")
        clock.advanceBy(10_001L)
        engine.drain()
        assertEquals(2, transport.launches.size, "the retry launched once the backoff elapsed")
        assertEquals("prepare:a:attempt1:rehand=false", events.last())

        // The delivery: the captured status, header and body reach onDelivered, then onSettled, then the row is gone.
        val answer = UploadResponse(201, mapOf("Location" to "/files/9"), """{"id":9}""".encodeToByteArray(), false)
        assertTrue(UploadGateway.complete(a, answer, 1.seconds))
        assertEquals(
            listOf(
                "classify:a:Completed(statusCode=201):body={\"id\":9}",
                "delivered:a:201:/files/9:{\"id\":9}",
                "settled:a:attempts=1:Delivered",
            ),
            events.takeLast(3),
        )
        assertTrue(store.snapshot().isEmpty())

        // A tag wipe cancels every upload of the tag, in flight or not, and removes the rows.
        val b: String = checkNotNull(engine.enqueue(handler, "b", tag = "wipe"))
        val c: String = checkNotNull(engine.enqueue(handler, "c", tag = "wipe"))
        engine.drain()
        assertEquals(listOf(b, c), transport.launches.drop(2).map { it.itemId })
        engine.cancelByTag("wipe")
        assertEquals(setOf(b, c), transport.cancels.toSet())
        assertTrue(store.snapshot().isEmpty())

        // REPLACE: the superseded item's upload is cancelled, the new one runs.
        val d1: String = checkNotNull(engine.enqueue(handler, "d1", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE))
        engine.drain()
        val d2: String = checkNotNull(engine.enqueue(handler, "d2", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE))
        engine.drain()
        assertTrue(d1 in transport.cancels, "the replaced upload was cancelled: ${transport.cancels}")
        assertEquals(d2, transport.launches.last().itemId)
        assertEquals(listOf(d2), store.snapshot().map { it.id })
        // Nothing is left armed or retained after the queue settles.
        UploadGateway.complete(d2, UploadResponse(200, emptyMap(), ByteArray(0), false), 1.seconds)
        assertTrue(store.snapshot().isEmpty())
        engine.close()
    }
}
