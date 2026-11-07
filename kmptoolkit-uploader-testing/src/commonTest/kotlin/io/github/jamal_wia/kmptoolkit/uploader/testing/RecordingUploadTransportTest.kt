package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadResponse
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.defaultUploadClassification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class RecordingUploadTransportTest {

    private val request = UploadRequest(url = "https://example.com", fields = emptyList())

    @Test
    fun `both launch overloads are recorded with the re-hand flag`() {
        val transport = RecordingUploadTransport()

        transport.launch("a", request)
        transport.launch("b", isRehandOff = true, request = request)

        assertEquals(
            listOf(
                RecordingUploadTransport.Launch("a", false, request),
                RecordingUploadTransport.Launch("b", true, request),
            ),
            transport.launches,
        )
    }

    @Test
    fun `a scripted failure is thrown and nothing is recorded`() {
        val transport = RecordingUploadTransport()
        transport.failLaunchWith = IllegalStateException("unavailable")

        assertFailsWith<IllegalStateException> { transport.launch("a", request) }
        assertEquals(emptyList(), transport.launches)
    }

    @Test
    fun `cancels are recorded per item in order and the lease is configurable`() {
        val transport = RecordingUploadTransport(leaseMillis = 5)
        transport.launch("a", request)

        transport.cancel("b")
        transport.cancel("a")

        assertEquals(listOf("b", "a"), transport.cancels)
        assertEquals(5L, transport.leaseMillis)
    }
}

/** The flow the [RecordingUploadTransport] KDoc shows, end to end through the new response overload. */
class RecordingUploadTransportResponseTest {

    private class AvatarHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "avatar"
        val delivered: MutableList<UploadResponse?> = mutableListOf()
        val classified: MutableList<UploadResponse?> = mutableListOf()

        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "https://example.com/avatar", fields = emptyList()))

        override suspend fun classify(payload: String, result: UploadResult, response: UploadResponse?): SettleResult {
            classified += response
            return defaultUploadClassification(result)
        }

        override suspend fun onDelivered(payload: String, response: UploadResponse?) {
            delivered += response
        }
    }

    @Test
    fun `completing a recorded launch with a response hands it to the handler`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = AvatarHandler(transport)
        val store = InMemoryUploaderStore()
        val engine: UploaderEngine = createUploaderEngine(store, listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        try {
            engine.enqueue(handler, "me")
            engine.drain()
            val itemId: String = transport.launches.single().itemId

            val sent = UploadResponse(201, mapOf("Location" to "/avatars/7"), """{"id":7}""".encodeToByteArray(), false)
            assertTrue(UploadGateway.complete(itemId, sent, 1.seconds))

            assertEquals(listOf<UploadResponse?>(sent), handler.delivered)
            assertEquals(listOf<UploadResponse?>(sent), handler.classified)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a status-only completion leaves the handler's response null`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = AvatarHandler(transport)
        val engine: UploaderEngine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        try {
            engine.enqueue(handler, "me")
            engine.drain()

            UploadGateway.complete(transport.launches.single().itemId, UploadResult.Completed(201), 1.seconds)

            assertEquals(listOf<UploadResponse?>(null), handler.delivered)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `completing an unknown id with a response is a no-op`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = AvatarHandler(transport)
        val engine: UploaderEngine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        try {
            assertTrue(UploadGateway.complete("nope", UploadResponse(200, emptyMap(), ByteArray(0), false), 1.seconds))

            assertTrue(handler.delivered.isEmpty())
        } finally {
            engine.close()
        }
    }
}
