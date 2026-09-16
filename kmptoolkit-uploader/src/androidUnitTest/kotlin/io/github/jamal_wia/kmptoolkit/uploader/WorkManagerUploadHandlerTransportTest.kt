package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The `WorkManager` transport for [UploadHandler]s, from [createWorkManagerUploadHandlerTransport]'s KDoc:
 * the job holds the item id and nothing of the request, and the worker prepares, uploads and settles
 * through [UploadGateway]. That a failed enqueue reaches the engine as a retry is covered in commonTest's
 * `UploadHandlerTest`: WorkManager's uninitialized state cannot be reproduced reliably once another test
 * in the same JVM has initialized it.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerUploadHandlerTransportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = UploadTransportConfig(uniqueWorkNamePrefix = "outbox_upload_", workTag = "outbox_upload")
    private var engine: UploaderEngine? = null

    @AfterTest
    fun closeEngine() {
        engine?.close()
    }

    private val secretRequest = UploadRequest(
        url = "https://example.com/upload",
        headers = mapOf("Authorization" to "Bearer secret-token"),
        fields = listOf(UploadField.Text("comment", "private text")),
    )

    @Test
    fun `the enqueued job carries the item id and no part of the request`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(context, config)

        transport.launch("item-7", isRehandOff = false, request = secretRequest)

        val info: WorkInfo = WorkManager.getInstance(context).getWorkInfosForUniqueWork("outbox_upload_item-7").get().single()
        assertTrue("outbox_upload" in info.tags)
        val stored: String = WorkManager.getInstance(context).getWorkInfosByTag("outbox_upload").get()
            .joinToString { it.toString() }
        assertTrue("secret-token" !in stored && "example.com" !in stored && "private text" !in stored)
    }

    @Test
    fun `a worker with no engine to prepare the attempt retries`() = runTest {
        val result: ListenableWorker.Result = worker(workDataOf(
            UploadHandlerWorker.ITEM_ID_KEY to "item-1",
            UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to 10L,
        )).doWork()

        assertIs<ListenableWorker.Result.Retry>(result)
    }

    @Test
    fun `a worker for an item that is no longer owed succeeds without uploading`() = runTest {
        val store = TestUploaderStore()
        val handler = UnreachableServerHandler()
        startEngine(store, handler)

        val result: ListenableWorker.Result = worker(workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "missing")).doWork()

        assertIs<ListenableWorker.Result.Success>(result)
        assertTrue(handler.classified.isEmpty())
    }

    @Test
    fun `a worker uploads the freshly prepared request and settles the raw outcome through the handler`() = runTest {
        val store = TestUploaderStore()
        val handler = UnreachableServerHandler()
        val engine: UploaderEngine = startEngine(store, handler)
        engine.enqueue(handler, "payload")
        engine.drain() // hands off to the recording transport, leaving the item in flight

        val result: ListenableWorker.Result = worker(workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "item-1")).doWork()

        assertIs<ListenableWorker.Result.Success>(result)
        assertIs<UploadResult.TransportFailure>(handler.classified.single())
        assertEquals(UploaderItemState.PARKED, store.find("item-1")?.state)
    }

    @Test
    fun `a subclass can read the item id from an earlier worker's input key`() = runTest {
        val store = TestUploaderStore()
        startEngine(store, UnreachableServerHandler())

        val result: ListenableWorker.Result = TestListenableWorkerBuilder<LegacyKeyWorker>(context)
            .setInputData(workDataOf("item_id" to "missing"))
            .build()
            .doWork()

        assertIs<ListenableWorker.Result.Success>(result, "item id read, nothing owed")
    }

    @Test
    fun `without an item id the worker fails`() = runTest {
        assertIs<ListenableWorker.Result.Failure>(worker(Data.EMPTY).doWork())
    }

    private fun worker(input: Data): UploadHandlerWorker =
        TestListenableWorkerBuilder<UploadHandlerWorker>(context).setInputData(input).build()

    private fun kotlinx.coroutines.test.TestScope.startEngine(store: TestUploaderStore, handler: UploaderHandler<*>): UploaderEngine =
        testEngine(store, listOf(handler), backgroundScope).also {
            engine = it
            UploaderEngineRegistry.register(it)
        }

    /** Reads the id from `item_id`, as a worker an app used before adopting this module did. */
    class LegacyKeyWorker(context: Context, params: WorkerParameters) : UploadHandlerWorker(context, params) {
        override fun readItemId(inputData: Data): String? = inputData.getString("item_id")
    }

    private class RecordingTransport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancelAll() = Unit
    }

    /** Points at a port nothing listens on, and parks whatever comes back so the settle is visible. */
    private class UnreachableServerHandler : UploadHandler<String>(RecordingTransport()) {
        val classified = mutableListOf<UploadResult>()
        override val type: String = "upload"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest(url = "http://127.0.0.1:9/upload", fields = emptyList()))

        override suspend fun classify(payload: String, result: UploadResult): SettleResult {
            classified += result
            return SettleResult.Park("classified $result")
        }
    }
}
