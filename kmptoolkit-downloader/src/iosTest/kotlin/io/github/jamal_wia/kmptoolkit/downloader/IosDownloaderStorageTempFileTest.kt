package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
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
        val unit = TestUnit(id = "model", sha256 = ABC_SHA256.uppercase())
        writePartial(unit, "abc")
        storage.markTempFileComplete(unit)

        storage.commitResource(unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(3L, storage.getResourceSize(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun aCompleteFileWhoseSha256DiffersIsDeletedAndNothingIsCommitted() = runTest {
        val unit = TestUnit(id = "model", sha256 = ABC_SHA256)
        writePartial(unit, "abd")
        storage.markTempFileComplete(unit)

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun aStatedHashThatIsNotHexIsAHostErrorNotAnIntegrityFailure() = runTest {
        val unit = TestUnit(id = "model", sha256 = "not-a-hash")
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

    private fun writePartial(unit: DownloadUnit, text: String) {
        val path: String = storage.getTempFilePath(unit)
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
        override val sha256: String? = null,
        override val format: ResourceFormat = ResourceFormat.Opaque,
    ) : DownloadUnit {
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
