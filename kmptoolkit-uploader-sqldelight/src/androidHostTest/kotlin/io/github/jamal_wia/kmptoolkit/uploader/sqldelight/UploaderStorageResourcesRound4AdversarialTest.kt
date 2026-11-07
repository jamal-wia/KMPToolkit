package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/** Round 4 (resource-starvation + contract-lawyer): documented limits, statement hygiene after failures, foreign drivers. */
@RunWith(AndroidJUnit4::class)
class UploaderStorageResourcesRound4AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val drivers: MutableList<SqlDriver> = mutableListOf()
    private val files: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        storages.forEach { runCatching { it.close() } }
        drivers.forEach { runCatching { it.close() } }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun open(): UploaderStorage {
        val name = "adv.a1r4.res.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    private fun item(id: String, payload: String = "p", uniqueKey: String? = null): UploaderItem = UploaderItem(
        id = id, type = "t", payload = payload, schemaVersion = 1, uniqueKey = uniqueKey, orderingKey = null, tag = null,
        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
    )

    @Test
    fun `a row at the limit is accepted, readable and still readable after a failure text is written`() = runTest {
        val store = open().store
        val payload = "x".repeat(MAX_ROW_TEXT_BYTES - 2 - 1) // id "a" and type "t" take two bytes
        assertEquals(true, store.insertKeep(item("a", payload)))
        assertEquals(payload.length, store.getAllActive().single().payload.length)

        store.recordFailure("a", 1, 0L, "e".repeat(4_096), UploaderItemState.PENDING, 0L, FailureDisposition.RETRY)

        assertEquals(1, store.getAllActive().size, "the queue became unreadable after a failure text was written")
    }

    @Test
    fun `a duplicate id fails typed and leaves the storage usable, for keep, replace and keyless inserts`() = runTest {
        val store = open().store
        assertEquals(true, store.insertKeep(item("dup")))

        assertFailsWith<UploaderStorageException> { store.insertKeep(item("dup")) }
        assertFailsWith<UploaderStorageException> { store.insertKeep(item("dup", uniqueKey = "k")) }
        assertFailsWith<UploaderStorageException> { store.insertReplace(item("dup", uniqueKey = "k2")) }
        assertFailsWith<UploaderStorageException> { store.insertReplace(item("dup")) }

        assertEquals(true, store.insertKeep(item("fine", uniqueKey = "k")), "a failed insert left a transaction open")
        assertEquals(listOf("dup", "fine"), store.getAllActive().map { it.id })
        assertEquals(1, store.getAllActive().count { it.id == "dup" }, "a rolled back insert left a half-written row")
    }

    @Test
    fun `a storage over a driver the consumer owns never closes it and two storages share its rows`() = runTest {
        val driver: SqlDriver = createDriver(uploaderDatabaseSchema, null).also { drivers += it }
        val first: UploaderStorage = createUploaderStorage(driver)
        first.store.insertKeep(item("kept"))
        first.close()

        val stillOpen: Long = driver.executeQuery(null, "SELECT COUNT(*) FROM kmptoolkit_uploader_item", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
        }, 0).value
        val second: UploaderStorage = createUploaderStorage(driver).also { storages += it }

        assertEquals(1L, stillOpen, "closing the storage closed the consumer's driver")
        assertEquals(listOf("kept"), second.store.getAllActive().map { it.id })
    }
}
