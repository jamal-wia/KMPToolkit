package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.Counting
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.FlakyDelete
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.engine
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.real
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.scope
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A1 boss round 3 over real SQLite: the transaction boundary with both conflict policies (a real rollback of a KEEP
 * that revives a parked key, a real rollback of a REPLACE that cancelled a running upload) and two engines over two
 * database files whose rows share ids, type and creation time.
 */
internal class BossRound3SqliteChecks {

    private class Rollback : RuntimeException("the consumer's transaction rolled back")

    private class Parking(override val type: String = BossRound2Sqlite.MARKER2 + "boss3.parking") : UploaderHandler<String> {
        val runs: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 60_000L)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            runs.update { it + payload }
            return if (payload == "v1") AttemptResult.Park("rejected") else AttemptResult.Success
        }
    }

    private class Transport : UploadTransport {
        val events: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override val leaseMillis: Long = 60_000L
        override fun launch(itemId: String, request: UploadRequest) {
            events.update { it + "launch:$itemId" }
        }

        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events.update { it + (if (isRehandOff) "relaunch:$itemId" else "launch:$itemId") }
        }

        override fun cancel(itemId: String) {
            events.update { it + "cancel:$itemId" }
        }
    }

    private class Uploading(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "boss3.upload"
        override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 60_000L)
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
    }

    private fun uploadEngine(storage: UploaderStorage, handler: UploadHandler<String>, scope: CoroutineScope, clock: MutableUploaderClock, ids: Iterator<String>): UploaderEngine =
        createUploaderEngine(
            store = storage.store,
            handlers = listOf(handler),
            scope = scope,
            transactionRunner = storage.transactionRunner,
            clock = clock,
            idGenerator = { ids.next() },
        )

    suspend fun rolledBackKeepRestoresTheParkedRowAndACommittedOneRevivesIt() {
        val name: String = uniqueDatabaseName("boss3-keep")
        val clock = MutableUploaderClock(1_000_000L)
        val handler = Parking()
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val scope1: CoroutineScope = scope()
        try {
            val engine1: UploaderEngine = engine(storage.store, storage, handler, scope1, clock, listOf("boss3-keep-1", "boss3-keep-2", "boss3-keep-3").iterator())
            real(10_000) { engine1.enqueue(handler, "v1", uniqueKey = "profile") }
            real(10_000) { engine1.drain() }
            val parked: UploaderItem = assertNotNull(storage.store.getById("boss3-keep-1"))
            assertEquals(UploaderItemState.PARKED, parked.state, "setup: parked")

            assertFailsWith<Rollback> {
                real(10_000) {
                    storage.transactionRunner.inTransaction {
                        assertEquals("boss3-keep-2", engine1.enqueue(handler, "v2", uniqueKey = "profile"), "the KEEP revives the parked key in the transaction")
                        throw Rollback()
                    }
                }
            }
            assertEquals(parked, storage.store.getById("boss3-keep-1"), "the rollback restored the parked row exactly")
            assertNull(storage.store.getById("boss3-keep-2"), "the reviving row rolled back")
            real(10_000) { engine1.drain() }
            assertEquals(listOf("v1"), handler.runs.value, "a parked row restored by a rollback runs nothing")

            assertEquals("boss3-keep-3", real(10_000) { engine1.enqueue(handler, "v3", uniqueKey = "profile") })
            real(10_000) { engine1.drain() }
            assertEquals(listOf("v1", "v3"), handler.runs.value, "a committed KEEP revives the key and runs once")
            assertEquals(emptyList(), real(10_000) { storage.store.getAllActive() })
            engine1.close()
        } finally {
            scope1.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }

    suspend fun rolledBackReplaceRestoresTheClaimAndTheUploadResumesAtTheLease() {
        val name: String = uniqueDatabaseName("boss3-replace")
        val clock = MutableUploaderClock(2_000_000L)
        val transport = Transport()
        val handler = Uploading(transport)
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val scope1: CoroutineScope = scope()
        try {
            val engine1: UploaderEngine = uploadEngine(storage, handler, scope1, clock, listOf("boss3-rep-1", "boss3-rep-2").iterator())
            real(10_000) { engine1.enqueue(handler, "v1", uniqueKey = "avatar") }
            real(10_000) { engine1.drain() }
            val inFlight: UploaderItem = assertNotNull(storage.store.getById("boss3-rep-1"))
            assertEquals(UploaderItemState.IN_FLIGHT, inFlight.state)

            assertFailsWith<Rollback> {
                real(10_000) {
                    storage.transactionRunner.inTransaction {
                        engine1.enqueue(handler, "v2", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.REPLACE)
                        throw Rollback()
                    }
                }
            }
            assertEquals(inFlight, storage.store.getById("boss3-rep-1"), "the rollback restored the claim and its exact lease")
            assertNull(storage.store.getById("boss3-rep-2"))
            assertEquals(listOf("launch:boss3-rep-1", "cancel:boss3-rep-1"), transport.events.value, "the cancel is not rolled back")

            real(10_000) { engine1.drain() }
            assertEquals(2, transport.events.value.size, "the restored claim is left alone while its lease runs")
            clock.advanceBy(60_001L)
            real(10_000) { engine1.drain() }
            assertEquals(
                listOf("launch:boss3-rep-1", "cancel:boss3-rep-1", "relaunch:boss3-rep-1"),
                transport.events.value,
                "the cancelled upload resumes no later than when its lease expires",
            )
            engine1.close()
        } finally {
            scope1.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }

    suspend fun twoEnginesOverTwoFilesWithSharedIdsKeepTheirDeliveredMemoryApart() {
        val nameA: String = uniqueDatabaseName("boss3-two-a")
        val nameB: String = uniqueDatabaseName("boss3-two-b")
        val clock = MutableUploaderClock(3_000_000L) // frozen: both rows share id, type and creation time
        val handlerA = Counting()
        val handlerB = Counting()
        val storageA: UploaderStorage = createFileUploaderStorage(nameA)
        val storageB: UploaderStorage = createFileUploaderStorage(nameB)
        val scopeA: CoroutineScope = scope()
        val scopeB: CoroutineScope = scope()
        try {
            val flakyA = FlakyDelete(storageA.store, 1)
            val engineA: UploaderEngine = engine(flakyA, storageA, handlerA, scopeA, clock, listOf("boss3-dup-1").iterator())
            val engineB: UploaderEngine = engine(storageB.store, storageB, handlerB, scopeB, clock, listOf("boss3-dup-1").iterator())
            real(10_000) { engineA.enqueue(handlerA, "account-a") }
            real(10_000) { engineA.drain() }
            assertNotNull(storageA.store.getById("boss3-dup-1"), "setup: A's confirming delete failed")

            real(10_000) { engineB.enqueue(handlerB, "account-b") }
            real(10_000) { engineB.drain() }
            assertEquals(listOf("account-b"), handlerB.runs.value, "B's item of the same id is another item: it runs")
            assertNull(storageB.store.getById("boss3-dup-1"))

            engineA.close()
            scopeA.cancel()
            val scopeA2: CoroutineScope = scope()
            try {
                val engineA2: UploaderEngine = engine(storageA.store, storageA, handlerA, scopeA2, clock, listOf("boss3-dup-2").iterator())
                real(10_000) { engineA2.drain() }
                assertEquals(listOf("account-a"), handlerA.runs.value, "A's replacement confirms the delivery without a run")
                assertNull(storageA.store.getById("boss3-dup-1"))
                engineA2.close()
            } finally {
                scopeA2.cancel()
            }
            engineB.close()
        } finally {
            scopeA.cancel()
            scopeB.cancel()
            storageA.close()
            storageB.close()
            deleteDatabaseFile(nameA)
            deleteDatabaseFile(nameB)
        }
    }
}
