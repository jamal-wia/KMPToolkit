package io.github.jamal_wia.kmptoolkit.downloader

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeSymbolicLink
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * B boss round 2, iOS twin of `AndroidSqliteQuarantineBoss2Test`: what stands at or beside a `SqliteDatabase` resource
 * path when its unit is replaced or deleted. The old file, "or whatever stands at it, a directory or a link included",
 * moves into the trash with its `-wal`, `-shm` and `-journal`; a link is moved as a link and its target is never touched
 * by the sweep. A commit that found nothing at the path leaves no trash entry, and many replacing commits do not grow
 * the trash.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSqliteQuarantineBoss2Test {

    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_quarantine_boss2")
    // timing-hygiene-ignore: TH4 create makes the test's own uniquely named directory and deletes it in deleteAfter
    private val scratch: String = "${NSTemporaryDirectory()}sqlite-quarantine-boss2-${NSProcessInfo.processInfo.globallyUniqueString}"
    private val outside: String = "$scratch/outside"

    private val baseDirectory: String
        get() {
            val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .first() as String
            return "$appSupport/${config.baseDirectoryName}"
        }

    private class DbUnit(override val id: String) : DownloadUnit {
        override val apiPath: String = "/d/$id"
        override val relativePath: String = "d/$id.db"
        override val format: ResourceFormat = ResourceFormat.SqliteDatabase()
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@DbUnit)
        }
    }

    @BeforeTest
    fun deleteBefore() {
        deleteAll()
        NSFileManager.defaultManager.createDirectoryAtPath(outside, true, null, null)
    }

    @AfterTest
    fun deleteAfter() {
        deleteAll()
        LegacyTempMigrations.resetForTests()
    }

    private fun deleteAll() {
        NSFileManager.defaultManager.removeItemAtPath(baseDirectory, error = null)
        NSFileManager.defaultManager.removeItemAtPath(scratch, error = null)
    }

    private fun run(connection: SQLiteConnection, sql: String) {
        val statement = connection.prepare(sql)
        try {
            statement.step()
        } finally {
            statement.close()
        }
    }

    private fun freshDatabase(rows: Int): String {
        val path = "$scratch/new-$rows.db"
        if (exists(path)) return path
        val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
        try {
            run(connection, "CREATE TABLE t (id INTEGER PRIMARY KEY, payload TEXT)")
            repeat(rows) { index: Int -> run(connection, "INSERT INTO t (payload) VALUES ('new$index')") }
        } finally {
            connection.close()
        }
        return path
    }

    private fun write(path: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
        val file = fopen(path, "wb") ?: error("could not open $path")
        try {
            if (bytes.isNotEmpty()) bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file) }
        } finally {
            fclose(file)
        }
    }

    private fun copyBytes(from: String, to: String) {
        val data: NSData = NSData.dataWithContentsOfFile(from) ?: error("no $from")
        val file = fopen(to, "wb") ?: error("could not open $to")
        try {
            if (data.length > 0u) fwrite(data.bytes, 1u.convert(), data.length.convert(), file)
        } finally {
            fclose(file)
        }
    }

    private suspend fun commit(storage: DownloaderStorage, unit: DownloadUnit, source: String) {
        storage.beginTempFile(unit, null)
        val temp: String = storage.getTempFilePath(unit)
        NSFileManager.defaultManager.createDirectoryAtPath(temp.substringBeforeLast('/'), true, null, null)
        copyBytes(source, temp)
        storage.markTempFileComplete(unit)
        storage.commitResource(unit)
    }

    private fun payloads(path: String): List<String> = runCatching {
        val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
        try {
            val statement = connection.prepare("SELECT payload FROM t ORDER BY id")
            try {
                val out: ArrayList<String> = ArrayList()
                while (statement.step()) out.add(statement.getText(0))
                out
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }.getOrElse { e: Throwable -> listOf("unreadable: $e") }

    /** lstat: the entry itself, a dangling link included. */
    private fun exists(path: String): Boolean = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) != null

    private fun isLink(path: String): Boolean =
        NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) == NSFileTypeSymbolicLink

    private fun sameBytes(path: String, expected: String): Boolean =
        NSFileManager.defaultManager.contentsEqualAtPath(path, andPath = expected)

    private fun sidecarsAt(resource: String): List<String> =
        listOf("-wal", "-shm", "-journal").filter { suffix: String -> exists(resource + suffix) }

    private fun trashEntries(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath("$baseDirectory/tmp/kmptoolkit-internal/trash", error = null)
            ?.filterIsInstance<String>().orEmpty().sorted()

    private fun link(at: String, target: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(at.substringBeforeLast('/'), true, null, null)
        assertTrue(NSFileManager.defaultManager.createSymbolicLinkAtPath(at, withDestinationPath = target, error = null), "could not link $at")
    }

    @Test
    fun aDirectoryAtTheDatabasePathWithALinkInsideIsMovedAsideAndItsLinkTargetSurvivesTheSweep() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("directory")
        val resource: String = storage.getResourcePath(unit)
        write("$outside/keep.bin", byteArrayOf(4, 5, 6))
        write("$scratch/keep-copy.bin", byteArrayOf(4, 5, 6))
        write("$resource/inner/stale.bin", byteArrayOf(1))
        link("$resource/inner/escape", outside)

        commit(storage, unit, freshDatabase(rows = 3))

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(listOf("new0", "new1", "new2"), payloads(resource))
        assertTrue(sameBytes("$outside/keep.bin", "$scratch/keep-copy.bin"), "the sweep followed a link inside the replaced directory")
        assertEquals(emptyList(), trashEntries(), "the replaced directory must not wait in the trash")
    }

    @Test
    fun aLinkAtTheDatabasePathIsReplacedAsALinkAndItsTargetIsNeverTouched() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("link")
        val resource: String = storage.getResourcePath(unit)
        val target = "$outside/target.db"
        copyBytes(freshDatabase(rows = 7), target)
        write("$target-wal", byteArrayOf(9, 9))
        write("$scratch/wal-copy", byteArrayOf(9, 9))
        link(resource, target)

        commit(storage, unit, freshDatabase(rows = 3))

        assertFalse(isLink(resource), "the committed database must be a file of its own, not the old link")
        assertEquals(listOf("new0", "new1", "new2"), payloads(resource))
        assertTrue(sameBytes(target, freshDatabase(rows = 7)), "the link's target was changed")
        assertTrue(sameBytes("$target-wal", "$scratch/wal-copy"), "a file beside the link's target was touched")
        assertEquals(emptyList(), trashEntries())

        storage.deleteResource(unit)
        link(resource, target)
        storage.deleteResource(unit)
        assertFalse(exists(resource))
        assertTrue(sameBytes(target, freshDatabase(rows = 7)), "deleteResource changed the target of a link at the path")
    }

    @Test
    fun sidecarsThatAreLinksToFilesOutsideTheBaseDirectoryAreMovedAsLinksAndTheirTargetsSurvive() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("sidecarlinks")
        commit(storage, unit, freshDatabase(rows = 5))
        val resource: String = storage.getResourcePath(unit)
        write("$outside/user.wal", byteArrayOf(1, 2, 3))
        write("$scratch/user-wal-copy", byteArrayOf(1, 2, 3))
        link("$resource-wal", "$outside/user.wal")
        // A dangling link as the journal: present to lstat, absent to fileExistsAtPath.
        link("$resource-journal", "$outside/missing-journal")

        commit(storage, unit, freshDatabase(rows = 3))

        assertEquals(emptyList(), sidecarsAt(resource), "a link standing as a sidecar was left beside the new database")
        assertEquals(listOf("new0", "new1", "new2"), payloads(resource))
        assertTrue(sameBytes("$outside/user.wal", "$scratch/user-wal-copy"), "the sweep deleted or changed a link's target")
        assertEquals(emptyList(), trashEntries())

        link("$resource-wal", "$outside/user.wal")
        link("$resource-journal", "$outside/missing-journal")
        storage.deleteResource(unit)

        assertFalse(exists(resource))
        assertEquals(emptyList(), sidecarsAt(resource), "deleteResource left a link standing as a sidecar")
        assertTrue(sameBytes("$outside/user.wal", "$scratch/user-wal-copy"), "deleteResource deleted or changed a link's target")
        assertEquals(emptyList(), trashEntries())
    }

    @Test
    fun aFirstCommitWithNothingAtThePathAndManyReplacingCommitsLeaveNothingInTheTrash() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("growth")
        val resource: String = storage.getResourcePath(unit)
        val versions: List<String> = listOf(freshDatabase(rows = 2), freshDatabase(rows = 3))

        commit(storage, unit, versions[0])
        assertEquals(emptyList(), trashEntries(), "a commit that found nothing at the path left a trash entry")
        storage.deleteResource(DbUnit("absent"))
        assertEquals(emptyList(), trashEntries(), "a delete that found nothing left a trash entry")

        repeat(100) { index: Int ->
            write("$resource-wal", byteArrayOf(index.toByte()))
            commit(storage, unit, versions[index % 2])
            assertEquals(emptyList(), trashEntries(), "commit #$index left something in the trash")
            assertEquals(emptyList(), sidecarsAt(resource), "commit #$index left a sidecar")
        }
        // The last commit (#99) took versions[1].
        assertEquals(listOf("new0", "new1", "new2"), payloads(resource))
    }

    @Test
    fun aSidecarThatIsADirectoryWithFilesIsMovedAsideWhole() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit = DbUnit("sidecardirectory")
        commit(storage, unit, freshDatabase(rows = 5))
        val resource: String = storage.getResourcePath(unit)
        write("$resource-wal/frame", byteArrayOf(1))

        commit(storage, unit, freshDatabase(rows = 3))

        assertEquals(emptyList(), sidecarsAt(resource))
        assertEquals(listOf("new0", "new1", "new2"), payloads(resource))
        assertEquals(emptyList(), trashEntries())
    }
}
