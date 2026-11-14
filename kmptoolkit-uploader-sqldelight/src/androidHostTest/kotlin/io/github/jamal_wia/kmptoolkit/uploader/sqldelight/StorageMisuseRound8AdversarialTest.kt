package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 8 (hostile consumer, SQLDelight): the documented misuse rules of docs/kmptoolkit-uploader-sqldelight 03-guide
 * ("Transactions: the three rules", "Failures") and every bad argument of the public configuration, each run for real.
 */
@RunWith(AndroidJUnit4::class)
class StorageMisuseRound8AdversarialTest {

    private suspend fun <T> real(millis: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(millis) { block() } }

    @Test
    fun `UploaderDatabaseConfig rejects blank names and names with a separator, a space or a NUL`() {
        for (bad in listOf("", " ", "\t", "\n", "a b", "a/b", "a\\b", "a\u0000b", " a", "a ", "/")) {
            assertFailsWith<IllegalArgumentException>("'${bad.replace("\u0000", "<NUL>")}'") { UploaderDatabaseConfig(bad) }
        }
        for (good in listOf("a", "com.example.chat", ".", "..", "a:b", "a*b", "ünï", "😀", "N".repeat(100))) {
            UploaderDatabaseConfig(good)
        }
        assertEquals(UploaderDatabaseConfig("x", true), UploaderDatabaseConfig("x", true).copy())
        assertNotEquals(UploaderDatabaseConfig("x"), UploaderDatabaseConfig("x", recreateOnCorruption = true))
        assertFailsWith<IllegalArgumentException> { UploaderDatabaseConfig("x").copy(name = "a b") }
    }

    @Test
    fun `an accepted but awkward name opens a queue or fails as an OPEN storage exception, never as anything else`() = runTest {
        for (name in listOf(".", "..", "a:b", "a*b", "a\nb", "ünï", "😀", "L".repeat(300), "L".repeat(1_000))) {
            val outcome: Result<UploaderStorage> = runCatching { createUploaderStorage(application(), UploaderDatabaseConfig(name)) }
            val storage: UploaderStorage? = outcome.getOrNull()
            if (storage != null) {
                try {
                    real(10_000) {
                        storage.store.insertKeep(anItem("a"))
                        assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
                    }
                } finally {
                    storage.close()
                    application().deleteDatabase(uploaderDatabaseFileName(name))
                }
            } else {
                val failure: UploaderStorageException = assertNotNull(
                    outcome.exceptionOrNull() as? UploaderStorageException,
                    "name length ${name.length}: ${outcome.exceptionOrNull()}",
                )
                assertEquals(UploaderStorageOperation.OPEN, failure.operation)
            }
        }
    }

    @Test
    fun `switching dispatchers inside a transaction is refused with an IllegalStateException and the queue stays usable`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val failure: Throwable? = runCatching {
                real(10_000) {
                    storage.transactionRunner.inTransaction {
                        storage.store.insertKeep(anItem("inside"))
                        withContext(Dispatchers.Default) { storage.store.insertKeep(anItem("elsewhere")) }
                    }
                }
            }.exceptionOrNull()

            assertNotNull(failure, "the documented refusal did not happen")
            assertTrue(failure is IllegalStateException || failure.cause is IllegalStateException, "got $failure")
            assertEquals(emptyList(), storage.store.getAllActive().map { it.id }, "the whole transaction rolled back")
            storage.store.insertKeep(anItem("after"))
            assertEquals(listOf("after"), storage.store.getAllActive().map { it.id })
        } finally {
            storage.close()
        }
    }

    @Test
    fun `close inside a transaction is refused with an IllegalStateException and the storage keeps working`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val failure: Throwable? = runCatching {
                real(10_000) {
                    storage.transactionRunner.inTransaction {
                        storage.store.insertKeep(anItem("a"))
                        storage.close()
                    }
                }
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure is IllegalStateException || failure.cause is IllegalStateException, "got $failure")
            storage.store.insertKeep(anItem("b"))
            assertEquals(listOf("b"), storage.store.getAllActive().map { it.id })
        } finally {
            storage.close()
        }
    }

    @Test
    fun `a block that throws a CancellationException rolls back and the very same exception propagates`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val cancellation = CancellationException("stop")
            val thrown: Throwable? = runCatching {
                storage.transactionRunner.inTransaction {
                    storage.store.insertKeep(anItem("a"))
                    throw cancellation
                }
            }.exceptionOrNull()
            val custom = IllegalArgumentException("mine")
            val thrownCustom: Throwable? = runCatching {
                storage.transactionRunner.inTransaction {
                    storage.store.insertKeep(anItem("b"))
                    throw custom
                }
            }.exceptionOrNull()

            // The coroutine stack-trace recovery of the test JVM may hand back a copy of the same type and message.
            assertTrue(thrown is CancellationException && thrown.message == "stop", "got $thrown")
            assertTrue(thrownCustom is IllegalArgumentException && thrownCustom.message == "mine", "got $thrownCustom")
            assertEquals(emptyList(), storage.store.getAllActive().map { it.id })
        } finally {
            storage.close()
        }
    }

    @Test
    fun `nested transactions join the outer one and an inner failure caught by the outer block keeps the outer rows consistent`() =
        runTest {
            val storage: UploaderStorage = createInMemoryUploaderStorage()
            try {
                storage.transactionRunner.inTransaction {
                    storage.store.insertKeep(anItem("outer"))
                    runCatching {
                        storage.transactionRunner.inTransaction {
                            storage.store.insertKeep(anItem("inner"))
                            error("inner failed")
                        }
                    }
                }

                val ids: List<String> = storage.store.getAllActive().map { it.id }
                assertTrue("outer" in ids, "the outer row survived: $ids")
            } finally {
                storage.close()
            }
        }

    @Test
    fun `a caller-supplied driver is never closed by the storage`() = runTest {
        val driver: SqlDriver = createDriver(uploaderDatabaseSchema, name = null)
        val storage: UploaderStorage = createUploaderStorage(driver)
        storage.store.insertKeep(anItem("a"))
        storage.close()
        storage.close()

        val rows: Long = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM sqlite_master",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value
        assertTrue(rows > 0, "the driver still answers after the storage was closed")
        driver.close()
    }

    @Test
    fun `thousands of rows keep their insertion order through the confinement thread`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        try {
            val done = CompletableDeferred<Unit>()
            real(60_000) {
                storage.transactionRunner.inTransaction {
                    for (i in 0 until 3_000) storage.store.insertKeep(anItem("id-$i", createdAtEpochMillis = (3_000 - i).toLong()))
                }
                done.complete(Unit)
            }
            done.await()

            assertEquals((0 until 3_000).map { "id-$it" }, storage.store.getAllActive().map { it.id })
        } finally {
            storage.close()
        }
    }
}
