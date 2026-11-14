package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import co.touchlab.sqliter.DatabaseFileContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager

/**
 * The documented behaviour (docs sqldelight 05): queue names are compared the way the volume compares file names, so
 * names that differ only by letter case (on a case-insensitive volume) or only in Unicode normalization (on every
 * Apple volume) are one queue. Each test probes the volume first and does nothing where it keeps the two names apart.
 * The tests open two storages over one file, the unsupported configuration, only to insert and read.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDatabaseNameFoldingTest {

    private fun directory(): String =
        DatabaseFileContext.databasePath("probe", null).substringBeforeLast('/')

    /** Whether the database directory treats [first] and [second] as one file name. */
    private fun foldsOnVolume(first: String, second: String): Boolean {
        val files: NSFileManager = NSFileManager.defaultManager
        val base: String = directory()
        files.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
        val created: String = "$base/$first"
        files.createFileAtPath(created, null, null)
        return try {
            files.fileExistsAtPath("$base/$second")
        } finally {
            files.removeItemAtPath(created, error = null)
        }
    }

    private suspend fun assertSharedRows(first: String, second: String) {
        val a: UploaderStorage = createUploaderStorage(UploaderDatabaseConfig(first))
        val b: UploaderStorage = createUploaderStorage(UploaderDatabaseConfig(second))
        try {
            a.store.clearAll()
            a.store.insertKeep(anItem("only-in-a"))
            assertEquals(listOf("only-in-a"), b.store.getAllActive().map { it.id }, "'$second' does not see the rows of '$first' on a volume that folds them")
        } finally {
            a.close()
            b.close()
            deleteDatabaseFile(uploaderDatabaseFileName(first))
            deleteDatabaseFile(uploaderDatabaseFileName(second))
        }
    }

    @Test
    fun `queues named apart only by letter case share their rows where the volume ignores case`() = runTest {
        val suffix: String = uniqueDatabaseName("case")
        if (!foldsOnVolume("probe-$suffix-A", "probe-$suffix-a")) {
            println("skipped: this volume keeps letter case apart")
            return@runTest
        }
        assertSharedRows("Queue.$suffix", "queue.$suffix")
    }

    @Test
    fun `queues named apart only by normalization share their rows on Apple volumes`() = runTest {
        val suffix: String = uniqueDatabaseName("nfc")
        if (!foldsOnVolume("probe-$suffix-café", "probe-$suffix-café")) {
            println("skipped: this volume keeps normalization forms apart")
            return@runTest
        }
        assertSharedRows("café.$suffix", "café.$suffix")
    }
}
