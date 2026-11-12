package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Round 9 (A2), environment hostility, storage: the SQLDelight store's database file changed under a running store by
 * someone else (cut to nothing, overwritten with noise, unlinked), and a data directory that refuses a new file. The
 * documented contract: a failure crosses the SPI only as [UploaderStorageException], never as the platform's own
 * exception, nothing hangs, and a store opened afterwards either works or fails the same typed way.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseFileEnvironmentRound9AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val names: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        storages.forEach { runCatching { it.close() } }
        names.forEach { runCatching { dbFile(it).parentFile?.setWritable(true) } }
        names.forEach { runCatching { deleteDatabaseFile(uploaderDatabaseFileName(it)) } }
    }

    private fun dbFile(name: String): File = application().getDatabasePath(uploaderDatabaseFileName(name))

    private fun open(name: String, recreate: Boolean = false): UploaderStorage {
        names += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name = name, recreateOnCorruption = recreate)).also { storages += it }
    }

    private fun <T> real(millis: Long = 20_000, block: suspend () -> T): T = runBlocking { withTimeout(millis) { block() } }

    /** One mixed operation; the only acceptable failure is the typed one. */
    private fun mixedOp(storage: UploaderStorage, n: Int, label: String): Boolean {
        val id = "id-${n % 7}"
        return try {
            real {
                when (n % 5) {
                    0 -> storage.store.insertKeep(anItem(id))
                    1 -> storage.store.getAllActive().isNotEmpty()
                    2 -> storage.store.getById(id) != null
                    3 -> storage.store.markInFlight(id, 1_000L, UploaderItemState.PENDING, 0L)
                    else -> { storage.store.deleteById(id); true }
                }
            }
            true
        } catch (e: UploaderStorageException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError("$label: operation $n failed with the platform's own ${e::class.qualifiedName}: ${e.message}", e)
        }
    }

    @Test
    fun `a database file cut to nothing under a running store fails only as a typed storage exception and never hangs`() {
        val storage: UploaderStorage = open("r9-cut")
        real { repeat(20) { storage.store.insertKeep(anItem("seed-$it")) } }
        val file: File = dbFile("r9-cut")
        RandomAccessFile(file, "rw").use { it.setLength(0) }
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        repeat(60) { n -> mixedOp(storage, n, "cut file") }
        storage.close()
        // Afterwards: opening is a success on an empty or restored queue, or a typed failure, never anything else.
        val reopened: Result<UploaderStorage> = runCatching { open("r9-cut") }
        reopened.exceptionOrNull()?.let { assertTrue(it is UploaderStorageException, "reopening a cut file failed with ${it::class.qualifiedName}") }
        reopened.getOrNull()?.let { s -> repeat(10) { n -> mixedOp(s, n, "after reopening a cut file") } }
    }

    @Test
    fun `a database file overwritten with noise under a running store fails only as a typed storage exception`() {
        val storage: UploaderStorage = open("r9-noise")
        real { repeat(40) { storage.store.insertKeep(anItem("seed-$it")) } }
        val file: File = dbFile("r9-noise")
        val size: Int = file.length().toInt().coerceAtLeast(8192)
        file.writeBytes(Random(9).nextBytes(size))
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        repeat(80) { n -> mixedOp(storage, n, "noisy file") }
        storage.close()
        val reopened: Result<UploaderStorage> = runCatching { open("r9-noise") }
        val failure: Throwable? = reopened.exceptionOrNull()
        assertTrue(failure == null || failure is UploaderStorageException, "reopening a noisy file failed with ${failure?.let { it::class.qualifiedName }}")
        if (failure != null) {
            assertEquals(UploaderStorageOperation.OPEN, (failure as UploaderStorageException).operation)
            assertTrue(file.exists(), "a corrupt file was deleted although the app did not opt in")
            // With the opt-in the damaged queue is replaced by an empty working one.
            val fresh: UploaderStorage = open("r9-noise", recreate = true)
            real { fresh.store.insertKeep(anItem("fresh")) }
            assertEquals(listOf("fresh"), real { fresh.store.getAllActive().map { it.id } })
        }
    }

    @Test
    fun `a database file unlinked under a running store keeps working or fails typed, and a new store starts from an empty queue`() {
        val storage: UploaderStorage = open("r9-unlinked")
        real { storage.store.insertKeep(anItem("before")) }
        val file: File = dbFile("r9-unlinked")
        assertTrue(file.delete(), "could not unlink the database file")
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
        repeat(50) { n -> mixedOp(storage, n, "unlinked file") }
        storage.close()
        val fresh: UploaderStorage = open("r9-unlinked")
        val seen: List<String> = real { fresh.store.getAllActive().map { it.id } }
        assertTrue(seen.none { it == "before" } || file.exists(), "a row came back from a file that was unlinked: $seen")
        real { fresh.store.insertKeep(anItem("after")) }
        assertNotNull(real { fresh.store.getById("after") })
    }

    @Test
    fun `a data directory that refuses a new file fails the open as a typed OPEN failure and leaves nothing running`() {
        val name = "r9-readonly"
        names += name
        val dir: File = dbFile(name).parentFile!!.also { it.mkdirs() }
        assumeTrue(dir.setWritable(false) && !File(dir, "probe").also { runCatching { it.createNewFile() } }.exists())
        val failure: Throwable? = runCatching { open(name) }.exceptionOrNull()
        dir.setWritable(true)
        assertNotNull(failure, "a store opened in a directory that cannot hold its file")
        assertTrue(failure is UploaderStorageException, "failed with ${failure::class.qualifiedName}: ${failure.message}")
        assertEquals(UploaderStorageOperation.OPEN, failure.operation)
        // The directory is writable again: the same name opens and works.
        val storage: UploaderStorage = open(name)
        real { storage.store.insertKeep(anItem("ok")) }
        assertEquals(listOf("ok"), real { storage.store.getAllActive().map { it.id } })
    }
}
