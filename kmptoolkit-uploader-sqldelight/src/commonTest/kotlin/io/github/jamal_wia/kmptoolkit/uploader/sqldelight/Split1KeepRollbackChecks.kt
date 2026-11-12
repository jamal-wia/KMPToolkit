package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A KEEP enqueue that supersedes an upload item parked at the re-hand of an expired lease (the earlier claim's transfer
 * still runs), inside a transaction that rolls back, over real SQLite: the row is restored exactly, the cancel the
 * KEEP issued for it is not rolled back (P8, as for a REPLACE or a wipe), and a late delivery settles the restored row.
 */
internal class Split1KeepRollbackChecks {

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
        override val type: String = "split1.sqlite.upload"
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

    suspend fun rolledBackKeepOverAParkedItemKeepsTheCancelAndRestoresTheRow() {
        val name: String = uniqueDatabaseName("split1-keep")
        val clock = MutableUploaderClock(4_000_000L)
        val transport = Transport()
        val handler = ParkAtRehand(transport)
        val storage: UploaderStorage = createFileUploaderStorage(name)
        val scope1: CoroutineScope = scope()
        try {
            val engine1: UploaderEngine = uploadEngine(storage, handler, scope1, clock, listOf("split1-keep-1", "split1-keep-2").iterator())
            val parked: UploaderItem = parkWithLiveTransfer(engine1, handler, storage, clock, transport, "split1-keep-1")

            assertFailsWith<Rollback> {
                real(10_000) {
                    storage.transactionRunner.inTransaction {
                        // The default policy is KEEP: the parked holder is superseded (and withdrawn) by this enqueue.
                        assertNotNull(engine1.enqueue(handler, "v2", uniqueKey = "avatar", tag = "account-1"))
                        throw Rollback()
                    }
                }
            }

            assertEquals(parked, real(10_000) { storage.store.getById("split1-keep-1") }, "the rollback restored the parked row exactly")
            assertNull(real(10_000) { storage.store.getById("split1-keep-2") }, "the rollback removed the new row")
            assertEquals(listOf("launch:split1-keep-1", "cancel:split1-keep-1"), transport.events.value, "the cancel is not rolled back (P8)")
            real(10_000) { engine1.settle("split1-keep-1", parked.claim, SettleResult.Delivered) }
            assertNull(real(10_000) { storage.store.getById("split1-keep-1") })
            real(10_000) { engine1.drain() }
            assertEquals(2, transport.events.value.size, "nothing launched again")
            engine1.close()
        } finally {
            scope1.cancel()
            storage.close()
            deleteDatabaseFile(name)
        }
    }
}
