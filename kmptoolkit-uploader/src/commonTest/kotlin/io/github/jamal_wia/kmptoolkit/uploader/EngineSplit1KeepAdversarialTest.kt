package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** The KEEP half of rule P8 that the boss round 4 suspects leave out: a KEEP refused by a live holder withdraws nothing. */
class EngineSplit1KeepAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    private class RecordingTransport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events += "launch:$itemId"
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += if (isRehandOff) "relaunch:$itemId" else "launch:$itemId"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class ProceedHandler(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "split1.keep.upload"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy()
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    @Test
    fun `a KEEP refused by an in-flight holder cancels nothing and keeps the holder`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("split1k")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar-v1", uniqueKey = "avatar"))
            engine.drain()
            assertEquals(UploaderItemState.IN_FLIGHT, store.find(id)?.state, "setup: the holder is in flight")

            val second: String? = engine.enqueue(handler, "avatar-v2", uniqueKey = "avatar")

            assertNull(second, "the KEEP is refused by the live holder")
            assertEquals(listOf("launch:$id"), transport.events, "nothing was cancelled or launched")
            assertEquals(UploaderItemState.IN_FLIGHT, store.find(id)?.state, "the holder is untouched")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a KEEP refused by a pending holder cancels nothing and keeps the holder`() = runTest {
        val parent = Job()
        try {
            val store = TestUploaderStore()
            val transport = RecordingTransport()
            val handler = ProceedHandler(transport)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("split1p")::next)
            val id: String = assertNotNull(engine.enqueue(handler, "avatar-v1", uniqueKey = "avatar"))

            val second: String? = engine.enqueue(handler, "avatar-v2", uniqueKey = "avatar")

            assertNull(second, "the KEEP is refused by the pending holder")
            assertEquals(emptyList(), transport.events, "nothing was cancelled")
            assertEquals(UploaderItemState.PENDING, store.find(id)?.state, "the holder is untouched")
        } finally {
            parent.cancel()
        }
    }
}
