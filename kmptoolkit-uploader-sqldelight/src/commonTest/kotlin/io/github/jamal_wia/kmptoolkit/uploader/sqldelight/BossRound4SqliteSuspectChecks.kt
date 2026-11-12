package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.real
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.BossRound2Sqlite.scope
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A1 boss round 4 over real SQLite: an upload item parked at the re-hand of an expired lease while the earlier claim's
 * transfer still runs, then wiped by tag inside a transaction that rolls back. (A KEEP that supersedes the parked row
 * is suspect bv4-S1, kept out of the committed suite.)
 */
internal class BossRound4SqliteSuspectChecks {

    private class Rollback : RuntimeException("the consumer's transaction rolled back")

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

    /** Proceeds on a first hand-off and parks at a re-hand ("signed out"). */
    private class ParkAtRehand(transport: UploadTransport) : UploadHandler<String>(transport) {
        override val type: String = "boss4.sqlite.upload"
        override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 60_000L)
        override val schemaVersion: Int = 1
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
            if (context.wasDetached) {
                UploadPreparation.Park("signed out")
            } else {
                UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
            }
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

    private suspend fun parkWithLiveTransfer(engine: UploaderEngine, handler: ParkAtRehand, storage: UploaderStorage, clock: MutableUploaderClock, transport: Transport, id: String): UploaderItem {
        real(10_000) { engine.enqueue(handler, "v1", uniqueKey = "avatar", tag = "account-1") }
        real(10_000) { engine.drain() }
        clock.advanceBy(61_000L)
        real(10_000) { engine.drain() }
        val parked: UploaderItem = assertNotNull(real(10_000) { storage.store.getById(id) })
        assertEquals(UploaderItemState.PARKED, parked.state, "setup: the re-hand parked the item")
        assertEquals(listOf("launch:$id"), transport.events.value, "setup: the earlier transfer is still running")
        return parked
    }

    suspend fun keepThatSupersedesAParkedItemCancelsItsTransfer() {
        val name: String = uniqueDatabaseName("boss4-keep")
        val clock = MutableUploaderClock(4_000_000L)
        val transport = Transport()
        val handler = ParkAtRehand(transport)
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val scope1: CoroutineScope = scope()
        try {
            val engine1: UploaderEngine = uploadEngine(storage, handler, scope1, clock, listOf("boss4-keep-1", "boss4-keep-2").iterator())
            parkWithLiveTransfer(engine1, handler, storage, clock, transport, "boss4-keep-1")

            assertEquals("boss4-keep-2", real(10_000) { engine1.enqueue(handler, "v2", uniqueKey = "avatar", conflictPolicy = ConflictPolicy.KEEP) })

            assertNull(real(10_000) { storage.store.getById("boss4-keep-1") }, "setup: the KEEP removed the parked row in SQLite")
            assertTrue(
                "cancel:boss4-keep-1" in transport.events.value,
                "a KEEP that superseded the parked row left its transfer uploading next to the new version: ${transport.events.value}",
            )
            engine1.close()
        } finally {
            scope1.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }
}
