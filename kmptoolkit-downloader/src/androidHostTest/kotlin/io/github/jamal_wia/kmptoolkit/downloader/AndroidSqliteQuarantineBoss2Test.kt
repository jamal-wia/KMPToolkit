package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B boss round 2: what stands at or beside a `SqliteDatabase` resource path when its unit is replaced or deleted. Contract
 * (SqliteDatabase KDoc, 04-api-reference, 05-platform-notes, D-131): the old file, "or whatever stands at it, a directory
 * or a link included", moves into the trash with its `-wal`, `-shm` and `-journal`, and a link is moved as a link, never
 * followed: the sweep deletes the link, not its target. Also pinned: a commit that found nothing at the path leaves no
 * trash entry behind, and many replacing commits do not grow the trash.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteQuarantineBoss2Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_quarantine_boss2")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val scratch: File get() = File(context.cacheDir, "sqlite_quarantine_boss2")
    private val outside: File get() = File(context.cacheDir, "sqlite_quarantine_boss2_outside")
    private val fixtures: AndroidSqliteFixturesBoss1 get() = AndroidSqliteFixturesBoss1(scratch)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        scratch.deleteRecursively()
        outside.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        scratch.deleteRecursively()
        outside.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Unit2(override val id: String, override val relativePath: String) : DownloadUnit {
        override val apiPath: String = "/m/$id"
        override val format: ResourceFormat = ResourceFormat.SqliteDatabase()
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@Unit2)
        }

        override fun toString(): String = "Unit2($id)"
    }

    private val dbUnit = Unit2("content", "m/content.db")

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun trash(): File = File(File(File(root, "tmp"), "kmptoolkit-internal"), "trash")

    /** The entries of the trash directory (none when it does not exist). */
    private fun trashEntries(): List<String> = trash().list().orEmpty().sorted()

    private fun present(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun sidecarsAt(resource: File): List<String> =
        listOf("-wal", "-shm", "-journal").filter { suffix: String -> present(File(resource.path + suffix)) }

    /** A file outside the base directory that a link at or beside the resource points to; it must never be touched. */
    private fun victim(name: String, bytes: ByteArray): File {
        outside.mkdirs()
        return File(outside, name).apply { writeBytes(bytes) }
    }

    private fun link(at: File, target: File) {
        at.parentFile!!.mkdirs()
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    @Test
    fun `a directory at the database path with a link inside is moved aside and its link target survives the sweep`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val resource = File(storage.getResourcePath(dbUnit))
            val keep: File = victim("keep.bin", byteArrayOf(4, 5, 6))
            val inner = File(resource, "inner")
            inner.mkdirs()
            File(inner, "stale.bin").writeBytes(byteArrayOf(1))
            link(File(inner, "escape"), outside)

            stage(storage, dbUnit, fixtures.freshDatabase(rows = 3))
            storage.commitResource(dbUnit)

            assertTrue(storage.isResourceAvailable(dbUnit))
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
            assertContentEquals(byteArrayOf(4, 5, 6), keep.readBytes(), "the sweep followed a link inside the replaced directory")
            assertEquals(emptyList(), trashEntries(), "the replaced directory must not wait in the trash")
        }
    }

    @Test
    fun `a link at the database path is replaced as a link and its target is never touched`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val resource = File(storage.getResourcePath(dbUnit))
            val target: File = victim("target.db", fixtures.freshDatabase(rows = 7))
            val before: ByteArray = target.readBytes()
            link(resource, target)
            File(target.path + "-wal").writeBytes(byteArrayOf(9, 9))

            stage(storage, dbUnit, fixtures.freshDatabase(rows = 3))
            storage.commitResource(dbUnit)

            assertFalse(Files.isSymbolicLink(resource.toPath()), "the committed database must be a file of its own, not the old link")
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
            assertContentEquals(before, target.readBytes(), "the link's target was changed")
            assertContentEquals(byteArrayOf(9, 9), File(target.path + "-wal").readBytes(), "a file beside the link's target was touched")
            assertEquals(emptyList(), trashEntries())

            // The delete of a link at the path moves the link, never the file it points to.
            storage.deleteResource(dbUnit)
            link(resource, target)
            storage.deleteResource(dbUnit)
            assertFalse(present(resource))
            assertContentEquals(before, target.readBytes(), "deleteResource changed the target of a link at the path")
        }
    }

    @Test
    fun `sidecars that are links to files outside the base directory are moved as links and their targets survive`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            stage(storage, dbUnit, fixtures.freshDatabase(rows = 5))
            storage.commitResource(dbUnit)
            val resource = File(storage.getResourcePath(dbUnit))
            val wal: File = victim("user.wal", byteArrayOf(1, 2, 3))
            val shm: File = victim("user.shm", byteArrayOf(4, 5))
            link(File(resource.path + "-wal"), wal)
            link(File(resource.path + "-shm"), shm)
            // A dangling link as the journal: present to lstat, absent to File.exists().
            link(File(resource.path + "-journal"), File(outside, "missing-journal"))

            stage(storage, dbUnit, fixtures.freshDatabase(rows = 3))
            storage.commitResource(dbUnit)

            assertEquals(emptyList(), sidecarsAt(resource), "a link standing as a sidecar was left beside the new database")
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
            assertContentEquals(byteArrayOf(1, 2, 3), wal.readBytes(), "the sweep deleted or changed a link's target")
            assertContentEquals(byteArrayOf(4, 5), shm.readBytes(), "the sweep deleted or changed a link's target")
            assertEquals(emptyList(), trashEntries())

            link(File(resource.path + "-wal"), wal)
            link(File(resource.path + "-journal"), File(outside, "missing-journal"))
            storage.deleteResource(dbUnit)

            assertFalse(present(resource))
            assertEquals(emptyList(), sidecarsAt(resource), "deleteResource left a link standing as a sidecar")
            assertContentEquals(byteArrayOf(1, 2, 3), wal.readBytes(), "deleteResource deleted or changed a link's target")
            assertEquals(emptyList(), trashEntries())
        }
    }

    @Test
    fun `a first commit with nothing at the path and many replacing commits leave nothing in the trash`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val resource = File(storage.getResourcePath(dbUnit))
            val versions: List<ByteArray> = listOf(fixtures.freshDatabase(rows = 2), fixtures.freshDatabase(rows = 3))

            stage(storage, dbUnit, versions[0])
            storage.commitResource(dbUnit)
            assertEquals(emptyList(), trashEntries(), "a commit that found nothing at the path left a trash entry")
            // A delete that finds nothing at all must not leave one either.
            val absent = Unit2("absent", "m/absent.db")
            storage.deleteResource(absent)
            assertEquals(emptyList(), trashEntries(), "a delete that found nothing left a trash entry")

            repeat(200) { index: Int ->
                File(resource.path + "-wal").writeBytes(byteArrayOf(index.toByte()))
                stage(storage, dbUnit, versions[index % 2])
                storage.commitResource(dbUnit)
                assertEquals(emptyList(), trashEntries(), "commit #$index left something in the trash")
                assertEquals(emptyList(), sidecarsAt(resource), "commit #$index left a sidecar")
            }
            // The last commit (#199) took versions[1].
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
        }
    }

    @Test
    fun `a sidecar that is a directory with files is moved aside whole`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            stage(storage, dbUnit, fixtures.freshDatabase(rows = 5))
            storage.commitResource(dbUnit)
            val resource = File(storage.getResourcePath(dbUnit))
            val walDirectory = File(resource.path + "-wal")
            walDirectory.mkdirs()
            File(walDirectory, "frame").writeBytes(byteArrayOf(1))

            stage(storage, dbUnit, fixtures.freshDatabase(rows = 3))
            storage.commitResource(dbUnit)

            assertEquals(emptyList(), sidecarsAt(resource))
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
            assertEquals(emptyList(), trashEntries())
        }
    }
}
