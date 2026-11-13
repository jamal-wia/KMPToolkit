package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B boss round 1: the resource side of a commit, both platforms through one table. Contract (04-api-reference
 * `DownloaderStorage`, `TempFileState`, 03-guide): only bytes that fail a check (`ResourceIntegrityException`) cost the
 * complete temp file; a commit that fails for any other reason (the file system refuses the move) keeps it `Complete`, the
 * only state committed without a transfer, so the next commit needs no new download. A marker below the archive's single root
 * directory is found after the root moves up.
 */
abstract class DestinationBoss1Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun deletePath(path: String)
    protected abstract fun deleteTestDirectory()

    /** Takes the write permission away from the directory [path], so nothing directly inside it can be deleted. */
    protected abstract fun makeReadOnly(path: String)

    /** Gives every directory under the test's base directory its write permission back, so the cleanup can delete it. */
    protected abstract fun restoreWritable()

    @AfterTest
    fun cleanUp() {
        restoreWritable()
        deleteTestDirectory()
        LegacyTempMigrations.resetForTests()
    }

    private class Unit1(
        override val id: String,
        override val relativePath: String,
        override val format: ResourceFormat,
    ) : DownloadUnit {
        override val apiPath: String = "/boss1/$id"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@Unit1)
        }

        override fun toString(): String = "Unit1($id)"
    }

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), bytes)
        storage.markTempFileComplete(unit)
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArray =
        ZipBytesRound6.build(entries.map { (name: String, body: String) -> ZipEntryRound6(name, body) }).bytes

    private suspend fun blockedParentKeepsTheTempFile(unit: DownloadUnit, bytes: ByteArray) {
        val storage: DownloaderStorage = newStorage()
        val resource: String = storage.getResourcePath(unit)
        val parent: String = assertNotNull(Paths.parentOf(resource))
        // A regular file where the resource's parent directory must go: the move cannot happen.
        writeFile(parent, "not a directory".encodeToByteArray())
        stage(storage, unit, bytes)

        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        assertNotNull(failure, "$unit: a commit whose parent directory is a file cannot succeed")
        assertFalse(failure is ResourceIntegrityException, "$unit: the bytes are fine, the file system refused: $failure")
        assertEquals(TempFileState.Complete, storage.tempFileState(unit), "$unit: a storage failure must keep the complete temp file")
        assertFalse(storage.isResourceAvailable(unit), "$unit: nothing was committed")

        deletePath(parent)
        storage.commitResource(unit)
        assertTrue(storage.isResourceAvailable(unit), "$unit: the retry without a transfer must commit")
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `an opaque commit whose parent directory is a file keeps the complete temp file for the retry`() = runTest {
        blockedParentKeepsTheTempFile(Unit1("opaque", "blocked/o.bin", ResourceFormat.Opaque), "payload".encodeToByteArray())
    }

    @Test
    fun `an archive commit whose parent directory is a file keeps the complete temp file for the retry`() = runTest {
        blockedParentKeepsTheTempFile(
            Unit1("zip", "blocked/z", ResourceFormat.ZipArchive("marker.txt")),
            archive("marker.txt" to "m", "a.txt" to "a"),
        )
    }

    @Test
    fun `a marker below a single root directory is found after the root moves up`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = Unit1("nested", "archives/nested", ResourceFormat.ZipArchive("meta/ready"))
        stage(storage, unit, archive("pages/meta/ready" to "m", "pages/p1.txt" to "1"))

        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        assertNull(failure, "a nested marker below the single root must commit: $failure")
        assertTrue(storage.isResourceAvailable(unit))
    }

    @Test
    fun `a stray directory whose contents cannot be deleted is replaced by the committed file in one move`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit = Unit1("stray", "stray/s.bin", ResourceFormat.Opaque)
        val resource: String = storage.getResourcePath(unit)
        val locked: String = Paths.join(resource, "locked")
        writeFile(Paths.join(locked, "kept.txt"), "kept".encodeToByteArray())
        // The tree can be renamed as a whole (that needs only its parent's permission) but not deleted in place.
        makeReadOnly(locked)
        stage(storage, unit, "payload".encodeToByteArray())

        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()

        assertNull(failure, "the stray tree must be moved aside in one rename, never deleted in place: $failure")
        assertTrue(storage.isResourceAvailable(unit), "the committed file must be in place")
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        // What the trash could not delete stays there; cleanups stay quiet about it.
        storage.deleteTempFile(unit)
        storage.deleteResource(unit)
        assertFalse(storage.isResourceAvailable(unit))
    }
}
