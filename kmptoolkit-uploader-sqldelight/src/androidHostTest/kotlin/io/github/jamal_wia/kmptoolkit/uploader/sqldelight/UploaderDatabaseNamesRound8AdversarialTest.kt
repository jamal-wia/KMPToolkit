package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Round 8 (A1, scale of names): odd and very many queue names. A name the config accepts either opens and round-trips a
 * row through a close and a reopen, or fails the open as [UploaderStorageException]; it never throws another type and
 * never creates a file outside the databases directory. A hundred and fifty queues open at once keep their rows apart
 * and release every database thread when closed.
 */
@RunWith(AndroidJUnit4::class)
class UploaderDatabaseNamesRound8AdversarialTest {

    private val opened: MutableList<UploaderStorage> = mutableListOf()
    private val used: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        used.forEach { runCatching { application().deleteDatabase(uploaderDatabaseFileName(it)) } }
    }

    private fun row(id: String): UploaderItem = UploaderItem(
        id = id, type = "t", payload = "p", schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
        state = UploaderItemState.PENDING, attempts = 0, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
    )

    private fun dbThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-uploader-db" && it.isAlive }

    private fun filesUnder(root: File): Set<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet()

    @Test
    fun `odd names open and round-trip or are refused as a storage exception, and never write outside the databases directory`() = runBlocking {
        val names: List<String> = listOf(
            "😀queue", "תור", "n".repeat(100), "n".repeat(150), "n".repeat(180), "n".repeat(200), "n".repeat(230), "n".repeat(250), "n".repeat(300),
            ":colon", "a:b*c?d\"e<f>g|h", "..", ".", "%2e%2e", "a\u0007b", "line\nbreak", "con", "nul", "file:mem?mode=memory&cache=shared",
            "á", "á", "Case", "case",
        )
        val dataRoot: File = application().getDatabasePath("probe").parentFile!!.parentFile!!
        val before: Set<String> = filesUnder(dataRoot)
        val outcomes: MutableList<String> = mutableListOf()
        for ((index, name) in names.withIndex()) {
            val config: UploaderDatabaseConfig = try {
                UploaderDatabaseConfig(name)
            } catch (e: IllegalArgumentException) {
                outcomes += "#$index rejected by the config"
                continue
            }
            used += name
            val id = "row-$index"
            val storage: UploaderStorage = try {
                createUploaderStorage(application(), config).also { opened += it }
            } catch (e: UploaderStorageException) {
                outcomes += "#$index (len ${name.length}) refused (${e.cause?.javaClass?.simpleName}: ${e.cause?.message?.take(160)?.replace(File.separatorChar, '/')?.substringAfterLast('/')})"
                continue
            }
            try {
                assertTrue(storage.store.insertKeep(row(id)).inserted, "name #$index")
                storage.close()
                val again: UploaderStorage = createUploaderStorage(application(), config).also { opened += it }
                val rows: List<String> = again.store.getAllActive().map { it.id }
                // two names that fold to one file (case, normalization) share rows on this volume; only the own row must be there
                assertTrue(id in rows, "name #$index lost its row across a reopen: $rows")
                outcomes += "#$index opened, ${rows.size} row(s)"
                again.close()
            } catch (e: UploaderStorageException) {
                outcomes += "#$index failed after open (${e.operation})"
            }
        }
        println("R8 NAMES: $outcomes")
        val created: Set<String> = filesUnder(dataRoot) - before
        val outside: List<String> = created.filterNot {
            // the framework's own process-lock files live in the cache directory
            it.replace(File.separatorChar, '/').let { p -> p.startsWith("databases/") || p.contains("/databases/") || p.endsWith(".lck") }
        }
        assertTrue(outside.isEmpty(), "files created outside the databases directory: $outside")
    }

    @Test
    fun `a hundred and fifty queues open at once keep their rows apart and give every thread back`() = runBlocking {
        val baseline: Int = dbThreads()
        val storages: List<Pair<String, UploaderStorage>> = (0 until 150).map { i ->
            val name = "adv.a1r8.many.$i"
            used += name
            name to createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { opened += it }
        }
        storages.forEachIndexed { i, (_, s) ->
            assertTrue(s.store.insertKeep(row("own-$i")).inserted)
            assertTrue(s.store.insertKeep(row("second-$i")).inserted)
        }
        storages.forEachIndexed { i, (name, s) ->
            assertEquals(listOf("own-$i", "second-$i"), s.store.getAllActive().map { it.id }, "rows of queue $name")
        }
        assertTrue(dbThreads() >= baseline + 150, "one database thread per open storage is expected")
        storages.forEach { it.second.close() }
        val deadline: Long = System.currentTimeMillis() + 10_000L
        while (dbThreads() > baseline && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(baseline, dbThreads(), "database threads outlived their storages")
    }
}
