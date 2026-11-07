package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The temp-file half of the [DownloaderStorage] contract on iOS — the same cases as the Android
 * storage's own test: a partial transfer and a complete one are different states, only a complete
 * one commits, and a commit checks the bytes against [DownloadUnit.sha256] through CommonCrypto
 * before anything reaches the final path.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDownloaderStorageTempFileTest {

    private val config = DownloaderStorageConfig(baseDirectoryName = "temp_file_test")
    private val storage: DownloaderStorage = createDownloaderStorage(config)

    @AfterTest
    fun cleanUp() {
        val appSupport: String = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).first() as String
        NSFileManager.defaultManager.removeItemAtPath("$appSupport/${config.baseDirectoryName}", error = null)
    }

    @Test
    fun noTempFileAndAnEmptyOneAreBothNone() {
        val unit = TestUnit(id = "model")
        assertEquals(TempFileState.None, storage.tempFileState(unit))

        writePartial(unit, "")

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun bytesWrittenToTheTempPathArePartial() {
        val unit = TestUnit(id = "model")

        writePartial(unit, "0123456789")

        assertEquals(TempFileState.Partial, storage.tempFileState(unit))
        assertEquals(10L, storage.getTempFileSize(unit))
    }

    @Test
    fun markingAPartialFileCompleteSurvivesANewStorageInstance() {
        val unit = TestUnit(id = "model")
        writePartial(unit, "0123456789")

        storage.markTempFileComplete(unit)
        storage.markTempFileComplete(unit)

        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
        assertEquals(TempFileState.Complete, createDownloaderStorage(config).tempFileState(unit))
    }

    @Test
    fun markingNothingThrows() {
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(TestUnit(id = "model")) }
    }

    @Test
    fun aPartialTempFileIsNeverCommitted() = runTest {
        val unit = TestUnit(id = "model")
        writePartial(unit, "0123456789")

        assertFailsWith<IllegalStateException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.Partial, storage.tempFileState(unit))
    }

    @Test
    fun deleteTempFileRemovesACompleteFileAsWellAsAPartialOne() {
        val unit = TestUnit(id = "model")
        writePartial(unit, "0123456789")
        storage.markTempFileComplete(unit)
        writePartial(unit, "01234")

        storage.deleteTempFile(unit)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun aCompleteFileWhoseSha256MatchesIsCommitted() = runTest {
        val unit = TestUnit(id = "model", sha256Text = ABC_SHA256.uppercase())
        writePartial(unit, "abc")
        storage.markTempFileComplete(unit)

        storage.commitResource(unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(3L, storage.getResourceSize(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun aCompleteFileWhoseSha256DiffersIsDeletedAndNothingIsCommitted() = runTest {
        val unit = TestUnit(id = "model", sha256Text = ABC_SHA256)
        writePartial(unit, "abd")
        storage.markTempFileComplete(unit)

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun aUnitWhoseHashGetterThrowsIsAHostErrorNotAnIntegrityFailure() = runTest {
        val unit = TestUnit(id = "model", sha256Text = "not-a-hash")
        writePartial(unit, "abc")
        storage.markTempFileComplete(unit)

        assertFailsWith<IllegalArgumentException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun bytesThatAreNotADatabaseFailAsAnIntegrityFailure() = runTest {
        val unit = TestUnit(id = "quran-db", format = ResourceFormat.SqliteDatabase())
        writePartial(unit, "x".repeat(2048))
        storage.markTempFileComplete(unit)

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun deletingTheTempFileAlsoRemovesTheStagingTreeAnInterruptedExtractionLeft() {
        val unit = TestUnit(id = "model")
        writePartial(unit, "0123456789")
        storage.markTempFileComplete(unit)
        val tmpDir: String = storage.getTempFilePath(unit).substringBeforeLast('/')
        val staging = "$tmpDir/staging-model/nested"
        val other = "$tmpDir/staging-other"
        NSFileManager.defaultManager.createDirectoryAtPath(staging, true, null, null)
        NSFileManager.defaultManager.createDirectoryAtPath(other, true, null, null)

        storage.deleteTempFile(unit)

        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$tmpDir/staging-model"))
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(other), "another unit's staging tree is not touched")
        val leftovers: List<*> = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$tmpDir/trash", null).orEmpty()
        assertTrue(leftovers.isEmpty(), "the detached tree must be deleted, not left as trash")
    }

    @Test
    fun deletingATempFileSweepsATrashTreeAKilledProcessLeftWhateverUnitItBelongedTo() {
        val tmpDir: String = storage.getTempFilePath(TestUnit(id = "unrelated")).substringBeforeLast('/')
        val leftover = "$tmpDir/trash/gone-123/nested"
        NSFileManager.defaultManager.createDirectoryAtPath(leftover, true, null, null)
        writePartialAt("$leftover/file", "half-deleted")
        val other = "$tmpDir/staging-other"
        NSFileManager.defaultManager.createDirectoryAtPath(other, true, null, null)
        // A unit whose id merely starts with "trash-" owns a temp file that must survive the sweep.
        val lookalike = TestUnit(id = "trash-model")
        writePartial(lookalike, "0123456789")

        storage.deleteTempFile(TestUnit(id = "unrelated"))

        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$tmpDir/trash/gone-123"), "the leftover trash tree is reclaimed")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(other), "another unit's staging tree is not touched")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(storage.getTempFilePath(lookalike)), "a unit id starting with trash- is not trash")
    }

    @Test
    fun deletingTheTempFileLeavesTheStagingTreeAloneWhileACommitOfTheUnitRuns() = runTest {
        val unit = TestUnit(id = "model")
        val baseDir: String = storage.getTempFilePath(unit).substringBeforeLast("/tmp/")
        val staging = "$baseDir/tmp/staging-model/nested"
        NSFileManager.defaultManager.createDirectoryAtPath(staging, true, null, null)
        val gate = CompletableDeferred<Unit>()
        val commit: Job = launch(Dispatchers.Unconfined) {
            UnitCommitLocks.withUnitLock(baseDir, "model") { gate.await() }
        }

        storage.deleteTempFile(unit)

        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(staging), "an extraction in progress must survive a cancel")
        gate.complete(Unit)
        withTimeout(5_000) { commit.join() }
        storage.deleteTempFile(unit)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$baseDir/tmp/staging-model"), "reclaimed once no commit runs")
    }

    private fun writePartial(unit: DownloadUnit, text: String) = writePartialAt(storage.getTempFilePath(unit), text)

    private fun writePartialAt(path: String, text: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data: NSData = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
        check(data.writeToFile(path, atomically = true)) { "could not write $path" }
    }

    private class TestUnit(
        override val id: String,
        /** The hash as text; parsed lazily so a malformed one throws from the getter, as a host's would. */
        private val sha256Text: String? = null,
        override val format: ResourceFormat = ResourceFormat.Opaque,
    ) : DownloadUnit {
        override val sha256: Sha256? get() = sha256Text?.let { Sha256.parse(it) }
        override val apiPath: String = "/resources/$id"
        override val relativePath: String = "resources/$id.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@TestUnit)
        }
        override fun toString(): String = "TestUnit($id)"
    }

    private companion object {
        /** SHA-256 of the three ASCII bytes "abc", the FIPS 180-2 test vector. */
        const val ABC_SHA256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
