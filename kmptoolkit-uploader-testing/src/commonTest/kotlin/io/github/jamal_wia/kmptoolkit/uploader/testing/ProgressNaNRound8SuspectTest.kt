package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadAttempt
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Round 8 SUSPECT R8-S5: a NaN progress fraction from a transport reaches the handler's onUploadProgress as NaN. */
class ProgressNaNRound8SuspectTest {

    private class P(transport: RecordingUploadTransport) : io.github.jamal_wia.kmptoolkit.uploader.UploadHandler<String>(transport) {
        val fractions: MutableList<Float> = mutableListOf()
        override val type: String = "prog"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String) =
            io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation.Proceed(
                io.github.jamal_wia.kmptoolkit.uploader.UploadRequest("https://example.com", fields = emptyList()),
            )
        override suspend fun onUploadProgress(payload: String, fraction: Float) { fractions += fraction }
    }

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    @Test
    fun `every fraction the handler sees is a number within zero to one whatever the transport reported`() = runTest {
        val transport = RecordingUploadTransport()
        val handler = P(transport)
        val engine = createUploaderEngine(InMemoryUploaderStore(), listOf(handler), backgroundScope)
        UploaderEngineRegistry.register(engine)
        engine.enqueue(handler, "x")
        engine.drain()
        val id: String = transport.launches.single().itemId

        for (f in listOf(Float.NaN, -3f, 7f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f)) UploadGateway.progress(id, f)

        assertTrue(handler.fractions.all { !it.isNaN() && it in 0f..1f }, "the handler saw ${handler.fractions}")
        engine.close()
    }
}
