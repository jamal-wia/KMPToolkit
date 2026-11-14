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

/** Round 8 SUSPECT R8-S2: drain() on a closed engine still runs handlers although close() makes it permanently inert. */
class ClosedEngineDrainRound8SuspectTest {

    private class H : UploaderHandler<String> {
        val seen: MutableList<String> = mutableListOf()
        override val type: String = "t"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            seen += payload
            return AttemptResult.Success
        }
    }

    @Test
    fun `after close even a manual drain runs nothing - the engine is permanently inert`() = runTest {
        val store = InMemoryUploaderStore()
        val handler = H()
        val engine = createUploaderEngine(store, listOf(handler), backgroundScope)
        engine.close()
        engine.enqueue(handler, "late")

        withTimeout(5.seconds) { engine.drain() }

        assertEquals(emptyList(), handler.seen, "drain() delivered through a closed engine")
        assertEquals(1, store.snapshot().size)
    }
}
