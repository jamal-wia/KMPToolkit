package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * App-restart hazards of the Android factory over a real SQLite file: damage that the open does not see,
 * a file that is not corrupt but cannot be opened (locked, downgraded, foreign, a directory), and a launch
 * loop that fails to open the same file again and again.
 *
 * Hazard: the next launch finds the file in a state the last process left it in, and what the factory does
 * about it decides whether the queue's owed effects survive.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStorageLifecycleRound2AdversarialTest {

    private fun fileOf(config: UploaderDatabaseConfig): File =
        application().getDatabasePath(uploaderDatabaseFileName(config.name!!)).also { it.parentFile?.mkdirs() }

    /** A queue of [count] items, closed cleanly so the file is self-contained. */
    private suspend fun seed(config: UploaderDatabaseConfig, count: Int) {
        val storage: UploaderStorage = createUploaderStorage(application(), config)
        try {
            storage.transactionRunner.inTransaction {
                repeat(count) { storage.store.insertKeep(anItem("seed-$it")) }
            }
        } finally {
            storage.close()
        }
    }

    private fun scribbleOverEverythingAfterTheFirstPage(file: File) {
        val bytes: ByteArray = file.readBytes()
        assertTrue(bytes.size >= 4 * 4096, "the seeded file is too small (${bytes.size}) for the damage to reach a data page")
        for (index in 4096 until bytes.size) bytes[index] = ((index * 131) xor 0x5A).toByte()
        file.writeBytes(bytes)
    }

    @Test
    fun `default config over a file damaged below a valid header fails typed on use and never deletes the file`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.interior")
        seed(config, 600)
        val file = fileOf(config)
        scribbleOverEverythingAfterTheFirstPage(file)
        val damaged: ByteArray = file.readBytes()

        val outcome: Result<UploaderStorage> = runCatching { createUploaderStorage(application(), config) }
        outcome.getOrNull()?.let { storage ->
            try {
                val failure: Throwable? = runCatching { storage.store.getAllActive() }.exceptionOrNull()
                assertTrue(failure is UploaderStorageException, "reading damaged pages must fail typed, got $failure")
            } finally {
                storage.close()
            }
        }
        outcome.exceptionOrNull()?.let { assertTrue(it is UploaderStorageException, "got $it") }

        assertTrue(file.exists(), "the damaged file was deleted without the app's say")
        assertTrue(damaged.contentEquals(file.readBytes()), "the damaged file was modified")
        application().deleteDatabase(file.name)
    }

    @Test
    fun `recreateOnCorruption over a file damaged below a valid header never acknowledges a write it then loses`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.interior.optin", recreateOnCorruption = true)
        seed(config, 600)
        val file = fileOf(config)
        scribbleOverEverythingAfterTheFirstPage(file)

        var acknowledged = false
        val first: UploaderStorage? = runCatching { createUploaderStorage(application(), config) }.getOrNull()
        if (first != null) {
            try {
                runCatching { first.store.getAllActive() } // the first use meets the damage
                acknowledged = runCatching { first.store.insertKeep(anItem("fresh")).inserted }.getOrNull() == true
            } finally {
                first.close()
            }
        }

        val second: UploaderStorage = createUploaderStorage(application(), config)
        try {
            val rows: List<String> = second.store.getAllActive().map { it.id }
            if (acknowledged) {
                assertTrue("fresh" in rows, "an insert that returned true is gone after a restart; the queue holds $rows")
            }
            second.store.insertKeep(anItem("after-restart"))
            assertTrue("after-restart" in second.store.getAllActive().map { it.id })
        } finally {
            second.close()
        }
        application().deleteDatabase(file.name)
    }

    @Test
    fun `a file locked by another connection is not corrupt so recreateOnCorruption keeps every row`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.locked", recreateOnCorruption = true)
        seed(config, 5)
        val file = fileOf(config)
        val other: SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            other.execSQL("BEGIN EXCLUSIVE")
            val opened: Result<UploaderStorage> = runCatching { createUploaderStorage(application(), config) }
            opened.getOrNull()?.let { storage ->
                try {
                    val failure: Throwable? = runCatching { storage.store.insertKeep(anItem("while-locked")) }.exceptionOrNull()
                    assertTrue(failure == null || failure is UploaderStorageException, "got $failure")
                } finally {
                    storage.close()
                }
            }
            opened.exceptionOrNull()?.let { assertTrue(it is UploaderStorageException, "got $it") }
        } finally {
            runCatching { other.execSQL("ROLLBACK") }
            other.close()
        }

        val storage: UploaderStorage = createUploaderStorage(application(), config)
        try {
            val ids: List<String> = storage.store.getAllActive().map { it.id }
            assertEquals(5, ids.count { it.startsWith("seed-") }, "a lock made the factory throw away the queue: $ids")
        } finally {
            storage.close()
        }
        application().deleteDatabase(file.name)
    }

    @Test
    fun `a database written by a newer schema fails the open typed and keeps its rows even with recreateOnCorruption`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.downgrade", recreateOnCorruption = true)
        seed(config, 3)
        val file = fileOf(config)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("PRAGMA user_version = 99")
        }

        val failure: Throwable? = runCatching { createUploaderStorage(application(), config) }.exceptionOrNull()

        assertTrue(failure is UploaderStorageException, "a downgrade must fail the open typed, got $failure")
        assertEquals(UploaderStorageOperation.OPEN, failure.operation)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM kmptoolkit_uploader_item", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0), "a newer-schema database was deleted as if it were corrupt")
            }
            raw.rawQuery("PRAGMA user_version", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(99, cursor.getInt(0))
            }
        }
        application().deleteDatabase(file.name)
    }

    @Test
    fun `a foreign database that already owns the table name fails the open typed and is left intact`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.foreign", recreateOnCorruption = true)
        val file = fileOf(config)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            raw.execSQL("CREATE TABLE kmptoolkit_uploader_item (mine TEXT)")
            raw.execSQL("INSERT INTO kmptoolkit_uploader_item VALUES ('precious')")
        }

        val failure: Throwable? = runCatching { createUploaderStorage(application(), config) }.exceptionOrNull()

        assertTrue(failure is UploaderStorageException, "got $failure")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.rawQuery("SELECT mine FROM kmptoolkit_uploader_item", null).use { cursor ->
                assertTrue(cursor.moveToFirst(), "the foreign table was dropped")
                assertEquals("precious", cursor.getString(0))
            }
        }
        application().deleteDatabase(file.name)
    }

    @Test
    fun `a directory where the file should be fails the open typed and recreateOnCorruption does not delete it`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.directory", recreateOnCorruption = true)
        val file = fileOf(config)
        assertTrue(file.mkdirs())
        val inner = File(file, "keep.txt").also { it.writeText("keep me") }

        val failure: Throwable? = runCatching { createUploaderStorage(application(), config) }.exceptionOrNull()

        assertTrue(failure is UploaderStorageException, "got $failure")
        assertTrue(inner.exists() && inner.readText() == "keep me", "the open deleted a directory it does not own")
        inner.delete()
        file.delete()
    }

    @Test
    fun `thirty failed opens in a launch loop leak no threads`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.loop")
        val file = fileOf(config)
        file.writeBytes(ByteArray(8_192) { (it * 7).toByte() })
        repeat(3) { runCatching { createUploaderStorage(application(), config) } } // warm up pools and class loading
        val before: Int = Thread.getAllStackTraces().keys.size

        repeat(30) {
            val failure: Throwable? = runCatching { createUploaderStorage(application(), config) }.exceptionOrNull()
            assertTrue(failure is UploaderStorageException, "open $it: got $failure")
        }

        val after: Int = Thread.getAllStackTraces().keys.size
        assertTrue(after - before <= 3, "threads grew from $before to $after over thirty failed opens")
        application().deleteDatabase(file.name)
    }

    @Test
    fun `closing the storage under a live observer neither hangs nor throws when the observer is cancelled afterwards`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.observer")
        val file = fileOf(config)
        val threadsBefore: Int = Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-uploader-db" }
        val storage: UploaderStorage = createUploaderStorage(application(), config)
        val seen = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            val collector: kotlinx.coroutines.Job = scope.launch {
                storage.store.observeByType("checks.type").collect { list -> seen.set(list.map { it.id }) }
            }
            storage.store.insertKeep(anItem("one"))
            val sawInsert: Boolean? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) { while ("one" !in seen.get()) kotlinx.coroutines.delay(10); true }
            }
            assertEquals(true, sawInsert, "setup: the observer never showed the inserted row, last emission ${seen.get()}")

            storage.close() // the observer is still subscribed
            val joined: Boolean? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(10_000) { collector.cancelAndJoin(); true }
            }
            assertEquals(true, joined, "cancelling an observer of a closed storage never completed")
        } finally {
            scope.cancel()
            application().deleteDatabase(file.name)
        }
        val threadsAfter: Int = Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-uploader-db" }
        assertTrue(threadsAfter <= threadsBefore, "the database thread outlived close(): $threadsBefore -> $threadsAfter")
    }
}
