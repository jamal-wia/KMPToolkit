package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Round 4 (contract-lawyer): "using the storage afterwards fails as an UploaderStorageException whose cause is an
 * IllegalStateException, never as a CancellationException", literally, for every operation the storage offers, and
 * the operation the exception names is the one that was called.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageClosedRound4AdversarialTest {

    private val names: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        names.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun item(): UploaderItem = UploaderItem(
        id = "a", type = "t", payload = "p", schemaVersion = 1, uniqueKey = "k", orderingKey = null, tag = "g",
        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
    )

    private suspend fun expectClosed(operation: UploaderStorageOperation, call: suspend () -> Unit) {
        val failure: UploaderStorageException = assertFailsWith<UploaderStorageException>("$operation after close") { call() }
        assertEquals(operation, failure.operation, "the exception names another operation than $operation")
        assertTrue(failure.cause is IllegalStateException, "$operation: cause was ${failure.cause}")
    }

    @Test
    fun `every operation of a closed storage fails typed with its own operation name`() = runTest {
        val name = "adv.a1r4.closed.${Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name))
        storage.store.insertKeep(item())
        storage.close()
        val store = storage.store

        expectClosed(UploaderStorageOperation.INSERT_KEEP) { store.insertKeep(item()) }
        expectClosed(UploaderStorageOperation.INSERT_REPLACE) { store.insertReplace(item()) }
        expectClosed(UploaderStorageOperation.GET_ALL_ACTIVE) { store.getAllActive() }
        expectClosed(UploaderStorageOperation.GET_BY_ID) { store.getById("a") }
        expectClosed(UploaderStorageOperation.RECORD_FAILURE) {
            store.recordFailure("a", 1, 0L, "e", UploaderItemState.PENDING, 0L, FailureDisposition.RETRY)
        }
        expectClosed(UploaderStorageOperation.MARK_IN_FLIGHT) { store.markInFlight("a", 1L, UploaderItemState.PENDING, 0L) }
        expectClosed(UploaderStorageOperation.PARK) { store.park("a", "x", UploaderItemState.PENDING, 0L) }
        expectClosed(UploaderStorageOperation.DELETE_BY_ID) { store.deleteById("a") }
        expectClosed(UploaderStorageOperation.DELETE_BY_TAG) { store.deleteByTag("g") }
        expectClosed(UploaderStorageOperation.CLEAR_ALL) { store.clearAll() }
        expectClosed(UploaderStorageOperation.TRANSACTION) { storage.transactionRunner.inTransaction { } }
        withTimeout(10.seconds) {
            val observed: UploaderStorageException = assertFailsWith { store.observeByType("t").first() }
            assertEquals(UploaderStorageOperation.OBSERVE_BY_TYPE, observed.operation)
        }
    }
}
