package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 8 (hostile consumer, Android): every bad argument of the public Android configs, requests a consumer should
 * never send but can, and the built-in transports used out of order. Nothing here may hang, crash a worker or throw
 * something undocumented.
 */
@RunWith(AndroidJUnit4::class)
class AndroidHostileConsumerRound8AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var engine: UploaderEngine? = null

    @AfterTest
    fun cleanUp() {
        engine?.close()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    // ---- configuration tables ----------------------------------------------------------------------------

    @Test
    fun `UploadTransportConfig rejects every documented bad value and keeps the extremes valid`() {
        for (blank in listOf("", " ", "\t", "\n")) {
            assertFailsWith<IllegalArgumentException> { UploadTransportConfig(uniqueWorkNamePrefix = blank) }
            assertFailsWith<IllegalArgumentException> { UploadTransportConfig(workTag = blank) }
        }
        for (lease in listOf(0L, -1L, Long.MIN_VALUE)) assertFailsWith<IllegalArgumentException> { UploadTransportConfig(leaseMillis = lease) }
        for (backoff in listOf(Duration.ZERO, (-1).seconds, 9_999.milliseconds, -Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException> { UploadTransportConfig(workBackoff = backoff) }
        }
        for (wait in listOf(Duration.ZERO, (-1).milliseconds, -Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException> { UploadTransportConfig(engineWait = wait) }
        }
        for (timeout in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { UploadTransportConfig(connectTimeoutMillis = timeout) }
            assertFailsWith<IllegalArgumentException> { UploadTransportConfig(readTimeoutMillis = timeout) }
        }
        UploadTransportConfig(leaseMillis = 1, workBackoff = 10.seconds, engineWait = 1.milliseconds, connectTimeoutMillis = 1, readTimeoutMillis = 1)
        UploadTransportConfig(leaseMillis = Long.MAX_VALUE, connectTimeoutMillis = Int.MAX_VALUE, readTimeoutMillis = Int.MAX_VALUE)
        assertEquals(UploadTransportConfig(), UploadTransportConfig().copy())
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig().copy(leaseMillis = 0) }
    }

    @Test
    fun `WorkManagerWakeConfig rejects every documented bad value`() {
        for (blank in listOf("", " ", "\t")) assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(uniqueWorkName = blank) }
        for (backoff in listOf(Duration.ZERO, (-1).seconds, 9_999.milliseconds)) {
            assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(initialBackoff = backoff) }
        }
        for (d in listOf(Duration.ZERO, (-1).milliseconds)) {
            assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(drainBudget = d) }
            assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(engineWait = d) }
        }
        WorkManagerWakeConfig(initialBackoff = 10.seconds, drainBudget = 1.milliseconds, engineWait = 1.milliseconds)
        assertEquals(WorkManagerWakeConfig(), WorkManagerWakeConfig().copy())
    }

    @Test
    fun `an infinite backoff or wait is accepted by the config and then neither launch nor the wake scheduler throw`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val request = UploadRequest("http://127.0.0.1:9/u", fields = emptyList())
        val infinite = UploadTransportConfig(
            uniqueWorkNamePrefix = "r8inf_", workTag = "r8inf",
            workBackoff = Duration.INFINITE, engineWait = Duration.INFINITE, leaseMillis = Long.MAX_VALUE,
        )

        createWorkManagerUploadHandlerTransport(context, infinite).launch("a", isRehandOff = false, request = request)
        createWorkManagerUploadTransport(context, infinite).launch("b", request)
        val wake: io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler = createWorkManagerWakeScheduler(
            context,
            WorkManagerWakeConfig(uniqueWorkName = "r8wake", initialBackoff = Duration.INFINITE, drainBudget = Duration.INFINITE, engineWait = Duration.INFINITE),
        )
        wake.scheduleWake()
        wake.scheduleWake()
        wake.cancelWake()

        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("r8inf_a").get().size)
    }

    // ---- the transports used out of order ------------------------------------------------------------------

    private val request = UploadRequest("http://127.0.0.1:9/u", fields = emptyList())

    @Test
    fun `launch is idempotent per item id, cancel is idempotent, and odd ids neither collide nor throw`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val transport: UploadTransport = createWorkManagerUploadHandlerTransport(
            context, UploadTransportConfig(uniqueWorkNamePrefix = "r8id_", workTag = "r8id"),
        )
        val ids: List<String> = listOf("same", "same", "", " ", "\u0000", "a/b", "ünï", "😀", "x".repeat(5_000), "SAME", "Same")

        for (id in ids) transport.launch(id, isRehandOff = false, request = request)
        transport.launch("same", isRehandOff = true, request = request)

        val work: WorkManager = WorkManager.getInstance(context)
        assertEquals(1, work.getWorkInfosForUniqueWork("r8id_same").get().size)
        assertEquals(WorkInfo.State.ENQUEUED, work.getWorkInfosForUniqueWork("r8id_same").get().single().state)
        assertEquals(1, work.getWorkInfosForUniqueWork("r8id_SAME").get().size, "case differs: a separate job")
        transport.cancel("same")
        transport.cancel("same")
        transport.cancel("never")
        transport.cancel("")
        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfosForUniqueWork("r8id_same").get().single().state)
        assertEquals(WorkInfo.State.ENQUEUED, work.getWorkInfosForUniqueWork("r8id_SAME").get().single().state)
    }

    @Test
    fun `a worker with hostile input data returns a result and never throws`() = runTest {
        val inputs: List<Data> = listOf(
            Data.EMPTY,
            workDataOf(UploadHandlerWorker.ITEM_ID_KEY to ""),
            workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "x".repeat(9_000)),
            workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "ghost", UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to 0L),
            workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "ghost", UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to -5L),
            workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "ghost", UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to Long.MIN_VALUE),
            workDataOf(UploadHandlerWorker.ITEM_ID_KEY to 42),
            workDataOf(UploadHandlerWorker.ITEM_ID_KEY to "ghost", UploadHandlerWorker.ENGINE_WAIT_MILLIS_KEY to "soon"),
        )
        for (input in inputs) {
            val result: ListenableWorker.Result = withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    TestListenableWorkerBuilder<UploadHandlerWorker>(context).setInputData(input).build().doWork()
                }
            }
            assertTrue(
                result is ListenableWorker.Result.Failure || result is ListenableWorker.Result.Retry || result is ListenableWorker.Result.Success,
                "$input gave $result",
            )
        }
    }

    // ---- requests a consumer should never send ----------------------------------------------------------------

    private class Transport : UploadTransport {
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) = Unit
        override fun cancel(itemId: String) = Unit
    }

    private class OddRequestHandler(val request: UploadRequest) : UploadHandler<String>(Transport()) {
        val outcomes: MutableList<UploadResult> = mutableListOf()
        override val type: String = "odd"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(request)

        override suspend fun classify(payload: String, result: UploadResult): SettleResult {
            outcomes += result
            return SettleResult.Park("seen")
        }
    }

    private suspend fun TestScope.runOddRequest(odd: UploadRequest): Pair<ListenableWorker.Result, OddRequestHandler> {
        val store = TestUploaderStore()
        val handler = OddRequestHandler(odd)
        engine?.close()
        val started: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine = started
        UploaderEngineRegistry.register(started)
        val id: String = checkNotNull(started.enqueue(handler, "p"))
        started.drain()
        val result: ListenableWorker.Result = withContext(Dispatchers.Default) {
            withTimeout(30.seconds) {
                TestListenableWorkerBuilder<UploadHandlerWorker>(context)
                    .setInputData(workDataOf(UploadHandlerWorker.ITEM_ID_KEY to id))
                    .build()
                    .doWork()
            }
        }
        return result to handler
    }

    @Test
    fun `an unusable url settles as a transport failure and never crashes or retries the worker forever`() = runTest {
        val urls: List<String> = listOf(
            "", " ", "not a url", "ftp://127.0.0.1/x", "file:///etc/hosts", "jar:file:///x!/", "http://", "http://[::1",
            "http://127.0.0.1:99999/", "http://127.0.0.1:-1/", "http://user:pass@127.0.0.1:9/", "HTTP://127.0.0.1:9/u",
            "http://127.0.0.1:9/" + "a".repeat(100_000),
        )
        for (url in urls) {
            val (result: ListenableWorker.Result, handler: OddRequestHandler) =
                runOddRequest(UploadRequest(url = url, fields = emptyList()))
            assertTrue(result is ListenableWorker.Result.Success, "url '${url.take(40)}' gave $result")
            assertEquals(1, handler.outcomes.size, "url '${url.take(40)}' settled ${handler.outcomes}")
            assertIs<UploadResult.TransportFailure>(handler.outcomes.single(), "url '${url.take(40)}'")
        }
    }

    @Test
    fun `an unusable method, header or field name settles as a transport failure`() = runTest {
        val cases: List<UploadRequest> = listOf(
            UploadRequest("http://127.0.0.1:9/u", method = "", fields = emptyList()),
            UploadRequest("http://127.0.0.1:9/u", method = "NOT A METHOD", fields = emptyList()),
            UploadRequest("http://127.0.0.1:9/u", method = "POST\r\nX-Injected: 1", fields = emptyList()),
            UploadRequest("http://127.0.0.1:9/u", headers = mapOf("" to "v"), fields = emptyList()),
            UploadRequest("http://127.0.0.1:9/u", headers = mapOf("a b" to "v"), fields = emptyList()),
            UploadRequest("http://127.0.0.1:9/u", headers = mapOf("X-Ok" to "v\r\nInjected: 1"), fields = emptyList()),
            UploadRequest("http://127.0.0.1:9/u", headers = mapOf("X\r\nInjected" to "v"), fields = emptyList()),
            UploadRequest("http://127.0.0.1:9/u", headers = mapOf("X-Nul" to "a\u0000b"), fields = emptyList()),
            UploadRequest(
                "http://127.0.0.1:9/u",
                fields = listOf(UploadField.File("f", "n", "text/plain", "/definitely/not/a/file")),
            ),
            UploadRequest(
                "http://127.0.0.1:9/u",
                fields = listOf(UploadField.File("f", "n", "text/plain", "")),
            ),
            UploadRequest(
                "http://127.0.0.1:9/u",
                fields = listOf(UploadField.Text("", ""), UploadField.Text("n\r\nInjected: 1", "v\r\n--boundary--")),
            ),
        )
        for ((index, odd) in cases.withIndex()) {
            val (result: ListenableWorker.Result, handler: OddRequestHandler) = runOddRequest(odd)
            assertTrue(result is ListenableWorker.Result.Success, "case $index gave $result")
            assertEquals(1, handler.outcomes.size, "case $index settled ${handler.outcomes}")
            assertIs<UploadResult.TransportFailure>(handler.outcomes.single(), "case $index: ${handler.outcomes}")
        }
    }
}
